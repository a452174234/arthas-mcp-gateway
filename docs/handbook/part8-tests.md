# Part 8 · 测试用例全清单

> 本部分列出全部测试类（单测 + 契约 IT + 集成 IT + 架构），逐个说明：目的、断言、真实故障条件、对应 spec 断言 ID。这是「全面测试」维度的查表。

> **测试体系原则**（宪法原则四/七 + CLAUDE.md）：
> - 双侧契约优先（网关↔arthas、网关↔Claude Code）。
> - TDD：测试先于实现（红-绿-重构）。
> - 真实性硬约束：真实 arthas + 真实业务服务，禁桩；故障用真实故障条件。
> - 驱动分层：工具可用性用真实 Claude Code；一致性/契约用官方 SDK client。
> - surefire 跑 `*Test.java`（单测），failsafe 跑 `*IT.java`（集成，绑 verify 阶段）。

---

## 第 58 章 单元测试（`*Test.java`，surefire，纯逻辑/状态机）

### 58.1 backend 包单测

| 测试类 | 目的 | 关键断言 |
|--------|------|----------|
| `BackendConfigTest` | BackendConfig 值对象 + Auth 脱敏 | maxConcurrentTasks∈[1,5]、url 校验、Auth.toString 脱敏（P3-1）、equals/hashCode 排除 source |
| `BackendConfigLoaderTest` | YAML 解析（严格整数） | version 拒浮点（P3-4）、占位符 `${VAR}`/`${VAR:default}`、默认值、跨实例 name 唯一、asInt 拒超 int 范围 Long |
| `CircuitBreakerTest` | 熔断状态机 | CLOSED→OPEN（连3次）、退避升级 1s/2s/4s/.../30s、HALF_OPEN 放1探测、并发安全（synchronized，P1-1）、retryAfterMs |
| `BackendEntryTest` | 统一拦截层 | execute RAII 释放槽（P0-2）、admit STATELESS 拒（P1-2）、invoke 故障分类（业务错误不计熔断 C-CB-2、cancel 不计 P1-3）、initializeOnce DCL（P1-4）、isHealthy 单一事实源（P3-2） |
| `BackendRegistryReloaderTest` | 静态 diff | version 去重、unchanged 复用旧 Entry（保连接池）、added/removed/toRetire |
| `RegistryComposerTest` | 静态∪动态合并 | I-2 热重载不误删动态、D-COEXIST-1 共存、sameEffective identity 复用、D-VERSION-1 version 去重 |
| `DynamicBackendStoreTest` | 动态注册 | I-3 冲突检测（动态∩静态/同名异 URL）、幂等（同名同 URL 无回调）、unregister 不存在幂等 |
| `SourceParsingTest` | source 解析 | STATIC 缺省、DYNAMIC 显式 |

### 58.2 handler 包单测

| 测试类 | 目的 | 关键断言 |
|--------|------|----------|
| `ToolsCallRouterTest` | 路由 + 错误翻译 | SYNC/ASYNC/GATEWAY_LOCAL 分派、target 剥离不进 backendArgs（C-CALL-1/2）、resolveTarget unknown_target+available、submitAsync GlobalConcurrencyLimit 显式释放槽（P0-2）、asyncAcceptedResponse 固定 working |
| `GatewayToolHandlersTest` | 4 自有工具 | listTargets 渲染、taskGet 按 status 分支、taskList 过滤、taskCancel 幂等 |
| `DiagnosticRequestTest` | target 解析 | requireTarget（缺失/非 String/空白→INVALID_PARAMS）、stripTarget、容忍 null value（P2-2） |

### 58.3 tool 包单测

| 测试类 | 目的 | 关键断言 |
|--------|------|----------|
| `StaticToolRegistryTest` | 工具注册 | target 注入到 required[0]、inputSchema 深冻结、snapshot 不可变、4 自有工具规格、buildArthasTool |
| `ExposedToolTest` | 工具 record | 深冻结 deepImmutable |

### 58.4 task 包单测

