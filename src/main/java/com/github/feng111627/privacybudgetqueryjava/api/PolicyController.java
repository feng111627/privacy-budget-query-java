package com.github.feng111627.privacybudgetqueryjava.api;

import com.github.feng111627.privacybudgetqueryjava.ledger.BudgetLedger;
import com.github.feng111627.privacybudgetqueryjava.policy.FieldPolicyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 预算充值与字段授权/撤权入口。
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

    /** 授权字段。 */
    @PostMapping("/policy/grant/{field}")
    public Map<String, Object> grant(@PathVariable String field) {
        return Map.of("field", field, "policyVersion", policyService.grant(field));
    }

    /** 撤权字段。 */
    @PostMapping("/policy/revoke/{field}")
    public Map<String, Object> revoke(@PathVariable String field) {
        return Map.of("field", field, "policyVersion", policyService.revoke(field));
    }

    /** 查询当前规则版本与字段状态。 */
    @GetMapping("/policy/{field}")
    public Map<String, Object> policyState(@PathVariable String field) {
        return Map.of("field", field,
                "authorized", policyService.isAuthorized(field),
                "policyVersion", policyService.currentVersion());
    }
}
