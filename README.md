# privacy-budget-query-java

带隐私预算管理的统计查询服务。通过本地账本（`BudgetLedger`）、统一版本快照
（`SystemStateService`）管理每个调用方可消耗的隐私预算、**按调用方隔离的字段授权**
以及**可按版本发布的统计参数**，保证不同调用方在重复查询、并发查询、撤权、
参数版本切换之后拿到的结论一致、可解释、可按历史版本复现。

## 架构

```
api/        QueryController（查询 / 历史结论复现）、PolicyController（充值 / 按调用方授权撤权 / 参数版本发布与查询）
            ApiExceptionHandler（拒绝原因 -> HTTP）
domain/     QueryRequest / QueryResult / QueryParams / AuthState /
            VersionSnapshot / ReplayResult / RejectionReason / QueryRejectedException
ledger/     BudgetLedger       —— 预算余额、预算批次、有界幂等记录
versioning/ SystemStateService —— 统一版本快照：参数版本 + 按调用方授权三态，历史可回放、可裁剪
query/      QueryService（编排，查询入口钉住单一版本快照）、Dataset（内存数据集）
audit/      AuditTrail         —— 有界审计日志：版本、批次、当时参数、消耗与结论
```

## 核心一致性模型：一次查询钉住一个版本快照

`SystemStateService` 中，**参数发布**与**按调用方的授权/撤权**都会在同一把锁内
产生一个新的、不可变的 `VersionSnapshot(version, params, authByCaller)`，版本号
全局单调递增。

`QueryService.execute` 在入口只读取一次当前快照，之后的授权判定、ε/门槛/截断范围、
预算扣减口径、噪声种子、审计记录全部来自这一个快照。因此即使「参数发布 / 授权变更 /
查询」并发发生：

- 一次查询必然**完整落在某一个确定版本**上，不会一半旧参数、一半新参数；
- 扣预算与判定授权参考的必然是同一个版本；
- 进行中的查询要么完整看到旧版本、要么完整看到新版本，切换期间不会对不上号。

原有约束继续成立：预算不会扣成负数、同一请求不会重复扣、被拒绝请求不消耗预算、
各种拒绝原因各自可区分。

## 按调用方隔离的授权

- 授权状态按 `(调用方, 字段)` 维度独立维护。对调用方 A 撤权**不牵连**调用方 B：
  同一字段可以对一部分调用方授权、对另一部分调用方撤权，两边互不影响。
- 管理侧授权/撤权必须指明调用方：
  - `POST /api/admin/policy/grant/{callerId}/{field}`
  - `POST /api/admin/policy/revoke/{callerId}/{field}`
- 授权是**三态**（`AuthState`），两种「查不了」用不同拒绝原因区分：

| 状态 | 含义 | 拒绝原因 | HTTP |
|---|---|---|---|
| `NEVER_GRANTED` | 该调用方对该字段**从来没授权过** | `FIELD_NEVER_GRANTED` | 403 |
| `GRANTED` | 当前已授权 | （放行） | — |
| `REVOKED` | 授权之后又被撤掉 | `FIELD_NOT_AUTHORIZED` | 403 |

- 每次授权/撤权同样产生一个全局递增版本，历史版本下「某调用方对某字段」的三态
  可通过 `authStateAt(caller, field, version)` 回放。

## 参数版本发布与历史结论复现

统计参数 `QueryParams(epsilonPerQuery, minGroupSize, maxValue)` 支持按版本发布：

- `POST /api/admin/params`，body 为新参数，返回新版本号；
- 发布后**新查询按新版本走**（单次消耗、最小分组门槛、取值截断上下限）；
- **历史结论不受影响**：每个版本的快照不可变，旧版本参数与授权状态永久冻结在快照里。

历史结论复现方式：每次成功查询都写入审计记录（含系统版本号、预算批次、当时参数、
查询内容与加噪结论）。通过 `GET /api/replay/{auditSeq}`：

1. 取回该审计记录；
2. 按记录里的版本号取当时的 `VersionSnapshot`（参数 + 该调用方当时授权三态）；
3. 用当时参数、同一确定性噪声种子重新计算。

噪声种子由 `(callerId, 预算批次, 系统版本, 字段, 统计类型, 分组键)` 决定，
因此**按历史版本复算结果与当初完全一致（double 逐位相等）**。复现同时会回放
该调用方在当时版本下的授权状态，可追溯「当时为什么放行 / 为什么拒绝」。

版本参数查询：

- `GET /api/admin/params/current`：当前版本参数与各调用方授权状态；
- `GET /api/admin/params/{version}`：指定历史版本的参数与该版本下授权状态；
  版本已超出保留窗口时返回 `VERSION_NOT_FOUND`（404）。

### 拒绝原因一览

