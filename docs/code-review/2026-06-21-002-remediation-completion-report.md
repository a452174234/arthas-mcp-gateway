# arthas MCP 网关 002 代码评审整改完成报告

| 项 | 值 |
|---|---|
| 特性 | `specs/002-code-review-remediation`(对 001 MVP 整库评审 15 项发现的整改) |
| 评审基线 | [2026-06-20-business-code-review.md](2026-06-20-business-code-review.md)(15 项发现,P0–P3) |
| 设计文档 | `docs/superpowers/specs/2026-06-21-code-review-remediation-design.md` |
| 工作流 | spec-kit SDD(specify → plan → tasks → 实现),测试先于实现(红-绿-重构) |
| 完成日期 | 2026-06-21 |

## 一、结论先行

**15 项发现全部修复并验证通过,对外 MCP 行为零回归(FR-016/FR-017)。**

- **回归门禁**:`./mvnw verify` **全绿**——surefire **182** 个单元/契约测试 + failsafe **20** 个真实后端 IT(9 个 `*IT`,真实 arthas + 真实业务服务,官方 MCP Java SDK client 确定性断言),合计 **202** 测试 0 失败 0 错误 0 跳过,`BUILD SUCCESS`。
- **端到端冒烟(SC-008)**:真实 Claude Code(`claude -p --mcp-config`)经网关调通——`list-targets`(双 target healthy)、`jvm target=order-service`(真实 JVM 诊断)、`watch`→`task-get`(异步 taskId 全流程 + 5 帧真实业务诊断),均非桩。
- **整改原则落实**:统一拦截层(`BackendEntry`)收口熔断/取还槽/分类;域异常与 `McpError`/注册表解耦;全局背压(动态 cap);健康判定/JSON 序列化单一事实源;凭据脱敏;严格配置解析;死代码清除。

> 驱动分层(宪法原则四/七):工具**可用性**用真实 Claude Code 走 MCP;**契约一致性**与**双侧协议**用官方 MCP Java SDK client 确定性断言;非裸 curl。IT 与冒烟均零桩——arthas、业务服务、诊断数据全真实。

## 二、15 项发现逐项映射(发现 → FR → 实现 → 测试 → 验证)

> 下表「修复实现」列为关键改动锚点(文件/机制);「验证测试」列均为真实环境测试(红-绿-重构,无桩冒充成功)。

### P0 — 并发与资源泄漏(US1,已收口)

| # | 发现(评审位置) | FR | 修复实现 | 验证测试 | 状态 |
|---|---|---|---|---|---|
| P0-1 | 异步提交失败遗留僵尸 WORKING 任务(`AsyncTaskExecutor`) | FR-001 | 统一拦截层:提交失败时任务回滚至终态(FAILED),不遗留僵尸;编排与提交纳入同一 try | `AsyncTaskExecutorShutdownRaceTest`(4) | ✅ |
| P0-2 | 编排期提交失败致并发槽永久泄漏(`AsyncTaskExecutor`) | FR-002 | 槽配对收口至 `BackendEntry`(admit 取 / `onTerminal=releaseSlot` 还);路由器闭包不再手动 release;`GlobalConcurrencyLimitException` 时显式释放目标槽 | `AsyncTaskExecutorShutdownRaceTest`(4) | ✅ |

### P1 — 契约违背 / 设计代价 / 并发(US2/US3)

| # | 发现(评审位置) | FR | 修复实现 | 验证测试 | 状态 |
|---|---|---|---|---|---|
| P1-1 | 熔断器非线程安全(`CircuitBreaker`) | FR-003 | 熔断状态读写原子化(并发累加/开启/半开/恢复无丢失更新) | `CircuitBreakerConcurrencyTest`(2)、`CircuitBreakerTest`(10) | ✅ |
| P1-2 | STATELESS 后端未在异步路径校验(`ToolsCallRouter`) | FR-004 | `BackendEntry.admit` 首检协议:STATELESS→`StatelessAsyncException`(路由器翻译 INVALID_PARAMS + `reason=stateless_unsupported_async`),前置拒绝、不提交后台 | `ToolsCallRouterStatelessTest`(2) | ✅ |
| P1-3 | 异步路径不驱动熔断(`ToolsCallRouter`) | FR-005 | 熔断记录下沉至 `BackendEntry.invoke`(同步/异步共用):成功/业务错误 recordSuccess,基础设施故障 recordFailure;**cancel 中断跳过 recordFailure**(检测 `Thread.interrupted()`) | `BackendEntryInterceptionLayerTest`(14) | ✅ |
| P1-4 | `initialize()` 双重检查非原子(`HttpBackendClient`) | FR-006 | `initializeOnce` 上移至拦截层,双检锁(volatile + synchronized):首个持锁者握手,余者等待(裸 CAS 会让 loser 抢跑) | `BackendEntryInterceptionLayerTest`(14) | ✅ |

