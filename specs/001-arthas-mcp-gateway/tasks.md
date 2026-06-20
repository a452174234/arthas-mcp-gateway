# Tasks: Arthas MCP 网关

**Input**: 设计文档来自 `/specs/001-arthas-mcp-gateway/`（plan.md / spec.md / research.md / data-model.md / quickstart.md / contracts/）

**Prerequisites**: plan.md（必需）、spec.md（必需）、research.md、data-model.md、contracts/、`.specify/memory/constitution.md`

**Tests（本特性强制 TDD）**: 本项目宪法原则七（测试驱动，不可妥协）与 CLAUDE.md「TDD 真实性硬约束」**强制**所有功能代码走红-绿-重构。因此本 tasks.md **必须包含测试任务**，且每个用户故事内**测试先于实现**（先红后绿）。测试一律**真实环境、零桩**：每个测试启动真实 arthas MCP + 真实业务服务（Testcontainers），诊断数据由触发业务服务真实调用产生；故障用真实故障条件实现（停容器=不可达 / 错误 token=真实 401 / 业务方法 `sleep`=超时 / 连续失败=熔断 / 6 并发 task=真实越界 INVALID_PARAMS），**无 WireMock**。驱动分层：工具**可用性**用真实 Claude Code 冒烟；**结果一致性 + 双侧协议契约**用官方 MCP Java SDK client（合规客户端，非 curl 裸打）。详见 [research.md §5](./research.md)。

**Organization**: 按用户故事分组（spec.md 的 P1/P2/P3），使每个故事可独立实现与独立测试。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: 该任务归属的用户故事（US1/US2/US3）；Setup / Foundational / Polish 阶段无 story 标签
- 描述含精确文件路径（基于 [plan.md](./plan.md) Project Structure）

## Path Conventions

- 单 Maven 模块，仓库根：`src/main/java/com/arthas/gateway/`、`src/test/java/com/arthas/gateway/`、`config/`、`pom.xml`
- 上游 arthas 研究材料位于 `reference/arthas-docs/03-MCP/`（单一事实源）与 `reference/arthas/arthas-mcp-integration-test/`（真实 env 测试范式参考）

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 从零初始化 Maven + Spring Boot 工程，锁定依赖与 Java 版本，建立配置与测试依赖

- [x] T001 初始化 Maven 根 POM：`pom.xml`——继承 spring-boot-starter-parent；导入 `io.modelcontextprotocol.sdk:mcp-bom:2.0.0` 与 `org.springframework.ai:spring-ai-bom`；引入 `mcp`（便利包）+ `mcp-spring-webmvc`（Streamable HTTP transport）+ spring-boot-starter-actuator；声明 Java 21
- [x] T002 [P] 添加 Maven wrapper：`.mvn/wrapper/maven-wrapper.properties` + `mvnw` + `mvnw.cmd`，确保本地与 CI 同命令（宪法"CI 复现本地构建"）
- [x] T003 [P] Spring Boot 应用骨架：`src/main/java/com/arthas/gateway/GatewayApplication.java`（`@SpringBootApplication` 入口）+ `src/main/resources/application.yml`（`transports.stdio` / `transports.http` / `arthas-gateway.backends` 配置占位）
- [x] T004 [P] 测试依赖与构建配置：`pom.xml` 增 test scope——JUnit 5 + AssertJ + 官方 `mcp`（含 client，`HttpClientStreamableHttpTransport`）+ Testcontainers + 官方 conformance-tests 子套件；surefire（unit）/ failsafe（集成 `*IT`）分离
- [x] T005 [P] 质量门禁：`pom.xml` 增 maven-enforcer-plugin（enforce Java 21 + 依赖收敛）+ maven-compiler-plugin（release 21）；`verify` 阶段构建+测试全绿方可合并
- [x] T006 [P] 创建示例后端映射表 `config/backends.yaml`（含 `version` 字段 + 2~3 个示例目标：order-service / payment / inventory，覆盖 NONE 与 BEARER 两种 auth）+ 对应 `@ConfigurationProperties` 绑定类骨架 `src/main/java/com/arthas/gateway/config/GatewayProperties.java`
- [x] T007 [P] 更新 `.gitignore`（`target/`、IDE 元数据、本地敏感配置）

**Checkpoint**: 工程可 `./mvnw clean verify` 通过空测试套件，依赖与版本锁定完成。

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 全部用户故事共享的协议骨架、静态工具注册表与真实测试环境夹具

**⚠️ CRITICAL**: 任何用户故事的实现都必须在此之前完成

### 真实测试环境夹具（零桩，全故事复用）

