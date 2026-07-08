# Part 3 · 002 韧性设计与契约守护

> 002 是 001 的代码评审整改（`docs/code-review/2026-06-20-business-code-review.md` 15 项发现），核心是「熔断线程安全 + 异步驱动熔断 + 关闭竞态清理 + 全局背压 + 凭据脱敏」等。本部分逐项剖析韧性机制 + 契约测试体系。

---

## 第 16 章 熔断器状态机（CircuitBreaker）

**文件**：`src/main/java/com/arthas/gateway/backend/CircuitBreaker.java:32`

### 16.1 状态机

```
CLOSED ──连续 N(=3) 次失败──► OPEN ──退避满──► HALF_OPEN ──探测成功──► CLOSED
                                 ▲                               │
                                 └──────────探测失败（退避升级）────┘
```

| 状态 | 行为 |
|------|------|
| `CLOSED` | 正常转发 |
| `OPEN` | 立即返错误（不等 30s），data 含 `reason=backend_unreachable + retryAfterMs + available` |
| `HALF_OPEN` | 放 1 个探测请求，成功→CLOSED，失败→OPEN（退避升级） |

### 16.2 默认参数（`:36-38`）

```java
public static final int DEFAULT_FAILURE_THRESHOLD = 3;
public static final Duration DEFAULT_BASE_BACKOFF = Duration.ofSeconds(1);
public static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(30);
```

退避序列：1s → 2s → 4s → 8s → 16s → 30s(cap) → 30s ...；恢复（→CLOSED）后重置回基础 1s。

### 16.3 核心方法（全部 `synchronized`，P1-1/FR-003 线程安全修复）

**allowRequest（`:82-94`）**——准入判定：
```java
public synchronized boolean allowRequest() {
    return switch (state) {
        case CLOSED -> true;
        case OPEN -> {
            if (nanoClock.getAsLong() - openedAtNanos >= currentBackoffNanos) {
                state = State.HALF_OPEN;
                yield true;   // 退避满→放 1 探测
            }
            yield false;
        }
        case HALF_OPEN -> false;   // 探测在途，第二个请求被拒（仅放 1 个）
    };
}
```

**recordSuccess（`:97-103`）**：清计数；HALF_OPEN 探测成功→CLOSED（退避重置）。

**recordFailure（`:106-119`）**——CLOSED 累计达阈值 OPEN；HALF_OPEN 探测失败→OPEN 升级退避：
```java
public synchronized void recordFailure() {
    switch (state) {
        case CLOSED -> {
            consecutiveFailures++;
            if (consecutiveFailures >= failureThreshold) { open(); }
        }
        case HALF_OPEN -> open();   // 探测失败→重新 OPEN（退避升级）
        case OPEN -> { /* 已 OPEN，不刷新 openedAt，避免人为延长阻断 */ }
    }
}
```

**open() + escalatedBackoff（`:122-135`）**——`backoff = min(base × 2^(opens-1), max)`：
```java
private void open() {
    consecutiveOpens++;
    currentBackoffNanos = escalatedBackoff();
    openedAtNanos = nanoClock.getAsLong();
    state = State.OPEN;
}
private long escalatedBackoff() {
    int shift = consecutiveOpens - 1;
    if (shift >= 31) return maxBackoffNanos;       // 避免位移溢出
    return Math.min(baseBackoffNanos * (1L << shift), maxBackoffNanos);
}
```

**retryAfterMillis（`:73-79`）**——OPEN 时剩余退避（毫秒），填 `data.retryAfterMs`：
```java
public synchronized long retryAfterMillis() {
    if (state != State.OPEN) return 0L;
    long remainingNanos = currentBackoffNanos - (nanoClock.getAsLong() - openedAtNanos);
    return Math.max(0L, Duration.ofNanos(remainingNanos).toMillis());
}
```

### 16.4 失败计入裁决（不在熔断器内部，在 BackendEntry.invoke）