### P2 — 健壮性 / 效率 / 资源(US4)

| # | 发现(评审位置) | FR | 修复实现 | 验证测试 | 状态 |
|---|---|---|---|---|---|
| P2-1 | 退役宽限与异步超时脱节,in-flight 异步被强制切断(`BackendConfigWatcher`) | FR-007 | 退役宽限默认 = `backendTimeout`(11min,保证 in-flight 完成);裸 `Thread.startVirtualThread(sleep)` 改可追踪 `ScheduledExecutorService`,`close()` 时 `awaitTermination` 优雅回收 | `HotReloadIT`(3) | ✅ |
| P2-2 | 诊断请求防御拷贝拒绝 null 值参数(`DiagnosticRequest`) | FR-008 | 防御拷贝改 `Collections.unmodifiableMap(new LinkedHashMap<>(backendArgs))`(容忍 null value) | `DiagnosticRequestNullArgTest`(1) | ✅ |
| P2-3 | `TaskStore.get` 单点查询触发全表清理(读放大)(`TaskStore`) | FR-009 | `get(taskId)` 仅判查到那一条(O(1));过期→`remove` 返 empty;全表 `cleanExpired()` 仅留 `list` + 后台 cleaner;包私有探针 `containsRawForTest` | `TaskStoreGetReadAmplificationTest`(3) | ✅ |
| P2-4 | 异步执行器无全局背压(`AsyncTaskExecutor`) | FR-010 | 全局背压 `AtomicInteger globalInflight` + 动态 cap(=注册表后端数 × 5,或配置 `arthas-gateway.task.global-max-inflight`);submit 取 / 编排 finally 释放;路由器翻译 INVALID_PARAMS + `reason=global_concurrency_limit` + `globalMaxInflight`/`available` | `GlobalBackpressureTest`(2) | ✅ |

### P3 — 安全卫生 / 清理(US5)

| # | 发现(评审位置) | FR | 修复实现 | 验证测试 | 状态 |
|---|---|---|---|---|---|
| P3-1 | Auth record 默认 toString 含明文凭据(`BackendConfig`) | FR-011 | `Auth.toString` 覆写:仅 mode + 掩码(`****` + 末 2 位;凭据 ≤2 位仅 `****`,不泄露任何明文片段) | `BackendConfigAuthMaskingTest`(4) | ✅ |
| P3-2 | healthy 判定三处重复,单一事实源缺失(`GatewayToolHandlers`/`HealthIndicator`) | FR-012 | `listTargets` 与 `HealthIndicator` 的 healthy 均委托 `BackendEntry.isHealthy()`(`state==ACTIVE && breaker!=OPEN`) | `HealthSingleSourceTest`(2)(钉三处一致) | ✅ |
| P3-3 | ObjectMapper 三处重复构造(`ToolsCallRouter`/`GatewayToolHandlers`/`StaticToolRegistry`) | FR-013 | 抽 `McpJson` 单例(`static final ObjectMapper MAPPER` + `json(Object)` 封装);三处 `new ObjectMapper()` 改委托 | `McpJsonTest`(3) | ✅ |
| P3-4 | 配置解析静默截断浮点 / 超大整数(`BackendConfigLoader`) | FR-014 | `asInt` 仅 Integer/Long(int 范围)通过,拒 Double/Float/超界 Long,错误信息含原始值;`readVersion` 同理拒浮点 | `BackendConfigLoaderParsingTest`(4) | ✅ |
| P3-5 | 死代码与等价复制(`ExposedTool`/`TaskError`/`TaskSupport`/`BackendConfigLoader`) | FR-015 | 删 `ExposedTool.gatewayOwned()`、`TaskError.REASON_CIRCUIT_OPEN`、`TaskSupport.wireValue()`;合并 `asNullableString`→`asString`(字节级等价) | 全量编译 + `./mvnw verify` 全绿 | ✅ |

## 三、对外报文字段逐字一致性(FR-016 核对)