| 测试类 | 目的 | 关键断言 |
|--------|------|----------|
| `GatewayTaskTest` | 任务状态机 | markCompleted（isError=true 原样 G-TG-2）、markFailed、markCancelled、终态不可逆（迟到返 false） |
| `TaskStoreTest` | 存储 + TTL | put/get/remove、TTL 过期（终态+超 ttl；WORKING 永不过期）、cleanExpired、list 触发清理 |
| `TaskStoreGetReadAmplificationTest` | 读放大（P2-3） | get 不触发全表清理（O(1) 定向 remove）、containsRawForTest 验证 |
| `AsyncTaskExecutorTest` | 异步编排 | submit 立即返 working、orchestrate markCompleted/Failed/Cancelled、cancel 中断 |
| `AsyncTaskExecutorShutdownRaceTest` | 关闭竞态（P0-1/2） | 外层拒绝→store.remove+onTerminal+releaseGlobalInflight、槽不泄漏 |
| `GlobalBackpressureTest` | 全局背压（P2-4） | acquireGlobalInflight CAS、超 cap 回滚、动态默认=后端数×5 |

### 58.5 portal 单测（004）

| 测试类 | 目的 | 关键断言 |
|--------|------|----------|
| `BackendDtoTest` | BackendDto | 字段全集、INV-SECRET-1 凭据脱敏（仅 authMode） |
| `BackendsYamlWriterTest` | YAML 写回 | INV-FILE-1 写 version+backends、热重载可解析、不保留注释（R2 已知） |
| `BackendAdminServiceTest` | CRUD 编排 | 静态 POST/PUT/DELETE 经 YamlWriter、动态 POST/PUT 拒绝 INV-DYN-1、DELETE=unregister、name 冲突 400 |
| `AdminCapabilitySwitchTest` | 能力开关 | INV-SWITCH-1/2 独立、默认开、各自关各自不装配 |
| `TaskExportDtoTest` | 导出 DTO | INV-EXP-1 frames 原样、null→List.of() |
| `TaskExportServiceTest` | 导出业务 | store.get→DTO、仅 COMPLETED 可导出、409/404（A-EXP-2） |
| `TaskSummaryDtoTest` | 摘要 DTO（增量） | 7 字段、无 frames（INV-LIST-1）、isError 映射 |
| `TaskListServiceTest` | 列表业务（增量） | status/tool/target 过滤、createdAt 倒序（INV-LIST-3）、分页 total=过滤后（INV-LIST-2）、size clamp 100/<1 取 1 |

### 58.6 orchestration 单测（003）

| 测试类 | 目的 | 关键断言 |
|--------|------|----------|
| `OrchestrationRecordTest` | 记录状态机 | ensuring→ready/reused/failed 转换、withExposed 副作用记录、Error 三元组非空 |
| `K8sExecTest`（如有） | exec 同步化 | exitCode 真实进程退出码（非 onClose）、超时/异常分类 |
| `DynamicBackendStoreTest`（003 共用） | 冲突检测 | I-3 动态∩静态拒绝 |

### 58.7 测试夹具单测

| 测试类 | 目的 |
|--------|------|
| `DemoBusinessAppHttpTest` | 夹具业务 JVM 的 /health + /order |
| `OrderServiceTest` | OrderService.hotMethod 行为 |
| `FakeBackendClientTest` | 测试用 client |

---

## 第 59 章 服务端契约测试（S-*，网关↔Claude Code）

**目录**：`src/test/java/com/arthas/gateway/contract/server/`

### 59.1 InitializeAndToolsListContractTest

- **驱动**：`@SpringBootTest(RANDOM_PORT)` + `McpClientHarness`（官方 SDK client 连 `/mcp`）。
- **断言**：
  - **S-INIT-1**：协议版本回显（`{2024-11-25,...}`）。
  - **S-INIT-2**：`serverInfo.name = arthas-mcp-gateway`、`version = 0.1.0`、capabilities = `仅 tools, listChanged=false`（不声明 prompts/resources/logging/completions）。
  - **S-INIT-3**：`instructions` 为空（MVP 不提供）。
  - **S-TL-1**：`tools/list` 返回 **38** 工具。
  - **S-TL-2**：每 arthas 工具 inputSchema 含 `target` in required。
  - **S-TL-3**：剥离 target 后 schema 逐字等于 baseline（arthas 原始 schema 不被改写，原则二）。
  - **S-TL-4**：不含 `taskSupport`/`execution`（内部字段不泄漏）。
  - **S-TL-5**：`nextCursor=null`（工具集静态，无分页）。

### 59.2 GatewayToolsContractTest