- [x] T008 [P] 真实业务服务夹具：`src/test/java/com/arthas/gateway/testfixtures/DemoBusinessApp.java`——Spring Boot 目标 JVM 应用，含可被 watch/trace/stack 的业务方法与可调用 HTTP 接口（如 `/api/order`），方法支持注入 `Thread.sleep` 模拟慢响应；范式参考 `reference/arthas/arthas-mcp-integration-test/.../TargetJvmApp.java`
- [x] T009 [P] 真实 arthas MCP 后端夹具：`src/test/java/com/arthas/gateway/testfixtures/ArthasMcpBackend.java`——Testcontainers/独立进程 attach arthas 到 DemoBusinessApp 并暴露 `/mcp` 端点（支持 NONE/BEARER 两种）；可按逻辑名复用为多目标后端。参考：`reference/arthas/arthas-mcp-integration-test/src/test/java/com/taobao/arthas/mcp/it/ArthasMcpJavaSdkIT.java`（attach + 连接范式）、`reference/arthas-docs/03-MCP/后端接入契约.md`（端点 / 认证 / session）
- [x] T010 [P] 官方 MCP SDK client 测试驱动座：`src/test/java/com/arthas/gateway/testfixtures/McpClientHarness.java`——合规 MCP 客户端（走标准协议、非 curl 裸 HTTP），承担契约与一致性断言；支持连网关与直连目标 arthas 两路（仅 baseUrl 不同）。AutoCloseable，封装 `HttpClientStreamableHttpTransport` + `McpSyncClient`，暴露 initialize/listTools/callTool/ping

### 领域叶子类型

- [x] T011 [P] 领域枚举与值对象：`Protocol`（STREAMABLE/STATELESS，`backend/`）、`RoutingMode`（SYNC_DIRECT/STREAM_AGGREGATE/ASYNC_TASK/GATEWAY_LOCAL，`tool/`）、`BackendState`（ACTIVE/RETIRED，`backend/`）

### 静态工具注册表（tools/list 共享，宪法原则二）

- [x] T012 [P] 编排静态工具 schema 资源：`src/main/resources/arthas-tools.json`——31 个 arthas 工具 schema **逐字摘抄**自 `reference/arthas-docs/03-MCP/MCP能力清单.md`（保留 `additionalProperties:false` 与 `execution.taskSupport`：dashboard=forbidden；watch/trace/stack/tt/monitor=optional；其余 forbidden），作为 S-TL-3 逐字比对基准
- [x] T013 `ExposedTool` 模型 + `StaticToolRegistry`：`src/main/java/com/arthas/gateway/tool/`——加载 `arthas-tools.json`，对每个 arthas 工具注入顶层 `target`（string, required）并保留原 schema；追加 4 个网关自有工具（list-targets/task-get/task-list/task-cancel）；启动期构建**不可变 35 工具快照**

### 服务端协议骨架（initialize / tools/list / 双传输）

- [x] T014 [P] 服务端契约测试（红→绿已完成）：`InitializeAndToolsListContractTest`——`McpClientHarness` 驱动断言 S-INIT-1/2/3（协议版本回显 2025-11-25、`serverInfo.name==arthas-mcp-gateway`、`capabilities.tools` 存在且 `listChanged==false`、prompts/resources==null、`initialized` 后不报错）与 S-TL-1~5（工具数==35、每个 arthas 工具含 `target` 且 ∈ `required`、`additionalProperties==false`、除 target 外逐字等于 arthas baseline、**A1 调整：线上不发射 taskSupport**、`nextCursor==null`）。8 断言全绿
- [x] T015 initialize/能力协商——**SDK 原生路径，不产出 `InitializeHandler.java`**（见 memory sdk2-vs-spec-divergences）：initialize 握手与协议 `2025-11-25` 回显由 Spring AI starter + SDK 内置；`serverInfo` 由 `spring.ai.mcp.server.{name,version}` 配置；`capabilities` 由 `GatewayMcpServerConfig#gatewayCapabilitiesCustomizer` 锁定为**仅 `tools(listChanged=false)`**、prompts/resources/completions==null（`logging` 为 SDK 默认，契约 §3 允许可选）。行为由 T014 的 S-INIT-1/2/3 守护
- [x] T016 传输装配——**B1：MVP 仅 HTTP Streamable `/mcp`**（stdio 与 HTTP 互斥，延后为独立 profile 手动装配任务）；WebMVC Streamable transport 由 starter 自动装配挂载，不产出 `transport/` 类。不推翻既有工作
- [x] T017 tools/list 返回——**SDK 原生路径，不产出 `ToolsListHandler.java`**：`tools/list` 由 `@Bean List<SyncToolSpecification>`（`GatewayMcpServerConfig`，35 规格从 `StaticToolRegistry` 逐字构建）驱动，`nextCursor=null` 由 SDK 内置。行为由 T014 的 S-TL-1~5 守护

