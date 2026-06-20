---
description: "代码评审发现修复 — 权威任务清单(SDD,测试先于实现)"
---

# Tasks: 代码评审发现修复

**Input**: 设计文档 `/specs/002-code-review-remediation/`(plan.md / spec.md / research.md / data-model.md / contracts/remediation-invariants.md / quickstart.md) + 设计文档 `docs/superpowers/specs/2026-06-21-code-review-remediation-design.md`

**Prerequisites**: plan.md(required)、spec.md(required)、research.md、data-model.md、contracts/、quickstart.md — 均已就绪。

**Tests**: 本特性为 TDD 推进(宪法原则七 + CLAUDE.md 不可妥协真实性约束)。**每项测试先于实现编写,确保失败(红)再实现至通过(绿)**。测试真实性分层:网关自身并发/资源逻辑用**真实 JVM 并发原语 + 受控 `BackendClient` test double 触发真实失败条件**(非 arthas 成功桩);与 arthas 交互走既有真实夹具(`ArthasMcpBackend`/`DemoBusinessApp`/`McpClientHarness`)。

**Organization**: 按 spec.md 五个用户故事组织(US1~US5),可追溯至 FR-001~015。统一拦截层基础设施(多故事共享)置于 Phase 2 Foundational。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行(不同文件、无依赖)
- **[Story]**: 所属用户故事(US1~US5);Setup/Foundational/Polish 无 story 标签
- 任务描述含确切文件路径

## 两条全局纪律(贯穿每个任务)

1. **不影响现有功能(FR-016)**:对外可观测 MCP 行为(工具/资源定义、`target` 路由、原样透传、热重载、错误传播、健康状态、报文字段 `available`/`retryAfterMs`/`reason`/错误码)逐项保持。
2. **每步验证(FR-017)**:每完成一个可独立提交的小步,先跑 `./mvnw verify`(既有测试全绿)再推进;任一步引入回归立即停下排查。

---

## Phase 1: Setup(回归起点)

**Purpose**: 锁定 001 基线为回归对照,确认真实环境就绪。

- [x] T001 跑 `./mvnw verify` 确认 001 基线全绿(回归起点);确认冒烟配置就绪(`smoke/gateway-start.sh`、`target/smoke-mcp-config.json` 或按需生成)、`tools/arthas-boot.jar` 与 `claude` CLI 可用

---

## Phase 2: Foundational(统一拦截层基础设施,阻塞全部用户故事)

**Purpose**: 多用户故事共享的底层(熔断线程安全、initialize 原子、域异常、受控测试替身)。**必须在 US1~US5 前完成。**

**⚠️ CRITICAL**: 未完成本阶段不得开始任何用户故事。

- [x] T002 [P] 创建受控 `BackendClient` test double 于 `src/test/java/com/arthas/gateway/testfixtures/FakeBackendClient.java`：可配置 `initialize` 行为(成功/抛异常/计数握手次数)、`callTool` 行为(返回固定 `CallToolResult`/抛指定异常/可阻塞)。**用途**:为统一拦截层/熔断/CAS/竞态单测触发**真实失败条件**(抛异常/拒绝/中断),**非 arthas 成功响应桩**——arthas 成功保真度仍由既有 IT(`ArthasMcpBackend`)覆盖。测试先:T002 先写一个 `FakeBackendClientTest` 断言其可配置行为,再落地 double。
- [x] T003 [P] `CircuitBreaker` 三方法加 `synchronized` 于 `src/main/java/com/arthas/gateway/backend/CircuitBreaker.java`(`allowRequest`/`recordSuccess`/`recordFailure`;`retryAfterMillis`/`state` 多字段读亦 `synchronized` 保一致)——P1-1/FR-003。测试先:`CircuitBreakerConcurrencyTest`(真实多线程并发 `recordFailure`,断言达阈值精确 OPEN,无丢失更新、HALF_OPEN 仅放 1 探测)
- [x] T004 [P] `HttpBackendClient.initialize` 改 `AtomicBoolean.compareAndSet` 守卫于 `src/main/java/com/arthas/gateway/backend/HttpBackendClient.java`(CAS 成功者才握手)——P1-4/FR-006。测试先:`HttpBackendClientInitializeCasTest`(并发首次路由同一后端,握手副作用恰好一次;用 `FakeBackendClient` 或可计数的 `McpSyncClient` 替身触发)
- [x] T005 [P] 创建 4 个域异常类于 `src/main/java/com/arthas/gateway/backend/`:`CircuitOpenException`(含 `long retryAfterMs`)、`ConcurrencyLimitException`(含 `int maxConcurrentTasks`)、`StatelessAsyncException`、`BackendUnreachableException`(含 `Throwable cause`)。均为 `RuntimeException` 子类,不依赖 `McpError`/注册表。