- **驱动**：纯逻辑（无传输，直接调 `GatewayToolHandlers`）。
- **断言**：
  - **G-ASYNC-1**：异步接受响应整形（`{taskId, status:"working", _meta:{toolName,target}}`）。
  - **G-LT-1**：list-targets 单 target（含 state/healthy/protocol）。
  - **G-TG-1**：task-get working（无 result）。
  - **G-TG-2**：task-get completed（result 原样，isError=true 保留）。
  - **G-TG-3**：task-get failed（error{reason,message}）。
  - **G-TL-1**：task-list 可选 status 过滤。
  - **G-TC-1**：task-cancel working→cancelled。
  - **G-TC-2**：task-cancel 终态幂等（返当前状态）。

### 59.3 ListTargetsContractTest

- **驱动**：纯逻辑。
- **断言**：
  - **G-LT-1**（多 target）：list-targets 多 target 含全部。
  - 熔断 OPEN→`healthy=false`（但仍在列表，SC-003 可见但不可诊断）。
  - healthy 派生公式 `state==ACTIVE && breaker!=OPEN`。

### 59.4 ToolsCallRoutingContractIT

- **驱动**：真实 arthas + `McpClientHarness`（failsafe IT）。
- **断言**：
  - **S-CALL**：真实 jvm 诊断含 `{jvmInfo,RUNTIME,resultCount,MACHINE-NAME,SPEC-NAME}`（非桩硬证据）。
  - **S-ERR-2**：target=ghost→INVALID_PARAMS + data.available 含真实在册 target。
  - **S-ERR-3**：未知工具→INVALID_PARAMS/METHOD_NOT_FOUND。

---

## 第 60 章 客户端契约测试（C-*，网关↔arthas）

**目录**：`src/test/java/com/arthas/gateway/contract/client/`

### 60.1 BackendClientContractIT

- **驱动**：真实 arthas 后端 + `McpClientHarness`。
- **断言**：
  - **C-INIT**：握手幂等（已握手 isInitialized=true，二次 initialize 不抛）。
  - **C-CALL**：callTool 路由 + 参数剥离（target 不进 backendArgs）。
  - **C-RESULT**：callTool 原样返回真实 JVM 诊断（content/isError/_meta 不篡改）。

### 60.2 FaultIsolationContractIT（故障隔离四件套）

- **驱动**：真实 arthas 后端 + 真实故障条件。
- **断言**：

| 契约 | 验证 | 真实故障条件 |
|------|------|--------------|
| **C-CB-1** | dead target 连 3 次→OPEN，第 4 次**立即**返 S-ERR-5（`elapsedMs<2000`，不发连接不等 30s） | `http://127.0.0.1:9` 关闭端口（连接拒绝） |
| **C-CB-2** | ognl 非法表达式连 5 次（>阈值 3）仍 CLOSED，business 错误不计熔断 | 真实 arthas 返 isError/McpError |
| **C-LIMIT-1** | 5 个 watch 持槽，第 6 个 tryAcquireSlot 失败→INVALID_PARAMS+`concurrency_limit`+`maxConcurrentTasks:5` | 真实并发任务占槽 |
| **C-ISO-1** | order 占资源时 payment 同步 jvm 即时返回（`elapsedMs<10000`） | 独立连接池/线程 |

---

## 第 61 章 集成测试（`integration/`）

### 61.1 ResultConsistencyIT（SC-005 一致性 A/B）

- **驱动**：真实 arthas + `McpClientHarness` 双连（网关 + 直连）。
- **断言**：
  - `viaGateway.isError() == direct.isError()`（错误标记一致）。
  - 两侧都含 `JVM_DIAGNOSTIC_MARKERS`（真实 JVM 诊断标记，非桩）。
- **设计**：不要求文本逐字相同（jvm 诊断含动态值）；一致性 = 透传不破坏结构。

### 61.2 HotReloadIT（SC-002 热重载）

- **断言**：
  - 新增：写 version 2 → 30s 内 list-targets 出现 order 且可诊断。
  - 校验失败保留旧表（§11 规则 7）：写 version 3 缺 auth → watcher catch → list-targets 仍含 order。
  - 移除：写 version 4 空表 → 30s 内 order 消失，ghost target 返 INVALID_PARAMS + available。

### 61.3 AsyncTaskContractIT / AsyncTaskTimeoutIT

| 测试 | 断言 |
|------|------|
| `AsyncTaskContractIT` | watch 提交立即返 working → 触发业务方法 → arthas 命中 → completed；task-list 含任务；cancel working→cancelled |
| `AsyncTaskTimeoutIT` | 注入 `backend-timeout=3s` + `numberOfExecutions=100`（真实慢 ~5s）→ 真实 TimeoutException → failed + `reason=backend_timeout` |

