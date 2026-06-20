# 代码评审发现修复 — 设计文档

| 项目 | 内容 |
|------|------|
| 主题 | arthas MCP 网关 MVP 业务代码评审（15 项发现）的整改设计 |
| 日期 | 2026-06-21 |
| 特性 | `specs/002-code-review-remediation` |
| 依据 | [评审报告](../../code-review/2026-06-20-business-code-review.md)、[特性 spec](../../../specs/002-code-review-remediation/spec.md)、[宪法](../../../.specify/memory/constitution.md) v1.2.0 |
| 设计阶段 | SuperPower 头脑风暴产出（CLAUDE.md：设计阶段走 brainstorming） |
| 关键决策 | **深度重构（altitude）**：熔断/限流/分类下沉为 `BackendEntry` 统一拦截层，路由器两路径委托 |

---

## 一、背景与约束

评审报告对 `src/main/java/com/arthas/gateway/` 整库审查，提出 15 项发现（P0×2 / P1×4 / P2×4 / P3×5）。本设计给出**修复后应满足的行为**与**采用的技术方案**。

两条全局硬约束（来自用户）贯穿全程：

1. **不影响现有功能**——对外可观测 MCP 行为（工具/资源定义、`target` 路由、结果原样透传、热重载、错误传播、健康状态）与修复前逐项一致。
2. **每完成一步必须验证现有功能完好**——每修一项，先确认既有测试 + 端到端冒烟全绿，再推进下一项。

---

## 二、核心设计决策：深度重构（altitude）

### 2.1 分叉与选择

评审对 P1-1/P1-3/P1-4（熔断线程安全、异步路径不驱动熔断、initialize 非原子）给出两条修复路径：

- **A 最小改动（surgical）**：在现有架构上手术式修——`CircuitBreaker` 方法加 `synchronized`、`initialize` 用 CAS、`submitAsync` 补熔断记录。改动小、回归面小。
- **B 深度重构（altitude）**：把"熔断守卫 + 槽管理 + 故障分类"下沉为 `BackendEntry` 的**统一拦截层**，路由器同步/异步两路径都委托。根治错层、最可维护，但核心调用路径重写、回归面大。

**选择：B（深度重构）。** 经头脑风暴与用户确认采用 B。

### 2.2 为什么选 B（理由）

- **根因一致**：P0-1（僵尸任务）、P0-2（槽泄漏）、P1-1（熔断竞态）、P1-3（异步不驱动熔断）、P3-2（healthy 三处重复）**同源于一个错层**——熔断/槽的"持有者"是 `BackendEntry`，但"操作者"散在 `ToolsCallRouter` 两条路径、且不全。A 路径只能在每条路径上各自打补丁，错层仍在、补丁会继续漂移；B 路径把操作收拢到持有者自身，从结构上消灭这一类"漏操作"。
- **P0-2 的结构性根因**：当前槽的"获取/释放"跨了 submit 边界（路由器取槽、后台闭包的 `finally` 还槽），`pool.submit` 失败时还槽被跳过 → 槽泄漏。B 把槽生命周期交由执行器统一保证（`onTerminal`），占/还同一负责方，**结构上不可能漏**。A 路径只能补 catch，仍依赖人记得补。
- **代价可控**：B 的"回归面大"由**逐步 TDD + 每步冒烟**对冲（约束 2）；核心路径重写后须重跑全部双侧契约测试（宪法原则四）。

> **放弃 A 的代价**：A 改动小，但保留"breaker 被 router 两路径分别手挂"的错层味道，P1-3 的"异步驱动熔断"在 A 下仍要在 `submitAsync` 单独加记录逻辑，与 `forwardSync` 各维护一份分类规则，未来易再次不一致。

### 2.3 不做什么（YAGNI / 不影响功能）

- **不改并发上限语义**：网关 `Semaphore(5)` 现在同步+异步一起挡（arthas 的 5 只挡 task session，网关更严）。此差异是 001 既有选择，**保持不变**（改了就影响行为）。
- **不重写双侧协议契约**：`execute`/`invoke` 返回的是 `client.callTool()` 的原始 `CallToolResult`，原样透传（原则二）；错误仍以结构化 `McpError` 传播（原则五）。对外报文不变。
- **不做 P1-3 的"半开视为降级"等行为扩展**：仅让异步路径**正确驱动既有熔断状态机**，不新增健康判定语义。

---

## 三、目标架构：统一拦截层

### 3.1 现状（错层的根因）

```
现状：breaker 持有者在 BackendEntry，操作者散在 ToolsCallRouter 两路径
  forwardSync : guardCircuit → tryAcquireSlot → call → recordSuccess/Failure → releaseSlot（手挂全套）
  submitAsync : guardCircuit → tryAcquireSlot → submit(executor, 闭包{ call; finally releaseSlot })（缺 record、release 跨边界）
```

