package com.github.feng111627.privacybudgetqueryjava.query;

import com.github.feng111627.privacybudgetqueryjava.audit.AuditTrail;
import com.github.feng111627.privacybudgetqueryjava.config.QueryParameters;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryRejectedException;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryRequest;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryResult;
import com.github.feng111627.privacybudgetqueryjava.domain.RejectionReason;
import com.github.feng111627.privacybudgetqueryjava.ledger.BudgetLedger;
import com.github.feng111627.privacybudgetqueryjava.policy.FieldPolicyService;
import com.github.feng111627.privacybudgetqueryjava.policy.FieldPolicyService.GrantState;
import com.github.feng111627.privacybudgetqueryjava.policy.FieldPolicyService.PolicySnapshot;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.SplittableRandom;

/**
 * 查询编排：参数校验 -> 幂等检查 -> <b>固定版本快照</b> -> 按调用方授权校验 ->
 * 最小分组保护 -> 确定性噪声注入 -> 原子预算扣减 -> 审计。
 *
 * <p><b>版本一致性</b>：查询在授权判定之前取一次 {@link PolicySnapshot}，
 * 之后授权状态、单次消耗、分组门槛、截断边界、噪声种子全部引用该快照的
 * 版本号与参数，保证一次查询完整落在某一个确定版本上，不会出现新旧参数、
 * 不同版本授权状态混用的情况。
 *
 * <p><b>可复现性</b>：噪声种子由 (callerId, 预算批次, <b>参数版本</b>, 查询内容)
 * 决定；参数版本发布后新查询走新版本，历史结论携带当时版本号，可按当时
 * 版本的参数与授权状态原样重算。
 */
@Service
public class QueryService {

    /** 默认口径下每次查询固定消耗的隐私预算（与第一轮行为一致）。 */
    public static final double EPSILON_PER_QUERY = QueryParameters.DEFAULTS.epsilonPerQuery();

    /** 默认口径下的最小分组样本量。 */
    public static final int MIN_GROUP_SIZE = QueryParameters.DEFAULTS.minGroupSize();

    /** 默认口径下的单条记录值域截断边界。 */
    public static final double MAX_VALUE = QueryParameters.DEFAULTS.maxValue();

    /** 拉普拉斯噪声 95% 置信区间的乘子：ln(1/0.05)。 */
    private static final double CI_95_MULTIPLIER = Math.log(20.0);

    private final BudgetLedger ledger;
    private final FieldPolicyService policyService;
    private final Dataset dataset;
    private final AuditTrail auditTrail;

    public QueryService(BudgetLedger ledger, FieldPolicyService policyService,
                        Dataset dataset, AuditTrail auditTrail) {
        this.ledger = ledger;
        this.policyService = policyService;
        this.dataset = dataset;
        this.auditTrail = auditTrail;
    }