### 61.4 FailedTargetErrorIT（SC-003 故障隔离）

- **真实故障**：`order.close()` 停 JVM。
- **断言**：`jvm target=order` 30s 内返 INVALID_PARAMS + `reason=backend_unreachable` + `retryAfterMs` + `available`；同时 `jvm target=payment` 仍成功（隔离）。

### 61.5 ArthasMcpBackendIT

- 真实 arthas MCP 后端夹具自身行为（`ArthasMcpBackend.start` 拉起 + 健康检查 + shutdown hook）。

---

## 第 62 章 K8S 编排测试（003）

### 62.1 K8sEnsureContractIT

- **驱动**：真实 k3s + 真实 demo-business pod + `McpClientHarness`。
- **断言**：
  - **K-ENS-1**：ensure 真实 pod → status:ready + mcpUrl + target 进注册表（list-targets 可见，source=DYNAMIC）。
  - **K-ENS-2**：重复 ensure → status:reused、零副作用。
  - **K-ENS-3**：用返回 target 调 watch/jvm → 捕获该 pod JVM 真实诊断。
  - **K-ENS-4/5/6/7**：真实故障条件（无 JVM / 无 shell / 无 exec / loopback）→ 结构化错误，且未注册。
  - **K-ENS-8**：target 命名确定性 `{server}-{pod}`。
  - **K-ENS-9**：动态名 ∩ 静态种子 → name_conflict。
  - **K-ATOMIC-1**：任一子步失败 → 不注册。

### 62.2 K8sListToolsContractIT

- **断言**：k8s.list-pods / list-services 真实返回（含 hasJvm/hasShell 探测、NodePort）。

### 62.3 ArthasProvisionerIT

- **断言**：ArthasProvisioner 各子步（locateJvm/installArthas/startArthas/expose/probeHealthy/register）真实行为 + 故障分类。

### 62.4 K8sExternalGatewaySmokeTest（SC-001 端到端，assumeTrue 跳过）

- **驱动**：对**运行中的 uber-jar 网关**（非 @SpringBootTest 嵌入式）经官方 SDK client 驱动「ensure → watch → 轮询 task-get → 真实诊断」。
- **启用门禁**：`Assumptions.assumeTrue(isGatewayUp())` —— 外置网关 `:8761` 不可达时跳过（CI 无常驻网关）。
- **断言**：ensure ready/reused + target 派生 + watch 完成 + 真实诊断含 hotMethod/OrderService。

---

## 第 63 章 portal 测试（004）

### 63.1 能力开关 IT

| 测试 | 配置 | 断言 |
|------|------|------|
| `AdminCapabilitySwitchIT` | crud=false + export=true | `/admin/backends/*` 404、`/admin/tasks/*/export` 在（task_not_found）、`/admin/tasks` 列表在（200）；INV-SWITCH-1 |
| `AdminExportSwitchIT` | export=false + crud=true | `/admin/tasks` 列表 + `/{id}/export` **同 404**（INV-LIST-4）；`/admin/backends` 在；INV-SWITCH-2 |

### 63.2 CRUD IT

- **`BackendAdminContractIT`**（真实网关 + 真实后端）：
  - A-LIST-1 健康一致 SC-003。
  - A-ADD-1 静态写 YAML 热重载 30s。
  - A-UPD-1/A-DEL-1。
  - INV-DYN-1（动态 POST/PUT 拒绝）。
  - INV-ERR-1（错误体）。
  - INV-SECRET-1（脱敏）。

### 63.3 任务 IT

- **`TaskExportContractIT`**：真实 watch→completed→export；A-EXP-1 frames 与 task-get 一致 INV-EXP-1；A-EXP-2 → 409/404；Content-Disposition attachment。
- **`TaskListContractIT`**（增量）：真实 Spring + TaskStore + JDK HttpClient；A-LIST-TASKS-1 倒序/total、A-LIST-TASKS-2 过滤组合、分页元数据、空结果 200。

### 63.4 前端 vitest

| 测试文件 | 断言 |
|----------|------|
| `App.test.ts` | 根路由加载 App、导航 |
| `api/adminClient.test.ts` | fetch 封装 + 错误传播 |
| `views/BackendListView.test.ts` | 列表渲染 + 健康徽标、表单提交触发 create、动态禁用、错误 inline |
| `components/BackendForm.test.ts` | 表单字段 + 动态警告 |
| `views/TaskExportView.test.ts` | 列表区 + 点项填 taskId + status 过滤 + 分页 + 空态/错误态 + 单查询 + 导出 |