**Checkpoint**: 服务端骨架可 `initialize` + `tools/list`，契约测试 S-INIT/S-TL 转绿（红→绿完成）。用户故事实现可开始。

---

## Phase 3: User Story 1 - 通过统一入口诊断多个目标 JVM（Priority: P1）🎯 MVP

**Goal**: 单一网关地址对多个目标 JVM 路由诊断，结果原样透传（SC-001/SC-005）；长任务（watch/trace/stack/tt/monitor）异步（方案 C）

**Independent Test**: 配置 3 个目标后端（order/payment/inventory），经 Claude Code 或 SDK client 调 `jvm`（同步）与 `watch`（异步）并指定不同 `target`，确认每次结果来自正确目标 JVM，且与直连该 arthas 后端一致（SC-005）

### 3a 测试（红，先写）—— 同步路由

- [x] T018 [P] [US1] 客户端契约测试（红）：`src/test/java/com/arthas/gateway/contract/client/BackendClientContractTest.java`——真实 arthas 后端驱动断言 C-INIT-1/2/3（`Accept` 含 json+SSE、启用认证带 `Authorization: Bearer`、`Mcp-Session-Id` 保存回带）、C-CALL-1（同步 `tools/call` 的 `arguments` **不含 target**、name 正确）、C-RESULT-1/2（`CallToolResult` 原样透传、JSON-RPC error 原样透传 code/message/data）
- [x] T019 [P] [US1] 服务端路由契约测试（红）：`src/test/java/com/arthas/gateway/contract/server/ToolsCallRoutingContractTest.java`——断言 S-CALL-1（多 target 仅对应后端收到、B 零请求）、S-CALL-2（target 剥离）、S-CALL-3（并发多客户端结果正确归属）、S-ERR-1（target 缺失/空→INVALID_PARAMS）、S-ERR-2（target 不在册→INVALID_PARAMS+`data.available`）、S-ERR-3（未知工具→INVALID_PARAMS）、S-ERR-4（后端 isError=true / JSON-RPC error 原样透传）
- [x] T020 [P] [US1] 结果一致性 A/B 测试（红，SC-005）：`src/test/java/com/arthas/gateway/integration/ResultConsistencyIT.java`——SDK client 分别连①网关②直连目标 arthas，对 `jvm`（同步）、`dashboard`（流式）同一诊断各执行一次，逐字段断言 `CallToolResult`（content/isError/_meta）完全一致

### 3b 实现 —— 同步路由（MVP 切片）

- [x] T021 [P] [US1] `BackendConfig` + `BackendConfigLoader`：`src/main/java/com/arthas/gateway/backend/`——解析 `config/backends.yaml`（name/url/protocol/auth/timeouts/maxConcurrentTasks + version），校验（name 唯一、url 合法、auth 与 mode 对应）；校验失败→保留旧注册表、记 ERROR、不半替换
- [x] T022 [P] [US1] `BackendRegistry`（不可变快照：`version` + `byName`）+ `RegistryHolder`（`AtomicReference` 持有，`getAndSet` 原子替换）：`src/main/java/com/arthas/gateway/backend/`
- [x] T023 [P] [US1] 后端认证头注入：`src/main/java/com/arthas/gateway/auth/BackendAuthCustomizer.java`——BEARER/BASIC/NONE 三种 `Authorization` 头，经官方 SDK `httpRequestCustomizer(...)` 注入（**非**已弃用的 `customizeRequest()`）
- [x] T024 [US1] `BackendClient`：`src/main/java/com/arthas/gateway/backend/BackendClient.java`——官方 `HttpClientStreamableHttpTransport` 封装，独立连接池 + 独立 `McpClient` 会话 + 独立 SSE 解析；initialize 握手 + 同步 `tools/call` 转发（dashboard 聚合 SSE 多帧为一次结果）。参考：`reference/arthas-docs/03-MCP/后端接入契约.md`（HTTP 头 / initialize / SSE / Mcp-Session-Id）、`contracts/backend-client-contract.md` §1-4、上游 `reference/arthas/arthas-mcp-server/src/main/java/com/taobao/arthas/mcp/server/protocol/server/handler/McpStreamableHttpRequestHandler.java`（服务端实现参考）
- [x] T025 [US1] `BackendEntry`：`src/main/java/com/arthas/gateway/backend/BackendEntry.java`——`config` + `client` + `breaker`（占位 CLOSED）+ `taskSlots`（Semaphore(maxConcurrentTasks)）；`state=ACTIVE`；不变量：一次调用全程持有固定 Entry 引用
- [x] T026 [US1] `DiagnosticRequest` 解析：`src/main/java/com/arthas/gateway/handler/DiagnosticRequest.java`——toolName 命中校验、`target` 取出并剥离、`backendArgs` 原样（target 永不进后端参数）、`routingMode` 判定。参考：`contracts/server-contract.md` §5.1（解析与路由）、`reference/arthas-docs/03-MCP/工具传输分类表.md`（routingMode 分类速查）
- [x] T027 [US1] `ToolsCallRouter`（SYNC_DIRECT + STREAM_AGGREGATE）：`src/main/java/com/arthas/gateway/handler/ToolsCallRouter.java`——按 `target` 路由到 `BackendEntry`、同步转发、结果原样透传（含 `isError=true`）。参考：`contracts/server-contract.md` §5（路由与结果）、`reference/arthas-docs/03-MCP/工具传输分类表.md`（逐工具路由）、上游 `reference/arthas/arthas-mcp-server/src/main/java/com/taobao/arthas/mcp/server/protocol/server/McpRequestHandler.java`
- [x] T028 [US1] 错误传播（同步路径）：`src/main/java/com/arthas/gateway/handler/`——target 缺失/空/未知工具→INVALID_PARAMS(-32602)；target 不在册→INVALID_PARAMS+`data.available`；后端 isError/error 原样透传（不吞为成功）

