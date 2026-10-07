package com.github.feng111627.privacybudgetqueryjava.policy;

import com.github.feng111627.privacybudgetqueryjava.config.QueryParameters;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 授权与脱敏参数的统一版本化管理。
 *
 * <p><b>按调用方隔离的授权</b>：授权状态的键是 (调用方, 字段)。
 * 对调用方 A 撤权不影响调用方 B；每个调用方各自经历自己的
 * 「从未授权 → 授权 → 撤权」生命周期，两种未授权状态严格可区分
 * （{@link GrantState#NEVER} 与 {@link GrantState#REVOKED}）。
 *
 * <p><b>统一版本号</b>：授权/撤权与参数发布共用一条全局单调递增的版本线。
 * 每个版本对应一个不可变的 {@link PolicySnapshot}（版本号 + 当时的参数），
 * 一次查询完整落在某一个版本上。
 *
 * <p><b>历史复现</b>：每个 (调用方,字段) 的授权状态变化只记录实际发生的
 * 状态跃迁（无变化的操作不产生新版本），参数只在真正发布时入表。
 * 因此历史版本下的参数与授权状态都可精确重放，且历史不会随管理操作无限
 * 冗余堆积（容量与真实变更次数同阶）。
 */
@Component
public class FieldPolicyService {

    /** 某调用方对某字段的授权生命周期状态。 */
    public enum GrantState {
        /** 从来没有授权过 */
        NEVER,
        /** 已授权，可查询 */
        GRANTED,
        /** 曾经授权过，但已被撤掉 */
        REVOKED
    }

    /**
     * 一次授权状态跃迁（版本号、调用方、字段、跃迁后的状态）。
     */
    public record AuthChange(long version, String callerId, String field, GrantState state) {
    }

    /**
     * 某个版本的不可变快照：版本号 + 该版本生效的参数。
     * 查询开始时取一次快照，整个查询（授权判定、门槛/截断、扣预算）都引用它。
     */
    public record PolicySnapshot(long version, QueryParameters parameters) {
    }

    private record FieldKey(String callerId, String field) {
    }

    /** 当前版本的不可变持有者，单条 volatile 引用保证 (版本号, 参数) 成对可见。 */
    private volatile PolicySnapshot current =
            new PolicySnapshot(0L, QueryParameters.DEFAULTS);

    /** 版本号 -> 当时发布的参数；未发布参数的版本沿用上一条（floorEntry）。 */
    private final NavigableMap<Long, QueryParameters> parameterVersions = new ConcurrentSkipListMap<>();

    /** (调用方,字段) -> 按版本有序的授权跃迁列表，只保存实际跃迁，支撑任意版本重放。 */
    private final Map<FieldKey, CopyOnWriteArrayList<AuthChange>> grantHistory =
            new ConcurrentHashMap<>();

    /**
     * 为指定调用方授权字段，返回生效的新版本号；状态未变化时不产生新版本。
     */
    public long grant(String callerId, String field) {
        return apply(callerId, field, GrantState.GRANTED);
    }

    /**
     * 撤掉指定调用方对字段的授权，返回生效的新版本号。
     * 对从未授权过的键执行撤权，状态记为 {@link GrantState#REVOKED}
     * （管理侧明确执行过撤权动作），与“从未授权”区分。
     */
    public long revoke(String callerId, String field) {
        return apply(callerId, field, GrantState.REVOKED);
    }

    private long apply(String callerId, String field, GrantState next) {
        if (isBlank(callerId) || isBlank(field)) {
            throw new IllegalArgumentException("callerId 与 field 均不能为空");
        }
        FieldKey key = new FieldKey(callerId, field);
        synchronized (this) {
            CopyOnWriteArrayList<AuthChange> list = grantHistory.get(key);
            GrantState previous = (list == null || list.isEmpty())
                    ? GrantState.NEVER : list.get(list.size() - 1).state();
            if (previous == next) {
                // 状态无变化：不产生新版本、不留冗余记录
                return current.version();
            }
            long newVersion = current.version() + 1;
            grantHistory.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>())
                    .add(new AuthChange(newVersion, callerId, field, next));
            current = new PolicySnapshot(newVersion, current.parameters());
            return newVersion;
        }
    }

    /**
     * 发布一版新参数（任意覆盖项为 null 时沿用上一版），返回新版本号。
     */
    public long publishParameters(Double epsilonPerQueryOverride,
                                  Integer minGroupSizeOverride,
                                  Double maxValueOverride) {
        if (epsilonPerQueryOverride != null && epsilonPerQueryOverride <= 0) {
            throw new IllegalArgumentException("epsilonPerQuery 必须为正数");
        }
        if (minGroupSizeOverride != null && minGroupSizeOverride < 1) {
            throw new IllegalArgumentException("minGroupSize 必须 >= 1");
        }
        if (maxValueOverride != null && maxValueOverride <= 0) {
            throw new IllegalArgumentException("maxValue 必须为正数");
        }
        QueryParameters base = current.parameters();
        QueryParameters candidate = base.withVersion(0L, epsilonPerQueryOverride,
                minGroupSizeOverride, maxValueOverride);
        synchronized (this) {
            if (candidate.equals(current.parameters())) {
                // 参数无实际变化：不产生新版本
                return current.version();
            }
            long newVersion = current.version() + 1;
            QueryParameters published = candidate.withVersion(newVersion, null, null, null);
            parameterVersions.put(newVersion, published);
            current = new PolicySnapshot(newVersion, published);
            return newVersion;
        }
    }

    /** 当前版本快照（一次 volatile 读，版本号与参数必然配对）。 */
    public PolicySnapshot snapshot() {
        return current;
    }

    /** 当前版本号（授权变更与参数发布共用）。 */
    public long currentVersion() {
        return current.version();
    }

    /** 当前生效参数。 */
    public QueryParameters currentParameters() {
        return current.parameters();
    }

    /** 某调用方对某字段当前的授权状态。 */
    public GrantState grantState(String callerId, String field) {
        return grantStateAt(callerId, field, current.version());
    }

    /** 某调用方对某字段当前是否已授权。 */
    public boolean isAuthorized(String callerId, String field) {
        return grantState(callerId, field) == GrantState.GRANTED;
    }

    /**
     * 重放指定历史版本下某调用方对某字段的授权状态。
     */
    public GrantState grantStateAt(String callerId, String field, long version) {
        CopyOnWriteArrayList<AuthChange> list =
                grantHistory.get(new FieldKey(callerId, field));
        if (list == null) {
            return GrantState.NEVER;
        }
        GrantState state = GrantState.NEVER;
        for (AuthChange change : list) {
            if (change.version() > version) {
                break;
            }
            state = change.state();
        }
        return state;
    }

    /** 重放指定历史版本下某调用方对某字段是否已授权。 */
    public boolean isAuthorizedAt(String callerId, String field, long version) {
        return grantStateAt(callerId, field, version) == GrantState.GRANTED;
    }

    /**
     * 查询指定历史版本生效的参数（该版本未发布参数时沿用上一条；
     * 早于第一次发布时返回内置默认版）。
     */
    public QueryParameters parametersAt(long version) {
        Map.Entry<Long, QueryParameters> floor = parameterVersions.floorEntry(version);
        return floor == null ? QueryParameters.DEFAULTS : floor.getValue();
    }

    /** 全部授权跃迁记录（按版本号升序的只读视图）。 */
    public List<AuthChange> history() {
        return grantHistory.values().stream()
                .flatMap(List::stream)
                .sorted((a, b) -> Long.compare(a.version(), b.version()))
                .toList();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