---

## 第 64 章 架构测试（ArchUnit）

### 64.1 PackageBoundaryTest

**文件**：`src/test/java/com/arthas/gateway/architecture/PackageBoundaryTest.java`

3 条否定式规则（静态字节码扫描，CI 可跑、零 K8S 依赖）：

1. **诊断核心 → orchestration**：禁止（gateway-core 零 K8S 编排依赖）。
2. **诊断核心 → fabric8/kubernetes**：禁止（K8S API 隔离）。
3. **诊断核心 → admin**：禁止（INV-ISOL-1 管理面隔离）。

诊断核心白名单：`backend.. / handler.. / tool.. / task.. / auth.. / obs..`。`config`（组合根）+ `orchestration` + `admin` 不受此限。

---

## 第 65 章 测试覆盖矩阵（能力 → 测试）

| 能力 | 单测 | 契约 IT | 集成 IT | 架构 |
|------|------|---------|---------|------|
| target 路由 | ToolsCallRouterTest | ToolsCallRoutingContractIT (S-CALL) | — | — |
| 工具暴露（38） | StaticToolRegistryTest | InitializeAndToolsListContractTest (S-TL) | — | — |
| initialize 握手 | BackendEntryTest (DCL) | InitializeAndToolsListContractTest (S-INIT) | BackendClientContractIT (C-INIT) | — |
| 结果原样透传 | — | ToolsCallRoutingContractIT | ResultConsistencyIT (SC-005) | — |
| 熔断 | CircuitBreakerTest | FaultIsolationContractIT (C-CB-1/2) | FailedTargetErrorIT (SC-003) | — |
| 并发限流 | BackendEntryTest | FaultIsolationContractIT (C-LIMIT-1) | — | — |
| 全局背压 | GlobalBackpressureTest | — | AsyncTaskContractIT | — |
| 关闭竞态 | AsyncTaskExecutorShutdownRaceTest | — | — | — |
| 异步任务 | AsyncTaskExecutorTest/GatewayTaskTest | GatewayToolsContractTest (G-TG) | AsyncTaskContractIT/TimeoutIT | — |
| TTL 清理 | TaskStoreTest/ReadAmplificationTest | — | — | — |
| 热重载 | BackendRegistryReloaderTest | — | HotReloadIT (SC-002) | — |
| 动态注册 | DynamicBackendStoreTest/RegistryComposerTest | — | — | — |
| STATELESS 拒异步 | BackendEntryTest | — | — | — |
| 包边界 | — | — | — | PackageBoundaryTest |
| K8S list-pods | — | K8sListToolsContractIT | — | — |
| K8S ensure | OrchestrationRecordTest | K8sEnsureContractIT (K-ENS) | ArthasProvisionerIT/K8sExternalGatewaySmokeTest (SC-001) | — |
| portal CRUD | BackendAdminServiceTest | — | BackendAdminContractIT | — |
| portal 导出 | TaskExportServiceTest | — | TaskExportContractIT | — |
| portal 列表 | TaskListServiceTest | — | TaskListContractIT | — |
| 能力开关 | AdminCapabilitySwitchTest | — | AdminCapabilitySwitchIT/AdminExportSwitchIT | — |

---

## 第 66 章 真实测试夹具

> 详见 [test-fixtures.md](../test-fixtures.md)。核心夹具：

| 夹具类 | 作用 | 编译 |
|--------|------|------|
| `ArthasMcpBackend` | 拉起一个真实 arthas MCP 后端 | `mvn test-compile` → `target/test-classes` |
| `DemoBusinessApp` | 真实业务 JVM（OrderService.hotMethod 自驱动） | 同上 |
| `OrderService`/`OrderResult` | 业务类 + 返回值 | 同上 |
| `McpClientHarness` | 官方 SDK client 封装（契约 IT 驱动） | 同上 |
| `FakeBackendClient` | 测试用 client | 同上 |
| `SmokeDemoLauncher` | 双后端一键拉起（冒烟） | 手动 `javac` → `target/smoke-classes` |
| `SmokeMcpClient`/`SmokeWatchAsync` | 冒烟 client | 同上 |

K8S 场景：3 业务类（DemoBusinessApp/OrderService/OrderResult）ship 到 debian → docker 镜像 → k3s → demo-business pod。

---

> **下一步**：Part 9 设计决策全（research.md 决策逐条 + brainstorming 设计要点）。