**Checkpoint**: 底层就绪。可开始用户故事(顺序遵循设计文档 §七波次:US1→US2→US3→US4→US5,因修法围绕统一拦截层强耦合)。

---

## Phase 3: User Story 1 — 关闭竞态下无僵尸任务、无槽泄漏(P0)🎯 MVP

**Goal**: 后台池关闭竞态下,异步任务不留僵尸 WORKING、不泄漏并发槽、目标不锁死。

**Independent Test**: 注入会抛 `RejectedExecutionException` 的受控执行池,提交异步任务,断言 `store` 无残留 WORKING、槽许可全数回收、目标重启可正常调用。

### Tests for User Story 1

> **先写,确保失败(红)再实现。**

- [x] T006 [US1] `AsyncTaskExecutorShutdownRaceTest` 于 `src/test/java/com/arthas/gateway/task/`：(a) 外层 `pool.submit` 被拒(池已关)→ 断言 `store` 无该 taskId、`onTerminal` 被调用恰好一次(槽释放);(b) 内层提交被拒 → 断言任务标 `FAILED`(`markFailed(BACKEND_UNREACHABLE)`)非 WORKING、`onTerminal` 调用;(c) 正常完成/超时/取消路径 → `onTerminal` 恰好一次。用真实 `ExecutorService`(`shutdownNow` 后 submit 触发真实拒绝)+ 受控 `Callable`。

### Implementation for User Story 1

- [x] T007 [US1] `BackendEntry` 统一拦截层主体于 `src/main/java/com/arthas/gateway/backend/BackendEntry.java`：新增 `execute(tool,args)`(admit→try{invoke}finally{releaseSlot},同步 RAII)、`invoke(tool,args)`(initializeOnce→callTool→分类,返回原始 `CallToolResult`)、`isHealthy()`(`state==ACTIVE && breaker.state()!=OPEN`)、`releaseSlot()`(既有,公开)。**注意**:`admit` 的熔断守卫/取槽/STATELESS 校验分别在本故事暂留最小版(熔断读+取槽),STATELESS 校验留 US3、`invoke` 的 recordSuccess/Failure 留 US2;本故事保证 execute/invoke 可编译且同步路径行为不变。
- [x] T008 [US1] `AsyncTaskExecutor.submit` 增 `Runnable onTerminal` 参数于 `src/main/java/com/arthas/gateway/task/AsyncTaskExecutor.java`：外层 `pool.submit` 纳入 try,`RejectedExecutionException`→`store.remove(taskId)` + `onTerminal.run()` + 抛出(P0-1/P0-2);`orchestrate` 内层 `pool.submit(work)` 纳入 try,内层 reject→`markFailed(BACKEND_UNREACHABLE)`(P0-1);`finally` 恒 `supervisorFutures.remove` + `onTerminal.run()`(任一终态释放,P0-2)。保留既有超时/取消终态映射。构造与 `TaskInfrastructureConfig` 装配同步更新签名。
- [x] T009 [US1] `ToolsCallRouter` 两路径委托于 `src/main/java/com/arthas/gateway/handler/ToolsCallRouter.java`：`forwardSync`→`entry.execute(tool,args)`(try/catch 域异常→`McpError` 翻译,字段逐字保持 `available`/`retryAfterMs`/`reason`/错误码);`submitAsync`→`entry.admit(tool)`(catch 翻译)+ `asyncExecutor.submit(tool, target, () -> entry.invoke(tool,args), entry::releaseSlot)`。**移除**两路径内联的 `guardCircuit`/`tryAcquireSlot`/`recordSuccess`/`recordFailure`/闭包手动 `releaseSlot`(全部下沉 `BackendEntry`)。本故事先把同步路径与异步取槽/释放打通,熔断写记录与 STATELESS 校验在 US2/US3 补。

