# Data Model: 代码评审发现修复（Phase 1）

**Feature**: 002-code-review-remediation
**Date**: 2026-06-21

> 本特性**不新增数据实体**（spec「关键实体」已声明）。本文聚焦对既有实体的**方法/不变量级变更**，作为实现期的落点清单与契约依据。实体字段定义沿用 [001 data-model.md](../001-arthas-mcp-gateway/data-model.md)，此处只列「变了什么」。决策依据见 [research.md](./research.md)，架构见 [设计文档](../../docs/superpowers/specs/2026-06-21-code-review-remediation-design.md)，对外契约点见 [contracts/remediation-invariants.md](./contracts/remediation-invariants.md)。

---

## 1. 变更总览

| 实体 | 变更类型 | 关联发现 | 摘要 |
|---|---|---|---|
| `BackendEntry` | 新增方法 + 重构 | P0/P1/P3-2 | 成为统一拦截层：`execute`/`admit`/`invoke`/`isHealthy`/`releaseSlot` |
| `CircuitBreaker` | 方法签名不变、加锁 | P1-1 | `allowRequest`/`recordSuccess`/`recordFailure` 全 `synchronized` |
| `HttpBackendClient` | 方法不变、守卫化 | P1-4 | `initialize` 以 `AtomicBoolean.compareAndSet` 守卫，CAS 成功者才握手 |
| `AsyncTaskExecutor` | 方法签名扩展 + 新增背压 | P0/P2-4 | `submit` 增 `onTerminal` 参数；增全局 `Semaphore` |
| `TaskStore` | 方法行为微调 | P2-3 | `get` 只判查到那一条（不再全表清理） |
| `DiagnosticRequest` | 防御拷贝改法 | P2-2 | `unmodifiableMap(new LinkedHashMap<>(…))`（容忍 null value） |
| `BackendConfig.Auth` | 新增 `toString` | P3-1 | 脱敏（mode + 掩码） |
| `BackendConfigLoader` | `asInt` 语义收紧 + 合并方法 | P3-4/P3-5 | 拒浮点/超界保留原值；`asNullableString`→`asString` |
| `BackendConfigWatcher` | 退役宽限 + 线程管理 | P2-1 | `retirementGrace` 默认=backendTimeout；`retireAll` 用 `ScheduledExecutorService` |
| `ToolsCallRouter` | 重构（两路径变薄） | P0/P1 | 委托 `execute`/`admit`；翻译域异常→`McpError`；移除手动 `releaseSlot` |
| `GatewayToolHandlers` / `BackendRegistryHealthIndicator` | healthy 委托 | P3-2 | 委托 `BackendEntry.isHealthy()` |
| `McpJson`（新类） | 新增 | P3-3 | `handler` 包内 JSON 序列化单例 |
| 4 个域异常类（新类） | 新增 | P0/P1 | `CircuitOpenException`/`ConcurrencyLimitException`/`StatelessAsyncException`/`BackendUnreachableException` |
| `ExposedTool`/`TaskError`/`TaskSupport` | 删除死代码 | P3-5 | 删 `gatewayOwned()`/`REASON_CIRCUIT_OPEN`/`wireValue()` |

---

## 2. BackendEntry（统一拦截层 · 核心重构）

> 设计文档 §3.2 的目标架构。原散在 `ToolsCallRouter` 两路径的"熔断守卫 + 取/还槽 + 故障分类"收口到此。

### 2.1 新增/变更方法

