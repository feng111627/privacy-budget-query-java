package com.github.feng111627.privacybudgetqueryjava.service;

import com.github.feng111627.privacybudgetqueryjava.auth.AuthorizationPolicy;
import com.github.feng111627.privacybudgetqueryjava.auth.AuthorizationRegistry;
import com.github.feng111627.privacybudgetqueryjava.budget.BudgetModel;
import com.github.feng111627.privacybudgetqueryjava.budget.BudgetRegistry;
import com.github.feng111627.privacybudgetqueryjava.data.Dataset;
import com.github.feng111627.privacybudgetqueryjava.engine.PrivacyQueryEngine;
import com.github.feng111627.privacybudgetqueryjava.ledger.PrivacyLedger;
import com.github.feng111627.privacybudgetqueryjava.ledger.Reservation;
import com.github.feng111627.privacybudgetqueryjava.model.BatchSnapshot;
import com.github.feng111627.privacybudgetqueryjava.model.DataRow;
import com.github.feng111627.privacybudgetqueryjava.model.MetricType;
import com.github.feng111627.privacybudgetqueryjava.model.QueryRecord;
import com.github.feng111627.privacybudgetqueryjava.model.QueryRequest;
import com.github.feng111627.privacybudgetqueryjava.model.QueryResponse;
import com.github.feng111627.privacybudgetqueryjava.model.RejectionCode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 查询编排服务。
 *
 * 处理顺序（每一步的拒绝原因都可区分）：
 * 1. 参数合法性校验（INVALID_PARAMETER）；
 * 2. 调用方登记校验（CALLER_NOT_FOUND）与重复提交（DUPLICATE_REQUEST）；
 * 3. 调用方整体撤权 / 字段未授权 / 字段撤权
 *    （AUTHORIZATION_REVOKED / FIELD_NOT_AUTHORIZED）；
 * 4. 账本原子预留预算（BATCH_NOT_FOUND / BUDGET_EXHAUSTED / DUPLICATE_REQUEST）；
 * 5. 引擎按确定性噪声执行，提交预算与流水。
 */
@Service
public class PrivacyQueryService {

    private final PrivacyLedger ledger;
    private final PrivacyQueryEngine engine;
    private final BudgetRegistry budgetRegistry;
    private final AuthorizationRegistry authorizationRegistry;
    private final Dataset dataset;

    public PrivacyQueryService(PrivacyLedger ledger, PrivacyQueryEngine engine,
                               BudgetRegistry budgetRegistry,
                               AuthorizationRegistry authorizationRegistry,
                               Dataset dataset) {
        this.ledger = ledger;
        this.engine = engine;
        this.budgetRegistry = budgetRegistry;
        this.authorizationRegistry = authorizationRegistry;
        this.dataset = dataset;
    }

    /** 登记调用方：授予字段集合，并按当前预算模型开设首个批次。 */
    public BatchSnapshot registerCaller(String callerId, Set<String> authorizedFields) {
        BudgetModel model = budgetRegistry.current();
        String version = publishCallerPolicy(callerId, authorizedFields == null ? Set.of() : authorizedFields);
        return ledger.registerCaller(callerId, defaultBatchId(callerId, 0),
                model.totalEpsilon(), model.version());
    }

    public void grantFields(String callerId, Set<String> fields) {
        // 重新发布包含新增字段的基线规则；运行时撤权集合仍保留在账本中。
        AuthorizationPolicy base = authorizationRegistry.current();
        Set<String> merged = new HashSet<>(base.authorizedFields()
                .getOrDefault(callerId, Set.of()));
        merged.addAll(fields);
        publishCallerPolicy(callerId, merged);
    }

    public void revokeCaller(String callerId) {
        ledger.revokeCaller(callerId);
    }

    public void reinstateCaller(String callerId) {
        ledger.reinstateCaller(callerId);
    }

    public void revokeField(String callerId, String field) {
        ledger.revokeField(callerId, field);
    }

