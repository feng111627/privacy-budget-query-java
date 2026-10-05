# 隐私统计查询（Privacy Budget Query）

在 Spring Boot 骨架上新增的一套隐私统计查询能力。系统用一个**本地账本**管理每个调用方的
隐私预算、预算批次、字段级授权与撤权状态，并以**确定性的拉普拉斯机制**输出带误差范围的
分组统计，保证不同调用方在重复查询、并发查询以及撤权之后，拿到的结论都是
**一致、可解释、可复现**的。

---

## 1. 总体结构

```
model/      请求、响应、分组结果、流水、快照、原因码等不可变类型
budget/     预算模型（BudgetModel）与版本注册表（BudgetRegistry）
auth/       字段级授权策略（AuthorizationPolicy）与版本注册表（AuthorizationRegistry）
data/       本地演示数据集 Dataset（固定 30 行，含样本不足的分组）
engine/     PrivacyQueryEngine：最小分组抑制 + 确定性拉普拉斯加噪 + 误差范围
            NoiseSampler / DeterministicLaplaceSampler：可复现噪声
ledger/     PrivacyLedger：调用方/批次/预算/去重/撤权/流水的本地账本（核心）
service/    PrivacyQueryService：校验 → 授权 → 原子预留 → 加噪执行 → 提交
web/        查询接口、管理接口、按原因码区分的异常映射
```

一次查询的处理顺序：

1. **参数校验**（`INVALID_PARAMETER`）；
2. 调用方登记校验（`CALLER_NOT_FOUND`）与**重复提交**判定（`DUPLICATE_REQUEST`）；
3. 调用方整体撤权 / 字段未授权 / 字段撤权
   （`AUTHORIZATION_REVOKED` / `FIELD_NOT_AUTHORIZED`）；
4. 账本在调用方级锁内**原子预留预算**（`BATCH_NOT_FOUND` / `BUDGET_EXHAUSTED`）；
5. 引擎执行确定性加噪，账本**提交**预算并写流水；引擎异常则**释放**预留。

---

## 2. 预算模型

由 `BudgetModel` 描述一个不可变版本，默认版本 `budget-v1`：

| 参数 | 默认值 | 含义 |
| --- | --- | --- |
| `totalEpsilon` | `1.0` | 每个预算批次的总隐私预算 ε |
| `countCostEpsilon` | `0.10` | 每次 COUNT 查询实际扣减的 ε |
| `sumCostEpsilon` | `0.20` | 每次 SUM 查询实际扣减的 ε |
| `minGroupSize` | `5` | 分组最小样本量，低于该值的分组被抑制 |
| `clampMin` / `clampMax` | `0` / `1_000_000` | SUM 逐行裁剪与输出裁剪上下界 |
| `confidenceLevel` | `0.95` | 误差范围对应的置信水平 |

- 预算按**查询实际消耗**扣减：COUNT 扣 `countCostEpsilon`，SUM 扣 `sumCostEpsilon`。
- 每个调用方拥有一个或多个**预算批次**（batch）；批次之间预算与噪声相互独立，
  可通过管理接口新开批次。
- 扣减采用「**预留 → 提交 / 释放**」两阶段。`reserve` 在调用方监视器内原子完成
  余额判断与占位，因此：
  - 并发请求不可能把预算扣成**负数**；
  - 同一 `requestId` 不可能被**重复扣**；
  - 余额不足时返回 `BUDGET_EXHAUSTED`，预算不扣减。

---

## 3. 噪声与脱敏规则（拉普拉斯机制）

- **敏感度 Δ**：COUNT 为 `1`；SUM 为 `clampMax - clampMin`（度量值逐行先裁剪，
  从而把单行影响限制在 Δ 内）。
- **噪声**：每个分组独立加入 `Lap(scale)`，`scale = Δ / ε`。
- **确定性、可复现**：噪声不是随机取的，而是由查询指纹 + 组键 + 模型版本派生种子，
  用种子化的 `java.util.Random` 逆变换采样得到：

  ```
  seed = FNV-1a(fingerprint | groupKey | modelVersion)
  u    = Random(seed).nextDouble() - 0.5
  X    = -scale · sign(u) · ln(1 - 2|u|)
  ```

  因此**同一调用方、同一预算批次、相同查询要素**（指标、维度、SUM 的度量字段、
  预算模型版本）必然得到完全一致的指纹与加噪结果；不同批次噪声独立。
  重复查询使用不同 `requestId` 时，每次仍各自扣减预算，返回的是同一稳定结论。
- **最小分组抑制（脱敏）**：分组真实样本数 `< minGroupSize` 时，结果中该分组
  `suppressed=true`、不返回任何真实值，只暴露“样本不足”这一事实。
  演示数据里 `west` 只有 2 行，始终被抑制。
- **输出裁剪**：加噪结果裁剪到 `[clampMin, clampMax]`。
- **误差范围（可解释性）**：每个充足分组返回 `marginOfError = k · scale`，
  其中 95% 置信水平下 `k = -ln(1 - 0.95) ≈ 2.996`。
  响应的 `explanation` 文本同时给出指标、维度、本次扣减、剩余预算、scale、
  误差半宽、敏感度、最小样本量、分组数/抑制数、模型版本、授权版本与指纹。

---

## 4. 授权语义

- 授权是**字段级**的：调用方登记时授予一组字段（维度字段与度量字段）。
  一次查询涉及的所有字段都必须已授权。
