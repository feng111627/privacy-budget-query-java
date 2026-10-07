package com.github.feng111627.privacybudgetqueryjava.api;

import com.github.feng111627.privacybudgetqueryjava.config.QueryParameters;
import com.github.feng111627.privacybudgetqueryjava.ledger.BudgetLedger;
import com.github.feng111627.privacybudgetqueryjava.policy.FieldPolicyService;
import com.github.feng111627.privacybudgetqueryjava.policy.FieldPolicyService.AuthChange;
import com.github.feng111627.privacybudgetqueryjava.policy.FieldPolicyService.GrantState;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 预算充值、按调用方的字段授权/撤权、参数版本发布与历史版本查询入口。
 */
@RestController
@RequestMapping("/api/admin")
public class PolicyController {

    private final BudgetLedger ledger;
    private final FieldPolicyService policyService;

    public PolicyController(BudgetLedger ledger, FieldPolicyService policyService) {
        this.ledger = ledger;
        this.policyService = policyService;
    }

    /** 为调用方充值预算，开启新批次。 */
    @PostMapping("/budget/{callerId}")
    public Map<String, Object> topUp(@PathVariable String callerId, @RequestParam double epsilon) {
        long batch = ledger.topUp(callerId, epsilon);
        return Map.of("callerId", callerId, "batch", batch, "balance", ledger.balanceOf(callerId));
    }

    /** 为指定调用方授权字段。 */
    @PostMapping("/policy/grant/{field}")
    public Map<String, Object> grant(@PathVariable String field, @RequestParam String callerId) {
        long version = policyService.grant(callerId, field);
        return Map.of("field", field, "callerId", callerId,
                "state", GrantState.GRANTED.name(), "policyVersion", version);
    }

    /** 撤掉指定调用方对字段的授权（不影响其他调用方）。 */
    @PostMapping("/policy/revoke/{field}")
    public Map<String, Object> revoke(@PathVariable String field, @RequestParam String callerId) {
        long version = policyService.revoke(callerId, field);
        return Map.of("field", field, "callerId", callerId,
                "state", GrantState.REVOKED.name(), "policyVersion", version);
    }

    /** 查询某调用方对某字段当前的授权状态与规则版本。 */
    @GetMapping("/policy/{field}")
    public Map<String, Object> policyState(@PathVariable String field,
                                           @RequestParam String callerId) {
        return Map.of("field", field, "callerId", callerId,
                "state", policyService.grantState(callerId, field).name(),
                "authorized", policyService.isAuthorized(callerId, field),
                "policyVersion", policyService.currentVersion());
    }

    /**
     * 发布一版新参数；任一项不传则沿用上一版。
     */
    @PostMapping("/parameters")
    public Map<String, Object> publishParameters(@RequestParam(required = false) Double epsilonPerQuery,
                                                 @RequestParam(required = false) Integer minGroupSize,
                                                 @RequestParam(required = false) Double maxValue) {
        long version = policyService.publishParameters(epsilonPerQuery, minGroupSize, maxValue);
        QueryParameters params = policyService.parametersAt(version);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("policyVersion", version);
        body.put("parameters", parameterBody(params));
        return body;
    }

    /** 查询历史某版本：当时生效的参数，以及（可选）某调用方对某字段当时的授权状态。 */
    @GetMapping("/versions/{version}")
    public Map<String, Object> versionAt(@PathVariable long version,
                                         @RequestParam(required = false) String callerId,
                                         @RequestParam(required = false) String field) {
        QueryParameters params = policyService.parametersAt(version);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("policyVersion", version);
        body.put("parameters", parameterBody(params));
        if (callerId != null && field != null) {
            body.put("grantState", policyService.grantStateAt(callerId, field, version).name());
            body.put("authorized", policyService.isAuthorizedAt(callerId, field, version));
        }
        return body;
    }

    /** 当前版本快照。 */
    @GetMapping("/versions/current")
    public Map<String, Object> currentVersion() {
        QueryParameters params = policyService.currentParameters();
        return Map.of("policyVersion", policyService.currentVersion(),
                "parameters", parameterBody(params));
    }

    /** 授权状态变更留痕（按版本升序）。 */
    @GetMapping("/policy/history")
    public java.util.List<Map<String, Object>> policyHistory() {
        return policyService.history().stream()
                .map(this::changeBody)
                .toList();
    }

    private Map<String, Object> parameterBody(QueryParameters p) {
        return Map.of("version", p.version(),
                "epsilonPerQuery", p.epsilonPerQuery(),
                "minGroupSize", p.minGroupSize(),
                "maxValue", p.maxValue());
    }

    private Map<String, Object> changeBody(AuthChange change) {
        return Map.of("version", change.version(),
                "callerId", change.callerId(),
                "field", change.field(),
                "state", change.state().name());
    }
}