### 3.2 目标：`BackendEntry` 成为"受守卫执行"的唯一所有者

收口三类职责：**熔断（synchronized）+ 槽（RAII 配对）+ 故障分类**。对外暴露四个原语。

> **错误边界**：`BackendEntry` **不**依赖 `McpError` 构造与全局注册表。结构化 `McpError` 的 `data.available`（全部目标名）只有路由器（持 `RegistryHolder`）能提供。故 `admit`/`invoke` 抛**域异常**（`CircuitOpenException`、`ConcurrencyLimitException`、`StatelessAsyncException`、`BackendUnreachableException`，各带必要数据如 `retryAfterMs`、`maxConcurrentTasks`、`cause`），由**路由器**捕获并翻译为结构化 `McpError`。这保持 `BackendEntry` 与协议层/注册表解耦。

```java
// 同步：守卫→取槽→调用→分类→释放，全在一个作用域（RAII），槽不可能漏
public CallToolResult execute(String toolName, Map<String,Object> args) {
    admit(toolName);
    try { return invoke(toolName, args); }
    finally { releaseSlot(); }
}

// 异步前置准入：协议校验(P1-2) + 熔断守卫(P1-3 读) + 取槽；槽由执行器经 onTerminal 释放
public void admit(String toolName) {
    if (config().protocol() == Protocol.STATELESS)
        throw new StatelessAsyncException();                          // P1-2
    if (!breaker().allowRequest()) throw new CircuitOpenException(breaker().retryAfterMillis()); // P1-3（读）
    if (!tryAcquireSlot())          throw new ConcurrencyLimitException(config().maxConcurrentTasks());
}

// 真正调后端 + 分类：成功/业务错误→recordSuccess，基础设施故障→recordFailure
public CallToolResult invoke(String toolName, Map<String,Object> args) {
    initializeOnce();                                                // P1-4 CAS
    try {
        CallToolResult r = client().callTool(toolName, args);        // 原样透传（原则二）
        breaker().recordSuccess();                                   // C-CB-2
        return r;
    } catch (McpError e) {                                           // 后端业务错误
        breaker().recordSuccess();                                   // 不计熔断
        throw e;
    } catch (RuntimeException e) {                                   // 基础设施故障
        breaker().recordFailure();                                   // C-CB-1（P1-3 写）
        throw new BackendUnreachableException(e);
    }
}

public boolean isHealthy() {                                         // P3-2 单一事实源
    return state() == BackendState.ACTIVE && breaker().state() != CircuitBreaker.State.OPEN;
}

private void initializeOnce() {                                      // P1-4
    if (initialized.compareAndSet(false, true)) client().initialize();
}
```

`CircuitBreaker.allowRequest()/recordSuccess()/recordFailure()` 全部加 `synchronized` → **P1-1**。

### 3.3 `AsyncTaskExecutor` 增 `onTerminal`，保证终态/提交失败必释放 + 无僵尸

```java
public GatewayTask submit(String tool, String target,
                          Callable<CallToolResult> work, Runnable onTerminal) {
    GatewayTask task = new GatewayTask(taskId, tool, target, clock.get(), clock);
    store.put(task);
    Future<?> sup;
    try {
        sup = pool.submit(() -> orchestrate(task, work, onTerminal));
    } catch (RejectedExecutionException e) {     // 外层 submit 被拒（池已关）
        store.remove(task.taskId());             // P0-1：不留僵尸
        onTerminal.run();                        // P0-2：释放槽
        throw e;
    }
    supervisorFutures.put(task.taskId(), sup);
    return task;
}

private void orchestrate(GatewayTask task, Callable<CallToolResult> work, Runnable onTerminal) {
    try {
        Future<CallToolResult> w = pool.submit(work);              // P0-2：纳入 try
        try { task.markCompleted(w.get(timeoutMs, MS)); }
        catch (TimeoutException t)    { w.cancel(true); task.markFailed(BACKEND_TIMEOUT); }
        catch (ExecutionException e)  { w.cancel(true); task.markFailed(toTaskError(e.getCause())); }
        catch (InterruptedException i){ Thread.currentThread().interrupt(); w.cancel(true);
                                        task.markCancelled(); }      // 取消：invoke 未跑完→不计熔断
    } catch (RejectedExecutionException e) {                        // 内层 submit 被拒
        task.markFailed(BACKEND_UNREACHABLE);                       // P0-1：标终态，无僵尸
    } finally {
        supervisorFutures.remove(task.taskId());
        onTerminal.run();                          // P0-2：任一终态都释放槽
    }
}
```

> 关键：**槽的"占/还"现在都归执行器管**（async）或同作用域（sync），路由器闭包里**不再手动 release**——P0-2 从结构上不可能再发生。

