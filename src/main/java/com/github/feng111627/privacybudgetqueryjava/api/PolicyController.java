package com.github.feng111627.privacybudgetqueryjava.api;

import com.github.feng111627.privacybudgetqueryjava.domain.AuthState;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryParams;
import com.github.feng111627.privacybudgetqueryjava.domain.QueryRejectedException;
import com.github.feng111627.privacybudgetqueryjava.domain.RejectionReason;
import com.github.feng111627.privacybudgetqueryjava.domain.VersionSnapshot;
import com.github.feng111627.privacybudgetqueryjava.ledger.BudgetLedger;
import com.github.feng111627.privacybudgetqueryjava.versioning.SystemStateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 预算充值、按调用方授权/撤权、参数版本发布与版本查询入口。
 */
@RestController
@RequestMapping("/api/admin")
public class PolicyController {

    private final BudgetLedger ledger;
    private final SystemStateService stateService;

    public PolicyController(BudgetLedger ledger, SystemStateService stateService) {
        this.ledger = ledger;
        this.stateService = stateService;
    }

    /** 为调用方充值预算，开启新批次。 */
    @PostMapping("/budget/{callerId}")
    public Map<String, Object> topUp(@PathVariable String callerId, @RequestParam double epsilon) {
        long batch = ledger.topUp(callerId, epsilon);
        return Map.of("callerId", callerId, "batch", batch, "balance", ledger.balanceOf(callerId));
    }

    /** 为指定调用方授权字段，返回新版本号（不影响其他调用方）。 */
    @PostMapping("/policy/grant/{callerId}/{field}")
    public Map<String, Object> grant(@PathVariable String callerId, @PathVariable String field) {
        long version = stateService.grant(callerId, field);
        return Map.of("callerId", callerId, "field", field,
                "state", AuthState.GRANTED.name(), "version", version);
    }

    /** 撤权指定调用方的字段，返回新版本号（不影响其他调用方）。 */
    @PostMapping("/policy/revoke/{callerId}/{field}")
    public Map<String, Object> revoke(@PathVariable String callerId, @PathVariable String field) {
        long version = stateService.revoke(callerId, field);
        return Map.of("callerId", callerId, "field", field,
                "state", AuthState.REVOKED.name(), "version", version);
    }

    /** 查询某调用方在当前版本下对字段的授权三态。 */
    @GetMapping("/policy/{callerId}/{field}")
    public Map<String, Object> policyState(@PathVariable String callerId, @PathVariable String field) {
        return Map.of("callerId", callerId, "field", field,
                "state", stateService.authState(callerId, field).name(),
                "version", stateService.currentVersion());
    }

    /**
     * 发布新的参数版本（单次 ε、最小分组门槛、截断上限）。
     * 仅影响发布后的新查询，历史结论仍按各自版本复现。
     */
    @PostMapping("/params")
    public Map<String, Object> publishParams(@RequestBody QueryParams params) {
        long version = stateService.publishParams(params);
        return Map.of("version", version, "params", params);
    }

    /** 查询当前版本参数。 */
    @GetMapping("/params/current")
    public Map<String, Object> currentParams() {
        return describe(stateService.current());
    }

    /** 查询指定历史版本的参数与该版本下各调用方的授权状态。 */
    @GetMapping("/params/{version}")
    public Map<String, Object> paramsAt(@PathVariable long version) {
        VersionSnapshot snapshot = stateService.snapshotAt(version);
        if (snapshot == null) {
            throw new QueryRejectedException(RejectionReason.VERSION_NOT_FOUND,
                    "版本不存在或已超出保留窗口: " + version);
        }
        return describe(snapshot);
    }

    private Map<String, Object> describe(VersionSnapshot snapshot) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", snapshot.version());
        body.put("params", snapshot.params());
        body.put("oldestRetainedVersion", stateService.oldestRetainedVersion());
        body.put("authByCaller", snapshot.authByCaller());
        return body;
    }
}