**Checkpoint**: 关闭竞态无僵尸/无槽泄漏。跑 `./mvnw verify` 既有套件全绿 + 场景 A 冒烟。**这是 MVP 增量,可独立验证。**

---

## Phase 4: User Story 2 — 熔断并发正确覆盖全部调用路径(P1-1/P1-3)

**Goal**: 默认并发下熔断精确累加失败计数;异步路径驱动熔断;人为取消不计失败。

**Independent Test**: 并发记录失败如期 OPEN;纯异步不可达计入熔断;取消不误熔断。

### Tests for User Story 2

- [x] T010 [US2] `BackendEntryInterceptionLayerTest` 于 `src/test/java/com/arthas/gateway/backend/`：(a) `invoke` 成功→`breaker.recordSuccess`、HALF_OPEN 探测成功→CLOSED;(b) `invoke` 后端业务错误(`FakeBackendClient` 返回 `isError=true` 或抛 `McpError`)→`recordSuccess`(不计熔断);(c) `invoke` 基础设施故障(`FakeBackendClient` 抛 `RuntimeException`)→`recordFailure`;抛 `BackendUnreachableException`;(d) **取消中断不计**:模拟 worker 线程中断态下 `invoke` 抛异常 → 不 `recordFailure`。

### Implementation for User Story 2

- [x] T011 [US2] `BackendEntry.admit/invoke` 补熔断记录与中断检测于 `src/main/java/com/arthas/gateway/backend/BackendEntry.java`：`admit` 加熔断守卫读(`!breaker.allowRequest()`→抛 `CircuitOpenException(retryAfterMillis())`)+ 取槽(`!tryAcquireSlot()`→抛 `ConcurrencyLimitException(maxConcurrentTasks())`)。`invoke`:`catch(McpError)`→`recordSuccess` 原样抛;`catch(RuntimeException e)`→**先检测 `Thread.currentThread().isInterrupted()`,若中断(取消导致)跳过 `recordFailure`** 否则 `recordFailure` 后抛 `BackendUnreachableException(e)`(P1-3,cancel 方案=中断 worker+检测跳过)。`ToolsCallRouter` 已委托,确认 `recordSuccess/Failure` 不再出现在 router。

**Checkpoint**: 熔断并发安全 + 异步驱动 + 取消不计。跑 `./mvnw verify` 全绿 + 场景 B 冒烟。

---

## Phase 5: User Story 3 — 异步调用遵循后端协议契约(P1-2)

**Goal**: 对 STATELESS 后端的异步类调用前置返明确错误,不提交后台、不耗尽兜底超时;STATELESS 同步调用仍正常。

**Independent Test**: STATELESS 异步立即 INVALID_PARAMS(`reason=stateless_unsupported_async`);STATELESS 同步正常。

### Tests for User Story 3

- [x] T012 [US3] `ToolsCallRouterStatelessTest` 于 `src/test/java/com/arthas/gateway/handler/`:配置 STATELESS 后端,对其调异步类工具(`watch`)→ 立即 `McpError`(INVALID_PARAMS + `reason=stateless_unsupported_async`),不进 `asyncExecutor.submit`;对其调同步类工具(`jvm`)→ 正常(不抛 STATELESS)。

### Implementation for User Story 3