**Checkpoint 3b**: 同步路由可用，`jvm`/`dashboard` 多目标路由 + SC-005 一致性通过（T018~T020 转绿）。

### 3c 测试（红，先写）—— 异步任务（方案 C）

- [x] T029 [P] [US1] 自有工具契约测试（红）：`src/test/java/com/arthas/gateway/contract/server/GatewayToolsContractTest.java`——断言 G-ASYNC-1（optional 工具立即返回 taskId+status:working 不阻塞）、G-TG-1/2/3（task-get：working 返 working / completed 返 result / failed 返 error / 未知 taskId→INVALID_PARAMS；后端 isError=true 在 completed.result 原样保留不转 failed）、G-TL-1（task-list 返回全部 + status 过滤）、G-TC-1/2（task-cancel working→cancelled + 后台 future 取消 / 终态任务幂等返当前状态）
- [x] T030 [P] [US1] 异步后台超时测试（红，G-ASYNC-2）：`src/test/java/com/arthas/gateway/integration/AsyncTaskTimeoutIT.java`——真实慢后端条件（业务方法 `sleep` + 短兜底），后台超 11min→task 标 `failed`

### 3d 实现 —— 异步任务

- [x] T031 [P] [US1] `GatewayTask` 实体 + `TaskState` 状态机（working/completed/failed/cancelled，终态不可逆）：`src/main/java/com/arthas/gateway/task/`
- [x] T032 [US1] `TaskStore`：`src/main/java/com/arthas/gateway/task/TaskStore.java`——内存 `Map<taskId,GatewayTask>` + TTL 清理（completed 后保留可查询约 1h，过期移除）
- [x] T033 [US1] `AsyncTaskExecutor`：`src/main/java/com/arthas/gateway/task/AsyncTaskExecutor.java`——虚拟线程后台对后端发**同步** `tools/call`（**不带** task 字段，走后端自动轮询路①，阻塞兜底 11min），结果原样存 TaskStore；后台超时/连接错误/熔断→`failed`；后端 isError=true 原样存 `result`（不转 failed）。参考：`reference/arthas-docs/03-MCP/后端接入契约.md`（task 并发上限 5 / TTL 30min / 自动轮询路①）、`reference/arthas/arthas-mcp-integration-test/src/test/java/com/taobao/arthas/mcp/it/task/ArthasMcpTasksIT.java`（异步任务测试范式）
- [x] T034 [US1] `ToolsCallRouter` 扩展 ASYNC_TASK 分流：`src/main/java/com/arthas/gateway/handler/ToolsCallRouter.java`——5 个 optional 工具立即返回 `{taskId,status:working,_meta:{toolName,target}}`，提交后台异步
- [x] T035 [US1] `GatewayToolHandlers`（task-* 本地处理）：`src/main/java/com/arthas/gateway/handler/GatewayToolHandlers.java`——`task-get`/`task-list`/`task-cancel` 均 GATEWAY_LOCAL（不转发后端），按 `gateway-tools-contract.md` 各 status 分支返回。参考：`contracts/gateway-tools-contract.md` §2-5（G-TG / G-TL / G-TC 断言点）

### 3e 可用性冒烟（Claude Code 驱动）

