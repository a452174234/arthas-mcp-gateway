# arthas MCP 网关业务代码审查报告

| 项目 | 内容 |
|------|------|
| 审查对象 | arthas MCP 网关 MVP 业务代码 |
| 审查范围 | `src/main/java/com/arthas/gateway/` 全部 38 个业务文件(整库审查,非 diff) |
| 审查日期 | 2026-06-20 |
| 审查力度 | max(10 finder 角度 → 对抗式验证 → sweep) |
| 涉及提交 | `4de3812 feat(001): 实现 arthas MCP 网关 MVP`(无 upstream,整库即本次实现) |
| 结论 | 5 项正确性/并发缺陷、2 项设计代价、1 项健壮性、2 项效率/资源、1 项安全卫生、4 项清理;另 3 项候选经验证驳回 |

---

## 一、执行摘要

整体而言,该 MVP 业务代码质量较高:**并发热点**(`BackendRegistry`/`RegistryHolder` 用 AtomicReference 整体替换 + record 不可变、`GatewayTask` 用 synchronized 终态转换 + volatile 字段、资源关闭普遍用 try-with-resources、错误码与结构化错误集中、注释为中文且描述意图)设计扎实,符合本项目宪法(优先官方 MCP SDK、错误显式传播、故障隔离)。

主要风险集中在 **`AsyncTaskExecutor` 的关闭竞态**(P0,资源泄漏/僵尸任务)与 **熔断器/异步路径的故障隔离一致性**(P1,作者部分已在注释中声明取舍)。本次共报告 **15 项发现**,按严重性分 P0–P3;另记录 **3 项经对抗式验证驳回** 的候选(含 SDK 反编译证据),避免后续重复怀疑。

**重要说明**:本报告标注了若干"作者已在 javadoc/注释中声明接受的取舍"(如 `CircuitBreaker` 非线程安全)。这些仍被报告,因为它们是真实的正确性风险;但报告同时注明其声明上下文,便于决策时权衡"MVP 可接受" vs "默认配置使前提存疑"。

---

## 二、审查范围与方法

### 2.1 审查范围

仅 `src/main/java/com/arthas/gateway/` 业务代码,排除 `src/test`、`src/main/resources`、`reference/`。模块分布:

| 包 | 职责 | 并发敏感性 |
|----|------|-----------|
| `backend/` | 后端配置/加载/监听、注册表、熔断器、HTTP 客户端、认证模式 | 高(注册表、熔断、热加载) |
| `task/` | 异步任务执行器、任务存储、任务状态机 | 高(线程池、任务并发) |
| `handler/` | MCP 工具路由、网关自有工具处理、诊断请求解析 | 中(请求并发) |
| `tool/` | 静态工具注册表、工具元数据 | 低(启动期构建) |
| `auth/` | 网关与后端认证定制 | 低 |
| `config/` | Spring 装配、配置属性、生命周期 | 中(bean 时序) |
| `obs/` | 健康指标 | 低(只读快照) |

### 2.2 审查方法(max effort)

1. **Phase 1 — 10 个独立 finder 角度**(每个至多 8 候选):
   - 正确性(5):逐行扫描、并发安全、跨文件契约追踪、Java/Spring 陷阱、包装/代理正确性。
   - 清理(3):重复实现、冗余复杂度、效率浪费。
   - 深度(1):实现深度(altitude)。
   - 规范(1):CLAUDE.md 一致性。
2. **Phase 2 — 对抗式验证**(3 态:CONFIRMED/PLAUSIBLE/REFUTED):对依赖 SDK 行为/契约的存疑高价值候选拦派独立 verifier,含 SDK jar 反编译取证。
3. **Phase 3 — sweep**:fresh 审查员只找前两阶段未覆盖的缺陷,聚焦尚未充分审查的文件与二线陷阱。
4. **人工把关**:审查员精读 15 个关键文件复核 finder 结论。

### 2.3 验证驳回的候选(详见第四节)