- [x] T013 [US3] `BackendEntry.admit` 加协议校验于 `src/main/java/com/arthas/gateway/backend/BackendEntry.java`:`admit` 首检 `config().protocol()==Protocol.STATELESS`→抛 `StatelessAsyncException`。`ToolsCallRouter.submitAsync` 的 catch 翻译为 INVALID_PARAMS(`reason=stateless_unsupported_async`,data 含 `target`/`available`)。**仅异步路径**校验(同步 `execute` 不经 `admit` 的 STATELESS 检查,保持 STATELESS 同步可用)。

**Checkpoint**: STATELESS 异步前置拒绝。跑 `./mvnw verify` 全绿 + 场景 C 冒烟。

---

## Phase 6: User Story 4 — 长生命周期与边界健壮(P2-1/2/3/4)

**Goal**: 退役不切断 in-flight;null 可选参数正常路由;单查 O(1);跨目标全局背压。

**Independent Test**: 退役+in-flight 任务 completed;null 参数转发;大规模存储 get 不扫全表;跨 target 累计 inflight ≤ 全局上限。

### Tests for User Story 4

- [x] T014 [P] [US4] `DiagnosticRequestNullArgTest` 于 `src/test/java/com/arthas/gateway/handler/`:`backendArgs` 含 null value(`{"target":"x","timeout":null}`)→ 构造与 `backendArgs()` 返回正常、含 null、不可变。
- [x] T015 [P] [US4] `TaskStoreGetReadAmplificationTest` 于 `src/test/java/com/arthas/gateway/task/`:大规模终态任务(如 10k)下 `get(某 taskId)` 不触发全表 `cleanExpired`(用计数探针/耗时断言),过期单条 `get` 返 empty 且仅移除该条。
- [x] T016 [US4] `GlobalBackpressureTest` 于 `src/test/java/com/arthas/gateway/task/`:多 target 各发并发异步,跨 target 累计 inflight 超 `global-max-inflight`(动态默认=后端数×5)→ 超限返 INVALID_PARAMS(`reason=global_concurrency_limit`);未越界正常。

### Implementation for User Story 4

- [x] T017 [P] [US4] `DiagnosticRequest` 防御拷贝改 `Collections.unmodifiableMap(new LinkedHashMap<>(backendArgs))` 于 `src/main/java/com/arthas/gateway/handler/DiagnosticRequest.java`(容忍 null value,保留不可变+稳定序)——P2-2/FR-008。
- [x] T018 [P] [US4] `TaskStore.get(taskId)` 只判查到那一条于 `src/main/java/com/arthas/gateway/task/TaskStore.java`:存在且未过期→返回;过期→`remove(taskId)` 返 empty;不存在→返 empty。**不再**调全表 `cleanExpired()`(全表清理仅留 `list` + 后台 cleaner)——P2-3/FR-009。
- [x] T019 [US4] `BackendConfigWatcher` 退役宽限改默认 `=backendTimeout` + `retireAll` 用可追踪 `ScheduledExecutorService` 于 `src/main/java/com/arthas/gateway/backend/BackendConfigWatcher.java`:`retirementGrace` 默认取 `backendTimeout`(11min,保证 in-flight 异步可完成);裸 `Thread.startVirtualThread(sleep)` 改 `ScheduledExecutorService` + `close()` 时 `awaitTermination`(容器关闭 graceful)——P2-1/FR-007。
- [x] T020 [US4] `AsyncTaskExecutor` 增全局背压 + 配置项于 `src/main/java/com/arthas/gateway/task/AsyncTaskExecutor.java` + `src/main/java/com/arthas/gateway/config/GatewayProperties.java`:新增 `Semaphore globalInflight`;配置项 `arthas-gateway.task.global-max-inflight`(Integer,null/未设→动态默认=注册表后端数×5,每次 `submit` 按当前注册表 size 计算 cap)。`submit` 前 `globalInflight.tryAcquire()`,失败→抛 `GlobalConcurrencyLimitException`(携带 `globalMaxInflight`),`onTerminal` 时 `release()`。`ToolsCallRouter.submitAsync` catch 翻译为 INVALID_PARAMS(`reason=global_concurrency_limit`,data 含 `globalMaxInflight`/`available`)——P2-4/FR-010。