    public BatchSnapshot openBatch(String callerId, String batchId) {
        return ledger.openBatch(callerId, batchId,
                budgetRegistry.current().totalEpsilon(), budgetRegistry.current().version());
    }

    public BatchSnapshot snapshot(String callerId, String batchId) {
        return ledger.snapshot(callerId, batchId);
    }

    public List<QueryRecord> history(String callerId) {
        return ledger.history(callerId);
    }

    /** 提交一次隐私统计查询。 */
    public QueryResponse submit(QueryRequest request) {
        validate(request);
        String callerId = request.callerId();

        if (!ledger.existsCaller(callerId)) {
            throw reject(RejectionCode.CALLER_NOT_FOUND,
                    "caller '" + callerId + "' is not registered");
        }

        // 重复提交最先判定：相同 requestId 无论何时重放，结论都保持一致。
        QueryRecord prior = ledger.findRecord(callerId, request.requestId());
        if (prior != null) {
            throw reject(RejectionCode.DUPLICATE_REQUEST,
                    "duplicate requestId=" + request.requestId()
                            + ", priorAccepted=" + prior.accepted());
        }

        BudgetModel model = budgetRegistry.current();

        // 授权校验：基线规则（版本注册表）+ 运行时撤权（账本）。
        if (!ledger.isCallerAuthorized(callerId)) {
            rejectAndRecord(callerId, request, model, null,
                    RejectionCode.AUTHORIZATION_REVOKED,
                    "caller '" + callerId + "' has been revoked");
        }
        AuthorizationPolicy base = authorizationRegistry.current();
        Set<String> baseline = base.authorizedFields().getOrDefault(callerId, Set.of());
        Set<String> runtimeRevoked = ledger.revokedFields(callerId);

        Set<String> queryFields = queryFields(request);
        for (String field : queryFields) {
            if (!baseline.contains(field)) {
                rejectAndRecord(callerId, request, model, base.version(),
                        RejectionCode.FIELD_NOT_AUTHORIZED,
                        "field '" + field + "' is not authorized for caller '" + callerId + "'");
            }
            if (runtimeRevoked.contains(field)) {
                rejectAndRecord(callerId, request, model, base.version(),
                        RejectionCode.AUTHORIZATION_REVOKED,
                        "field '" + field + "' has been revoked for caller '" + callerId + "'");
            }
        }

        // 固化“当时生效”的授权快照（基线授权减去运行时撤权），供历史复现使用。
        AuthorizationPolicy effective = effectiveSnapshot(callerId, base, runtimeRevoked);

        double epsilon = cost(request, model);
        String fingerprint = fingerprint(request, model.version());

        Reservation reservation;
        try {
            reservation = ledger.reserve(callerId, request.batchId(), request.requestId(),
                    epsilon, fingerprint, model.version(), effective.version());
        } catch (PrivacyLedger.LedgerRejection e) {
            throw reject(e.code(), e.getMessage());
        }

        try {
            BatchSnapshot snap = ledger.snapshot(callerId, request.batchId());
            QueryResponse response = engine.run(request, epsilon, snap.remainingBudget(),
                    model, effective, fingerprint);
            ledger.commit(reservation, response);
            return response;
        } catch (RuntimeException e) {
            // 引擎异常时释放预留，绝不允许预算被悬空占用或重复扣。
            ledger.releaseOnFailure(reservation);
            throw e;
        }
    }

    private static double cost(QueryRequest request, BudgetModel model) {
        return request.metric() == MetricType.COUNT
                ? model.countCostEpsilon()
                : model.sumCostEpsilon();
    }

    private static Set<String> queryFields(QueryRequest request) {
        Set<String> fields = new HashSet<>();
        fields.add(request.dimensionField());
        if (request.metric() == MetricType.SUM) {
            fields.add(request.metricField());
        }
        return fields;
    }