| 候选 | 裁决 | 关键证据 |
|------|------|----------|
| `ToolsCallRouter.forwardSync` 的 `McpError.getJsonRpcError()` 可能 NPE → 误熔断 | REFUTED | SDK `McpError(JSONRPCError)` 构造器对 null 输入先行 NPE,任何成功构造的实例 `jsonRpcError` 必非 null |
| `BackendAuthCustomizer.customize` 用 `header()` 会叠加重复 Authorization 头 | REFUTED | SDK transport 每请求 `Builder.copy()` 新建 builder 且只 customize 一次,transport 自身不设 Authorization |
| `BackendEntry` RETIRED 状态不被 `resolveTarget` 检查 → 退役后端仍可路由 | REFUTED | `resolveTarget` 每次读 `RegistryHolder.current()` 最新 registry,退役 entry 不在新 registry 中,天然不可达 |

---

## 三、发现清单

> 每项含:**位置** `file:line`、**严重性**、**问题描述**、**触发场景**、**修复方向**。

### P0 — 真实可触发,建议尽快修复(并发与资源泄漏)

#### P0-1 异步任务提交失败遗留僵尸 WORKING 任务

- **位置**:`task/AsyncTaskExecutor.java:95-99`
- **严重性**:中(关闭竞态,任务泄漏)
- **问题**:`submit` 中 `store.put(task)`(line 95)先于 `pool.submit(supervisor)`(line 97)。容器关闭竞态下 `pool.submit` 抛 `RejectedExecutionException`,task 已写入 `TaskStore` 且状态为 WORKING,但无 future 驱动。
- **触发场景**:`close()`→`pool.shutdownNow()` 与新的 ASYNC_TASK 调用竞态:`store.put` 成功后 `pool.submit` 抛 `RejectedExecutionException` 向上冒泡;`ToolsCallRouter.submitAsync` 的 catch 会 `releaseSlot`(槽不泄漏),但 `TaskStore` 残留一个永久 WORKING 任务。而 `TaskStore.isExpired`(line 103-105)仅清理终态任务,**WORKING 永不回收** → `task-get` 永远返 working,`task-list` 长期显示僵尸任务,任务无界泄漏。
- **修复方向**:`submit` 内将 `pool.submit(supervisor)` 包入 try-catch,捕获 `RejectedExecutionException` 时回滚 `store.remove(taskId)`(或标记为 FAILED)再向上抛;或在 `TaskStore` 增加 `remove` 语义。确保"任务入存储"与"后台入池"是原子可见的——任一失败都要么不入存储,要么补终态。

#### P0-2 后台编排期提交失败导致并发槽永久泄漏

- **位置**:`task/AsyncTaskExecutor.java:104`(以及 `handler/ToolsCallRouter.java:138-145` 的释放闭包)
- **严重性**:中(target 可被锁死)
- **问题**:`orchestrate` 中 `Future<CallToolResult> worker = pool.submit(backendWork)`(line 104)位于 `try` 块**之外**(try 只包 line 106 的 `worker.get`)。关闭竞态下该 `pool.submit` 抛 `RejectedExecutionException`,异常逃出 `orchestrate`,**backendWork callable 从未被调用** → `ToolsCallRouter.submitAsync` 闭包(line 138-145)的 `finally { entry.releaseSlot(); }` 永不执行。
- **触发场景**:容器关闭时一个已运行的 supervisor 虚拟线程进入 `orchestrate`,line 104 `pool.submit(backendWork)` 因池已 `shutdownNow` 立即抛 `RejectedExecutionException`;`backendWork` 未执行 → `releaseSlot` 不执行。该 target 的 `taskSlots`(`Semaphore`)累积泄漏 5 次后,所有后续请求(同步与异步)被 `concurrency_limit` 永久拒绝,target 彻底锁死。
- **修复方向**:把 `pool.submit(backendWork)` 纳入与 `worker.get` 同一 try,或在外层 catch `RejectedExecutionException` 显式释放槽;更彻底的做法是按 altitude 建议——将"获取/释放槽"封装为 `BackendEntry.withSlot(Callable)`(RAII 风格,acquire 与 release 在同一作用域强制配对),消除跨方法的隐式配对契约。P0-1 与 P0-2 同源(关闭路径未清理已分配资源),建议一并修复。