**Checkpoint**: 健壮性四项。跑 `./mvnw verify` 全绿 + 场景 F 冒烟。

---

## Phase 7: User Story 5 — 凭据脱敏/单一事实源/代码卫生(P3-1/2/3/4/5)

**Goal**: 凭据不泄漏;健康判定单一事实源;序列化单例;配置拒截断;死代码清除。

**Independent Test**: `Auth.toString` 脱敏;`list-targets`/`/actuator/health`/守卫三处 healthy 一致;浮点/超界配置报错保留原值;无死代码残留且编译通过。

### Tests for User Story 5

- [x] T021 [P] [US5] `BackendConfigAuthMaskingTest` 于 `src/test/java/com/arthas/gateway/backend/`:各 `AuthMode`(BEARER/BASIC/NONE)下 `Auth.toString()` 不含明文 token/username/password(仅 mode + 掩码如 `****XX`)。
- [x] T022 [P] [US5] `BackendConfigLoaderParsingTest` 于 `src/test/java/com/arthas/gateway/backend/`:`maxConcurrentTasks: 5.0`/超 int 范围 Long(`2147483648`)→ 报错且错误信息含原始值;`version: 1.0` → 报错;合法整数通过。
- [x] T023 [US5] 健康单一事实源测试(可并入既有 `BackendRegistryHealthIndicatorTest`/`ListTargetsContractTest`):三处对熔断 OPEN 后端返回 healthy=false 一致。

### Implementation for User Story 5

- [x] T024 [P] [US5] `BackendConfig.Auth` 重写 `toString()` 脱敏于 `src/main/java/com/arthas/gateway/backend/BackendConfig.java`:仅 mode + 凭据掩码(`****` + 末 2 位),不含明文——P3-1/FR-011。
- [x] T025 [P] [US5] 抽 `McpJson` 单例于 `src/main/java/com/arthas/gateway/handler/McpJson.java`:`static final ObjectMapper MAPPER` + `json(...)` 封装;`ToolsCallRouter` 等三处 `new ObjectMapper()` 改委托——P3-3/FR-013。
- [x] T026 [P] [US5] `BackendConfigLoader.asInt` 拒浮点/超界 + 合并等价方法于 `src/main/java/com/arthas/gateway/backend/BackendConfigLoader.java`:`asInt` 仅 `Integer`/`Long`(在 int 范围)通过,`Double`/`Float`/超界 Long 报错并保留原始值;`readVersion` 同理拒浮点;删 `asNullableString`,3 处改 `asString`(二者字节级等价)——P3-4/P3-5/FR-014/015。
- [x] T027 [US5] 健康单一事实源于 `src/main/java/com/arthas/gateway/handler/GatewayToolHandlers.java` + `src/main/java/com/arthas/gateway/obs/BackendRegistryHealthIndicator.java`:`listTargets` 与 `HealthIndicator` 的 healthy 判定均委托 `BackendEntry.isHealthy()`(P3-2/FR-012)。
- [x] T028 [P] [US5] 删死代码:`ExposedTool.gatewayOwned()`(`src/main/java/com/arthas/gateway/tool/ExposedTool.java`)、`TaskError.REASON_CIRCUIT_OPEN`(`src/main/java/com/arthas/gateway/task/TaskError.java`)、`TaskSupport.wireValue()`(`src/main/java/com/arthas/gateway/tool/TaskSupport.java`)——P3-5/FR-015。删后编译通过、既有测试全绿。

**Checkpoint**: 安全卫生与清理。跑 `./mvnw verify` 全绿 + 场景 G 冒烟。

---

## Phase 8: Polish & 回归门禁

**Purpose**: 全量回归 + 端到端冒烟,确认 FR-016/SC-008。

