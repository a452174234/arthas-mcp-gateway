# Research — 代码评审发现修复

> **Phase 0 产出**（`/speckit-plan`）。本文档汇总 15 项发现的**技术决策**（决策/理由/备选），并解决全部技术未知。
> 架构层决策（深度重构 + 统一拦截层）详见 [设计文档](../../docs/superpowers/specs/2026-06-21-code-review-remediation-design.md)；本文不重复其架构论述，仅落"逐项技术决策"。
> 输入：[评审报告](../../docs/code-review/2026-06-20-business-code-review.md)、[spec](./spec.md)、[宪法](../../.specify/memory/constitution.md) v1.2.0。

## 0. 先决研究结论（宪法原则八）

精读 15 项发现涉及的全部业务源码（`AsyncTaskExecutor`/`ToolsCallRouter`/`TaskStore`/`CircuitBreaker`/`BackendEntry`/`HttpBackendClient`/`BackendClient`/`Protocol`/`BackendConfig`/`BackendConfigWatcher`/`DiagnosticRequest`/`GatewayToolHandlers`/`BackendRegistryHealthIndicator`/`StaticToolRegistry`/`BackendConfigLoader`/`ExposedTool`/`TaskError`/`TaskSupport`/`TaskInfrastructureConfig`），逐项核对评审描述，**全部确认属实**（无臆测）。关键事实：

- **并发上限 5 的来源**：arthas 后端 `DEFAULT_MAX_CONCURRENT_TASK_SESSIONS=5`（`TaskDefaults.java:48`，第 6 个 task session 抛 INVALID_PARAMS）。网关 `Semaphore(5)` 是前置挡板。**注意语义偏差**：arthas 的 5 只挡 task session，网关同步+异步一起挡（更严）；此差异是 001 既有选择，**本次保持不变**（改即影响行为）。
- **P1-1 前提存疑已证实**：`taskSlots` 是 `Semaphore`（允许并发），非互斥锁；默认 `maxConcurrentTasks=5` 下最多 5 线程同时操作 `CircuitBreaker`，"并发由槽串行化保证"的注释前提**不成立**。
- **P3-5 死代码已 grep 确认**：`gatewayOwned()`、`REASON_CIRCUIT_OPEN`、`wireValue()` 全库无调用点；`asNullableString` 与 `asString` 字节级等价（前者在 `readAuth` 用 3 次）。

## 1. NEEDS CLARIFICATION 处置

spec 无 `[NEEDS CLARIFICATION]`（评审已给修复方向、用户明确"修全部"）。唯一的设计分叉（P1 最小改动 vs 深度重构）经头脑风暴确认选**深度重构**（见设计文档 §2）。

## 2. 逐项技术决策

### 2.1 A 组 · 统一拦截层（P0-1/P0-2/P1-1/P1-2/P1-3/P1-4/P3-2）

| 发现 | 决策 | 理由 | 备选（未选） |
|------|------|------|--------------|
| **P0-1** 僵尸 WORKING | `AsyncTaskExecutor.submit` 外层 `pool.submit` 被 `RejectedExecutionException` 时 `store.remove(taskId)` + `onTerminal` 再抛 | "入存储"与"入池"任一失败都要么不入存储、要么补终态 | 在 `TaskStore` 加 WORKING 回收（污染 TTL 语义，拒） |
| **P0-2** 槽泄漏 | `orchestrate` 内层 `pool.submit` 纳入 try；任一终态路径 `finally { onTerminal.run() }`；路由器闭包**移除**手动 `releaseSlot` | 槽释放只由执行器 `onTerminal` 负责，单点保证、不可漏 | 在路由器闭包 catch 补 release（仍跨方法、易漏，拒） |
| **P1-1** 熔断非线程安全 | `CircuitBreaker.allowRequest/recordSuccess/recordFailure` 加 `synchronized` | 熔断非热路径，synchronized 开销可接受；最简单正确 | 降默认并发到 1（改变行为，违反"不影响功能"，拒）、字段 volatile（仍非原子组合操作，拒） |
| **P1-2** STATELESS 未校验 | `BackendEntry.admit` 前置检查 `protocol==STATELESS` 抛 `StatelessAsyncException`（路由器翻译为 INVALID_PARAMS, `reason=stateless_unsupported_async`） | 契约修复，一行校验；前置拒绝避免耗尽 11min 兜底超时 | 后台失败兜底（用户体验差，拒） |
| **P1-3** 异步不驱动熔断 | `invoke` 统一分类+记录；异步 `backendWork` 调 `invoke`，故异步基础设施故障 `recordFailure`、成功 `recordSuccess`；**取消中断不计**（在 `orchestrate` 层判别，`invoke` 未跑完不记录） | 与设计文档"统一拦截层"一致；纯异步负载下故障隔离生效 | 维持"只同步驱动"（评审要求修，不选） |
| **P1-4** initialize 非原子 | `HttpBackendClient.initialize` 改 `AtomicBoolean.compareAndSet(false,true)` 守卫，仅 CAS 成功者真正握手 | initialize 非热路径；CAS 最小且正确 | 全方法 synchronized（可行但更重，备选） |
| **P3-2** healthy 三处重复 | `BackendEntry.isHealthy()`（`state==ACTIVE && breaker!=OPEN`）单一事实源，`listTargets`/`HealthIndicator`/`admit` 守卫委托 | 同属统一拦截层；改一处全联动 | 维持三处内联（可维护性差，拒） |