熔断器**不自己判断**什么是「失败」——裁决在 `BackendEntry.invoke`（`BackendEntry.java:149-166`，见 Part 2 §9.5）：
- 基础设施故障（连接拒绝/超时、initialize 失败、SSE 中断）→ `recordFailure`。
- 后端业务错误（`isError=true`/INVALID_PARAMS/McpError）→ `recordSuccess`（**不计熔断**，C-CB-2）。
- cancel 中断（`Thread.interrupted()`）→ 不计（避免 cancel 误开，P1-3）。

---

## 第 17 章 三层并发限流

### 17.1 第一层：per-target 信号量（BackendEntry）

每后端独立 `Semaphore taskSlots`（`BackendEntry.java:50,59`），= `maxConcurrentTasks`（默认 5）。

**admitCore（`BackendEntry.java:128-135`）**——熔断读 + 取槽，越界立即拒（前置限流，不越界打后端）：
```java
private void admitCore() {
    if (!breaker.allowRequest()) throw new CircuitOpenException(breaker.retryAfterMillis());
    if (!taskSlots.tryAcquire()) throw new ConcurrencyLimitException(config.maxConcurrentTasks());
}
```

**RAII 同步释放**（`BackendEntry.java:94-101`）——try/finally 配对，槽结构性不漏（P0-2）：
```java
public CallToolResult execute(String toolName, Map<String, Object> backendArgs) {
    admitCore();
    try { return invoke(toolName, backendArgs); }
    finally { releaseSlot(); }   // 任意路径必释放
}
```

**异步路径**：槽由 `onTerminal=entry::releaseSlot` 在终态/提交失败释放（`AsyncTaskExecutor.orchestrate` finally + submit catch）。

### 17.2 第二层：全局背压（AsyncTaskExecutor）

跨 target 累计 `AtomicInteger globalInflight`（`AsyncTaskExecutor.java:65`），cap 来自供应器（配置 `globalMaxInflight` 或动态默认 `max(1, 后端数×5)`）。

**acquireGlobalInflight（`:236-245`）**——CAS 自增，超 cap 回滚抛异常：
```java
private void acquireGlobalInflight() {
    int cap = globalInflightCap.get();
    if (cap <= 0) throw new GlobalConcurrencyLimitException(cap);
    if (globalInflight.incrementAndGet() > cap) {
        globalInflight.decrementAndGet();
        throw new GlobalConcurrencyLimitException(cap);
    }
}
```

**装配动态默认（`TaskInfrastructureConfig.java:34-44`）**：
```java
() -> {
    Integer configured = props.getTask().getGlobalMaxInflight();
    if (configured != null) return configured;
    return Math.max(1, holder.current().size() * 5);  // 后端数 × 5
}
```

**全局背压拒绝的处理（`ToolsCallRouter.submitAsync`）**——显式释放 admit 已取的 per-target 槽（P0-2）：
```java
catch (GlobalConcurrencyLimitException e) {
    entry.releaseSlot();  // 不漏槽
    throw globalConcurrencyLimitError(dr, e.globalMaxInflight());
}
```

### 17.3 第三层：配置硬上限

`BackendConfig.java:44-47`——`maxConcurrentTasks ∈ [1,5]`（后端硬上限，arthas `TaskDefaults.DEFAULT_MAX_CONCURRENT_TASK_SESSIONS=5`）：
```java
if (maxConcurrentTasks < 1 || maxConcurrentTasks > 5) {
    throw new BackendConfigException(name + ": maxConcurrentTasks 须 ∈ [1,5]（后端硬上限），实得 " + maxConcurrentTasks);
}
```

### 17.4 三层限流协同

| 层 | 作用 | 默认 | 越界错误 |
|----|------|------|----------|
| 配置硬上限 | 加载期拒绝非法配置 | `[1,5]` | `BackendConfigException`（启动失败） |
| per-target 信号量 | 单后端并发隔离 | `maxConcurrentTasks=5` | `ConcurrencyLimitException` → `concurrency_limit` |
| 全局背压 | 跨 target 总并发上限 | 后端数×5 | `GlobalConcurrencyLimitException` → `global_concurrency_limit` |