- [x] T029 全量 `./mvnw verify`(单测 + IT + 双侧契约)全绿;确认既有 001 全套 S-*/C-*/G-* 断言逐项通过(对外报文字段 `available`/`retryAfterMs`/`reason`/错误码逐字保持)。
- [x] T030 端到端 `claude -p` 冒烟(SC-008):启 `smoke/gateway-start.sh`(真实 arthas + 真实业务服务 + 网关),逐项验证——`tools/list` 35 工具、`jvm target=` 路由与直连一致、`watch` 异步 taskId、热重载增删、故障隔离、`list-targets` healthy。按 `arthas-mcp-gateway-冒烟测试报告.md` 记录。
- [x] T031 核对对外报文字段逐字一致(熔断 OPEN/并发越界/不可达/STATELESS/global_concurrency_limit 各错误码 + data 字段),确认无 FR-016 回归;产出整改完成报告(15 项发现逐项对应 FR/测试/验证状态)。

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup(Phase 1)**: 无依赖,立即开始(T001 锁定基线)。
- **Foundational(Phase 2)**: 依赖 Setup;**阻塞**全部用户故事。
- **用户故事(Phase 3~7)**: 依赖 Foundational。**顺序执行**(非并行)——修法围绕统一拦截层强耦合:US1(execute/admit/invoke/onTemporal/router 委托)→ US2(invoke 熔断记录+取消检测)→ US3(admit STATELESS)→ US4(健壮性+全局背压)→ US5(清理)。每故事完成跑 `./mvnw verify` 再下一个。
- **Polish(Phase 8)**: 依赖全部用户故事完成。

### Within Each User Story

- 测试**先于**实现编写并确认失败(红)。
- 实现(绿)→ 重构 → `./mvnw verify` 全绿 → 下一个。
- 任一步引入回归立即停下排查(CLAUDE.md「连续失败 3 次暂停」)。

### Parallel Opportunities

- Phase 2:T002/T003/T004/T005 不同文件、无依赖,可并行([P])。
- Phase 4(US4):T014/T015 测试、T017/T018 实现不同文件,可并行([P])。
- Phase 7(US5):T021/T022 测试、T024/T025/T026/T028 实现不同文件,可并行([P])。
- 其余(BackendEntry/AsyncTaskExecutor/ToolsCallRouter 改动)强耦合,串行。

---

## Implementation Strategy

### MVP First(US1 only)

1. Phase 1 Setup(T001 基线)。
2. Phase 2 Foundational(T002~T005 底层)。
3. Phase 3 US1(P0 关闭竞态根治)。
4. **STOP & VALIDATE**:`./mvnw verify` 全绿 + 场景 A 冒烟(无僵尸/无槽泄漏)。这是最高优先级(P0)增量。

### Incremental Delivery

5. US2(熔断并发)→ 验证;US3(STATELESS)→ 验证;US4(健壮性)→ 验证;US5(清理)→ 验证。
6. Phase 8 回归门禁(全套 + 端到端冒烟)。

### 测试真实性(宪法原则七 + CLAUDE.md 硬约束)

- **网关自身逻辑**(竞态/熔断/CAS/槽/读放大/背压):真实 JVM 并发原语 + `FakeBackendClient`(T002)/受控 `Callable` 触发**真实失败条件**,非 arthas 成功桩。
- **与 arthas 交互**:既有真实夹具(`ArthasMcpBackend`/`DemoBusinessApp`/`McpClientHarness`)覆盖双侧契约;故障用真实条件(停后端/错 token/sleep/6 并发越界)。
- **端到端**:真实 `claude -p` MCP 冒烟(工具可用性)。

---

## Notes

- [P] = 不同文件、无依赖可并行;[Story] 映射用户故事。
- 每个用户故事独立可测;Polish 阶段做全量回归门禁。
- 测试先失败再实现(TDD 红绿);每个 checkpoint 跑 `./mvnw verify` 确认无回归(FR-017)。
- 对外报文字段逐字保持是 FR-016 安全边界,任何偏差即回归,立即停下。
- **不提交 git**(用户决策):仅产出工作区文件,待用户回来 review 后提交。