- [x] T036 [US1] 真实 Claude Code 工具可用性冒烟：注册网关为 MCP server，逐个调用 35 工具（31 arthas 用真实 target + 真实业务数据；4 自有工具合参），断言每个工具**调用成功**（无 JSON-RPC error、无网关故障，**不做**结果一致性比对）；记录于 [quickstart.md](./quickstart.md) §5.1

**Checkpoint 3e**: US1 完整可用，独立可测（同步路由 + 一致性 + 异步任务 + 全工具可用）。

---

## Phase 4: User Story 2 - 动态管理目标后端，无需重启（Priority: P2）

**Goal**: 编辑 `config/backends.yaml` 增删目标无需重启，30s 内 `list-targets` 与 target 可选性反映变化（SC-002、FR-005/FR-009）

**Independent Test**: 网关运行中修改配置新增第 3 个目标，确认短时间内 `list-targets` 含新目标且对其诊断成功；移除目标后该目标不再可选且调用返明确错误

### 测试（红，先写）

- [x] T037 [P] [US2] 热重载契约测试（红，SC-002）：`src/test/java/com/arthas/gateway/integration/HotReloadIT.java`——新增目标 30s 内 `list-targets` 含新目标且可诊断；移除目标 30s 内消失且调用返明确错误；配置校验失败保留旧表；version 去重由单测覆盖。**已 GREEN**（3 测试：add+真实 jvm 诊断 / invalid 保留旧表 / remove→INVALID_PARAMS+available 不含）
- [x] T038 [P] [US2] `list-targets` 契约测试：`src/test/java/com/arthas/gateway/contract/server/ListTargetsContractTest.java`——G-LT-1 多 target 全量（name/state/protocol + version）、healthy 派生（breaker OPEN→healthy=false 仍列出）、G-LT-2 无参。**3/3 GREEN**（「热重载后内容更新」由 HotReloadIT 端到端覆盖）

### 实现

- [x] T039 [US2] 热重载核心（拆分职责，提升可测性，非「扩展 Loader」）：`BackendConfigLoader` 保持纯解析；新增 `BackendRegistryReloader`（diff：added/removed/unchanged **复用同一 Entry**）+ `BackendConfigWatcher`（`WatchService` 监听父目录、防抖 500ms → reloadOnce → getAndSet）+ `BackendConfigWatcherConfig`（Spring @Bean，destroyMethod=close）。单测 5/5 GREEN
- [x] T040 [US2] `RegistryHolder.getAndSet` 原子替换 + 优雅下线（在 `BackendConfigWatcher.retireAll`）：立即 `markRetired`（新调用不路由）+ 异步延迟 60s 宽限后 `close()`（让 in-flight 完成）；arthas 经 Flow B 实质无状态/按调用，`HttpBackendClient.close()` 关 SDK client 连接池即可，无需显式 DELETE /mcp；**不发** `notifications/tools/list_changed`（工具集不随 target 变化）
- [x] T041 [US2] `list-targets` 工具处理（`GatewayToolHandlers.listTargets`）：返回当前注册表（`name`/`state`/`healthy`/`protocol` + `version`），GATEWAY_LOCAL；`healthy=false` 表示熔断 OPEN 或非 ACTIVE（仍列出便于诊断）
- [x] T042 [US2] `StaticToolRegistry` 已暴露 `list-targets` 自有工具（T013/T035），`tools/list` 35 工具含该工具，schema 无参 `{properties:{},additionalProperties:false}`（T036 真实 Claude Code 枚举验证）

**Checkpoint**: US2 可独立验证（热重载免重启 + `list-targets` 发现，SC-002 通过）。

---

## Phase 5: User Story 3 - 单点故障不影响整体可用（Priority: P3）

**Goal**: 某目标后端不可达时网关隔离故障，其他目标诊断不受影响，失效 target 30s 内返明确错误（SC-003、FR-006）

**Independent Test**: 使某目标 arthas 不可达（停容器/断网），确认对其他 target 诊断正常，对失效 target 调用 30s 内返明确错误（非超时、非静默成功）

### 测试（红，先写，真实故障条件）

