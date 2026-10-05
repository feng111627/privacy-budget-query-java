package com.github.feng111627.privacybudgetqueryjava.auth;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * 字段级授权规则的一个不可变版本。
 * authorizedFields: 每个调用方被授权可用于统计的字段集合；
 * revokedFields: 在已授权字段中被显式撤权的字段（撤权语义优先于授权）。
 */
public record AuthorizationPolicy(
        String version,
        Instant effectiveAt,
        Map<String, Set<String>> authorizedFields,
        Map<String, Set<String>> revokedFields) {

    public AuthorizationPolicy {
        authorizedFields = Map.copyOf(authorizedFields);
        revokedFields = Map.copyOf(revokedFields);
    }
}