**域异常 vs McpError 边界**（设计文档 §3.2 已定）：`BackendEntry` 抛域异常（`CircuitOpenException`/`ConcurrencyLimitException`/`StatelessAsyncException`/`BackendUnreachableException`），路由器翻译为结构化 `McpError`（因 `data.available` 需 `RegistryHolder`）。保持 `BackendEntry` 与协议层/注册表解耦。

### 2.2 B 组 · 健壮性（P2-1/2/3/4）

| 发现 | 决策 | 理由 | 备选 |
|------|------|------|------|
| **P2-1** 退役宽限切断 in-flight | `retirementGrace` 默认 `= backendTimeout`（配置可覆盖）；`retireAll` sleep 线程改用可追踪 `ScheduledExecutorService`（容器关闭 graceful + `awaitTermination`） | 宽限 ≥ 兜底超时，保证 in-flight 异步可完成；线程可管理不堆积 | 事件驱动"槽归零关 client"（更优但更复杂，列为后续演进） |
| **P2-2** null 参数被拒 | `DiagnosticRequest` 防御拷贝 `Collections.unmodifiableMap(new LinkedHashMap<>(backendArgs))` | 容忍 null value（arthas 可选参数合法可 null），保留不可变性 | stripTarget 阶段过滤 null（丢失"显式 null"语义，不选） |
| **P2-3** get 读放大 | `get` 只判查到的那一条（过期 `remove(taskId)` 返 empty）；全表 `cleanExpired` 只留 `list`，后台 `cleaner` 兜底 | O(1) 单查；惰性清理仍由 list + 后台兜底 | 给每个 task 加过期时间戳索引（过度设计，拒） |
| **P2-4** 无全局背压 | `AsyncTaskExecutor` 增全局 `Semaphore`（跨 target 累计上限，配置驱动），`submit` 前 `tryAcquire`、`onTerminal` `release` | 防集群规模下后端连接先于 per-target 限流触顶 | 共享有界 `ExecutorService`（改动更大，备选） |

### 2.3 C 组 · 清理（P3-1/3/4/5）

| 发现 | 决策 | 理由 |
|------|------|------|
| **P3-1** 凭据泄漏 | `BackendConfig.Auth` 重写 `toString`：仅 mode + 凭据掩码（如 `****` + 末 2 位） | 杜绝未来调试/异常打印泄漏凭据 |
| **P3-3** ObjectMapper 重复 | 抽 `handler` 包内 `McpJson`（static 单例 `MAPPER` + `json(node)` 封装）；三处委托 | Jackson 3.x 线程安全，惯例单例；CLAUDE.md「优先复用既有库」 |
| **P3-4** 配置静默截断 | `asInt`：仅 `Integer`/`Long` 通过（Long 校验 int 范围，超限报错保留原值）；`Double`/`Float` 报错保留原值；`readVersion` 同理拒浮点 | 杜绝 `5.0` 静默通过、`2147483648` 截为负的误导 |
| **P3-5** 死代码 | 删 `gatewayOwned()`/`REASON_CIRCUIT_OPEN`/`wireValue()`；合并 `asNullableString`→`asString` | grep 已确认无调用/等价；CLAUDE.md「简单性」 |

## 3. 依赖与风险

- **无新增运行时依赖**：`synchronized`/`AtomicBoolean`/`Semaphore`/`ScheduledExecutorService`/`Collections`/`LinkedHashMap` 均 JDK 内置；`McpJson` 复用既有 Jackson。符合宪法原则六（Java 主力）与 CLAUDE.md「优先复用既有库」。
- **主要风险**：核心 `tools/call` 路径重写（A 组）引入回归。**对冲**：逐步 TDD + 每步冒烟；重写后重跑全部双侧契约测试（宪法原则四）；`onTerminal` 双重释放由"闭包移除手动 release"消除。
- **行为变化声明**（非回归、是修正）：P1-3 使纯异步负载下熔断生效、P1-2 使 STATELESS 异步前置拒绝——这是评审要求修复的**预期行为修正**，须在契约测试显式断言，并在 spec FR-004/FR-005 对应。

## 4. 测试真实性（宪法原则七 + CLAUDE.md 硬约束）

- **网关内部并发/资源逻辑**（executor 关闭竞态、熔断线程安全、槽 RAII、CAS、读放大）：真实 JVM 并发原语 + DIP 缝注入受控 callable/client 触发**真实失败条件**，**非 arthas 成功桩**（与既有 `AsyncTaskExecutorTest` 一致）。
- **与 arthas 交互**：工具可用性走真实 Claude Code MCP（冒烟）；结果一致性 + 双侧契约走官方 MCP Java SDK client；故障用真实条件（停后端/错 token/sleep/6 并发越界）。
- **逐步验证**：A 组拆"CircuitBreaker synchronized → initialize CAS → execute/admit/invoke/onTerminal → router 委托 + STATELESS"小步，每步先跑既有测试 + 冒烟全绿再下一步。

## 5. 不做（YAGNI / 范围外）

- 不改并发上限语义（同步+异步一起挡 5，保持现状）。
- 不改对外 MCP 报文/契约结构（`available`/`retryAfterMs`/`reason` 字段不变）。
- 不做 P1-3 的"半开视为降级"等健康语义扩展。
- 不实现 P2-1 的"事件驱动关 client"（列后续演进）。
- 评审驳回候选（REFUTED-1/2/3）不在范围。