---

## 第 18 章 超时体系

| 超时项 | 默认值 | 配置项 | 来源 | 作用 |
|--------|--------|--------|------|------|
| 连接超时 | 5000ms | `connectTimeoutMs` | `BackendConfigLoader.java:36` `DEFAULT_CONNECT_TIMEOUT_MS` | TCP + initialize 握手 |
| 调用超时 | 30000ms | `callTimeoutMs` | `BackendConfigLoader.java:37` `DEFAULT_CALL_TIMEOUT_MS` | 单次同步 `tools/call`（对齐 SC-003 30s） |
| 异步兜底 | **11min** | `arthas-gateway.task.backend-timeout` | `GatewayProperties.java:73` `Duration.ofMinutes(11)` | 后台阻塞等后端兜底（>后端 10min 上限） |
| 任务结果保留 | 1h | `arthas-gateway.task.result-ttl` | `GatewayProperties.java:75` | 终态任务可查询时长 |
| 退役宽限 | =backendTimeout (11min) | 构造注入 `retirementGrace` | `BackendConfigWatcher.java:78` | 旧固定 60s→11min（保证 in-flight 完成，P2-1） |
| K8S exec 探测 | 5s | `PROBE_TIMEOUT` | `K8sPodExplorer.java:32` | 单 pod 探测避免悬挂 |

**连接/调用超时接线（`HttpBackendClient.java:53-63`）**——官方 SDK：
```java
HttpClientStreamableHttpTransport.builder(config.url())
    .connectTimeout(Duration.ofMillis(config.connectTimeoutMs()))
    ...
McpClient.sync(transport)
    .requestTimeout(Duration.ofMillis(config.callTimeoutMs()))
    .build();
```

**异步兜底实现（`AsyncTaskExecutor.java:172-200`）**——嵌套 submit，supervisor `worker.get(timeout)` 等内层 worker：
```java
worker = pool.submit(backendWork);
CallToolResult result = worker.get(callTimeout.toMillis(), TimeUnit.MILLISECONDS);
task.markCompleted(result);
// 超时分支
catch (TimeoutException te) {
    worker.cancel(true);  // 中断后端 HTTP 调用
    task.markFailed(new TaskError(REASON_BACKEND_TIMEOUT, "后端 " + callTimeout + " 内未返回"));
}
```

---

## 第 19 章 故障隔离与错误结构化

### 19.1 独立三件套（每后端独立）

`BackendEntryFactory.java:37-41`——每后端独立 client+breaker+slot：
```java
public BackendEntry create(BackendConfig config) {
    BackendClient client = new HttpBackendClient(config);   // 独立连接池+会话+SSE
    CircuitBreaker breaker = CircuitBreaker.create(clock);  // 独立熔断
    return new BackendEntry(config, client, breaker);       // 独立 Semaphore
}
```

→ 单后端熔断/慢/挂，不影响其他后端（独立资源）。

### 19.2 invoke 故障分类（业务错误 vs 基础设施故障）

见 Part 2 §9.5。核心：业务错误（McpError/isError）不计熔断原样抛；基础设施故障 `recordFailure` 包装。

### 19.3 initialize CAS 守卫（P1-4）

`BackendEntry.java:175-185`——双检锁，恰好一次握手（见 Part 2 §9.6）。

### 19.4 热重载并发不串台

`BackendRegistry` 不可变 record + `Map.copyOf`；`RegistryHolder.getAndSet` 原子替换；调用方持固定 `final BackendEntry` 引用，registry 中途替换不影响 in-flight。

### 19.5 错误结构化（域异常 → McpError）

**5 个域异常类**（`backend/` + `task/`，不依赖 McpError/注册表）：

