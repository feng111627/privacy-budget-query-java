package com.github.feng111627.privacybudgetqueryjava.ledger;

import com.github.feng111627.privacybudgetqueryjava.model.BatchSnapshot;
import com.github.feng111627.privacybudgetqueryjava.model.QueryRecord;
import com.github.feng111627.privacybudgetqueryjava.model.QueryResponse;
import com.github.feng111627.privacybudgetqueryjava.model.RejectionCode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地隐私账本（内存实现）。
 *
 * 职责：
 * - 每个调用方拥有独立的预算批次、授权运行时状态、请求去重表与查询流水；
 * - 预算采用「预留 → 提交 / 释放」两阶段：{@link #reserve} 在调用方级锁内原子完成
 *   重复校验、撤权校验、批次校验与预算占位，因此并发请求不可能把预算扣成负数，
 *   同一个 requestId 也不可能被重复扣减；
 * - 接受与拒绝都会写入流水，流水带规则版本号，支撑审计与历史复现。
 */
@Component
public class PrivacyLedger {

    /** 浮点比较容差，避免 0.99+0.01 之类的表示误差导致误判。 */
    private static final double EPS = 1e-9;

    private final Map<String, CallerState> callers = new HashMap<>();

    /** 登记调用方，并按指定预算模型开设首个批次。 */
    public BatchSnapshot registerCaller(String callerId, String firstBatchId,
                                        double totalBudget, String budgetModelVersion) {
        CallerState state = createCaller(callerId);
        synchronized (state) {
            return openBatchLocked(state, firstBatchId, totalBudget, budgetModelVersion);
        }
    }

    public boolean existsCaller(String callerId) {
        synchronized (callers) {
            return callers.containsKey(callerId);
        }
    }

    /** 为已有调用方新开一个预算批次（批次号不可重复）。 */
    public BatchSnapshot openBatch(String callerId, String batchId,
                                   double totalBudget, String budgetModelVersion) {
        CallerState state = requireCaller(callerId);
        synchronized (state) {
            return openBatchLocked(state, batchId, totalBudget, budgetModelVersion);
        }
    }

    /** 调用方级整体撤权：撤权后新请求一律拒绝，历史流水不受影响。 */
    public void revokeCaller(String callerId) {
        CallerState state = requireCaller(callerId);
        synchronized (state) {
            state.enabled = false;
        }
    }

    /** 字段级撤权：被撤权字段不再参与该调用方的任何统计。 */
    public void revokeField(String callerId, String field) {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("field is required");
        }
        CallerState state = requireCaller(callerId);
        synchronized (state) {
            state.revokedFields.add(field);
        }
    }

    /** 恢复调用方的整体授权（字段级撤权记录仍保留）。 */
    public void reinstateCaller(String callerId) {
        CallerState state = requireCaller(callerId);
        synchronized (state) {
            state.enabled = true;
        }
    }

    public boolean isCallerAuthorized(String callerId) {
        CallerState state = requireCaller(callerId);
        synchronized (state) {
            return state.enabled;
        }
    }

    /** 运行时字段授权：调用方未被整体撤权且该字段未被撤权。 */
    public boolean isFieldAuthorized(String callerId, String field) {
        CallerState state = requireCaller(callerId);
        synchronized (state) {
            return state.enabled && !state.revokedFields.contains(field);
        }
    }

    public java.util.Set<String> revokedFields(String callerId) {
        CallerState state = requireCaller(callerId);
        synchronized (state) {
            return java.util.Set.copyOf(state.revokedFields);
        }
    }

    public BatchSnapshot snapshot(String callerId, String batchId) {
        CallerState state = requireCaller(callerId);
        synchronized (state) {
            return toSnapshot(state, requireBatch(state, batchId));
        }
    }

    /**
     * 原子预留预算。失败时抛 {@link LedgerRejection}（携带可区分的拒绝原因码）。
     * 成功后预算被占位，直到 {@link #commit} 或 {@link #reject} 释放。
     */
    public Reservation reserve(String callerId, String batchId, String requestId,
                               double epsilon, String fingerprint,
                               String budgetModelVersion, String authorizationVersion) {
        CallerState state = requireCaller(callerId);
        synchronized (state) {
            // 1) 重复提交优先判定：即使之后发生撤权，同一 requestId 的结论也保持一致。
            QueryRecord prior = state.requests.get(requestId);
            if (prior != null) {
                throw new LedgerRejection(RejectionCode.DUPLICATE_REQUEST,
                        "duplicate requestId=" + requestId + ", priorAccepted=" + prior.accepted());
            }
            // 2) 整体撤权。
            if (!state.enabled) {
                throw new LedgerRejection(RejectionCode.AUTHORIZATION_REVOKED,
                        "caller '" + callerId + "' has been revoked");
            }
            // 3) 批次存在性。
            BatchState batch = state.batches.get(batchId);
            if (batch == null) {
                throw new LedgerRejection(RejectionCode.BATCH_NOT_FOUND,
                        "batch '" + batchId + "' does not exist for caller '" + callerId + "'");
            }
            // 4) 预算余额（已用 + 其他在途预留 + 本次）。
            double available = batch.totalBudget - batch.used - batch.reserved;
            if (epsilon > available + EPS) {
                throw new LedgerRejection(RejectionCode.BUDGET_EXHAUSTED,
                        String.format("budget exhausted in batch '%s': requested=%.6f available=%.6f",
                                batchId, epsilon, available));
            }
            batch.reserved += epsilon;
            long seq = ++state.seq;
            return new Reservation(callerId, batchId, requestId, fingerprint, epsilon,
                    budgetModelVersion, authorizationVersion, seq);
        }
    }

    /** 提交预留：扣减预算并写入接受流水。 */
    public void commit(Reservation reservation, QueryResponse response) {
        CallerState state = requireCaller(reservation.callerId());
        synchronized (state) {
            BatchState batch = requireBatch(state, reservation.batchId());
            batch.reserved -= reservation.epsilon();
            batch.used += reservation.epsilon();
            if (batch.used > batch.totalBudget + EPS) {
                // 理论上不可达：reserve 已做原子检查。
                throw new IllegalStateException("budget overrun detected for caller="
                        + reservation.callerId());
            }
            QueryRecord record = new QueryRecord(
                    Instant.now(), reservation.callerId(), reservation.requestId(),
                    reservation.batchId(), reservation.fingerprint(), true, null,
                    reservation.epsilon(), reservation.budgetModelVersion(),
                    reservation.authorizationVersion(), response);
            state.requests.put(reservation.requestId(), record);
            state.history.add(record);
        }
    }

    /** 释放预留并写入拒绝流水（预算不扣减）。 */
    public void reject(Reservation reservation, RejectionCode code) {
        CallerState state = requireCaller(reservation.callerId());
        synchronized (state) {
            BatchState batch = requireBatch(state, reservation.batchId());
            batch.reserved -= reservation.epsilon();
            QueryRecord record = new QueryRecord(
                    Instant.now(), reservation.callerId(), reservation.requestId(),
                    reservation.batchId(), reservation.fingerprint(), false, code,
                    0d, reservation.budgetModelVersion(),
                    reservation.authorizationVersion(), null);
            state.requests.put(reservation.requestId(), record);
            state.history.add(record);
        }
    }

    /** 引擎失败时释放在途预留（预算不扣减，不留接受流水）。 */
    public void releaseOnFailure(Reservation reservation) {
        CallerState state = requireCaller(reservation.callerId());
        synchronized (state) {
            BatchState batch = requireBatch(state, reservation.batchId());
            batch.reserved -= reservation.epsilon();
        }
    }

    /**
     * 记录一个在预留之前就被拒绝的请求（参数非法、调用方/字段未授权等），
     * 同样写入去重表，保证同一 requestId 的重复提交结论一致。
     */
    public void recordPreReservationRejection(String callerId, String requestId, String batchId,
                                              String fingerprint, RejectionCode code,
                                              String budgetModelVersion,
                                              String authorizationVersion) {
        CallerState state = callers.get(callerId);
        if (state == null) {
            throw new LedgerRejection(RejectionCode.CALLER_NOT_FOUND,
                    "caller '" + callerId + "' is not registered");
        }
        synchronized (state) {
            if (state.requests.containsKey(requestId)) {
                return;
            }
            QueryRecord record = new QueryRecord(
                    Instant.now(), callerId, requestId, batchId, fingerprint,
                    false, code, 0d, budgetModelVersion, authorizationVersion, null);
            state.requests.put(requestId, record);
            state.history.add(record);
        }
    }

    /** 按 requestId 取回历史流水（无论接受或拒绝），不存在返回 null。 */
    public QueryRecord findRecord(String callerId, String requestId) {
        synchronized (callers) {
            CallerState state = callers.get(callerId);
            if (state == null) {
                return null;
            }
            synchronized (state) {
                return state.requests.get(requestId);
            }
        }
    }

    public List<QueryRecord> history(String callerId) {
        CallerState state = requireCaller(callerId);
        synchronized (state) {
            return List.copyOf(state.history);
        }
    }

    private CallerState createCaller(String callerId) {
        if (callerId == null || callerId.isBlank()) {
            throw new IllegalArgumentException("callerId is required");
        }
        synchronized (callers) {
            CallerState existing = callers.get(callerId);
            if (existing != null) {
                throw new IllegalArgumentException("caller already registered: " + callerId);
            }
            CallerState state = new CallerState(callerId);
            callers.put(callerId, state);
            return state;
        }
    }

    private CallerState requireCaller(String callerId) {
        synchronized (callers) {
            CallerState state = callers.get(callerId);
            if (state == null) {
                throw new LedgerRejection(RejectionCode.CALLER_NOT_FOUND,
                        "caller '" + callerId + "' is not registered");
            }
            return state;
        }
    }

    private BatchState requireBatch(CallerState state, String batchId) {
        BatchState batch = state.batches.get(batchId);
        if (batch == null) {
            throw new LedgerRejection(RejectionCode.BATCH_NOT_FOUND,
                    "batch '" + batchId + "' does not exist for caller '" + state.id + "'");
        }
        return batch;
    }

    private BatchSnapshot openBatchLocked(CallerState state, String batchId,
                                          double totalBudget, String budgetModelVersion) {
        if (batchId == null || batchId.isBlank()) {
            throw new IllegalArgumentException("batchId is required");
        }
        if (totalBudget <= 0d) {
            throw new IllegalArgumentException("totalBudget must be positive");
        }
        if (state.batches.containsKey(batchId)) {
            throw new IllegalArgumentException("batch already exists: " + batchId);
        }
        BatchState batch = new BatchState(batchId, totalBudget, budgetModelVersion);
        state.batches.put(batchId, batch);
        return toSnapshot(state, batch);
    }

    private static BatchSnapshot toSnapshot(CallerState state, BatchState batch) {
        double remaining = batch.totalBudget - batch.used - batch.reserved;
        return new BatchSnapshot(state.id, batch.id, batch.totalBudget, batch.used,
                Math.max(0d, remaining), batch.budgetModelVersion, batch.open);
    }

    /** 调用方可变状态：所有读写都在 this 监视器内进行。 */
    private static final class CallerState {
        final String id;
        boolean enabled = true;
        final java.util.Set<String> revokedFields = new java.util.HashSet<>();
        final Map<String, BatchState> batches = new HashMap<>();
        final Map<String, QueryRecord> requests = new HashMap<>();
        final List<QueryRecord> history = new ArrayList<>();
        long seq = 0;

        CallerState(String id) {
            this.id = id;
        }
    }

    /** 批次可变状态：在所属 CallerState 监视器内访问。 */
    private static final class BatchState {
        final String id;
        final double totalBudget;
        final String budgetModelVersion;
        double used;
        double reserved;
        boolean open = true;
        final Instant openedAt = Instant.now();

        BatchState(String id, double totalBudget, String budgetModelVersion) {
            this.id = id;
            this.totalBudget = totalBudget;
            this.budgetModelVersion = budgetModelVersion;
        }
    }

    /** 账本内部拒绝异常，由服务层翻译成 {@code RejectedQueryException}。 */
    public static final class LedgerRejection extends RuntimeException {
        private final RejectionCode code;

        public LedgerRejection(RejectionCode code, String message) {
            super(message);
            this.code = code;
        }

        public RejectionCode code() {
            return code;
        }
    }
}