- **撤权**分两层：
  - 调用方整体撤权：该调用方的一切新查询被拒（`AUTHORIZATION_REVOKED`），可恢复；
  - 字段级撤权：单个字段从该调用方的生效集合中移除，不可用于后续统计。
- “**从未授权**”与“**授权后被撤权**”是两个不同原因码：
  `FIELD_NOT_AUTHORIZED` vs `AUTHORIZATION_REVOKED`，调用方可精确区分。
- 撤权**不追溯改写历史**：历史流水与结论保持不变；同一 `requestId` 撤权后重放，
  仍然返回首次处理时的结论（重复提交），而不是按撤权处理。
- 每次查询都会把“当时真正生效”的授权集合固化成一个**不可变快照版本**
  （`eff-<caller>-<hash>`），响应与流水引用该版本。

### 版本化与历史复现

- `BudgetRegistry` 与 `AuthorizationRegistry` **保留全部历史版本**，发布新版本不会
  删除旧版本；“当前版本”即最近发布的版本。
- 每条流水 `QueryRecord`（接受与拒绝都记录）都带有
  `budgetModelVersion` 与 `authorizationVersion`、查询指纹、扣减量与拒绝原因。
- 规则或预算模型变更后，仍可按流水上的版本号取回当时的参数与授权集合，
  历史响应原样可查（见 `RevocationAndVersioningTest` 用例 4）。

---

## 5. HTTP 接口

### 查询

```bash
curl -X POST http://localhost:8080/api/privacy/queries \
  -H 'Content-Type: application/json' \
  -d '{"callerId":"alice","requestId":"req-1","batchId":"batch-alice-0",
       "metric":"COUNT","dimensionField":"region"}'
```

SUM 需要 `metricField`：

```json
{"callerId":"alice","requestId":"req-2","batchId":"batch-alice-0",
 "metric":"SUM","dimensionField":"region","metricField":"income"}
```

- `GET /api/privacy/callers/{callerId}/history`：查询流水（含拒绝原因与版本）。
- `GET /api/privacy/callers/{callerId}/batches/{batchId}`：批次预算快照。

### 管理（演示用）

```bash
# 登记并授权字段
curl -X POST .../api/privacy/admin/callers/alice/register \
  -H 'Content-Type: application/json' -d '{"fields":["region","income"]}'
# 字段撤权 / 整体撤权 / 恢复 / 新开批次
curl -X POST .../api/privacy/admin/callers/alice/revoke-field -d '{"field":"region"}'
curl -X POST .../api/privacy/admin/callers/alice/revoke
curl -X POST .../api/privacy/admin/callers/alice/reinstate
curl -X POST .../api/privacy/admin/callers/alice/batches/second
```

### 拒绝响应

不同原因映射为可区分的状态码与稳定 `code`：

| code | HTTP | 含义 |
| --- | --- | --- |
| `INVALID_PARAMETER` | 400 | 参数缺失/非法/引用不存在的字段 |
| `CALLER_NOT_FOUND` / `BATCH_NOT_FOUND` | 404 | 调用方/批次不存在 |
| `FIELD_NOT_AUTHORIZED` | 409 | 字段从未授权 |
| `AUTHORIZATION_REVOKED` | 409 | 调用方或字段已撤权 |
| `BUDGET_EXHAUSTED` | 409 | 批次预算不足 |
| `DUPLICATE_REQUEST` | 409 | `requestId` 重复提交 |

---

## 6. 验证方式

```bash
mvn test
# 或带上单测日志配置，控制台会打印每个业务/用例/结果：
mvn test -Djava.util.logging.config.file=src/test/resources/logging.properties
```

测试不依赖随机全局状态，每个用例手工装配一套全新组件，结果确定。覆盖的边界：

| 测试类 | 覆盖业务 | 用例 |
| --- | --- | --- |
| `BudgetExhaustionTest` | 预算耗尽、按实际消耗扣减、拒绝原因区分 | 连续扣到耗尽且余额非负；耗尽/非法/未登记/重复四类 code 两两不同 |
| `ConcurrentDeductionTest` | 并发扣减 | 40 线程抢 5 个名额：恰好 5 成功、35 耗尽、无超扣无重复扣 |
| `SampleSuppressionAndReproducibilityTest` | 最小分组、噪声、可复现 | west(2 行) 被抑制；同批次相同查询逐位一致；批内稳定、批间独立；带误差范围 |
| `RevocationAndVersioningTest` | 字段授权、撤权、版本追溯 | 未授权 vs 撤权区分；整体撤权与重放一致性；接受/拒绝都留痕；模型+规则变更后历史仍可复现 |

测试日志形如：

```
【并发扣减】用例: 40 个线程并发 COUNT（总预算0.5ε、单价0.1ε）→ 期望成功=5、耗尽=35、余额≥0
  成功=5 耗尽=35 实际累计扣减=0.50ε 已用=0.50ε 剩余=0.00ε
【并发扣减】结果: 无超扣、无重复扣、余额=0、流水一致 ✔
```

---

## 7. 设计取舍与边界说明

- 本项目为演示用途，账本、数据集均为进程内内存实现；接口语义（两阶段预留、
  调用方级串行化、版本不可变）可平移到数据库事务/行锁实现。
- 噪声刻意选择**确定性派生**而非每次随机，以满足“同批次相同查询稳定可复现”的要求；
  这意味着同一批次内重复执行相同查询不会获得新的随机性，但每次仍会扣减预算。
  新批次会重置预算并产生相互独立的噪声。
- 被抑制分组只揭示“样本不足”，不揭示真实计数或总和，避免小样本泄露。