### 3.4 `ToolsCallRouter` 两路径变薄、都委托

```java
private CallToolResult forwardSync(ExposedTool tool, DiagnosticRequest dr) {
    BackendEntry e = resolveTarget(dr);
    long t = nanoTime();
    try {
        CallToolResult r = e.execute(tool.name(), dr.backendArgs()); // 全部生命周期进 execute
        log.info("工具调用完成 tool={} target={} isError={} 耗时={}ms", ...);  // 原则五
        return r;
    } catch (CircuitOpenException c)        { throw backendUnreachableMcpError(dr, "target 熔断中", c.retryAfterMs()); }
      catch (ConcurrencyLimitException c)   { throw concurrencyLimitMcpError(dr, c.maxConcurrentTasks()); }
      catch (StatelessAsyncException c)     { throw invalidParamsMcpError(dr, "stateless_unsupported_async"); }
      catch (BackendUnreachableException c) { throw backendUnreachableMcpError(dr, c.cause()); }
      // McpError（后端业务错误）原样向上抛，不翻译
}

private CallToolResult submitAsync(ExposedTool tool, DiagnosticRequest dr) {
    BackendEntry e = resolveTarget(dr);
    try { e.admit(tool.name()); }                                    // P1-2/熔断/取槽 前置（同上翻译 4 类域异常）
    catch (CircuitOpenException | ConcurrencyLimitException | StatelessAsyncException c) { throw translate(dr, c); }
    GatewayTask task = asyncExecutor.submit(
        tool.name(), dr.target(),
        () -> e.invoke(tool.name(), dr.backendArgs()),               // 后台：分类+记熔断（P1-3）
        e::releaseSlot);                                             // 执行器保证释放
    log.info("异步任务已接受 tool={} target={} taskId={}", ...);
    return asyncAcceptedResponse(task);
}
```

`backendUnreachableMcpError` 等辅助方法在路由器内构造结构化 `McpError`（含 `data.available` = `registry.current().names()`、`retryAfterMs`、`reason`），与现状逐字一致。`GatewayToolHandlers.listTargets` 与 `BackendRegistryHealthIndicator` 的 `healthy` 判定改为委托 `BackendEntry.isHealthy()`（P3-2）。

---

## 四、其余发现的修法

### 4.1 P2 健壮性

| 发现 | 修法 |
|------|------|
| **P2-1** 退役宽限 60s 与异步兜底 11min 脱节 | 退役宽限默认 `= backendTimeout`（配置可覆盖，保证 in-flight 异步可完成）；`retireAll` 的 sleep 线程纳入可追踪 `ScheduledExecutorService`（容器关闭 graceful + `awaitTermination`，不再裸 `Thread.startVirtualThread(sleep)` 堆积） |
| **P2-2** `Map.copyOf` 拒 null 值参数 | `DiagnosticRequest` 防御拷贝改 `Collections.unmodifiableMap(new LinkedHashMap<>(backendArgs))`（容忍 null value，保留不可变性） |
| **P2-3** `TaskStore.get` 单查触发全表清理 | `get` 只判查到的那一条（过期则 `remove(taskId)` 返 empty），全表 `cleanExpired()` 只保留给 `list` 路径，过期清理后台 `cleaner` 兜底 |
| **P2-4** 虚拟线程池无全局背压 | `AsyncTaskExecutor` 增一个全局 `Semaphore`（跨 target 累计上限，配置驱动），`submit` 前 `tryAcquire`、`onTerminal` 时 `release`，与 per-target 槽并列 |

### 4.2 P3 安全卫生与清理

| 发现 | 修法 |
|------|------|
| **P3-1** `Auth` toString 含明文凭据 | `BackendConfig.Auth` 重写 `toString` 脱敏（仅 mode + 凭据掩码） |
| **P3-2** healthy 三处重复 | 抽 `BackendEntry.isHealthy()` 单一事实源，`listTargets`/`HealthIndicator`/`guardCircuit` 三处委托（见 §3.4） |
| **P3-3** `ObjectMapper` 三处重复构造 | 抽 `handler` 包内 `McpJson` 工具类承载全局单例 + `json()` 封装 |
| **P3-4** `asInt` 静默截断浮点/超大整数 | `asInt` 校验 `Number` 为整数类型（`Integer`/`Long`），拒 `Double`/`Float`；`Long` 超 int 范围报错并保留原始值；`readVersion` 同理 |
| **P3-5** 死代码/等价复制 | 删 `ExposedTool.gatewayOwned()`、`TaskError.REASON_CIRCUIT_OPEN`、`TaskSupport.wireValue()`；合并 `asNullableString` 入 `asString` |

---

## 五、宪法保真（原则一/二/三/四/五不变）

