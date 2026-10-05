package com.github.feng111627.privacybudgetqueryjava.web;

import com.github.feng111627.privacybudgetqueryjava.model.BatchSnapshot;
import com.github.feng111627.privacybudgetqueryjava.service.PrivacyQueryService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/** 调用方登记、预算批次与授权撤权的管理接口（演示用）。 */
@RestController
@RequestMapping("/api/privacy/admin")
public class PrivacyAdminController {

    private final PrivacyQueryService service;

    public PrivacyAdminController(PrivacyQueryService service) {
        this.service = service;
    }

    /** 登记调用方并授权字段。请求体：{"fields": ["region", "income"]} */
    @PostMapping("/callers/{callerId}/register")
    public BatchSnapshot register(@PathVariable String callerId,
                                  @RequestBody(required = false) Map<String, Set<String>> body) {
        Set<String> fields = body == null ? Set.of() : body.getOrDefault("fields", Set.of());
        return service.registerCaller(callerId, fields);
    }

    @PostMapping("/callers/{callerId}/revoke")
    public Map<String, String> revokeCaller(@PathVariable String callerId) {
        service.revokeCaller(callerId);
        return Map.of("callerId", callerId, "status", "revoked");
    }

    @PostMapping("/callers/{callerId}/reinstate")
    public Map<String, String> reinstate(@PathVariable String callerId) {
        service.reinstateCaller(callerId);
        return Map.of("callerId", callerId, "status", "active");
    }

    /** 字段级撤权。请求体：{"field": "region"} */
    @PostMapping("/callers/{callerId}/revoke-field")
    public Map<String, String> revokeField(@PathVariable String callerId,
                                           @RequestBody Map<String, String> body) {
        service.revokeField(callerId, body.get("field"));
        return Map.of("callerId", callerId, "field", body.get("field"), "status", "revoked");
    }

    @PostMapping("/callers/{callerId}/batches/{batchId}")
    public BatchSnapshot openBatch(@PathVariable String callerId,
                                   @PathVariable String batchId) {
        return service.openBatch(callerId, batchId);
    }
}