| 异常类 | 携带字段 | 翻译为 |
|--------|----------|--------|
| `CircuitOpenException` | `retryAfterMs` | `backend_unreachable` |
| `ConcurrencyLimitException` | `maxConcurrentTasks` | `INVALID_PARAMS + concurrency_limit` |
| `BackendUnreachableException` | `cause` | `backend_unreachable` |
| `StatelessAsyncException` | — | `INVALID_PARAMS + stateless_unsupported_async` |
| `GlobalConcurrencyLimitException` | `globalMaxInflight` | `INVALID_PARAMS + global_concurrency_limit` |

**路由器翻译（`ToolsCallRouter.java:167-225`）**——字段集与修复前**逐字一致**（FR-016），`data.available` = `List.copyOf(registry.current().names())`。

### 19.6 完整错误码表

| 错误情形 | JSON-RPC | reason | data |
|----------|----------|--------|------|
| target 熔断中 | INVALID_PARAMS(-32602) | `backend_unreachable` | `target`,`reason`,`retryAfterMs`,`available` |
| target 不可达 | INVALID_PARAMS(-32602) | `backend_unreachable` | 同上 |
| per-target 并发越界 | INVALID_PARAMS(-32602) | `concurrency_limit` | `target`,`reason`,`maxConcurrentTasks` |
| 全局背压越界 | INVALID_PARAMS(-32602) | `global_concurrency_limit` | `target`,`reason`,`globalMaxInflight`,`available` |
| STATELESS 异步 | INVALID_PARAMS(-32602) | `stateless_unsupported_async` | `target`,`reason`,`available` |
| target 缺失/不在册 | INVALID_PARAMS(-32602) | `unknown_target` | `available` |
| target 格式非法 | INVALID_PARAMS(-32602) | — | — |
| 未知工具名 | METHOD_NOT_FOUND(-32601) | — | — |
| 后端业务错误 | 原样透传 McpError | （后端的 reason） | （后端的 data） |

---

## 第 20 章 002 整改 15 项发现逐项剖析

> 评审报告 `docs/code-review/2026-06-20-business-code-review.md` 15 项发现，按 P0–P3 分级。每项含「问题 / 修复 / 关键代码」。

### P0 组（资源/关闭竞态，2 项）

#### P0-1/FR-001 异步提交失败遗留僵尸 WORKING 任务

**问题**：外层 `pool.submit` 被拒时（关闭竞态），刚 `store.put` 的 WORKING 任务无人清理 → 永久僵尸（永不终态，占 task-list）。

**修复**：外层 `pool.submit` 失败 → `store.remove(taskId)` + `onTerminal.run()` + `releaseGlobalInflight()` + 抛出。

**关键代码**（`AsyncTaskExecutor.java:160-166`）：
```java
try {
    supervisor = pool.submit(() -> orchestrate(task, backendWork, onTerminal));
} catch (RejectedExecutionException ree) {
    store.remove(taskId);        // P0-1 移除僵尸
    onTerminal.run();            // P0-2 释放槽
    releaseGlobalInflight();
    throw ree;
}
```

#### P0-2/FR-002 后台编排失败致并发槽泄漏（目标锁死）

**问题**：后台提交失败时，跳过槽释放 → 该目标槽累积泄漏 → 后续所有调用被并发上限永久拒绝（目标锁死）。

**修复**：槽释放**唯一由 `onTerminal` 负责**（所有终态 + 提交失败），路由器闭包删除手动 release。

**关键代码**（`AsyncTaskExecutor.java:198` + `ToolsCallRouter.java:143-145`）：
```java
// orchestrate finally
finally {
    onTerminal.run();   // 任一终态/内层拒绝都释放 per-target 槽（P0-2）
    releaseGlobalInflight();
}
// submitAsync catch GlobalConcurrencyLimitException
entry.releaseSlot();   // 显式释放 admit 已取的槽
```

### P1 组（熔断/并发，4 项）

#### P1-1/FR-003 熔断器非线程安全