---

### P1 — 契约违背 / 设计代价 / 并发(需决策)

#### P1-1 熔断器非线程安全,默认配置使"串行化"前提不成立

- **位置**:`backend/CircuitBreaker.java:43-47`(无同步字段)、`27-28`(javadoc 声明)、`107`(`consecutiveFailures++`)、`84-89`(OPEN→HALF_OPEN 转换)
- **严重性**:中(故障隔离降级;**作者已声明取舍**)
- **问题**:5 个状态字段(`state`/`consecutiveFailures`/`consecutiveOpens`/`openedAtNanos`/`currentBackoffNanos`)均无 `volatile`/`synchronized`。javadoc line 27-28 声称"非线程安全——并发由 BackendEntry 的 taskSlots 或调用方串行化保证;MVP 单后端低并发可接受"。但 `BackendEntry.taskSlots` 是 `Semaphore`(允许多线程并发,**非互斥锁**),而 `ToolsCallRouter.forwardSync`(`98-122`)持 slot 后无额外同步地调用 `breaker.allowRequest()`/`recordSuccess()`/`recordFailure()`。
- **触发场景**(默认配置使取舍前提存疑):
  - **默认并发度 = 5**:`BackendConfigLoader.DEFAULT_MAX_CONCURRENT_TASKS = 5`(line 38)、`BackendConfig` 范围 `[1,5]`、`config/backends.yaml` 示例显式 = 5。即默认部署下每后端允许 5 路并发同时操作同一 breaker。
  - **丢失更新**:5 线程并发 `recordFailure` 时 line 107 `consecutiveFailures++` 非原子,连续故障计数可能只涨到 1-2 而不达阈值 3 → **熔断该开不开**,故障隔离失效。
  - **状态撕裂**:无 `volatile`,一线程 OPEN 后其他线程可能仍读到旧 CLOSED 短暂放行。
  - **HALF_OPEN 放多探测**:line 84-89 OPEN→HALF_OPEN 转换非原子,两线程可同时通过放 2 个探测,违反"HALF_OPEN 仅放 1 探测"语义。
- **修复方向**:两条路——
  - (A)接受取舍但使前提成立:把默认 `maxConcurrentTasks` 降为 1,或对 breaker 的公开方法加 `synchronized`(熔断非热路径,开销可接受)。
  - (B)按 altitude 建议,把熔断记录下沉到 `BackendClient`/transport 层统一分类(见 P1-3),router 不再手挂。

#### P1-2 STATELESS 后端未在异步路径校验(契约违背)

- **位置**:`handler/ToolsCallRouter.java:127-152`(`submitAsync` 未读 protocol);契约声明见 `backend/Protocol.java:11`
- **严重性**:中(契约违背)
- **问题**:`Protocol.java:11` 明确声明"决定是否可对该后端发起异步任务(STATELESS 后端的 optional 工具不可走 ASYNC_TASK)"。但 `submitAsync` 全程未读取 `entry.config().protocol()`,任何后端都可被提交异步任务。
- **触发场景**:运维配置一个 STATELESS 后端(无状态、纯 JSON 一来一回),用户对其调用 watch/trace/stack/tt/monitor(routingMode=ASYNC_TASK)→ `submitAsync` 不拦截 → 后台对无状态后端发带任务语义的同步 `tools/call`(期望轮询)→ STATELESS 后端无法承载 → 阻塞至 `backendTimeout`(11min)兜底超时后 `markFailed(backend_timeout)`。
- **修复方向**:`submitAsync` 解算 target 后、提交后台前校验 `entry.config().protocol()`,对 STATELESS 后端前置返 INVALID_PARAMS(结构化错误,reason 可用 `stateless_unsupported_async`)。可补一条契约测试守护。

#### P1-3 异步路径不驱动熔断,纯异步流量绕过故障隔离

