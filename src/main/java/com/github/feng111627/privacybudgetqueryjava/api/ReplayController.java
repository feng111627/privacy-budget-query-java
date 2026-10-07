package com.github.feng111627.privacybudgetqueryjava.api;

import com.github.feng111627.privacybudgetqueryjava.audit.AuditTrail;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryRequest;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryResult;
import com.github.feng111627.privacybudgetqueryjava.query.Dataset;
import com.github.feng111627.privacybudgetqueryjava.query.QueryService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 历史结论复现入口：按审计记录的 requestId 取回当时的查询，
 * 用当时的参数版本与预算批次重算，并与原始结论逐字段比对。
 */
@RestController
@RequestMapping("/api/admin/replay")
public class ReplayController {

    private final AuditTrail auditTrail;
    private final QueryService queryService;

    public ReplayController(AuditTrail auditTrail, QueryService queryService) {
        this.auditTrail = auditTrail;
        this.queryService = queryService;
    }

    @GetMapping("/{callerId}/{requestId}")
    public Map<String, Object> replay(@PathVariable String callerId,
                                      @PathVariable String requestId) {
        AuditTrail.Entry entry = auditTrail.entriesOf(callerId).stream()
                .filter(e -> e.requestId().equals(requestId))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "审计窗口内未找到该 requestId（可能已超出保留窗口）: " + requestId));

        String groupKey = Dataset.DEFAULT_GROUP.equals(entry.groupKey()) ? null : entry.groupKey();
        QueryRequest original = new QueryRequest(entry.requestId(), entry.callerId(), entry.field(),
                entry.statistic(), groupKey);
        QueryResult recomputed = queryService.replay(original, entry.policyVersion(), entry.batchId());

        boolean exact = Math.abs(entry.value() - recomputed.value()) < 1e-12
                && Math.abs(entry.errorMargin() - recomputed.errorMargin()) < 1e-12
                && Math.abs(entry.epsilonConsumed() - recomputed.epsilonConsumed()) < 1e-12;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("callerId", callerId);
        body.put("requestId", requestId);
        body.put("policyVersion", entry.policyVersion());
        body.put("batchId", entry.batchId());
        body.put("recorded", Map.of("value", entry.value(),
                "errorMargin", entry.errorMargin(),
                "epsilonConsumed", entry.epsilonConsumed()));
        body.put("recomputed", Map.of("value", recomputed.value(),
                "errorMargin", recomputed.errorMargin(),
                "epsilonConsumed", recomputed.epsilonConsumed()));
        body.put("exactMatch", exact);
        return body;
    }
}