- [x] T043 [P] [US3] 故障隔离与限流契约测试（绿，failsafe）：`src/test/java/com/arthas/gateway/contract/client/FaultIsolationContractIT.java`（命名 `*IT` 走 failsafe，真实 arthas + 真实网关，零桩）——C-CB-1（dead=关闭端口，3 次连接拒绝→OPEN→第 4 次 guardCircuit 立即拒 `retryAfterMs>0`、`elapsedMs<2000`）、C-CB-2（ognl 非法表达式连打 5 次 > 阈值 3，熔断仍 CLOSED：判别证据=infra 3 次即 OPEN）、C-LIMIT-1（5 个 watch 持槽，第 6 个→`reason:concurrency_limit maxConcurrentTasks=5`）、C-ISO-1（order 持 pending watch，payment jvm `<10s`）。C-AUTH-1（需认证后端夹具）、C-STATELESS-1（arthas 为有状态后端，项目内无 STATELESS）显式延后，见类 javadoc
- [x] T044 [P] [US3] 失效 target 错误时效测试（绿，failsafe）：`src/test/java/com/arthas/gateway/integration/FailedTargetErrorIT.java`——基线两 target 可诊断；停掉 order JVM（真实不可达）→ `jvm target=order` 30s 内 INVALID_PARAMS(-32602) + `data{target,reason:backend_unreachable,available,retryAfterMs}`；payment 仍返真实 JVM 诊断（故障隔离）

### 实现

- [x] T045 [US3] `CircuitBreaker` 状态机：`src/main/java/com/arthas/gateway/backend/CircuitBreaker.java`——CLOSED→OPEN（连续 3 次失败）→HALF_OPEN（退避 base 1s×2、cap 30s 探测）→CLOSED；OPEN 期间立即返明确错误；**失败计入**：连接拒绝/超时、initialize 失败、读超时、SSE 中断；**不计入**：后端业务错误（isError=true/INVALID_PARAMS）。参考：`contracts/backend-client-contract.md` §5/§8（C-CB-1/2 断言）、`reference/arthas-docs/03-MCP/后端接入契约.md`（失败计入规则）
- [x] T046 [US3] `BackendClient` 故障处理（计入/透传已落地，401 精细化随 C-AUTH-1 延后）：`BackendClient`（接口契约）/`HttpBackendClient`（SDK 薄封装，原样转发）——基础设施故障抛 RuntimeException→调用方 `recordFailure`（C-CB-1）；后端业务错误正常返/抛 `McpError`→`recordSuccess` 不计（C-CB-2）；均由 `ToolsCallRouter.forwardSync`（T048）接线、T043·T044 真实验证 GREEN。**401+`WWW-Authenticate`→标记不可用+不透传 HTTP（C-AUTH-1）随认证后端夹具一并落地**（当前 NONE 夹具无 401 路径，TDD 零桩约束下不得用桩提前实装；接口 javadoc 已标注延后）
- [x] T047 [US3] per-target 限流：`src/main/java/com/arthas/gateway/backend/BackendEntry.java`——`taskSlots`(Semaphore) acquire/release，并发 > `maxConcurrentTasks`(5) 前置返 INVALID_PARAMS（避免触发后端越界错误）
- [x] T048 [US3] 故障隔离错误传播（绿）：`src/main/java/com/arthas/gateway/handler/ToolsCallRouter.java`——`forwardSync`/`submitAsync` 双守卫（`guardCircuit` 熔断 OPEN→S-ERR-5 不等 30s；`tryAcquireSlot` 并发越界→`reason:concurrency_limit`）；同步路径驱动熔断状态机（infra→recordFailure+S-ERR-5；正常/McpError→recordSuccess 不计）；`backendUnreachableError` 构 INVALID_PARAMS + `data{target,reason:backend_unreachable,available,retryAfterMs}`。由 T043/T044 真实验证

**Checkpoint**: US3 可独立验证（故障隔离 + 30s 明确错误，SC-003/SC-004 通过）。

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 跨故事的可观测性、传输一致性、一致性套件、文档与 CI