**问题**：`CircuitBreaker` 原 `allowRequest`/`recordSuccess`/`recordFailure` 用 Semaphore 不互斥 → 默认并发下连续失败计数丢失更新（该断不断）。

**修复**：三方法 `synchronized`。

**关键代码**（`CircuitBreaker.java:63,73,82,97,106`）：全部可变方法加 `synchronized`。

#### P1-2/FR-004 STATELESS 后端异步路径未校验

**问题**：STATELESS 后端不支持 task，但路由异步路径未校验 → 提交后台后阻塞至兜底超时才失败。

**修复**：`admit` 首检协议 → 抛 `StatelessAsyncException`（仅异步路径）；同步 `execute` 不校验（STATELESS 同步仍可用）。

**关键代码**（`BackendEntry.java:120-125`）：
```java
public void admit(String toolName) {
    if (config.protocol() == Protocol.STATELESS) throw new StatelessAsyncException();
    admitCore();
}
```

#### P1-3/FR-005 异步不驱动熔断

**问题**：原异步路径不调 `recordFailure` → 纯异步负载下后端不可达不熔断 → 每次耗尽兜底超时。

**修复**：`invoke` 统一分类，异步也 `recordSuccess`/`recordFailure`；**取消中断和业务错误不计**。

**关键代码**（`BackendEntry.java:149-166`，见 §16.4）。

#### P1-4/FR-006 initialize 双检非原子

**问题**：原 `check-then-act` 非原子 → 并发首次路由同一后端时，多个线程同时 `initialize` → 会话状态紊乱。

**修复**：双检锁（`volatile + synchronized initLock`），保证「恰好一次 + 其余等待」。

**关键代码**（`BackendEntry.java:175-185`，见 Part 2 §9.6）。

### P2 组（长生命周期/健壮性，4 项）

#### P2-1/FR-007 退役宽限 60s < 兜底 11min，切断 in-flight

**问题**：退役宽限 60s < 异步兜底 11min → in-flight 异步任务被强制切断为失败。

**修复**：`retirementGrace` 默认 = `backendTimeout`（11min）；裸虚拟线程改 `ScheduledExecutorService`（才能延迟调度）。

**关键代码**（`BackendConfigWatcher.java:71-72,229-235`）：
```java
this.retireScheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
// retireAll
retireScheduler.schedule(() -> closeRetiredClient(entry), retirementGrace.toMillis(), TimeUnit.MILLISECONDS);
```

#### P2-2/FR-008 Map.copyOf 拒 null 可选参数

**问题**：`DiagnosticRequest` 原 `Map.copyOf(backendArgs)` 遇 null value 抛 NPE → MCP SDK 反序列化可选参数 `{target:"x",timeout:null}` 误伤。

**修复**：改 `Collections.unmodifiableMap(new LinkedHashMap<>(...))`（容忍 null）。

**关键代码**（`DiagnosticRequest.java:44`）。

#### P2-3/FR-009 TaskStore.get 触发全表清理（读放大）

**问题**：原 `get` 调 `cleanExpired` 全表扫描 → 高频单点查询下 O(N²) 读放大。

**修复**：`get` 只判单条过期（O(1)），定向 `remove`；全表清理留 `list` + 后台 cleaner。

**关键代码**（`TaskStore.java:85-95`，见 Part 2 §12.4）。

#### P2-4/FR-010 跨 target 无全局背压

**问题**：原异步执行池无全局上限 → 集群规模下后端连接/资源无界增长。

**修复**：`AsyncTaskExecutor` 加全局 `AtomicInteger`，cap 动态 = 后端数×5。

**关键代码**（`AsyncTaskExecutor.java:64-65,236-245`，见 §17.2）。

### P3 组（安全卫生/清理，5 项）

#### P3-1/FR-011 Auth.toString 泄漏明文凭据

**问题**：`BackendConfig.Auth.toString` 含明文 token/密码 → 经日志/异常泄漏。

**修复**：重写 `toString`：仅 mode + 掩码 `****XX`（末 2 位，≤2 位仅 `****`）。