- **位置**:`handler/ToolsCallRouter.java:134-135`(注释声明)、`forwardSync` 才 `recordFailure/recordSuccess`
- **严重性**:中(**有意设计**,但有功能代价)
- **问题**:`submitAsync` 注释明确"异步不改熔断",仅同步路径 `forwardSync` 调用 `recordFailure`/`recordSuccess`。注释解释原因(避免 cancel 中断误计 + 单异步失败误熔断拖累同步路径),但代价是**纯异步负载下的故障隔离失效**。
- **触发场景**:
  - 某 target 仅被 watch/trace 等 optional 工具高频调用:后端基础设施不可达 → 异步任务 `markFailed(backend_unreachable)`,但从不 `recordFailure`,熔断器永驻 CLOSED,每次异步任务仍耗满 11min 兜底超时。
  - 反向:若该 target 曾因同步故障进入 OPEN,退避期满后即便异步调用成功也不 `recordSuccess` → HALF_OPEN 永转不回 CLOSED,异步的 `guardCircuit` 守卫持续误拒。
- **修复方向**:与 P1-1 同源——熔断挂在 `BackendEntry` 却只被某条路径驱动,是错层。深层修法是把熔断记录下沉到 `BackendClient`/transport 拦截点统一分类(基础设施故障计、业务错误/cancel 中断不计),router 两条路径统一委托。退一步:异步路径的 `backend_unreachable`(非 cancel)也应 `recordFailure`,仅排除 cancel 中断。

#### P1-4 HttpBackendClient.initialize() 双重检查非原子

- **位置**:`backend/HttpBackendClient.java:46`(`volatile boolean initialized`)、`66-71`(check-then-act)
- **严重性**:中低(后果取决于 SDK `initialize` 幂等性)
- **问题**:`initialize()` 用 `volatile boolean initialized` 做 check-then-act(`if(!initialized){client.initialize(); initialized=true;}`)但非原子。并发首次路由两线程可同时读到 `initialized==false`,都进入 if 体各发一次 MCP initialize 握手。
- **触发场景**:同一 `BackendEntry` 并发到达两个 tools/call(如 list-targets 与 watch 同时首调同后端):两线程均通过 `if(!initialized)` → 各调一次 `client.initialize()`。SDK `McpSyncClient.initialize()` 重复握手可能覆盖 `Mcp-Session-Id` 或被后端拒绝,session 状态紊乱。`initialized` 虽 volatile,但缺 `synchronized`/`AtomicBoolean.compareAndSet` 守卫。
- **修复方向**:`initialize()` 改 `synchronized`(与 DCL 配合,或直接全同步,initialize 非热路径);或用 `AtomicBoolean.compareAndSet(false, true)` 守卫"执行 initialize"的入口,只有 CAS 成功者真正调用。

---

### P2 — 健壮性 / 效率 / 资源

#### P2-1 退役宽限与异步超时脱节,in-flight 异步任务被强制切断

- **位置**:`backend/BackendConfigWatcher.java:152-167`(`retireAll`);默认宽限 60s vs `backendTimeout` 11min
- **严重性**:中(破坏 §3 in-flight 完成承诺 + 资源堆积)
- **问题**:`retireAll` 对每个退役 entry 启动独立虚拟线程 `sleep(retirementGrace=60s)` 后 `client.close()`。但异步任务 `backendTimeout=11min`。退役 target 上若有 in-flight 异步任务,60s 后 `client.close()` 关闭后端会话,async 调用抛异常 → `markFailed(backend_unreachable)`,违反 `data-model.md §3`「in-flight 可完成」承诺。另:这些 sleep 虚拟线程无引用跟踪,`close()` 不回收,高频热重载会堆积。
- **触发场景**:热重载移除某 target 时其上有 3 个 in-flight watch(槽已占):`retireAll` `markRetired` + 60s 后 `client.close()` → 3 个 watch 的 `callTool` 抛 IOException → `markFailed`。用户期望 in-flight watch 完成返回诊断,实际 60s 后强制失败。
- **修复方向**:宽限应 ≥ `backendTimeout`(或按"所有槽归零"作为关 client 信号,而非固定 sleep);sleep 线程改为可追踪的 `ScheduledExecutorService`(容器关闭 graceful shutdown + awaitTermination)。

#### P2-2 诊断请求防御拷贝拒绝 null 值参数

