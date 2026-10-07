package com.github.feng111627.privacybudgetqueryjava.query;

import com.github.feng111627.privacybudgetqueryjava.audit.AuditTrail;
import com.github.feng111627.privacybudgetqueryjava.domain.AuthState;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryParams;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryRejectedException;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryRequest;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryResult;
import com.github.feng111627.privacybudgetqueryjava.domain.RejectionReason;
import com.github.feng111627.privacybudgetqueryjava.domain.ReplayResult;
import com.github.feng111627.privacybudgetqueryjava.domain.VersionSnapshot;
import com.github.feng111627.privacybudgetqueryjava.ledger.BudgetLedger;
import com.github.feng111627.privacybudgetqueryjava.versioning.SystemStateService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.SplittableRandom;

/**
 * 查询编排：钉住版本快照 -> 参数校验 -> 幂等检查 -> 按调用方授权校验 ->
 * 最小分组保护 -> 确定性噪声注入 -> 原子预算扣减 -> 审计（联动容量控制）。
 *
 * <p>版本一致性：每次查询入口只读取一次 {@link SystemStateService#current()}，
 * 之后的授权判定、ε/门槛/截断范围、预算扣减口径、噪声种子、审计记录全部来自
 * 这一个不可变快照。因此即使参数发布/授权变更与查询并发，进行中的查询也必然
 * 完整落在某一个确定版本上。</p>
 *
 * <p>可复现性：噪声种子由 (callerId, 预算批次, 系统版本, 字段, 统计类型, 分组键)
 * 决定；同一批次内相同查询结论一致。历史结论可凭审计记录里的版本号取回当时
 * 参数快照重新计算，结果与当初完全一致。</p>
 */
@Service
public class QueryService {

    /** 拉普拉斯噪声 95% 置信区间的乘子：ln(1/0.05)。 */
    private static final double CI_95_MULTIPLIER = Math.log(20.0);

    private final BudgetLedger ledger;
    private final SystemStateService stateService;
    private final Dataset dataset;
    private final AuditTrail auditTrail;

    public QueryService(BudgetLedger ledger, SystemStateService stateService,
                        Dataset dataset, AuditTrail auditTrail) {
        this.ledger = ledger;
        this.stateService = stateService;
        this.dataset = dataset;
        this.auditTrail = auditTrail;
    }

    public QueryResult execute(QueryRequest request) {
        validate(request);

        // 唯一一次读取当前版本：之后全部基于该不可变快照。
        VersionSnapshot snapshot = stateService.current();
        QueryParams params = snapshot.params();
        long version = snapshot.version();

        // 幂等：同一调用方同一 requestId 在当前批次内只处理一次。
        if (ledger.findCached(request.callerId(), request.requestId()).isPresent()) {
            throw new QueryRejectedException(RejectionReason.DUPLICATE_REQUEST,
                    "requestId 已处理过，重复提交被拒绝: " + request.requestId());
        }

        // 按调用方的授权三态判定：从未授权 vs 授权后撤权，拒绝原因可区分。
        AuthState auth = stateService.authState(request.callerId(), request.field());
        if (auth != AuthState.GRANTED) {
            throw new QueryRejectedException(
                    auth == AuthState.REVOKED
                            ? RejectionReason.FIELD_NOT_AUTHORIZED
                            : RejectionReason.FIELD_NEVER_GRANTED,
                    auth == AuthState.REVOKED
                            ? "字段授权后已被撤权（调用方 " + request.callerId() + "）: " + request.field()
                            : "该调用方从未被授权此字段（调用方 " + request.callerId() + "）: " + request.field());
        }

        // 最小分组保护：样本不足的分组不得返回结果（也不扣预算）。
        String groupKey = request.groupKey() == null ? Dataset.DEFAULT_GROUP : request.groupKey();
        List<Double> samples = dataset.column(request.field())
                .getOrDefault(groupKey, List.of());
        if (samples.size() < params.minGroupSize()) {
            throw new QueryRejectedException(RejectionReason.INSUFFICIENT_SAMPLE,
                    String.format("分组样本量不足: %d < %d（参数版本 %d）",
                            samples.size(), params.minGroupSize(), version));
        }

        // 确定性噪声注入（参数全部来自钉住的快照）。
        long batchId = ledger.currentBatch(request.callerId());
        double raw = rawStatistic(request, samples, params.maxValue());
        double sensitivity = sensitivityOf(request, samples.size(), params.maxValue());
        double noiseScale = sensitivity / params.epsilonPerQuery();
        double noise = laplaceNoise(seedOf(request, batchId, version), noiseScale);
        double value = raw + noise;
        double errorMargin = noiseScale * CI_95_MULTIPLIER;

        String explanation = String.format(
                "消耗预算 ε=%.3f（批次 %d，参数版本 %d，最小分组 %d，截断 ±%.0f）；"
                        + "拉普拉斯噪声尺度 b=%.4f，95%% 置信误差 ±%.4f；样本量 %d",
                params.epsilonPerQuery(), batchId, version,
                params.minGroupSize(), params.maxValue(),
                noiseScale, errorMargin, samples.size());

        QueryResult result = new QueryResult(value, errorMargin, params.epsilonPerQuery(),
                0.0, batchId, version, samples.size(), explanation);

        // 原子扣减：并发下不会扣成负数；同一 requestId 不会被重复扣减。
        if (!ledger.tryDeduct(request.callerId(), request.requestId(),
                params.epsilonPerQuery(), result)) {
            if (ledger.findCached(request.callerId(), request.requestId()).isPresent()) {
                throw new QueryRejectedException(RejectionReason.DUPLICATE_REQUEST,
                        "requestId 已处理过，重复提交被拒绝: " + request.requestId());
            }
            throw new QueryRejectedException(RejectionReason.BUDGET_EXHAUSTED,
                    String.format("隐私预算不足: 需要 %.3f，剩余 %.4f",
                            params.epsilonPerQuery(), ledger.balanceOf(request.callerId())));
        }
        double remaining = ledger.balanceOf(request.callerId());
        QueryResult finalResult = new QueryResult(value, errorMargin, params.epsilonPerQuery(),
                remaining, batchId, version, samples.size(), explanation);

        auditTrail.next(request.callerId(), request.requestId(), request.field(),
                request.statistic(), groupKey, version, batchId, params,
                params.epsilonPerQuery(), value, samples.size(), "OK");

        // 容量控制联动：以仍保留的最老审计记录为准裁剪版本快照，
        // 保证「保留窗口内的历史结论一定能复现」。
        auditTrail.oldestRetainedVersion().ifPresent(stateService::pruneBefore);
        return finalResult;
    }

