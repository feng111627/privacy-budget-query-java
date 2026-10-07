package com.github.feng111627.privacybudgetqueryjava.domain;

import java.util.Map;

/**
 * 一个不可变的系统版本快照：版本号 + 该版本生效的参数 +
 * 各调用方对各字段的授权状态。
 *
 * <p>一次查询从开始到结束钉住同一个快照，因此参数判定、授权判定、
 * 预算扣减口径必然来自同一个版本，不会出现半新半旧。</p>
 */
public record VersionSnapshot(long version,
                              QueryParams params,
                              Map<String, Map<String, AuthState>> authByCaller) {
}