- **位置**:`handler/DiagnosticRequest.java:39`(`Map.copyOf`)、`80`(`LinkedHashMap` 保留 null)
- **严重性**:中低(健壮性)
- **问题**:紧凑构造器 `backendArgs = Map.copyOf(backendArgs)`(line 39)。`Map.copyOf` 对 **null value 抛 NPE**(JDK 语义)。而 `stripTarget`(line 76-83)用 `new LinkedHashMap<>(arguments)` 原样保留 null value。arthas 工具的可选参数(如 watch 的 conditionExpr/timeout)合法可为 null。
- **触发场景**:客户端 tools/call watch 传 `{target:'jvm-1', classExpr:'Foo', conditionExpr:null}`(合法 JSON,null 表示无此可选参数):`stripTarget` 返回 `{classExpr:'Foo', conditionExpr:null}` → line 39 `Map.copyOf` 对 null value 抛 `NullPointerException` → 客户端收 INTERNAL_ERROR(-32603),而非正常路由转发或 INVALID_PARAMS。null 可选参数被错误掩盖为协议层错误。
- **修复方向**:防御拷贝改用容忍 null 的实现(如 `Collections.unmodifiableMap(new LinkedHashMap<>(backendArgs))`),或在 `stripTarget` 阶段过滤 null 值;保留 `Map.copyOf` 的不可变性但单独处理 null。

#### P2-3 TaskStore.get 单点查询触发全表清理(读放大)

- **位置**:`task/TaskStore.java:64-67`(`get` 无条件 `cleanExpired`)、`89-100`(全表 `removeIf`)
- **严重性**:低(O(1) 变 O(N))
- **问题**:`get(taskId)` 每次都调用 `cleanExpired()`,后者 `tasks.entrySet().removeIf` 全表遍历并逐个判 `isExpired`。本应是 O(1) 的单值查找被放大为 O(N)。
- **触发场景**:`task-get`/`task-cancel` 高频调用(如 Claude Code 轮询任务状态)时,每次单查都付全表扫描成本。task 多(异步长任务 + ttl 内终态任务堆积)时累计可观。
- **修复方向**:`get` 路径只对查到的那一条做过期判断(过期则 `remove(taskId)` 返 empty),过期清理交后台周期 `cleaner` 兜底;仅 `list` 路径保留全表清理。

#### P2-4 异步执行器线程池无全局背压

- **位置**:`task/AsyncTaskExecutor.java:72`(`newVirtualThreadPerTaskExecutor()`)
- **严重性**:低(**MVP 单后端掩盖**)
- **问题**:`pool` 为无界虚拟线程池。per-target 有 slot 限流(≤5),但**跨 target 累计并发无约束**。
- **触发场景**:调用方对 N 个 target 各发 5 个 async → N×5 个虚拟线程 + N×5 个 in-flight 后端连接(HttpClient 连接池、arthas JVM attach session)。虚拟线程虽轻,后端连接不轻;集群后端接入后 FD/堆内存可能先于 slot 限流触顶。
- **修复方向**:加全局 `Semaphore` 闸(或共享有界 `ExecutorService`),限制跨 target 累计并发上限。

---

### P3 — 安全卫生 / 清理

#### P3-1 Auth record 默认 toString 含明文凭据

- **位置**:`backend/BackendConfig.java:69`(`Auth` record)
- **严重性**:低(安全卫生,**当前未直接触发**)
- **问题**:`Auth` record(含 `token`/`username`/`password`)自动生成 `toString` 输出明文凭据。当前代码无直接 log 完整 `BackendConfig`(reloader 用 `equals` 比对、日志只打印 `name`),暂未泄漏,但属潜在凭据泄漏点。
- **触发场景**:任何未来调试/异常/堆栈打印 `BackendConfig` 或 `Auth`,凭据即写入日志/可观测系统。
- **修复方向**:重写 `Auth.toString` 脱敏(如仅输出 mode + 凭据掩码),或将凭据从 record 组件改为私有字段。

#### P3-2 healthy 判定三处重复,单一事实源缺失