| 方法 | 签名 | 行为 | 关联 |
|---|---|---|---|
| `execute` | `CallToolResult execute(String tool, Map<String,Object> args)` | **同步入口**：`admit` → `try { invoke } finally { releaseSlot }`。RAII 保证槽配对 | P0-2（同步路径槽不漏） |
| `admit` | `void admit(String tool)` | **异步前置准入**：① 协议校验（STATELESS 抛 `StatelessAsyncException`）→ ② 熔断守卫读（OPEN 抛 `CircuitOpenException(retryAfterMs)`）→ ③ 取槽（满抛 `ConcurrencyLimitException(maxConcurrentTasks)`）。**不调后端** | P1-2/P1-3(读)/P0-2 |
| `invoke` | `CallToolResult invoke(String tool, Map<String,Object> args)` | **真正调后端 + 故障分类**：`initializeOnce`(CAS) → `callTool`；成功/业务错误→`recordSuccess`；基础设施故障→`recordFailure` 并抛 `BackendUnreachableException`。返回原始 `CallToolResult`（原样透传） | P1-4/P1-3(写)/原则二 |
| `isHealthy` | `boolean isHealthy()` | **单一事实源**：`state==ACTIVE && breaker.state()!=OPEN` | P3-2 |
| `releaseSlot` | `void releaseSlot()` | 释放 per-target 槽（供异步 `onTerminal` 调） | P0-2 |
| `initializeOnce` | private `void initializeOnce()` | `if (initialized.compareAndSet(false,true)) client.initialize()` | P1-4 |

### 2.2 不变量

- **槽 RAII**（同步）：`execute` 的 `admit` 取槽与 `finally releaseSlot` 在同一作用域，任意路径必配对 → 同步路径槽**结构性不漏**。
- **槽 RAII**（异步）：`admit` 取槽；释放**唯一**由 `AsyncTaskExecutor.onTerminal` 负责；路由器闭包**不再**手动 `releaseSlot` → 异步路径槽**结构性不漏**。
- **故障分类一致性**：成功与业务错误（`McpError`）一律 `recordSuccess`，仅基础设施故障（`RuntimeException` 非 `McpError`）`recordFailure`。同步/异步共用同一 `invoke`，分类规则单点。
- **错误边界**：`admit`/`invoke` 抛**域异常**（携带 `retryAfterMs`/`maxConcurrentTasks`/`cause`），**不**依赖 `McpError`/注册表；结构化 `McpError`（含 `data.available`）由路由器翻译（`data.available` 需 `RegistryHolder`）。

---

## 3. CircuitBreaker（P1-1 线程安全）

状态机（CLOSED/OPEN/HALF_OPEN）与字段**不变**（见 001 data-model §8），仅三方法加锁：

| 方法 | 变更 |
|---|---|
| `allowRequest()` | 加 `synchronized` |
| `recordSuccess()` | 加 `synchronized` |
| `recordFailure()` | 加 `synchronized` |

**不变量**：默认 `maxConcurrentTasks=5` 下多线程同时记录失败/读守卫，计数与状态转换**原子一致**——无"丢失更新"、无"半开放行多个探测"。

> 熔断非热路径（每次 tools/call 的守卫/记录各一次），`synchronized` 开销可忽略（research.md §2.1）。

---

## 4. HttpBackendClient（P1-4 原子初始化）

| 字段 | 变更 |
|---|---|
| `initialized` | 新增 `AtomicBoolean initialized = new AtomicBoolean(false)`（替代原 volatile check-then-act） |

| 方法 | 变更 |
|---|---|
| `initialize()` | 改为 `initializeOnce` 语义：`if (initialized.compareAndSet(false,true)) { 真正握手 }` |

**不变量**：并发首次路由同一后端，仅 CAS 成功的单一线程发起握手；其余线程 CAS 失败直接跳过。无重复握手、无会话状态紊乱。

---

## 5. AsyncTaskExecutor（P0 / P2-4）

### 5.1 `submit` 签名扩展

```
submit(String tool, String target, Callable<CallToolResult> work, Runnable onTerminal)
```

新增 `onTerminal` 参数（执行器在任务到达**任意终态**或**提交失败**时调用，负责释放 per-target 槽与全局背压）。

| 路径 | 行为 |
|---|---|
| 外层 `pool.submit` 抛 `RejectedExecutionException` | `store.remove(taskId)`（P0-1 无僵尸）+ `onTerminal.run()`（P0-2 释放槽/背压）+ 原样抛出 |
| 正常 | 入存储 → 入池 → 返回 `GatewayTask` |

