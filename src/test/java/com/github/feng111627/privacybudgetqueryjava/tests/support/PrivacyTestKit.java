package com.github.feng111627.privacybudgetqueryjava.tests.support;

import com.github.feng111627.privacybudgetqueryjava.auth.AuthorizationRegistry;
import com.github.feng111627.privacybudgetqueryjava.budget.BudgetRegistry;
import com.github.feng111627.privacybudgetqueryjava.data.Dataset;
import com.github.feng111627.privacybudgetqueryjava.engine.DeterministicLaplaceSampler;
import com.github.feng111627.privacybudgetqueryjava.engine.PrivacyQueryEngine;
import com.github.feng111627.privacybudgetqueryjava.ledger.PrivacyLedger;
import com.github.feng111627.privacybudgetqueryjava.service.PrivacyQueryService;

import java.util.Set;

/** 为每个测试装配一套全新（无共享状态）的组件。 */
public final class PrivacyTestKit {

    public final Dataset dataset = new Dataset();
    public final BudgetRegistry budgetRegistry = new BudgetRegistry();
    public final AuthorizationRegistry authorizationRegistry = new AuthorizationRegistry();
    public final PrivacyLedger ledger = new PrivacyLedger();
    public final PrivacyQueryService service;

    public PrivacyTestKit() {
        PrivacyQueryEngine engine = new PrivacyQueryEngine(dataset, new DeterministicLaplaceSampler());
        this.service = new PrivacyQueryService(ledger, engine, budgetRegistry,
                authorizationRegistry, dataset);
    }

    /** 登记一个拥有常见字段授权的调用方，返回首个批次ID。 */
    public String register(String callerId, String... fields) {
        service.registerCaller(callerId, Set.copyOf(java.util.Arrays.asList(fields)));
        return "batch-" + callerId + "-0";
    }

    /** 先发布预算模型，再登记调用方，使首个批次按新模型开设。返回首个批次ID。 */
    public String publishModelThenRegister(String callerId,
                                           com.github.feng111627.privacybudgetqueryjava.budget.BudgetModel model,
                                           String... fields) {
        budgetRegistry.publish(model);
        return register(callerId, fields);
    }
}
