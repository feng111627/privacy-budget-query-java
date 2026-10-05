# privacy-budget-query-java

带隐私预算管理的统计查询服务。通过一个本地账本（`BudgetLedger`）管理每个调用方
可消耗的隐私预算与字段授权状态，保证不同调用方在重复查询、并发查询以及撤权之后
拿到的结论一致、可解释、可复现。

## 架构

```
api/      QueryController（查询入口）、PolicyController（充值/授权/撤权）、ApiExceptionHandler（拒绝原因映射）
domain/   QueryRequest / QueryResult / RejectionReason / QueryRejectedException
ledger/   BudgetLedger    —— 本地账本：预算余额、预算批次、幂等记录
policy/   FieldPolicyService —— 字段级授权/撤权，版本化规则历史
query/    QueryService（编排）、Dataset（内存数据集）
audit/    AuditTrail      —— 审计日志：每次查询的规则版本、批次、消耗与结论
```

## 预算模型

- 每次查询固定消耗 **ε = 0.1**（`QueryService.EPSILON_PER_QUERY`）。
- 调用方通过 `POST /api/admin/budget/{callerId}?epsilon=...` 充值；**每次充值开启一个新的
  预算批次**（批次号单调递增），批次号参与噪声种子计算。
- 扣减在账本内按调用方串行化（每个调用方一个临界区），扣减与幂等登记是**同一个原子
  操作**：余额不足时不扣减、不登记、无任何副作用；并发下不会扣成负数，也不会对同一
  请求重复扣减。
- 被拒绝的查询（参数非法、样本不足、未授权、重复提交、预算耗尽）**一律不消耗预算**。

### 拒绝原因（可区分）

| 原因 | 含义 | HTTP |
|---|---|---|
| `INVALID_PARAMETER` | 参数非法 | 400 |
| `BUDGET_EXHAUSTED` | 预算不足 | 402 |
| `DUPLICATE_REQUEST` | 同一调用方同一 `requestId` 重复提交 | 409 |
| `INSUFFICIENT_SAMPLE` | 分组样本量不足 | 422 |
| `FIELD_NOT_AUTHORIZED` | 字段未授权或已撤权 | 403 |

## 噪声与脱敏规则

- **最小分组保护**：分组样本量 < 5（`MIN_GROUP_SIZE`）时直接拒绝，不返回任何统计值。
- **值域截断**：单条记录截断到 [-1000, 1000]（`MAX_VALUE`），用于限定敏感度：
  COUNT 敏感度 1，SUM 敏感度 1000，AVG 敏感度 1000/n。
- **拉普拉斯噪声**：尺度 b = 敏感度 / ε，逆变换法生成。
- **可复现性**：噪声种子由 `(callerId, 预算批次, 字段, 统计类型, 分组键)` 决定，
  同一调用方在同一预算批次内对相同查询必然得到相同结论；充值开启新批次后种子改变。
- **可解释性**：每次响应携带 `errorMargin`（95% 置信区间 = b·ln20）、`epsilonConsumed`、
  `budgetRemaining`、`batchId`、`policyVersion` 以及人类可读的 `explanation` 说明。

## 授权语义

- 字段级授权/撤权：`POST /api/admin/policy/grant/{field}`、`/revoke/{field}`。
- 每次变更产生**单调递增的规则版本号**，全部变更留痕（`FieldPolicyService.history()`）。
- 未授权或已撤权的字段不得参与统计（`FIELD_NOT_AUTHORIZED`）。
- 每条查询结论记录当时生效的 `policyVersion`；通过
  `isAuthorizedAt(field, version)` 可重放任一历史版本的授权状态，
  **规则更新不会让历史结论无法复现**。审计日志（`AuditTrail`）按序记录每次查询的
  规则版本、批次、消耗与结论，支撑完整追溯。

## API 一览

```
POST /api/query                      统计查询（body: QueryRequest）
POST /api/admin/budget/{callerId}    充值预算（参数 epsilon），开启新批次
POST /api/admin/policy/grant/{field}   授权字段
POST /api/admin/policy/revoke/{field}  撤权字段
GET  /api/admin/policy/{field}         查询字段当前授权状态与规则版本
```

## 验证方式

```
mvn test
```

测试日志以 `[单测覆盖] 业务=... | 用例=... | 结果=...` 格式输出，覆盖：

- **本地账本**（`BudgetLedgerTest`）：并发扣减不为负、重复 requestId 幂等、
  余额不足无副作用、充值开新批次。
- **统计查询**（`QueryServiceTest`）：参数非法、重复提交、预算耗尽、样本不足、
  未授权字段、同批次可复现、撤权与版本追溯、新批次噪声种子。
- **字段授权**（`FieldPolicyServiceTest`）：版本单调递增、历史版本追溯、默认拒绝。
- **并发查询**（`ConcurrentQueryTest`）：40 线程抢 0.5 预算恰好成功 5 次且余额为 0；
  20 线程同一 requestId 恰好成功 1 次且只扣一次预算。
