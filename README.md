# privacy-budget-query-java

带隐私预算管理的统计查询服务。通过本地账本（`BudgetLedger`）、统一版本化的
授权/参数服务（`FieldPolicyService`）管理每个调用方可消耗的隐私预算、
**按调用方隔离的字段授权**以及**可按版本发布的脱敏参数**，保证不同调用方在
重复查询、并发查询、撤权与参数版本切换之后拿到的结论一致、可解释、可复现。

## 架构

```
api/      QueryController（查询入口）
          PolicyController（充值/按调用方授权撤权/参数版本发布/历史版本查询）
          ReplayController（按审计记录复现历史结论）
          ApiExceptionHandler（拒绝原因映射）
config/   QueryParameters —— 一版不可变的脱敏/预算参数（ε、最小分组、截断边界）
domain/   QueryRequest / QueryResult / RejectionReason / QueryRejectedException
ledger/   BudgetLedger    —— 本地账本：预算余额、预算批次、有界幂等记录
policy/   FieldPolicyService —— 按调用方授权 + 统一版本号 + 参数版本发布 + 历史重放
query/    QueryService（编排，整查询固定单一版本快照）、Dataset（内存数据集）
audit/    AuditTrail      —— 有界审计环：每次查询的版本、批次、查询内容、消耗与结论
```

## 预算模型

- **每次查询消耗按当前参数版本的 ε**（默认 0.1）；可发布新版本调整。
- 调用方通过 `POST /api/admin/budget/{callerId}?epsilon=...` 充值；**每次充值开启
  一个新的预算批次**（批次号单调递增），批次号参与噪声种子计算。
- 扣减在账本内按调用方串行化（每个调用方一个临界区），扣减与幂等登记是**同一个
  原子操作**：余额不足时不扣减、不登记、无任何副作用；并发下不会扣成负数，也不会
  对同一请求重复扣减。
- 被拒绝的查询（参数非法、样本不足、从未授权、授权后撤权、重复提交、预算耗尽）
  **一律不消耗预算**。

### 拒绝原因（可区分）

| 原因 | 含义 | HTTP |
|---|---|---|
| `INVALID_PARAMETER` | 参数非法 | 400 |
| `BUDGET_EXHAUSTED` | 预算不足（按该版本 ε 判定） | 402 |
| `DUPLICATE_REQUEST` | 同一调用方同一 `requestId` 重复提交 | 409 |
| `INSUFFICIENT_SAMPLE` | 分组样本量低于该版本门槛 | 422 |
| `FIELD_NEVER_AUTHORIZED` | **该调用方对该字段从来没授权过** | 403 |
| `FIELD_AUTHORIZATION_REVOKED` | **该调用方曾被授权，但授权已被撤掉** | 403 |

## 按调用方隔离的授权

- 授权状态的键是 **(调用方, 字段)**，每个调用方各自维护自己的授权生命周期：
  `NEVER（从未授权）→ GRANTED（已授权）→ REVOKED（授权后撤权）`。
- 管理侧授权/撤权必须指明调用方：
  `POST /api/admin/policy/grant/{field}?callerId=...`、`.../revoke/{field}?callerId=...`。
- **撤权互不牵连**：撤掉调用方 A 对某字段的授权，不改变调用方 B 的授权状态，
  B 仍可正常查询。
- 「从来没授权过」与「授权之后又被撤掉」是**两种不同的拒绝原因**
  （`FIELD_NEVER_AUTHORIZED` / `FIELD_AUTHORIZATION_REVOKED`），不会合并。
- 仅当授权状态发生实际跃迁时才产生新版本；重复授权/重复撤权不产生新版本。
  对一个从未授权的键由管理侧**显式执行撤权**，状态记为 `REVOKED`
  （表示“管理上明确撤过”），与从未做过任何操作的 `NEVER` 区分。

## 参数版本与历史复现（兼容范围）

- **一条统一版本线**：授权/撤权与参数发布共用全局单调递增的版本号。
  `POST /api/admin/parameters?epsilonPerQuery=&minGroupSize=&maxValue=` 发布新版，
  任一参数不传则沿用上一版；参数无变化不产生新版本。
- **新查询只走新版本**：发布之后的查询按新版的 ε、最小分组门槛、截断边界执行。
- **历史结论按当时版本复现，口径与当初完全一致**：
  - 版本服务长期保存每次授权跃迁与每次参数发布；未发布参数的版本沿用上一版，
    早于首次发布回落到内置默认版（v0：ε=0.1、最小分组 5、截断 ±1000，与第一轮一致）。
  - 噪声种子包含**参数版本号**：`(callerId, 预算批次, 参数版本, 字段, 统计类型, 分组键)`，
    因此同一查询在不同版本下结论可不同，而在同一版本/批次下必然一致、可原样重算。
  - 复现入口：`GET /api/admin/replay/{callerId}/{requestId}`，按审计记录的
    版本与批次重算并与原结论逐字段比对（`exactMatch`）；
    程序化复现可直接调用 `QueryService.replay(request, version, batchId)`。
  - 历史版本查询：`GET /api/admin/versions/{version}` 可查当时参数，
    附带 `callerId&field` 还可查该调用方在当时版本的授权状态。