**关键代码**（`BackendConfig.java:148-164`）。

#### P3-2/FR-012 健康判定三处重复

**问题**：list-targets/HealthIndicator/admitCore 各自内联健康判定 → 一处改他处漏改。

**修复**：收敛单一事实源 `BackendEntry.isHealthy()`。

**关键代码**（`BackendEntry.java:188-190`，见 Part 2 §9.7）。

#### P3-3/FR-013 多处 new ObjectMapper

**问题**：散在 `ToolsCallRouter`/`GatewayToolHandlers`/`StaticToolRegistry` 各 `new ObjectMapper()` → 重复构造。

**修复**：抽 `McpJson` 全局单例。

**关键代码**（`McpJson.java:25-40`）。

#### P3-4/FR-014 asInt 静默截断浮点/超 int 范围 Long

**问题**：原 `n.longValue()` 接受 `1.0` → 静默截断为看似合法但错误的值。

**修复**：仅 `Integer`/`Long`（int 范围内）通过；浮点/超界报错 + **保留原始值**；`readVersion` 拒浮点。

**关键代码**（`BackendConfigLoader.java:80-92,197-208`，见 Part 2 §11.2）。

#### P3-5/FR-015 死代码 + 等价复制

**问题**：已定义未用（`gatewayOwned`/`REASON_CIRCUIT_OPEN`/`wireValue`）+ 等价复制（`asNullableString` ≡ `asString`）。

**修复**：全部删除/合并（`TaskError.java` 已无 `REASON_CIRCUIT_OPEN`）。

### 全局回归（FR-016/017）

- **FR-016**：全部修复后，对外可观测 MCP 行为与修复前逐字一致（现有测试 + 冒烟全绿）。
- **FR-017**：每完成一项修复，先确认既有测试 + 冒烟全绿，再推进下一项（逐步验证）。

---

## 第 21 章 契约测试体系（双侧 + 一致性 + ArchUnit）

> 宪法原则四：双侧契约（网关↔arthas、网关↔Claude Code）优先，且**先于实现编写**（TDD）。驱动分层：工具可用性用真实 Claude Code，结果一致性/契约用官方 MCP Java SDK client。

### 21.1 服务端 S-* 契约（网关作为 MCP 服务端对 Claude Code）

**目录**：`src/test/java/com/arthas/gateway/contract/server/`

| 测试类 | 驱动 | 核心断言 |
|--------|------|----------|
| `InitializeAndToolsListContractTest` | `@SpringBootTest(RANDOM_PORT)` + `McpClientHarness` | S-INIT-1/2/3（协议版本回显、serverInfo.name=`arthas-mcp-gateway`、capabilities.tools.listChanged=false 不声明 prompts/resources）；S-TL-1 工具数=**38**；S-TL-2 每 arthas 工具 inputSchema 含 `target` required；S-TL-3 剥离后 schema 逐字等于 baseline；S-TL-4 不含 taskSupport/execution；S-TL-5 nextCursor=null |
| `GatewayToolsContractTest` | 纯逻辑（无传输） | G-ASYNC-1 异步接受响应整形；G-LT-1 list-targets 单 target；G-TG-1/2/3 task-get 各状态；G-TL-1 task-list 过滤；G-TC-1/2 cancel 幂等 |
| `ListTargetsContractTest` | 纯逻辑 | G-LT-1 多 target + 熔断 OPEN→healthy=false（但仍在列表）+ healthy 派生公式 |
| `ToolsCallRoutingContractIT` | 真实 arthas + `McpClientHarness` | S-CALL 真实 jvm 诊断含 `{jvmInfo,RUNTIME,resultCount,MACHINE-NAME,SPEC-NAME}`；S-ERR-2 target=ghost→data.available 含真实在册 target；S-ERR-3 未知工具→INVALID_PARAMS/METHOD_NOT_FOUND |

### 21.2 客户端 C-* 契约（网关作为 MCP 客户端对 arthas）

