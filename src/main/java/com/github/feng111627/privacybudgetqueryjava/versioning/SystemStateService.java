package com.github.feng111627.privacybudgetqueryjava.versioning;

import com.github.feng111627.privacybudgetqueryjava.domain.AuthState;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryParams;
import com.github.feng111627.privacybudgetqueryjava.domain.VersionSnapshot;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 统一版本快照服务。
 *
 * <p>参数发布与「按调用方」的授权/撤权都在同一把锁内产生一个新的、不可变的
 * {@link VersionSnapshot}，版本号全局单调递增。快照同时携带：</p>
 * <ul>
 *   <li>该版本生效的统计参数（单次 ε、最小分组门槛、截断范围）；</li>
 *   <li>每个调用方对每个字段的授权三态（从未授权 / 已授权 / 授权后撤权）。</li>
 * </ul>
 *
 * <p>因此一次查询只需在入口取一次当前快照，参数判定、授权判定、预算扣减口径
 * 必然来自同一个版本；发布/授权/撤权与查询并发时，进行中的查询要么完整看到
 * 旧版本、要么完整看到新版本，不会半新半旧。</p>
 *
 * <p>历史快照在保留窗口内可按版本号取回（{@link #snapshotAt(long)}），支撑历史
 * 结论按当时参数与授权状态复现；超出窗口的快照由 {@link #pruneBefore(long)}
 * 裁剪，容量有界。</p>
 */
@Service
public class SystemStateService {

    /** v0 默认参数，与上一轮行为保持一致。 */
    public static final QueryParams DEFAULT_PARAMS = new QueryParams(0.1, 5, 1000.0);

    private final Object lock = new Object();

    /** version -> 不可变快照，仅在 lock 内增删。 */
    private final NavigableMap<Long, VersionSnapshot> history = new TreeMap<>();

    private volatile VersionSnapshot current;

    public SystemStateService() {
        VersionSnapshot v0 = new VersionSnapshot(0L, DEFAULT_PARAMS, Map.of());
        history.put(0L, v0);
        current = v0;
    }

    /** 当前（最新）快照。 */
    public VersionSnapshot current() {
        return current;
    }

    /** 当前版本号。 */
    public long currentVersion() {
        return current.version();
    }

    /**
     * 按版本号取快照；版本不存在或已超出保留窗口时返回 null。
     */
    public VersionSnapshot snapshotAt(long version) {
        synchronized (lock) {
            return history.get(version);
        }
    }

    /** 保留窗口内最早仍可复现的版本号。 */
    public long oldestRetainedVersion() {
        synchronized (lock) {
            return history.firstKey();
        }
    }

    /** 当前保留窗口内的全部版本号（升序只读视图）。 */
    public NavigableMap<Long, VersionSnapshot> retainedSnapshots() {
        synchronized (lock) {
            return new TreeMap<>(history);
        }
    }

    /**
     * 发布新参数版本。只改参数，授权状态原样继承。
     * 返回新版本号。
     */
    public long publishParams(QueryParams params) {
        validate(params);
        synchronized (lock) {
            VersionSnapshot next = new VersionSnapshot(
                    current.version() + 1, params, current.authByCaller());
            history.put(next.version(), next);
            current = next;
            return next.version();
        }
    }

    /**
     * 为指定调用方授权某字段，返回新版本号。只影响该调用方，其他调用方无感。
     */
    public long grant(String callerId, String field) {
        return applyAuth(callerId, field, AuthState.GRANTED);
    }

    /**
     * 撤权指定调用方的某字段，返回新版本号。只影响该调用方，其他调用方无感。
     */
    public long revoke(String callerId, String field) {
        return applyAuth(callerId, field, AuthState.REVOKED);
    }

    private long applyAuth(String callerId, String field, AuthState state) {
        if (callerId == null || callerId.isBlank() || field == null || field.isBlank()) {
            throw new IllegalArgumentException("callerId 与 field 均不能为空");
        }
        synchronized (lock) {
            Map<String, Map<String, AuthState>> copied = deepCopy(current.authByCaller());
            copied.computeIfAbsent(callerId, k -> new HashMap<>()).put(field, state);
            VersionSnapshot next = new VersionSnapshot(
                    current.version() + 1, current.params(), immutableDeepCopy(copied));
            history.put(next.version(), next);
            current = next;
            return next.version();
        }
    }

    /** 当前版本下某调用方对某字段的授权三态。 */
    public AuthState authState(String callerId, String field) {
        return stateOf(current, callerId, field);
    }

    /**
     * 指定历史版本下某调用方对某字段的授权三态。
     * 调用前应先确认 {@link #snapshotAt(long)} 非空（在保留窗口内）。
     */
    public AuthState authStateAt(String callerId, String field, long version) {
        VersionSnapshot snapshot;
        synchronized (lock) {
            snapshot = history.get(version);
        }
        return snapshot == null ? AuthState.NEVER_GRANTED : stateOf(snapshot, callerId, field);
    }

    private AuthState stateOf(VersionSnapshot snapshot, String callerId, String field) {
        return snapshot.authByCaller()
                .getOrDefault(callerId, Map.of())
                .getOrDefault(field, AuthState.NEVER_GRANTED);
    }

    /**
     * 裁剪掉版本号小于 oldestVersion 的快照。
     * 由查询编排层在审计保留窗口收敛后调用，保证「仍保留的审计结论一定能复现」。
     */
    public void pruneBefore(long oldestVersion) {
        synchronized (lock) {
            history.headMap(oldestVersion, false).clear();
        }
    }

    private static void validate(QueryParams params) {
        if (params == null
                || !(params.epsilonPerQuery() > 0)
                || params.minGroupSize() < 1
                || !(params.maxValue() > 0)) {
            throw new IllegalArgumentException(
                    "参数非法: epsilonPerQuery>0, minGroupSize>=1, maxValue>0");
        }
    }

    private static Map<String, Map<String, AuthState>> deepCopy(
            Map<String, Map<String, AuthState>> source) {
        Map<String, Map<String, AuthState>> copy = new HashMap<>();
        source.forEach((caller, fields) -> copy.put(caller, new HashMap<>(fields)));
        return copy;
    }

    private static Map<String, Map<String, AuthState>> immutableDeepCopy(
            Map<String, Map<String, AuthState>> source) {
        Map<String, Map<String, AuthState>> outer = new HashMap<>();
        source.forEach((caller, fields) -> outer.put(caller, Map.copyOf(fields)));
        return Map.copyOf(outer);
    }
}
