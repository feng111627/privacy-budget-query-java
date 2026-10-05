package com.github.feng111627.privacybudgetqueryjava.query;

import com.github.feng111627.privacybudgetqueryjava.audit.AuditTrail;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryRejectedException;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryRequest;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryResult;
import com.github.feng111627.privacybudgetqueryjava.domain.RejectionReason;
import com.github.feng111627.privacybudgetqueryjava.ledger.BudgetLedger;
import com.github.feng111627.privacybudgetqueryjava.policy.FieldPolicyService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.SplittableRandom;

/**
 * 查询编排：参数校验 -> 幂等检查 -> 授权校验 -> 最小分组保护 ->
 * 确定性噪声注入 -> 原子预算扣减 -> 审计。
 *
 * 可复现性：噪声种子由 (callerId, 预算批次, 查询内容) 决定，
 * 同一调用方在同一批次内对相同查询必然得到相同结论。
 */
@Service
public class QueryService {

    /** 每次查询固定消耗的隐私预算。 */
    public static final double EPSILON_PER_QUERY = 0.1;

    /** 最小分组样本量，不足时拒绝返回结果。 */
    public static final int MIN_GROUP_SIZE = 5;

    /** 单条记录的值域截断边界，用于限定 SUM/AVG 的敏感度。 */
    public static final double MAX_VALUE = 1000.0;

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

        // 授权：未授权或已撤权的字段不得参与统计。
        if (!policyService.isAuthorized(request.field())) {
            throw new QueryRejectedException(RejectionReason.FIELD_NOT_AUTHORIZED,
                    "字段未授权或已被撤权: " + request.field());
        }
        long policyVersion = policyService.currentVersion();

        // 最小分组保护：样本不足的分组不得返回结果（也不扣预算）。
        String groupKey = request.groupKey() == null ? Dataset.DEFAULT_GROUP : request.groupKey();
        List<Double> samples = dataset.column(request.field())
                .getOrDefault(groupKey, List.of());
        if (samples.size() < MIN_GROUP_SIZE) {
            throw new QueryRejectedException(RejectionReason.INSUFFICIENT_SAMPLE,
                    "分组样本量不足: " + samples.size() + " < " + MIN_GROUP_SIZE);
        }

        // 确定性噪声注入。
        long batchId = ledger.currentBatch(request.callerId());
        double raw = rawStatistic(request, samples);
        double sensitivity = sensitivityOf(request, samples.size());
        double noiseScale = sensitivity / EPSILON_PER_QUERY;
        double noise = laplaceNoise(seedOf(request, batchId), noiseScale);
        double value = raw + noise;
        double errorMargin = noiseScale * CI_95_MULTIPLIER;

        String explanation = String.format(
                "消耗预算 ε=%.2f（批次 %d，规则版本 %d）；拉普拉斯噪声尺度 b=%.4f，"
                        + "95%% 置信误差 ±%.4f；样本量 %d",
                EPSILON_PER_QUERY, batchId, policyVersion, noiseScale, errorMargin, samples.size());

        QueryResult result = new QueryResult(value, errorMargin, EPSILON_PER_QUERY,
                0.0, batchId, policyVersion, samples.size(), explanation);

        // 原子扣减：并发下不会扣成负数；同一 requestId 不会被重复扣减。
        if (!ledger.tryDeduct(request.callerId(), request.requestId(), EPSILON_PER_QUERY, result)) {
            if (ledger.findCached(request.callerId(), request.requestId()).isPresent()) {
                throw new QueryRejectedException(RejectionReason.DUPLICATE_REQUEST,
                        "requestId 已处理过，重复提交被拒绝: " + request.requestId());
            }
            throw new QueryRejectedException(RejectionReason.BUDGET_EXHAUSTED,
                    String.format("隐私预算不足: 需要 %.2f，剩余 %.4f",
                            EPSILON_PER_QUERY, ledger.balanceOf(request.callerId())));
        }
        double remaining = ledger.balanceOf(request.callerId());
        QueryResult finalResult = new QueryResult(value, errorMargin, EPSILON_PER_QUERY,
                remaining, batchId, policyVersion, samples.size(), explanation);

        auditTrail.next(request.callerId(), request.requestId(), request.field(),
                policyVersion, batchId, EPSILON_PER_QUERY, value, "OK");
        return finalResult;
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

    private double rawStatistic(QueryRequest request, List<Double> samples) {
        return switch (request.statistic()) {
            case COUNT -> samples.size();
            case SUM -> samples.stream().mapToDouble(v -> clip(v)).sum();
            case AVG -> samples.stream().mapToDouble(v -> clip(v)).average().orElse(0.0);
        };
    }

    private double sensitivityOf(QueryRequest request, int n) {
        return switch (request.statistic()) {
            case COUNT -> 1.0;
            case SUM -> MAX_VALUE;
            case AVG -> MAX_VALUE / n;
        };
    }

    private double clip(double v) {
        return Math.max(-MAX_VALUE, Math.min(MAX_VALUE, v));
    }

    /**
     * 噪声种子：由调用方、预算批次与查询内容决定，保证同批次内可复现。
     */
    private long seedOf(QueryRequest request, long batchId) {
        String canonical = request.callerId() + "|" + batchId + "|" + request.field()
                + "|" + request.statistic() + "|"
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