| 原因 | 含义 | HTTP |
|---|---|---|
| `INVALID_PARAMETER` | 参数非法 | 400 |
| `BUDGET_EXHAUSTED` | 预算不足 | 402 |
| `DUPLICATE_REQUEST` | 同一调用方同一 `requestId` 重复提交 | 409 |
| `INSUFFICIENT_SAMPLE` | 分组样本量不足 | 422 |
| `FIELD_NEVER_GRANTED` | 该调用方从未被授权此字段 | 403 |
| `FIELD_NOT_AUTHORIZED` | 该调用方曾被授权但已撤权 | 403 |
| `VERSION_NOT_FOUND` | 指定版本/审计记录已超出保留窗口，无法复现 | 404 |

## 预算模型

- 每次查询消耗的 ε **取自查询所钉住版本的参数**（默认 0.1）。
- `POST /api/admin/budget/{callerId}?epsilon=...` 充值；**每次充值开启一个新预算批次**，
  批次号参与噪声种子。
- 扣减在账本内按调用方串行化，扣减与幂等登记是**同一个原子操作**：
  余额不足不扣减、不登记、无副作用；并发下不扣成负数，也不对同一请求重复扣减。
- 被拒绝查询（参数非法、样本不足、从未授权、已撤权、重复提交、预算耗尽）一律不消耗预算。

## 噪声与脱敏

- **最小分组保护**：分组样本量低于该版本 `minGroupSize`（默认 5）直接拒绝。
- **值域截断**：单条记录对称截断到 `[-maxValue, maxValue]`（默认 ±1000），
  COUNT 敏感度 1，SUM 敏感度 maxValue，AVG 敏感度 maxValue/n。
- **拉普拉斯噪声**：尺度 b = 敏感度 / ε，逆变换法生成。
- 响应携带 `errorMargin`（95% 置信区间 = b·ln20）、`epsilonConsumed`、
  `budgetRemaining`、`batchId`、`version` 与人类可读 `explanation`。

## 容量控制（不破坏窗口内历史复现）

长期运行时账本与版本历史有界：

- `AuditTrail` 只保留最近 N 条成功查询的审计（默认 10000）；
- `BudgetLedger` 每调用方的幂等缓存为有界 LRU（默认 4096），充值开新批次时整批清空；
- `SystemStateService` 的历史快照由查询编排层在每次记账后**联动裁剪**：
  以「仍保留的最老一条审计记录」引用的版本为下界，只裁更老的快照。

因此：**只要审计记录还在，它引用的版本快照一定还在，窗口内历史结论必然可复现**；
超出窗口的审计/版本会被明确拒绝（`VERSION_NOT_FOUND`），而不会给出错误重算结果。

## API 一览

```
POST /api/query                              统计查询（body: QueryRequest）
GET  /api/replay/{auditSeq}                  按审计序号复现历史结论（原值 + 按当时版本重算值）
POST /api/admin/budget/{callerId}            充值预算（参数 epsilon），开启新批次
POST /api/admin/policy/grant/{callerId}/{field}  为指定调用方授权字段
POST /api/admin/policy/revoke/{callerId}/{field} 撤权指定调用方的字段
GET  /api/admin/policy/{callerId}/{field}        查询该调用方对字段的授权三态
POST /api/admin/params                       发布新参数版本（body: QueryParams）
GET  /api/admin/params/current               当前版本参数与授权状态
GET  /api/admin/params/{version}             指定历史版本参数与授权状态
```

## 验证方式

```
mvn test
```

测试日志以 `[单测覆盖] 业务=... | 用例=... | 结果=...` 格式输出，覆盖：

- **本地账本**（`BudgetLedgerTest`，4）：并发扣减不为负、重复 requestId 幂等、
  余额不足无副作用、充值开新批次。
- **统计查询**（`QueryServiceTest`，9）：参数非法、重复提交、预算耗尽、样本不足、
  **从未授权（FIELD_NEVER_GRANTED）/ 授权后撤权（FIELD_NOT_AUTHORIZED）区分**、
  同批次可复现、撤权与版本追溯、新批次噪声种子。
- **按调用方授权与参数版本**（`SystemStateServiceTest`，7）：撤权按调用方隔离、
  三态区分、版本单调与快照不可变、历史版本参数+授权完整追溯、默认值与非法参数拒绝、
  快照容量裁剪、撤权后重新授权。
- **参数版本复现**（`ParamVersionReplayTest`，4）：发布新参数后新查询走新版、
  **老结论按旧版本复算与当初逐位一致**、新旧版本各自独立复现、历史按调用方授权回放、
  容量窗口联动裁剪（窗口内可复现、窗口外 VERSION_NOT_FOUND）。
- **并发版本切换**（`ConcurrentVersionSwitchTest`，2）：查询与连续参数发布并发时，
  每条结论的参数/版本/扣减口径自洽且完整落在单一版本、余额非负；查询与按调用方
  授权/撤权并发时，A 的请求按 NEVER_GRANTED / GRANTED / REVOKED 分窗口正确分流，
  恒授权的 B 全部成功、不受 A 撤权牵连。
- **并发查询**（`ConcurrentQueryTest`，2）：40 线程抢 0.5 预算恰好成功 5 次且余额为 0；
  20 线程同一 requestId 恰好成功 1 次且只扣一次预算（上一轮行为无回归）。