整改**不改变**对外可观测 MCP 行为,错误翻译字段集与修复前逐字保持。由真实后端 IT(官方 SDK client 确定性断言)+ 路由器单测共同守护:

| 错误场景 | 错误码 | data 字段(逐字保持) | 守护测试 |
|---|---|---|---|
| 后端不可达 / 熔断 OPEN | `backend_unreachable` | `target`、`reason`、`available`、`retryAfterMs` | `FaultIsolationContractIT`(4)、`FailedTargetErrorIT`(2) |
| 并发越界(单 target) | INVALID_PARAMS | `target`、`reason`、`maxConcurrentTasks` | `ToolsCallRouterTest`(3) |
| STATELESS 异步(新增拒绝,**新行为**,非回归) | INVALID_PARAMS | `target`、`reason=stateless_unsupported_async`、`available` | `ToolsCallRouterStatelessTest`(2) |
| 全局并发越界(新增背压,**新行为**,非回归) | INVALID_PARAMS | `target`、`reason=global_concurrency_limit`、`globalMaxInflight`、`available` | `GlobalBackpressureTest`(2) |

> STATELESS 拒绝与全局背压是**新增**的结构化错误(原为隐式兜底超时/无保护),属整改新增能力,非 FR-016 回归。其余错误码与字段集与 001 基线逐字一致。

## 四、验证证据

### 4.1 回归门禁 `./mvnw verify`

```
surefire:  Tests run: 182, Failures: 0, Errors: 0, Skipped: 0
failsafe:  Tests run: 20,  Failures: 0, Errors: 0, Skipped: 0   (9 个真实后端 *IT)
BUILD SUCCESS   Total time: 53.322 s
```

9 个 IT(真实 arthas 4.3.0 + 真实业务 JVM + 官方 MCP Java SDK client):
`BackendClientContractIT`、`FaultIsolationContractIT`、`ToolsCallRoutingContractIT`、`ArthasMcpBackendIT`、`AsyncTaskContractIT`、`AsyncTaskTimeoutIT`、`FailedTargetErrorIT`、`HotReloadIT`、`ResultConsistencyIT`。

### 4.2 端到端冒烟 `claude -p`(真实 Claude Code,SC-008)

| 场景 | 工具 | 结果 | 证据(节选) |
|---|---|---|---|
| 网关自有工具 | `arthas-gateway.list-targets` | ✅ `num_turns=2`、`permission_denials=[]` | `{"version":1,"targets":[{"name":"order-service","state":"ACTIVE","healthy":true,"protocol":"STREAMABLE"},{"name":"payment",...}]}` |
| 同步诊断 | `jvm target=order-service` | ✅ 真实 JVM 诊断 | `SPEC-VERSION=21`、`VM-VERSION=21.0.5+9-LTS-239`、`INPUT-ARGUMENTS=[...,-Ddemo.slowMs=0]`(业务服务入参铁证) |
| 异步诊断 | `watch`→`arthas-gateway.task-get` | ✅ taskId 全流程 | step1 `status=working,taskId=t-116700`;step2 `status=completed`;5 帧真实 `hotMethod`(`OrderResult[orderId=176,price=5463,valid=true]`) |

冒烟产物存于 `target/smoke-002-listtargets.json`、`target/smoke-002-jvm.txt`、`target/smoke-002-watch.txt`。

## 五、整改新增/变更的对外能力(非回归,登记备查)

- **STATELESS 异步前置拒绝**(FR-004):无状态后端的异步诊断调用现前置返 INVALID_PARAMS + `reason=stateless_unsupported_async`,不再隐式提交后台阻塞至兜底超时。
- **全局跨 target 背压**(FR-010):异步并发新增全局上限(动态 = 后端数 × 5,可配置),触顶返 INVALID_PARAMS + `reason=global_concurrency_limit` + `globalMaxInflight`。

两者均为评审发现点名的**新增保护**,不破坏任何既有合法调用路径(同步对 STATELESS 仍可用;单 target 限额不变)。

## 六、未纳入本次范围

- 评审第四节「验证裁决记录」的 3 项候选(REFUTED-1/2/3)经对抗式验证驳回,不修复(含 SDK 反编译证据)。
- Git 提交:按用户决策,本次仅工作区文件改动,**不 commit git**。

---

**整改完成**:15/15 发现修复,202 测试全绿,端到端冒烟通过,对外行为零回归。
