package com.github.feng111627.privacybudgetqueryjava.auth;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 授权规则版本注册表。
 *
 * 两类版本都会被完整保留：
 * 1. 通过 {@link #publish} 发布的规则变更（成为新查询默认采用的当前版本）；
 * 2. 通过 {@link #snapshot} 固化的运行时授权状态（例如调用方登记、字段撤权后），
 *    历史查询记录引用快照版本，即可取回当时真正生效的字段集合完成复现。
 */
@Component
public class AuthorizationRegistry {

    private final Map<String, AuthorizationPolicy> versions = new LinkedHashMap<>();
    private volatile String currentVersion;

    public AuthorizationRegistry() {
        AuthorizationPolicy empty = new AuthorizationPolicy(
                "auth-v0", Instant.parse("2026-01-01T00:00:00Z"), Map.of(), Map.of());
        publish(empty);
    }

    /** 发布规则版本并将其置为当前生效版本。 */
    public synchronized void publish(AuthorizationPolicy policy) {
        validate(policy);
        versions.put(policy.version(), policy);
        currentVersion = policy.version();
    }

    /** 固化一份运行时授权快照；不改变当前生效版本。 */
    public synchronized AuthorizationPolicy snapshot(String version, Instant effectiveAt,
                                                      Map<String, Set<String>> authorizedFields,
                                                      Map<String, Set<String>> revokedFields) {
        AuthorizationPolicy policy = new AuthorizationPolicy(
                version, effectiveAt == null ? Instant.now() : effectiveAt,
                authorizedFields, revokedFields);
        validate(policy);
        versions.put(version, policy);
        return policy;
    }

    private void validate(AuthorizationPolicy policy) {
        if (policy == null || policy.version() == null || policy.version().isBlank()) {
            throw new IllegalArgumentException("authorization policy and its version are required");
        }
    }

    public synchronized AuthorizationPolicy current() {
        return versions.get(currentVersion);
    }

    public synchronized Optional<AuthorizationPolicy> find(String version) {
        return Optional.ofNullable(versions.get(version));
    }

    public synchronized AuthorizationPolicy version(String version) {
        AuthorizationPolicy policy = versions.get(version);
        if (policy == null) {
            throw new IllegalArgumentException("unknown authorization version: " + version);
        }
        return policy;
    }

    public synchronized Map<String, AuthorizationPolicy> all() {
        return Map.copyOf(versions);
    }
}