- **位置**:`handler/GatewayToolHandlers.java:79`(listTargets)、`obs/BackendRegistryHealthIndicator.java`、`handler/ToolsCallRouter.java:155-161`(guardCircuit)
- **严重性**:低(可维护性)
- **问题**:`state==ACTIVE && breaker 未 OPEN` 这一布尔在 listTargets、HealthIndicator、guardCircuit 三处各自内联,无共享抽象。
- **触发场景**:未来若 HALF_OPEN 想视为 degraded、或 RETIRED 想移出 healthy,必须同时改三处——漏改即 health 端点说 healthy 但 router 拒路由(或反之),运维仪表盘与实际行为不一致。
- **修复方向**:抽 `BackendEntry.isHealthy()` 作为单一事实源,三处委托。

#### P3-3 ObjectMapper 三处重复构造

- **位置**:`handler/ToolsCallRouter.java:62`、`handler/GatewayToolHandlers.java:49`、`tool/StaticToolRegistry.java:67`
- **严重性**:低(清理)
- **问题**:`new ObjectMapper()` 在三个类各 new 一份;Jackson 3.x(`tools.jackson.databind`)线程安全,惯例应全局单例。另:`asyncAcceptedResponse` 的 `CallToolResult` 构造与 `GatewayToolHandlers.json()`(line 201-203)逐字重复。
- **修复方向**:抽 `handler` 包内 `McpJson` 工具类承载单例 + `json()` 封装(符合 CLAUDE.md「优先复用既有库」)。

#### P3-4 配置解析静默截断浮点数 / 超大整数错误信息误导

- **位置**:`backend/BackendConfigLoader.java:184`(`asInt`)、`80`(`readVersion`)
- **严重性**:低(配置校验健壮性)
- **问题**:`asInt`/`readVersion` 用 `instanceof Number` 接受浮点数并 `intValue()`/`longValue()` 静默截断;超大 int 截断为负数后错误信息丢失原始值。
- **触发场景**:YAML 写 `version: 1.0` 或 `maxConcurrentTasks: 5.0`(SnakeYAML 解析为 Double)→ 截断静默通过校验;写 `connectTimeoutMs: 2147483648`(超 int 上限)→ `intValue()` 截为 -2147483648 → BackendConfig 报"须为正数,实得 -2147483648"误导排查。
- **修复方向**:`asInt` 校验 `Number` 为整数类型(`Integer`/`Long`)拒浮点(`Double`/`Float`),错误信息保留原始值。

#### P3-5 死代码与等价复制

- **位置**:`tool/ExposedTool.java:35`(`gatewayOwned()`,全库无调用,路由器 line 83 直接裸比较 `routingMode==GATEWAY_LOCAL`)等
- **严重性**:低(清理)
- **问题**:`gatewayOwned()` 定义但无调用;另有 `task/TaskError.java` 的 `REASON_CIRCUIT_OPEN`(自称 US3 future 但已落地未用)、`tool/TaskSupport.java` 的 `wireValue()`(对称臆测产物,仅反向 `fromWire` 被用)、`backend/BackendConfigLoader.java:179` 的 `asNullableString`(与 `asString` 字节码完全等价,且命名误导:暗示不返 null 实际返 null)。
- **修复方向**:删除未用方法/常量,或保留并注释"未用";合并 `asNullableString` 与 `asString`。

---

## 四、验证裁决记录(已驳回候选)

> 记录驳回项及其证据,避免后续重复怀疑;体现"证据驱动,禁止臆测"。

### REFUTED-1 `McpError.getJsonRpcError()` NPE 导致误熔断

- **候选**:`ToolsCallRouter.forwardSync` 的 `catch(McpError e)`(line 109-114)调用 `e.getJsonRpcError().code()/.message()`,若返回 null 则 NPE,被同方法 `catch(RuntimeException)`(line 115)捕获 → `recordFailure` 误熔断,与"业务错误不计熔断"语义相反。
- **裁决**:**REFUTED**。
- **证据**:反编译 SDK `mcp-core-2.0.0.jar` 的 `io.modelcontextprotocol.spec.McpError`:
  - 字段 `jsonRpcError` 为 `private`,无 setter。
  - 唯一公共构造器 `McpError(JSONRPCError)` 第一步 `invokevirtual JSONRPCError.message()`,**对 null 输入先行 NPE**(在构造器内、对象未发布),任何成功构造的实例 `jsonRpcError` 必非 null。
  - `Builder.build()` 强制 `new JSONRPCError(...)`(非 null)传入构造器。
  - 故 `getJsonRpcError()` 不可能返回 null,NPE→误熔断链不可达。