**目录**：`src/test/java/com/arthas/gateway/contract/client/`

| 测试类 | 核心契约 |
|--------|----------|
| `BackendClientContractIT` | C-INIT 握手幂等（已握手 isInitialized=true，二次 initialize 不抛）；C-CALL/C-RESULT callTool 原样返回真实 JVM 诊断 |
| `FaultIsolationContractIT` | **故障隔离四件套**（见下表） |

**`FaultIsolationContractIT` 4 大契约点**（真实故障条件，禁桩）：

| 契约 | 验证 | 真实故障条件 |
|------|------|--------------|
| **C-CB-1** | dead target 连 3 次→OPEN，第 4 次**立即**返 S-ERR-5（`elapsedMs<2000`，不发连接不等 30s） | `http://127.0.0.1:9` 关闭端口（连接拒绝） |
| **C-CB-2** | ognl 非法表达式连 5 次（>阈值 3）仍 CLOSED，business 错误不计熔断 | 真实 arthas 返 isError/McpError |
| **C-LIMIT-1** | 5 个 watch 持槽，第 6 个 `tryAcquireSlot` 失败→INVALID_PARAMS+`concurrency_limit`+`maxConcurrentTasks:5` | 真实并发任务占槽 |
| **C-ISO-1** | order 占资源时 payment 同步 jvm 即时返回（`elapsedMs<10000`） | 独立连接池/线程 |

### 21.3 结果一致性 A/B（ResultConsistencyIT）

**文件**：`src/test/java/com/arthas/gateway/integration/ResultConsistencyIT.java`

**SC-005 A/B 对比设计**：
- A（经网关）：`McpClientHarness` 连 `/mcp`，`callTool("jvm",{target:"order"})`。
- B（直连）：`McpClientHarness` 直连 arthas 根 URL，`callTool("jvm", Map.of())`。

**关键设计**（不要求文本逐字相同，因 jvm 诊断含动态值）：
```java
// 网关不吞/不改写错误标记
assertThat(viaGateway.isError()).isEqualTo(direct.isError());
// 两侧都含真实 JVM 诊断标记（非桩硬证据）
assertThat(viaGatewayText).containsAnyOf(JVM_DIAGNOSTIC_MARKERS);  // {jvmInfo,RUNTIME,resultCount,MACHINE-NAME,SPEC-NAME}
```

→ 一致性 = 「网关透传不破坏结果结构」（宪法原则二）。

### 21.4 热重载（HotReloadIT）

**文件**：`src/test/java/com/arthas/gateway/integration/HotReloadIT.java`

链路：`WatchService` → **500ms 防抖** → `BackendConfigLoader` 解析 → `BackendRegistryReloader` diff → `RegistryHolder.getAndSet` 原子替换 → `markRetired` + 延迟 grace 关 client。

3 个测试：
1. **新增**：写 version 2 → 30s 内 `list-targets` 出现 order 且可诊断。
2. **校验失败保留旧表**（§11 规则 7）：写 version 3 缺 auth 块 → watcher catch `BackendConfigException` → list-targets 仍含 order（不半替换）。
3. **移除**：写 version 4 空表 → 30s 内 order 消失，调用 ghost target 返 INVALID_PARAMS + data.available 不含 order。

### 21.5 异步任务（AsyncTaskContractIT / AsyncTaskTimeoutIT）

| 测试类 | 验证 |
|--------|------|
| `AsyncTaskContractIT` | watch 提交立即返 working → 触发业务方法 → arthas 命中 → task 转 COMPLETED；task-list 含任务；cancel working→cancelled |
| `AsyncTaskTimeoutIT` | 注入短兜底 `backend-timeout=3s` + `numberOfExecutions=100`（真实慢后端 ~5s）→ 真实 TimeoutException → task 转 failed + `error.reason=backend_timeout` |

### 21.6 失效 target（FailedTargetErrorIT）

**文件**：`src/test/java/com/arthas/gateway/integration/FailedTargetErrorIT.java`