    /**
     * 按审计序号取回历史结论，并以当时版本的参数与授权状态重新计算，
     * 验证可复现性。原审计记录或其版本已超出保留窗口时拒绝。
     */
    public ReplayResult replay(long auditSeq) {
        AuditTrail.Entry entry = auditTrail.bySeq(auditSeq)
                .orElseThrow(() -> new QueryRejectedException(RejectionReason.VERSION_NOT_FOUND,
                        "审计记录不存在或已超出保留窗口: seq=" + auditSeq));
        VersionSnapshot snapshot = stateService.snapshotAt(entry.version());
        if (snapshot == null) {
            throw new QueryRejectedException(RejectionReason.VERSION_NOT_FOUND,
                    "历史版本已超出保留窗口，无法复现: version=" + entry.version());
        }
        QueryParams params = snapshot.params();

        // 复现时同样按当时版本校验该调用方的授权状态，支持追溯授权口径。
        AuthState auth = stateService.authStateAt(
                entry.callerId(), entry.field(), entry.version());

        List<Double> samples = dataset.column(entry.field())
                .getOrDefault(entry.groupKey(), List.of());
        double sensitivity = sensitivityOf(entry.statistic(), samples.size(), params.maxValue());
        double noiseScale = sensitivity / params.epsilonPerQuery();
        double noise = laplaceNoise(
                seedOf(entry.callerId(), entry.field(), entry.statistic(),
                        entry.groupKey(), entry.batchId(), entry.version()),
                noiseScale);
        double replayed = rawStatistic(entry.statistic(), samples, params.maxValue()) + noise;

        return new ReplayResult(entry.version(), entry.batchId(), auth,
                entry.value(), replayed, samples.size());
    }

    /** 当前版本下某调用方对某字段的授权三态（供管理侧/测试查看）。 */
    public AuthState authState(String callerId, String field) {
        return stateService.authState(callerId, field);
    }

    private void validate(QueryRequest request) {
        if (request == null
                || isBlank(request.requestId()) || isBlank(request.callerId())
                || isBlank(request.field()) || request.statistic() == null) {
            throw new QueryRejectedException(RejectionReason.INVALID_PARAMETER,
                    "requestId/callerId/field/statistic 均不能为空");
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private double rawStatistic(QueryRequest request, List<Double> samples, double maxValue) {
        return rawStatistic(request.statistic(), samples, maxValue);
    }

    private double rawStatistic(com.github.feng111627.privacybudgetqueryjava.domain.StatisticType statistic,
                                List<Double> samples, double maxValue) {
        return switch (statistic) {
            case COUNT -> samples.size();
            case SUM -> samples.stream().mapToDouble(v -> clip(v, maxValue)).sum();
            case AVG -> samples.stream().mapToDouble(v -> clip(v, maxValue)).average().orElse(0.0);
        };
    }

    private double sensitivityOf(QueryRequest request, int n, double maxValue) {
        return sensitivityOf(request.statistic(), n, maxValue);
    }

    private double sensitivityOf(com.github.feng111627.privacybudgetqueryjava.domain.StatisticType statistic,
                                 int n, double maxValue) {
        return switch (statistic) {
            case COUNT -> 1.0;
            case SUM -> maxValue;
            case AVG -> maxValue / n;
        };
    }

    private double clip(double v, double maxValue) {
        return Math.max(-maxValue, Math.min(maxValue, v));
    }

    /**
     * 噪声种子：由调用方、预算批次、系统版本与查询内容决定，保证同批次同版本可复现。
     */
    private long seedOf(QueryRequest request, long batchId, long version) {
        return seedOf(request.callerId(), request.field(), request.statistic(),
                request.groupKey() == null ? Dataset.DEFAULT_GROUP : request.groupKey(),
                batchId, version);
    }

    private long seedOf(String callerId, String field,
                        com.github.feng111627.privacybudgetqueryjava.domain.StatisticType statistic,
                        String groupKey, long batchId, long version) {
        String canonical = callerId + "|" + batchId + "|" + version + "|" + field
                + "|" + statistic + "|" + groupKey;
        long seed = 1125899906842597L;
        for (int i = 0; i < canonical.length(); i++) {
            seed = 31 * seed + canonical.charAt(i);
        }
        return seed;
    }

    /**
     * 逆变换法生成拉普拉斯噪声，尺度 b = 敏感度 / ε。
     */
    private double laplaceNoise(long seed, double scale) {
        double u = new SplittableRandom(seed).nextDouble() - 0.5;
        return -scale * Math.signum(u) * Math.log(1 - 2 * Math.abs(u));
    }
}