- **一次查询固定单一版本**：查询在授权判定前取一次不可变快照
  （版本号 + 参数成对可见），此后授权判定、门槛/截断、噪声、**预算扣减额**
  全部引用同一快照。并发的版本发布/授权切换不会让一次查询出现
  “一半旧参数一半新参数”或“扣预算与授权参考不同版本”。

## 噪声与脱敏规则

- **最小分组保护**：分组样本量 < 当前版本 `minGroupSize` 时直接拒绝，不返回统计值。
- **值域截断**：单条记录截断到 ±当前版本 `maxValue`，用于限定敏感度：
  COUNT 敏感度 1，SUM 敏感度 maxValue，AVG 敏感度 maxValue/n。
- **拉普拉斯噪声**：尺度 b = 敏感度 / ε，逆变换法生成。
- **可解释性**：每次响应携带 `errorMargin`（95% 置信区间 = b·ln20）、
  `epsilonConsumed`、`budgetRemaining`、`batchId`、`policyVersion` 及人类可读
  `explanation`（含版本、门槛、截断边界）。

## 容量控制

- 幂等缓存：每个调用方的 requestId 缓存为**有界 LRU**（默认 10 000 条，
  `new BudgetLedger(capacity)` 可调）。幂等承诺在当前预算批次（充值即清空缓存）
  与缓存容量内成立；淘汰只移除最旧幂等记录，**不影响余额、批次与历史复现**。
- 审计日志：保留最近 **10 000 条**成功查询的环形窗口
  （`new AuditTrail(capacity)` 可调）。超出窗口的旧审计条目被淘汰，
  但精确复现依赖的**参数版本与授权跃迁历史不设上限式冗余**——只保存实际变更
  （存储量与真实变更次数同阶），因此窗口内可一键复现，更早的结论仍可用
  版本/批次通过 `replay` 重算。

## API 一览

```
POST /api/query                                      统计查询（body: QueryRequest）
POST /api/admin/budget/{callerId}?epsilon=           充值预算，开启新批次
POST /api/admin/policy/grant/{field}?callerId=       为该调用方授权字段
POST /api/admin/policy/revoke/{field}?callerId=      撤掉该调用方的字段授权（不牵连他人）
GET  /api/admin/policy/{field}?callerId=             查该调用方当前授权状态与版本
GET  /api/admin/policy/history                       授权跃迁留痕（按版本升序）
POST /api/admin/parameters?epsilonPerQuery=&minGroupSize=&maxValue=
                                                     发布新参数版本（缺省沿用上一版）
GET  /api/admin/versions/current                     当前版本与参数
GET  /api/admin/versions/{version}?callerId=&field=  查历史版本参数（及当时授权状态）
GET  /api/admin/replay/{callerId}/{requestId}        按审计记录复现历史结论并比对
```

## 验证方式

```
mvn test
```

测试日志以 `[单测覆盖] 业务=... | 用例=... | 结果=...` 格式输出，覆盖：

- **本地账本**（`BudgetLedgerTest`）：并发扣减不为负、重复 requestId 幂等、
  余额不足无副作用、充值开新批次。
- **统计查询**（`QueryServiceTest`）：参数非法、重复提交、预算耗尽、样本不足、
  从未授权、同批次可复现、撤权（`FIELD_AUTHORIZATION_REVOKED`）与版本追溯、新批次种子。
- **字段授权**（`FieldPolicyServiceTest`）：版本单调递增、历史版本追溯、默认 NEVER。
- **按调用方授权**（`CallerIsolationPolicyTest`）：同一字段不同调用方互不牵连、
  NEVER 与 REVOKED 拒绝原因可区分、一方撤权另一方仍可查且旧结论可复现。
- **参数版本**（`VersionedParametersTest`）：默认口径、发布新版新查询走新版、
  老结论按老版本精确复现、历史参数/授权查回、截断与门槛按版本生效、无变化不升版。
- **并发切换**（`ConcurrentVersionSwitchTest`）：查询×并发参数发布下每条结论
  可按记录版本精确重放且各调用方预算守恒不为负；撤权×查询并发下结论按版本二值一致、
  撤权只影响目标调用方。
- **容量控制**（`CapacityControlTest`）：幂等缓存有界 LRU、审计环形窗口有界且
  窗口内历史仍可复现。
- **并发查询**（`ConcurrentQueryTest`）：40 线程抢 0.5 预算恰好成功 5 次；
  20 线程同一 requestId 恰好成功 1 次且只扣一次预算。