- **备注**:`getJsonRpcError()` 是无 null 防护的 `getfield`,依赖"构造器不变式",属脆弱设计但非当前缺陷。若未来 SDK 新增不带 JSONRPCError 的构造路径,本项可能转为 PLAUSIBLE。

### REFUTED-2 BackendAuthCustomizer 用 `header()` 叠加重复 Authorization 头

- **候选**:`BackendAuthCustomizer.customize`(`auth/BackendAuthCustomizer.java:56`)用 `java.net.http.HttpRequest.Builder.header(AUTHORIZATION, ...)`(追加语义),担心叠加多个 Authorization 头导致后端 401,建议改 `setHeader`。
- **裁决**:**REFUTED**(建议本身可行,但危害不成立)。
- **证据**:
  - JDK `HttpRequest.Builder` **确实同时有** `header`(追加)与 `setHeader`(覆盖),故"改用 setHeader"在 API 层成立(非伪命题)。
  - 反编译 SDK transport:`HttpClientStreamableHttpTransport` 的两条出站路径(sendMessage、reconnect)均**先 `Builder.copy()` 得到全新 builder**,再设 transport 头,最后 `customize` 该 copy——**customizer 在每请求的新 builder 上只调用一次**。
  - transport 自身设的头(`Mcp-Session-Id`/`Accept`/`Content-Type`/`Cache-Control`/`MCP-Protocol-Version`/`Last-Event-ID`)**不含 Authorization**。
  - 故既无"同 builder 多次 customize",也无"与 transport 自带头叠加",重复头不产生。`header` 与 `setHeader` 在此场景行为等价,改 `setHeader` 仅为防御性风格偏好(YAGNI)。

### REFUTED-3 RETIRED 状态不被 resolveTarget 检查导致退役后端仍可路由

- **候选**:`BackendEntry.markRetired`(line 58)仅设 `state=RETIRED`,但 `ToolsCallRouter.resolveTarget`(line 195-204)不检查 `state==ACTIVE`,退役后端仍可被路由,破坏 `data-model.md §3`「新调用不再路由到 RETIRED」。
- **裁决**:**REFUTED**(机制不成立)。
- **证据**:`resolveTarget` 每次 `registry.get(dr.target())`,而 `registry` 是 `RegistryHolder` 字段——其 `get` 读 `current()` 最新 registry。热重载时 `BackendRegistryReloader.reload` 构造的新 registry **只含 unchanged + added/changed entry**,**toRetire entry 不进新 registry**;`holder.getAndSet` 原子替换后,`current()` 不再含退役 entry。故 `resolveTarget` 天然取不到 RETIRED entry,RETIRE 的拦截靠 registry 替换实现而非 state 检查。`state` 字段主要服务于语义标记(in-flight 调用持有的旧 entry 引用仍可完成)。设计自洽。

---

## 五、修复优先级总览

