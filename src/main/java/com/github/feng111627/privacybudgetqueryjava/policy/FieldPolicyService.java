package com.github.feng111627.privacybudgetqueryjava.policy;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 字段级授权与撤权。
 *
 * 每次授权/撤权产生一条带版本号的规则快照，版本号单调递增。
 * 历史查询结论记录其生效版本，可通过 {@link #isAuthorizedAt(String, long)}
 * 追溯到当时的规则状态，规则更新不会让历史结论无法复现。
 */
@Component
public class FieldPolicyService {

    /**
     * 一条规则变更：版本号、字段、该版本起是否授权。
     */
    public record RuleChange(long version, String field, boolean authorized) {
    }

    private final List<RuleChange> history = new CopyOnWriteArrayList<>();
    private final Map<String, Boolean> current = new ConcurrentHashMap<>();
    private volatile long version = 0;

    /**
     * 授权字段，返回新的规则版本号。
     */
    public synchronized long grant(String field) {
        return apply(field, true);
    }

    /**
     * 撤权字段，返回新的规则版本号。
     */
    public synchronized long revoke(String field) {
        return apply(field, false);
    }

    private long apply(String field, boolean authorized) {
        version += 1;
        current.put(field, authorized);
        history.add(new RuleChange(version, field, authorized));
        return version;
    }

    /**
     * 当前版本下字段是否可用。
     */
    public boolean isAuthorized(String field) {
        return current.getOrDefault(field, false);
    }

    /**
     * 当前规则版本号。
     */
    public long currentVersion() {
        return version;
    }

    /**
     * 查询指定历史版本下字段是否可用（用于复现历史结论）。
     * 重放 <= version 的所有变更，取该字段最后一条的状态。
     */
    public boolean isAuthorizedAt(String field, long version) {
        boolean authorized = false;
        for (RuleChange change : history) {
            if (change.version() > version) {
                break;
            }
            if (change.field().equals(field)) {
                authorized = change.authorized();
            }
        }
        return authorized;
    }

    /**
     * 完整规则变更历史（只读视图）。
     */
    public List<RuleChange> history() {
        return List.copyOf(history);
    }
}