    public QueryResult execute(QueryRequest request) {
        validate(request);

        // 幂等：同一调用方同一 requestId 在当前批次内只处理一次，
        // 重复提交被拒绝且不会重复扣减预算。
        if (ledger.findCached(request.callerId(), request.requestId()).isPresent()) {
            throw new QueryRejectedException(RejectionReason.DUPLICATE_REQUEST,
                    "requestId 已处理过，重复提交被拒绝: " + request.requestId());
        }

        // 整个查询固定在一个版本快照上：版本号与参数成对可见，
        // 并发发布/授权切换时不会读到“半截新、半截旧”的口径。
        PolicySnapshot snapshot = policyService.snapshot();
        long version = snapshot.version();
        QueryParameters params = snapshot.parameters();

        // 按调用方隔离的授权：“从未授权”与“授权后被撤”给出可区分的拒绝原因。
        GrantState grantState =
                policyService.grantStateAt(request.callerId(), request.field(), version);
        if (grantState != GrantState.GRANTED) {
            if (grantState == GrantState.NEVER) {
                throw new QueryRejectedException(RejectionReason.FIELD_NEVER_AUTHORIZED,
                        String.format("调用方 %s 对字段 %s 从未授权",
                                request.callerId(), request.field()));
            }
            throw new QueryRejectedException(RejectionReason.FIELD_AUTHORIZATION_REVOKED,
                    String.format("调用方 %s 对字段 %s 的授权已被撤掉",
                            request.callerId(), request.field()));
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

        // 确定性噪声注入（参数全部来自固定快照）。
        long batchId = ledger.currentBatch(request.callerId());
        double raw = rawStatistic(request, samples, params.maxValue());
        double sensitivity = sensitivityOf(request, samples.size(), params.maxValue());
        double noiseScale = sensitivity / params.epsilonPerQuery();
        double noise = laplaceNoise(seedOf(request, batchId, version), noiseScale);
        double value = raw + noise;
        double errorMargin = noiseScale * CI_95_MULTIPLIER;

        String explanation = String.format(
                "消耗预算 ε=%.3f（批次 %d，参数版本 %d）；拉普拉斯噪声尺度 b=%.4f，"
                        + "95%% 置信误差 ±%.4f；最小分组门槛 %d；截断 ±%.0f；样本量 %d",
                params.epsilonPerQuery(), batchId, version, noiseScale, errorMargin,
                params.minGroupSize(), params.maxValue(), samples.size());

        QueryResult result = new QueryResult(value, errorMargin, params.epsilonPerQuery(),
                0.0, batchId, version, samples.size(), explanation);

        // 原子扣减：扣减额同样取自固定快照，与授权/参数同一版本；
        // 并发下不会扣成负数；同一 requestId 不会被重复扣减。
        if (!ledger.tryDeduct(request.callerId(), request.requestId(),
                params.epsilonPerQuery(), result)) {
            if (ledger.findCached(request.callerId(), request.requestId()).isPresent()) {
                throw new QueryRejectedException(RejectionReason.DUPLICATE_REQUEST,
                        "requestId 已处理过，重复提交被拒绝: " + request.requestId());
            }
            throw new QueryRejectedException(RejectionReason.BUDGET_EXHAUSTED,
                    String.format("隐私预算不足: 需要 %.3f，剩余 %.4f（参数版本 %d）",
                            params.epsilonPerQuery(),
                            ledger.balanceOf(request.callerId()), version));
        }
        double remaining = ledger.balanceOf(request.callerId());
        QueryResult finalResult = new QueryResult(value, errorMargin, params.epsilonPerQuery(),
                remaining, batchId, version, samples.size(), explanation);

        auditTrail.next(request.callerId(), request.requestId(), request.field(),
                request.statistic(), groupKey, samples.size(),
                version, batchId, params.epsilonPerQuery(), value, errorMargin, "OK");
        return finalResult;
    }

    /**
     * 按历史版本重算结论：参数、授权状态、噪声种子全部按该版本/批次重放，
     * 不做预算扣减（历史复现是只读操作）。
     */
    public QueryResult replay(QueryRequest request, long version, long batchId) {
        validate(request);
        QueryParameters params = policyService.parametersAt(version);
        String groupKey = request.groupKey() == null ? Dataset.DEFAULT_GROUP : request.groupKey();
        List<Double> samples = dataset.column(request.field())
                .getOrDefault(groupKey, List.of());

        double raw = rawStatistic(request, samples, params.maxValue());
        double sensitivity = sensitivityOf(request, samples.size(), params.maxValue());
        double noiseScale = sensitivity / params.epsilonPerQuery();
        double noise = laplaceNoise(seedOf(request, batchId, version), noiseScale);
        double value = raw + noise;
        double errorMargin = noiseScale * CI_95_MULTIPLIER;

        String explanation = String.format(
                "按历史参数版本 %d、预算批次 %d 复现；ε=%.3f，噪声尺度 b=%.4f，样本量 %d",
                version, batchId, params.epsilonPerQuery(), noiseScale, samples.size());
        return new QueryResult(value, errorMargin, params.epsilonPerQuery(),
                ledger.balanceOf(request.callerId()), batchId, version,
                samples.size(), explanation);
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
        return switch (request.statistic()) {
            case COUNT -> samples.size();
            case SUM -> samples.stream().mapToDouble(v -> clip(v, maxValue)).sum();
            case AVG -> samples.stream().mapToDouble(v -> clip(v, maxValue)).average().orElse(0.0);
        };
    }

    private double sensitivityOf(QueryRequest request, int n, double maxValue) {
        return switch (request.statistic()) {
            case COUNT -> 1.0;
            case SUM -> maxValue;
            case AVG -> maxValue / n;
        };
    }

    private double clip(double v, double maxValue) {
        return Math.max(-maxValue, Math.min(maxValue, v));
    }

    /**
     * 噪声种子：由调用方、预算批次、参数版本与查询内容决定。
     * 参数版本进入种子，保证“同一查询在不同版本下结论可不同、
     * 同一版本下结论必一致、历史版本可原样复现”。
     */
    private long seedOf(QueryRequest request, long batchId, long version) {
        String canonical = request.callerId() + "|" + batchId + "|" + version + "|"
                + request.field() + "|" + request.statistic() + "|"
                + (request.groupKey() == null ? Dataset.DEFAULT_GROUP : request.groupKey());
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