| 优先级 | 编号 | 位置 | 主题 | 工作量 |
|--------|------|------|------|--------|
| **P0** | P0-1 | `AsyncTaskExecutor.java:95-99` | submit 失败遗留僵尸 WORKING 任务 | 小 |
| **P0** | P0-2 | `AsyncTaskExecutor.java:104` | orchestrate 提交失败致槽泄漏 | 小(与 P0-1 同源) |
| **P1** | P1-1 | `CircuitBreaker.java` | 熔断器非线程安全(默认并发 5) | 中 |
| **P1** | P1-2 | `ToolsCallRouter.java:127` | STATELESS 未在异步路径校验 | 小 |
| **P1** | P1-3 | `ToolsCallRouter.java:134` | 异步不驱动熔断(错层) | 中-大 |
| **P1** | P1-4 | `HttpBackendClient.java:66` | initialize 双重检查非原子 | 小 |
| **P2** | P2-1 | `BackendConfigWatcher.java:152` | 退役宽限与异步超时脱节 | 中 |
| **P2** | P2-2 | `DiagnosticRequest.java:39` | 防御拷贝拒绝 null 值参数 | 小 |
| **P2** | P2-3 | `TaskStore.java:64` | get 读放大(O(1)→O(N)) | 小 |
| **P2** | P2-4 | `AsyncTaskExecutor.java:72` | 线程池无全局背压 | 小-中 |
| **P3** | P3-1 | `BackendConfig.java:69` | Auth toString 含明文凭据 | 小 |
| **P3** | P3-2 | `GatewayToolHandlers.java:79` | healthy 三处重复 | 小 |
| **P3** | P3-3 | `ToolsCallRouter.java:62` | ObjectMapper 三份重复 | 小 |
| **P3** | P3-4 | `BackendConfigLoader.java:184` | 浮点静默截断 | 小 |
| **P3** | P3-5 | `ExposedTool.java:35` 等 | 死代码 | 小 |

### 建议推进顺序

1. **P0-1 + P0-2 一并修**:同源(关闭竞态未清理资源),改动小、风险高(可致 target 锁死),优先。
2. **P1-2(STATELESS 校验)**:一行契约修复,改动小、收益明确。
3. **P1-1 + P1-3 + P1-4 一起评估**:三者均围绕"熔断/限流/认证应在哪一层",建议按 altitude 方向统一(下沉到 BackendClient/entry),避免在 router 两条路径分别手挂。
4. **P2 按需**:P2-2(null 参数)影响正常 arthas 调用健壮性,建议早修;P2-1/P2-3/P2-4 可纳入下一轮。
5. **P3 批量清理**:低风险,可一次重构收口(ObjectMapper 单例 + 死代码删除 + 脱敏)。

---

## 六、附录

### 6.1 审查覆盖文件清单

精读(审查员把关):`CircuitBreaker`、`AsyncTaskExecutor`、`TaskStore`、`BackendRegistryReloader`、`HttpBackendClient`、`ToolsCallRouter`、`BackendEntry`、`BackendAuthCustomizer`、`BackendConfigWatcher`、`GatewayMcpServerConfig`、`Protocol`、`BackendConfig`、`BackendConfigLoader`、`GatewayToolHandlers`、`DiagnosticRequest`。

finder 覆盖:全 38 个业务文件。

### 6.2 未发现问题的区域(正向确认)

为避免"未报告即未审查"的误解,以下区域经审查确认设计正确:

- **注册表并发**:`BackendRegistry`(不可变 record + `Map.copyOf`)、`RegistryHolder`(`AtomicReference.getAndSet` 整体替换)、`BackendRegistryReloader`(`IdentityHashMap` 去重复用 entry)——并发安全。
- **任务状态机**:`GatewayTask`(synchronized 终态转换 + volatile 字段,终态转换后 `result`/`error` 发布顺序正确,幂等)——并发安全。
- **资源关闭**:`BackendConfigWatcher.reloadOnce`、`BackendRegistryBootstrap.load`、`StaticToolRegistry.fromClasspath` 均正确 try-with-resources。
- **Optional 使用**:`ToolsCallRouter.resolveTarget`、`GatewayToolHandlers.taskGet/taskCancel` 均先 `isEmpty`/`orElseThrow` 校验。
- **Spring 装配**:`@Primary` 解决 customizer 多候选、`destroyMethod=infer` 生命周期回调齐全、`@ConfigurationProperties` 标准绑定,无循环依赖/装配时序错误。
- **长比较**:`version` 比较用基本类型 `long ==`,无包装缓存陷阱。

### 6.3 备注

- 本报告为只读分析,未修改任何代码。
- 按本项目 `CLAUDE.md`,代码落地需走 spec-kit SDD 流程(测试先于实现);如需落实修复,建议在 `specs/001-arthas-mcp-gateway/tasks.md` 新增对应任务并按 TDD 推进。