- **原则一（MCP 规范符合性）**：`execute`/`invoke` 不触碰 JSON-RPC 帧处理，仅围绕官方 SDK `McpSyncClient` 调用做生命周期包装；不新增自定义协议原语。
- **原则二（透明无损聚合）**：`invoke` 返回 `client.callTool()` 的原始 `CallToolResult`，原样透传、不篡改/截断/摘要。
- **原则三（局部故障韧性）**：统一拦截层强化故障隔离——熔断/槽/分类集中在 `BackendEntry`（per-target 独立），异步路径正确驱动熔断后纯异步负载也能隔离；跨后端并发不相互阻塞（P2-4 全局背压防集群触顶）。
- **原则四（双侧契约）**：核心 `tools/call` 路径重写后须重跑全部双侧契约测试（gateway↔arthas、gateway↔Claude Code）；本次新增/更新对应契约测试（见 §6）。
- **原则五（可观测性）**：结构化日志保留 tool/target/isError/duration；MCP 错误码与后端错误显式传播（熔断 OPEN/并发越界/不可达/STATELESS 拒绝均结构化）。

---

## 六、测试策略（宪法原则七 TDD + CLAUDE.md 真实性硬约束）

**TDD 红绿重构**：每项修复先写失败测试（红），再实现至通过（绿），再重构。

**真实性分层**（对齐 CLAUDE.md「零桩、真实环境」与既有 DIP 缝）：

- **网关自身并发/资源逻辑**（executor 关闭竞态、熔断线程安全、槽 RAII、initialize 原子、读放大）——用**真实 JVM 并发原语**测（真实 `ExecutorService`/虚拟线程/真实并发计数），`AsyncTaskExecutor` 的 DIP 缝（注入 `Callable`）与 `BackendClient` 接口允许注入受控测试双端**触发真实失败条件**（抛基础设施异常、模拟中断），**非 arthas 成功响应桩**。这部分与既有 `AsyncTaskExecutorTest`「注入 callable 测编排契约」一致。
- **与 arthas 交互的行为**——遵循既有驱动分层：工具可用性走**真实 Claude Code MCP**（冒烟）；结果一致性 + 双侧协议契约走**官方 MCP Java SDK client**（确定性断言）；故障用例用**真实故障条件**（停真实后端=不可达、错 token=arthas 真实 401、`Thread.sleep`=慢响应、真实发起 6 并发越界=arthas 真实 INVALID_PARAMS）。
- **逐步验证**：每个可独立提交的小步（如「CircuitBreaker synchronized」「executor onTerminal」「admit STATELESS 校验」「invoke CAS」）完成后，先跑既有测试 + 冒烟确认全绿，再下一步。

---

## 七、推进顺序（契合评审建议 + 逐步验证）

1. **A 组核心**（深度重构）：先 `CircuitBreaker synchronized`（P1-1）→ `initialize CAS`（P1-4）→ `BackendEntry.execute/admit/invoke/isHealthy` + `AsyncTaskExecutor.onTerminal`（P0-1/P0-2/P1-3/P3-2）→ `ToolsCallRouter` 两路径委托 + `admit` 加 STATELESS 校验（P1-2）。每小步 TDD + 冒烟。
2. **B 组健壮性**（P2-2 早修，影响正常调用健壮性）→ P2-1 → P2-3 → P2-4。
3. **C 组清理**（低风险批量收口）：P3-1/3/4/5 一次重构。
4. **回归门禁**：全部完成后跑完整测试套件 + 端到端冒烟，确认对外行为逐项一致。

---

## 八、风险与对冲

| 风险 | 对冲 |
|------|------|
| 核心调用路径重写引入回归 | 逐步 TDD + 每步冒烟；重写后重跑全部双侧契约测试（原则四） |
| `onTerminal` 双重释放（旧闭包残留 release + 执行器 release） | 路由器闭包内**移除**手动 release，槽释放只由执行器 `onTerminal` 负责；单测断言"提交失败时恰好释放一次" |
| 取消中断被误计熔断 | `invoke` 仅在 call 实际执行后分类；取消在 `orchestrate` 层判别（`InterruptedException`），不进 `invoke` 的 recordFailure |
| 异步路径新增熔断记录改变"纯异步负载"行为 | 这正是 P1-3 要求的修复（评审 + 用户均要求修）；行为变化是**修正**而非回归，须在契约测试中显式断言 |

---

## 九、与 spec / plan 的关系

- 本文档是**设计阶段（brainstorming）产出**，记录"深度重构"决策与统一拦截层架构。
- `specs/002-code-review-remediation/research.md`（spec-kit Phase 0）将逐项落地技术决策、引用本文档。
- `plan.md` / `data-model.md` / `contracts/` / `quickstart.md`（spec-kit Phase 1）据此展开。
- 实施走 `/speckit-tasks` → `/speckit-implement`（SDD，测试先于实现）。