### 5.2 `orchestrate` 内层提交纳入 try

| 路径 | 行为 |
|---|---|
| 内层 `pool.submit(work)` 抛 `RejectedExecutionException` | `task.markFailed(BACKEND_UNREACHABLE)`（P0-1 标终态，无僵尸） |
| `finally` | `supervisorFutures.remove(taskId)` + `onTerminal.run()`（**任一**终态都释放槽/背压 → P0-2） |

### 5.3 全局背压（P2-4）

| 字段 | 类型 | 说明 |
|---|---|---|
| `globalInflight` | `Semaphore` | 跨 target 累计 inflight 上限（配置驱动，如 `gateway.async.global-max-inflight`） |

- `submit` 前 `globalInflight.tryAcquire()`（失败→拒绝/排队策略由配置决定，至少不无界增长）；`onTerminal` 时 `release()`。

### 5.4 不变量

- **无僵尸**（P0-1）：任务一旦进存储，要么成功驱动到终态，要么在提交失败时被 `remove`/`markFailed`——绝不长期驻留 WORKING。
- **槽/背压必释放**（P0-2/P2-4）：`onTerminal` 在外层拒绝、内层拒绝、正常完成、超时、取消、异常**所有**路径都执行。
- **取消不计熔断**：`InterruptedException` 在 `orchestrate` 层判别（`markCancelled`），不进 `invoke` 的 `recordFailure`。

---

## 6. TaskStore（P2-3）

| 方法 | 变更 |
|---|---|
| `get(taskId)` | 只判**查到的那一条**：存在且未过期→返回；过期→`remove(taskId)` 返 empty；不存在→返 empty。**不再**触发全表 `cleanExpired()` |
| `list()` | 保持：返回全部任务（顺带触发全表过期清理） |

**不变量**：单点查询 O(1)，与存储规模无关；过期清理由 `list` + 后台 `cleaner` 兜底，不放大单查开销。

---

## 7. DiagnosticRequest（P2-2）

| 方法 | 变更 |
|---|---|
| 防御拷贝构造 | 由 `Map.copyOf(backendArgs)` 改为 `Collections.unmodifiableMap(new LinkedHashMap<>(backendArgs))` |

**不变量**：容忍 null value（arthas 可选参数合法可 null）；保留不可变性 + 稳定迭代序（`LinkedHashMap`）。

---

## 8. BackendConfig.Auth（P3-1 脱敏）

新增方法：

| 方法 | 行为 |
|---|---|
| `toString()` | 仅含 `mode` + 凭据掩码（如 `Auth[mode=BEARER, token=****XX]`，末 2 位）；**不含**明文 token/username/password |

**不变量**：任何代码路径（日志/异常/调试）把 `Auth` 转文本，输出均脱敏。

---

## 9. BackendConfigLoader（P3-4 / P3-5）

### 9.1 `asInt` 语义收紧（P3-4）

| 输入 | 旧行为 | 新行为 |
|---|---|---|
| `Integer` | 通过 | 通过 |
| `Long`（在 int 范围内） | 截断 | 通过（校验范围） |
| `Long`（超 int 范围） | 截断为负/错值 | **报错并保留原始值** |
| `Double`/`Float`（如 `5.0`） | 截断为 5 | **报错并保留原始值** |
| 其他 | 通过/默认 | 报错并保留原始值 |

`readVersion` 同理拒浮点。

**不变量**：非整数/越界值**明确报错且错误信息含原始值**，绝不静默截断为看似合法的错值。

### 9.2 合并等价方法（P3-5）

`asNullableString` 删除，其 3 处调用点改用 `asString`（二者字节级等价，research.md §0 已确认）。

---

## 10. BackendConfigWatcher（P2-1）