停掉 order JVM（`order.close()`）→ `jvm target=order` 30s 内返 INVALID_PARAMS + `reason=backend_unreachable` + `retryAfterMs` + `available`；同时 `jvm target=payment` 仍成功（隔离，SC-003）。

### 21.7 ArchUnit 包边界（PackageBoundaryTest）

**文件**：`src/test/java/com/arthas/gateway/architecture/PackageBoundaryTest.java`

**3 条否定式规则**（ArchUnit 静态字节码扫描，CI 可跑、零 K8S 依赖）：

```java
// 规则 1：诊断核心包不得依赖 orchestration（K8S 编排隔离）
noClasses().that().resideInAnyPackage(DIAGNOSTIC_CORE)
    .should().dependOnClassesThat().resideInAnyPackage("..orchestration..");

// 规则 2：诊断核心不得直接依赖 fabric8/kubernetes-client API
noClasses().that().resideInAnyPackage(DIAGNOSTIC_CORE)
    .should().dependOnClassesThat().resideInAnyPackage("io.fabric8..", "io.kubernetes..");

// 规则 3（004 增量）：诊断核心不得依赖 admin（INV-ISOL-1）
noClasses().that().resideInAnyPackage(DIAGNOSTIC_CORE)
    .should().dependOnClassesThat().resideInAnyPackage("..admin..");
```

**诊断核心白名单（`:28-35`）**：
```
com.arthas.gateway.backend.. / handler.. / tool.. / task.. / auth.. / obs..
```

**关键点**：gateway-core 零 K8S 依赖**不靠 pom 排除**（fabric8 仍在 pom），而是 ArchUnit **字节码静态扫描**锁定依赖方向——任何在诊断核心包内 `import io.fabric8`/`orchestration`/`admin` 的提交都被本测试拦截。`config` 包（组合根，装配 orchestration/admin bean）**刻意不在禁止范围**。

### 21.8 关键复用模式（项目测试套路）

1. **动态注册表注入**（多 IT 复用）：`@SpringBootTest` context 装配占位 url 的 `RegistryHolder`（HttpBackendClient 构造不连）；`@BeforeAll` 启真实后端后 `holder.getAndSet(new BackendRegistry(version, Map.of(name, entry)))` 覆盖；`@TestInstance(PER_CLASS)` 使 `@BeforeAll` 可为非静态实例方法访问 `@Autowired`。

2. **非桩硬证据**：`JVM_DIAGNOSTIC_MARKERS = {jvmInfo, RUNTIME, resultCount, MACHINE-NAME, SPEC-NAME}` 反复出现——arthas jvm 真实返回的进程级结构标记，`containsAnyOf` 即证「非桩」。

3. **真实故障条件四件套**（无桩）：
   - 不可达 = `http://127.0.0.1:9` 关闭端口（`FaultIsolationContractIT.dead`）。
   - 停后端 = `order.close()` 停 JVM（`FailedTargetErrorIT`）。
   - 真实慢 = `numberOfExecutions=100` + 注入短 `backend-timeout=3s`（`AsyncTaskTimeoutIT`）。
   - 真实越界 = 5 watch 持槽 + 第 6 个（`FaultIsolationContractIT.C-LIMIT-1`）。

4. **配置注入覆写**：`@SpringBootTest(properties="arthas-gateway.backends-file=...")` 与 `properties="arthas-gateway.task.backend-timeout=3s"`——不改主代码、不引桩，仅缩短 IT 时效。

5. **驱动分层**（CLAUDE.md 硬约束）：工具可用性验证用真实 Claude Code 走 MCP（`claude -p --mcp-config`）；结果一致性与双侧契约用官方 MCP Java SDK client（`McpClientHarness`）；除网关健康检查（Actuator 端点）外不裸 curl。

---

> **下一步**：Part 4 深入 003 K8S 编排（重点）——远端调用场景设计 + ensure 全流程 + 动态注册。