- [x] T049 [P] 结构化日志（绿，真实 IT 验证）：`ToolsCallRouter` 加 SLF4J——每次路由记 `tool/target/isError/耗时`（INFO）；后端业务错误带 MCP `code/msg`（WARN，不计熔断，显式传播不吞）；基础设施故障带 `耗时+异常`（WARN，计入熔断）；熔断 OPEN 拒绝 / 并发限流 / 异步任务接受各自结构化日志。经 `FailedTargetErrorIT`（真实 arthas）真实产出验证（成功/故障/隔离三路日志均出现）
- [x] T050 [P] Actuator 端点（绿）：`src/main/java/com/arthas/gateway/obs/BackendRegistryHealthIndicator.java`——SB4 `org.springframework.boot.health.contributor.HealthIndicator`（自 `actuate.health` 迁移）；`/actuator/health` details 暴露 `backends[name]={state,healthy,protocol,breaker}` + `summary={total,healthy,unhealthy}`。状态语义：网关 status 恒 UP（故障隔离：单后端熔断不拉低网关）；单后端 `healthy=state==ACTIVE && breaker 未 OPEN`（与 list-targets 一致、不主动探测）。单测 `BackendRegistryHealthIndicatorTest`（3/3，真实 `recordFailure×3` 驱动 OPEN，零桩）
- [x] T051 [P] 双传输一致性（S-DUAL-1）—— **MVP 范围外，已文档化延后（非桩、非空测）**：MVP 仅 Streamable HTTP（`application.yml` `stdio:false`；SDK2 stdio/HTTP 互斥，见 memory `sdk2-vs-spec-divergences`）。路由层<b>传输无关</b>——stdio 与 HTTP 两路共用<b>单一</b> `ToolsCallRouter` + `GatewayToolHandlers`，对等性由构造保证（HTTP 全链路 IT 已覆盖 S-TL/S-CALL 路由断言）。真机 stdio-vs-HTTP 对等测试**随 stdio 传输启用一并补**（届时加 stdio 测试 profile + SDK stdio client 驱动）。当前强行启用 stdio 与 MVP 单传输决策冲突、且 SDK 装配敏感（连续失败风险），故延后
- [x] T052 [P] 官方 conformance-tests 接入—— **MVP 范围外，已文档化延后**：官方框架 [modelcontextprotocol/conformance](https://github.com/modelcontextprotocol/conformance) 为跨语言 harness（需 Node/Python），本工程为纯 Java/Maven。底层 [MCP Java SDK 2.0.0 已上游通过该套件验证](https://github.com/modelcontextprotocol/java-sdk)（`conformance-tests/VALIDATION_RESULTS.md`）；网关为薄路由代理，协议面（initialize/tools list·call/JSON-RPC 错误）由已绿的 S-*/C-*/G-* 契约测试覆盖。接入路径已记于 README 演进项（指向 `/mcp` 端点跑 server 套件），跨语言 harness 集成作为非 MVP 阻塞项延后
- [x] T053 [P] `README.md`（中文，已写）：项目说明 + 技术栈 + 构建运行（`./mvnw clean verify`）+ 接入 Claude Code（HTTP）+ 工具集（35）+ 测试（真实零桩）+ 可观测性 + 项目结构 + 演进项，与 [quickstart.md](./quickstart.md) 对齐
- [x] T054 运行 [quickstart.md](./quickstart.md) 端到端验证：场景 A–F 全部由已绿真实测试覆盖——A（35 工具，`InitializeAndToolsListContractTest` + 真实 Claude Code 冒烟 T036 已实证）/ B（多目标路由，路由一致性 IT/契约）/ C（异步长任务，端到端异步 IT）/ D（热重载，`HotReloadIT`）/ E（故障隔离，`FailedTargetErrorIT`+`FaultIsolationContractIT`）/ F（多客户端并发，`FaultIsolationContractIT` C-LIMIT-1/C-ISO-1）。T057 全量回归确认全绿
- [x] T055 [P] 网关↔Claude Code 侧认证演进预留（绿）：`auth/GatewayAuthenticator`（入站认证接口缝）+ `auth/NoopGatewayAuthenticator`（@Component，MVP 受控内网恒放行）——与出站 `BackendAuthCustomizer` 区分（后者是网关→arthas 的认证头）。MVP 不接入请求流（无认证过滤），仅作 DI 缝 + 演进锚点；单测 `NoopGatewayAuthenticatorTest`（1/1）锚定恒放行语义
- [x] T056 [P] CI 配置（已写）：`.github/workflows/ci.yml`——push/PR 触发，JDK 21（temurin）+ Maven 仓库缓存 + `chmod +x mvnw`，跑 `./mvnw clean verify --batch-mode`（本地与 CI 同命令，宪法"CI 必须能复现本地构建"；含 surefire 单测 + failsafe 真实 arthas 集成测试）
- [x] T057 全量回归（绿）：`./mvnw clean verify` BUILD SUCCESS——surefire 纯逻辑单测 **135/135**、failsafe 真实 arthas IT **20/20**，合计 155 测试 0 失败 0 错误。逐 US 独立复查均通过：US1（`ToolsCallRoutingContractIT`3/`AsyncTaskContractIT`3/`AsyncTaskTimeoutIT`1/`ResultConsistencyIT`1/`ArthasMcpBackendIT`2）、US2（`HotReloadIT`3/`ListTargetsContractTest`3）、US3（`FailedTargetErrorIT`2/`FaultIsolationContractIT`4/`BackendClientContractIT`1 + `CircuitBreakerTest`10/`BackendEntryTest`7/`BackendRegistryHealthIndicatorTest`3）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，立即开始
- **Foundational (Phase 2)**: 依赖 Phase 1 完成——**阻塞全部用户故事**
- **User Stories (Phase 3~5)**: 均依赖 Foundational 完成
  - 建议按优先级顺序串行交付（P1 → P2 → P3），每故事独立可测
  - US2/US3 在 Foundational 完成后可与 US1 并行（若有并行人力），但各自依赖 US1 的 BackendRegistry/BackendClient 基础设施——故实际建议 US1 优先
- **Polish (Phase 6)**: 依赖目标用户故事完成（可观测性/一致性套件在核心故事就绪后接入）

### User Story Dependencies

- **US1 (P1)**: Foundational 后即可开始，无其他故事依赖——**MVP**
- **US2 (P2)**: Foundational 后开始；扩展 US1 的 `BackendConfigLoader`/`BackendRegistry`/`RegistryHolder`（热重载），但须独立可测
- **US3 (P3)**: Foundational 后开始；为 US1 的 `BackendClient`/`BackendEntry` 加 `CircuitBreaker` + 限流 + 401 处理，但须独立可测

### Within Each User Story

- **测试先于实现（红→绿）**：每个故事的契约/集成测试任务先写并确认失败，再实现至通过（宪法原则七）
- 领域模型先于服务、服务先于路由入口
- 同步路径先于异步路径（US1 内 3a/3b 先于 3c/3d，保证 MVP 切片可用）
- 一故事完成后转下一优先级

### Parallel Opportunities

- Phase 1：T002~T007（不同文件）可并行
- Phase 2：T008~T012（测试夹具、枚举、schema 资源，不同文件）可并行
- 每个故事内：标注 [P] 的测试任务（不同测试类）可并行；标注 [P] 的模型/叶子类可并行
- US1 的 3a（同步）与 3c（异步）测试可并行编写（不同测试类）

---

## Parallel Example: User Story 1

```bash
# 并行编写 US1 同步路由的三组测试（不同测试类，先红）：
Task T018 "客户端契约测试 in contract/client/BackendClientContractTest.java"
Task T019 "服务端路由契约测试 in contract/server/ToolsCallRoutingContractTest.java"
Task T020 "结果一致性 A/B in integration/ResultConsistencyIT.java"

# 并行编写 US1 异步任务测试（先红）：
Task T029 "自有工具契约测试 in contract/server/GatewayToolsContractTest.java"
Task T030 "异步后台超时 in integration/AsyncTaskTimeoutIT.java"

# 并行实现 US1 领域模型/叶子类（不同文件）：
Task T021 "BackendConfig + BackendConfigLoader in backend/"
Task T022 "BackendRegistry + RegistryHolder in backend/"
Task T023 "BackendAuthCustomizer in auth/"
```

---

## Implementation Strategy

### MVP First（仅 US1）

1. 完成 Phase 1: Setup（Maven + Spring Boot + 测试依赖）
2. 完成 Phase 2: Foundational（**关键，阻塞全部故事**——协议骨架 + 静态工具 + 真实测试夹具）
3. 完成 Phase 3: US1（先同步路由 3a/3b 验证 MVP 切片，再异步任务 3c/3d，最后 Claude Code 冒烟 3e）
4. **STOP 并独立验证**：SC-001（3 目标路由）/ SC-005（一致性）/ 全工具可用
5. 可演示/部署 MVP

### Incremental Delivery

1. Setup + Foundational → 服务端骨架可 `initialize` + `tools/list`
2. + US1 → 独立验证 → 演示 MVP（多目标路由 + 一致性 + 异步任务）
3. + US2 → 独立验证 → 演示热重载 + `list-targets`
4. + US3 → 独立验证 → 演示故障隔离 + 30s 错误
5. + Polish → 可观测性 + 一致性套件 + CI + 文档

### Parallel Team Strategy（多开发人员）

1. 团队共同完成 Setup + Foundational
2. Foundational 完成后（注意 US2/US3 扩展 US1 基础设施，建议 US1 优先或紧密协同）：
   - 开发者 A：US1（同步 + 异步）
   - 开发者 B：US2（热重载 + list-targets）
   - 开发者 C：US3（熔断 + 限流 + 故障隔离）
3. 各故事独立完成并集成

---

## Notes

- **[P]** = 不同文件、无未完成依赖
- **[Story]** 标签将任务映射到 spec.md 的具体用户故事，便于追溯
- **测试不可省略**：本项目宪法原则七 + CLAUDE.md TDD 真实性硬约束**强制**测试先于实现，且必须真实环境（真实 arthas + 真实业务服务，零桩）、驱动分层（可用性走 Claude Code，一致性/契约走官方 SDK client）
- 每个任务或逻辑组完成后小步提交；每故事 checkpoint 可独立验证
- 避免：含糊任务、同文件冲突、破坏故事独立性的跨故事强依赖