| 字段/方法 | 变更 |
|---|---|
| `retirementGrace` 默认值 | 由固定 60s 改为 `= backendTimeout`（配置可覆盖） |
| `retireAll` 宽限执行 | 由裸 `Thread.startVirtualThread(sleep)` 改用可追踪 `ScheduledExecutorService`（容器关闭 graceful + `awaitTermination`） |

**不变量**：退役宽限 ≥ 异步兜底超时，保证 in-flight 异步任务可在退役窗口内完成、不被强制切断为 failed。

---

## 11. ToolsCallRouter（重构 · 两路径变薄）

| 方法 | 变更 |
|---|---|
| `forwardSync` | 调 `e.execute(tool, args)`；catch 4 类域异常翻译为结构化 `McpError`（含 `data.available`/`retryAfterMs`/`reason`）；`McpError`（后端业务错误）原样向上抛 |
| `submitAsync` | 调 `e.admit(tool)`（catch 翻译）；`asyncExecutor.submit(tool, target, () -> e.invoke(tool,args), e::releaseSlot)` |

**移除**：原两路径内联的 `guardCircuit`/`tryAcquireSlot`/`recordSuccess`/`recordFailure`/`releaseSlot`（全部下沉到 `BackendEntry`）。

**不变量**：路由器不再直接操作熔断/槽；错误翻译保持对外 `McpError` 字段与修复前**逐字一致**（`available`/`retryAfterMs`/`reason`/错误码）。

---

## 12. McpJson（新类 · P3-3）

| 成员 | 说明 |
|---|---|
| `static final ObjectMapper MAPPER` | 全局单例（Jackson 3.x 线程安全） |
| `static String json(ObjectNode/JsonNode)` | 序列化封装 |

消费方（原三处各自 `new ObjectMapper()`）改为委托 `McpJson`。

---

## 13. 域异常类（新类 · P0/P1）

均置于 `backend/` 包，携带翻译所需最小数据，**不**依赖 `McpError`/注册表：

| 类 | 携带数据 | 抛出点 | 路由器翻译为 |
|---|---|---|---|
| `StatelessAsyncException` | — | `admit`（STATELESS） | INVALID_PARAMS, `reason=stateless_unsupported_async` |
| `CircuitOpenException` | `long retryAfterMs` | `admit`（breaker OPEN 读） | `backend_unreachable`，data 含 `retryAfterMs` |
| `ConcurrencyLimitException` | `int maxConcurrentTasks` | `admit`（槽满） | INVALID_PARAMS，data 含 `maxConcurrentTasks` |
| `BackendUnreachableException` | `Throwable cause` | `invoke`（基础设施故障） | `backend_unreachable`，data 含 `available` |

---

## 14. 删除（P3-5 死代码）

| 位置 | 删除项 | 依据 |
|---|---|---|
| `ExposedTool` | `gatewayOwned()` | 全库无调用点 |
| `TaskError` | `REASON_CIRCUIT_OPEN` | 全库无引用 |
| `TaskSupport` | `wireValue()` | 全库无调用点 |
| `BackendConfigLoader` | `asNullableString`（合并入 `asString`） | 与 `asString` 字节级等价 |

**不变量**：删除后编译通过、行为不变（既有测试 + 冒烟全绿）。

---

## 15. 关系图（重构后）

```text
ToolsCallRouter
  │ forwardSync ──► BackendEntry.execute  ──(admit→invoke→finally releaseSlot)
  │ submitAsync ──► BackendEntry.admit ──► AsyncTaskExecutor.submit(work=invoke, onTerminal=releaseSlot)
  │                                          │ 外层 reject → store.remove + onTerminal
  │                                          │ orchestrate ── work=invoke ──► recordSuccess/Failure
  │                                          │ finally ──► onTerminal(releaseSlot)
  │ catch 域异常 ──► 翻译为 McpError(available/retryAfterMs/reason)

CircuitBreaker (synchronized: allowRequest/recordSuccess/recordFailure)
HttpBackendClient.initialize  (AtomicBoolean CAS)
BackendEntry.isHealthy ◄── listTargets / HealthIndicator / admit 守卫 (单一事实源)
```