    /** 指纹：同一调用方、同一预算批次、相同查询要素 + 模型版本 → 同一指纹。 */
    private static String fingerprint(QueryRequest request, String modelVersion) {
        String metricPart = request.metric() == MetricType.COUNT
                ? "COUNT"
                : "SUM:" + request.metricField();
        return "fp:" + request.callerId()
                + "|" + request.batchId()
                + "|" + request.dimensionField()
                + "|" + metricPart
                + "|" + modelVersion;
    }

    /** 合并基线授权与运行时撤权，固化为不可变快照版本。 */
    private AuthorizationPolicy effectiveSnapshot(String callerId, AuthorizationPolicy base,
                                                   Set<String> runtimeRevoked) {
        Set<String> effective = new HashSet<>(
                base.authorizedFields().getOrDefault(callerId, Set.of()));
        effective.removeAll(runtimeRevoked);
        Map<String, Set<String>> authorized = new HashMap<>(base.authorizedFields());
        authorized.put(callerId, Set.copyOf(effective));
        Map<String, Set<String>> revoked = new HashMap<>(base.revokedFields());
        revoked.put(callerId, Set.copyOf(runtimeRevoked));
        String version = "eff-" + callerId + "-" + Integer.toHexString(
                (effective + "|" + runtimeRevoked).hashCode());
        return authorizationRegistry.snapshot(version, Instant.now(), authorized, revoked);
    }

    private String publishCallerPolicy(String callerId, Set<String> authorizedForCaller) {
        AuthorizationPolicy base = authorizationRegistry.current();
        Map<String, Set<String>> authorized = new HashMap<>(base.authorizedFields());
        authorized.put(callerId, Set.copyOf(authorizedForCaller));
        String version = "auth-" + callerId + "-" + Instant.now().toEpochMilli();
        authorizationRegistry.publish(
                new AuthorizationPolicy(version, Instant.now(), authorized, base.revokedFields()));
        return version;
    }

    private static String defaultBatchId(String callerId, int index) {
        return "batch-" + callerId + "-" + index;
    }

    private void validate(QueryRequest request) {
        if (request == null) {
            throw reject(RejectionCode.INVALID_PARAMETER, "request body is required");
        }
        if (isBlank(request.callerId()) || isBlank(request.requestId())
                || isBlank(request.batchId()) || isBlank(request.dimensionField())
                || request.metric() == null) {
            throw reject(RejectionCode.INVALID_PARAMETER,
                    "callerId, requestId, batchId, dimensionField, metric are required");
        }
        if (request.metric() == MetricType.SUM && isBlank(request.metricField())) {
            throw reject(RejectionCode.INVALID_PARAMETER, "metricField is required for SUM");
        }
        if (unknownField(request)) {
            throw reject(RejectionCode.INVALID_PARAMETER,
                    "query references an unknown field (dimension or metric)");
        }
    }

    private boolean unknownField(QueryRequest request) {
        if (dataset.rows().isEmpty()) {
            return false;
        }
        DataRow sample = dataset.rows().get(0);
        if (!sample.dimensions().containsKey(request.dimensionField())) {
            return true;
        }
        return request.metric() == MetricType.SUM
                && !sample.metrics().containsKey(request.metricField());
    }

    private void rejectAndRecord(String callerId, QueryRequest request, BudgetModel model,
                                 String authorizationVersion, RejectionCode code, String message) {
        String fp = "fp:" + callerId + "|" + request.batchId() + "|"
                + request.dimensionField() + "|" + request.metric() + "|" + model.version();
        try {
            ledger.recordPreReservationRejection(callerId, request.requestId(), request.batchId(),
                    fp, code, model.version(),
                    authorizationVersion == null ? "none" : authorizationVersion);
        } catch (PrivacyLedger.LedgerRejection e) {
            // 例如调用方在到达此步骤时已不存在。
            throw reject(e.code(), e.getMessage());
        }
        throw reject(code, message);
    }

    private static RejectedQueryException reject(RejectionCode code, String message) {
        return new RejectedQueryException(code, message);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
