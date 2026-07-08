# Part 1 · 总览：背景、设计哲学、能力全景、技术栈、架构

> 本部分回答：网关**是什么、为什么这样设计、由哪些技术构成、整体架构如何分层组织**。后续 Part 2–5 逐子系统深入代码实现。

---

## 第 1 章 项目背景与价值

### 1.1 问题：arthas MCP 化后的多端点困境

[arthas](https://arthas.aliuncode.com/) 是 Java 生态最强的在线诊断工具（`watch`/`trace`/`stack`/`jvm`/`dashboard` 等）。arthas 4.3.0 起**原生支持 MCP**（Model Context Protocol）—— 每个 arthas 实例可暴露一个 MCP 端点，供 Claude Code 等 AI 客户端调用。

但 MCP 化带来新的困境：

1. **多端点管理**：每个目标 JVM 各起一个 arthas MCP 端点，客户端要管理 N 个 server（N = 目标 JVM 数）。Claude Code 的 `--mcp-config` 配置膨胀，运维心智负担重。
2. **无法跨目标编排**：客户端对每个目标单独建连，无法在一个会话里「列目标 → 选目标 → 诊断」编排（需人工切换 server）。
3. **长任务阻塞会话**：arthas 的 5 个长耗时工具（`watch`/`trace`/`stack`/`tt`/`monitor`）是阻塞的——一次 `watch` 可能跑数分钟，期间整个 MCP 会话被挂住，Claude Code 无法响应其他请求。
4. **故障扩散**：单后端 arthas 挂掉，若客户端直连，该连接上的所有请求超时；缺乏统一的健康监控与故障隔离。
5. **配置静态**：增删目标 JVM 需改客户端配置 + 重启。

### 1.2 解决：网关作为 MCP 反向代理 + 编排层

arthas MCP 网关（`arthas-mcp-gateway`）作为 **MCP 反向代理 + 编排层**，把 N 个目标 JVM 的 arthas 聚合为**一个** MCP server，对 Claude Code 只暴露一个端点（`http://<gateway>:8761/mcp`）。

核心机制：

- **统一入口**：N 个后端 arthas 聚合为 1 个 MCP server，38 个工具经一个端点暴露。
- **`target` 路由**：每个 arthas 工具注入必填 `target` 参数（逻辑名，如 `order-service`），网关据此转发到对应后端，结果原样回传（不篡改）。
- **异步长任务**：5 个长耗时工具后台化（虚拟线程），立即返回 `taskId`，经 `task-get`/`task-list`/`task-cancel` 跟踪——会话不阻塞。
- **故障隔离**：单后端故障经 per-target 熔断器 + 并发限流 + 独立连接池隔离，不波及其他目标，30s 内返回明确错误（不长时间挂起）。
- **配置热重载**：改 `config/backends.yaml` 免重启增删目标（WatchService 监听 + 原子替换注册表）。
- **K8S 编排**（003）：对指定 K8S pod 一键拉起 arthas MCP（上传 arthas + 启动 + NodePort 暴露 + 动态纳管 + 健康检查），诊断结果明确来自该 pod JVM。
- **Web 管理面**（004）：浏览器 portal 做 CRUD + 任务列表查询 + 结果导出。

### 1.3 不做什么（边界）

- **不依赖 arthas 工程源码**（用户约束 2026-06-20）：`arthas-boot.jar` 作为**静态工具文件**置于 `tools/`，经 `java -jar` 使用；不入 Maven 依赖、不构建 reference 源码。详见 [memory arthas-no-dependency]。
- **MVP 无入站认证**：受控内网假设，入站 MCP 请求 Noop 放行（`NoopGatewayAuthenticator`）；出站认证（连后端 arthas）支持 BEARER/BASIC（`BackendAuthCustomizer` 注入 `Authorization` 头）。
- **MVP 仅 Streamable HTTP 传输**：stdio 与 HTTP 互斥（Spring AI starter 限制），stdio 延后。详见 [memory sdk2-vs-spec-divergences]。

---

## 第 2 章 设计哲学：宪法 8 原则

> 项目最高权威文档是 `.specify/memory/constitution.md`（v1.2.0，2026-06-19）。8 条核心原则凌驾于临时实践之上。本节逐条解读 + 给出对应的代码实现锚点。

### 原则一：MCP 规范符合性（不可妥协）

> 网关同时在**两个 MCP 接口**运作——上游客户端（连 N 个 arthas MCP 后端）+ 下游服务端（被 Claude Code 消费）。两接口都必须符合 MCP 规范。

要求：
- 线上传输 = JSON-RPC 2.0。
- 生命周期正确实现：`initialize` → `initialized`、能力协商、有序关闭。
- 协议原语（tools/resources/prompts）严格按规范，无自定义原语。
- 锁定具体 MCP 规范版本（本项目锁定 `2024-11-05`/`2025-11-25`），偏离须单独记录。

**代码实现**：
- 下游服务端经 Spring AI 2.0.0 starter + MCP SDK 2.0.0 装配，传输 = Streamable HTTP（JSON-RPC 2.0 over HTTP）。装配见 `src/main/java/com/arthas/gateway/config/GatewayMcpServerConfig.java:44`。
- 协议版本回显 + 能力协商由 `InitializeAndToolsListContractTest`（S-INIT-1/2/3）断言——锁定「仅 tools、listChanged=false、不声明 prompts/resources」。
- 上游客户端经官方 SDK `McpSyncClient` + `HttpClientStreamableHttpTransport`（`HttpBackendClient.java:53-63`），与 arthas 后端标准 MCP 互通。

### 原则二：透明无损的聚合

> 聚合多个 arthas 后端到一个端点之后，**不改变诊断语义**。

要求：
- 工具/resource/prompt 定义来源于**对 arthas 源码如实摘抄（静态拷贝）**，不从各后端动态发现；忠实反映 arthas 规范能力，不做无依据改写。
- 可经命名空间/target 参数消除歧义。
- **调用结果必须原样透传**——不得静默改写、摘要、截断、丢弃。
- 工具→后端映射确定性，且能经网关自身接口发现（`list-targets`）。

**代码实现**：
- 31 arthas 工具 schema 来自 `src/main/resources/arthas-tools.json`（摘抄自 arthas 4.3.0 `@Tool` 注解），`StaticToolRegistry.fromClasspath` 加载并注入 `target` 参数（`StaticToolRegistry.java:84-112`）。
- 结果原样透传：`HttpBackendClient.callTool` 直接返回 `McpSyncClient.callTool` 的 `CallToolResult`（content/isError/_meta 不动），路由器与 task 不篡改（`HttpBackendClient.java:74-76`）。
- 业务错误（`isError=true`）也原样透传，不吞为成功（`BackendEntry.invoke` 对 McpError 走 `recordSuccess` 后原样抛，`BackendEntry.java:156-159`）。
- 一致性经 `ResultConsistencyIT`（SC-005 A/B 对比）断言：网关 vs 直连的 `isError` 一致 + 都含真实 JVM 诊断标记。

### 原则三：连接生命周期与局部故障韧性

> 每个后端都是受管理的一等对象，不是临时 socket。某后端故障**优雅降级**（其工具变不可用），不影响其他后端、不崩溃网关进程；跨后端并发调用互不阻塞。

**代码实现**：
- 每后端独立三件套：`BackendEntry(config, client, breaker, taskSlots)`（`BackendEntryFactory.java:37-41`）——独立连接池 + 独立 `McpSyncClient` 会话 + 独立 SSE 解析 + 独立熔断器 + 独立信号量。
- 显式生命周期：注册（`BackendRegistryBootstrap` 启动期装配）→ 能力发现（首次路由时 `initializeOnce` DCL 握手）→ 健康监控（`BackendRegistryHealthIndicator` + per-target 探测）→ 带退避重连（`CircuitBreaker` OPEN→HALF_OPEN→CLOSED）→ 干净下线（`markRetired` + `retirementGrace=11min` 后 `close`）。
- 故障隔离：熔断 OPEN 立即返错误（不等 30s 超时，`C-CB-1`）；独立连接池/线程使慢后端不拖慢其他（`C-ISO-1`）。

### 原则四：双侧契约优先的测试

> MCP 是作用于两个集成界面上的契约，用测试保障。双侧（网关↔arthas、网关↔Claude Code）都要有契约测试，且**先于实现编写**（TDD）。

**代码实现**：
- 服务端契约（S-*）：`src/test/java/com/arthas/gateway/contract/server/`——`InitializeAndToolsListContractTest`（S-INIT/S-TL）、`GatewayToolsContractTest`（G-*）、`ToolsCallRoutingContractIT`（S-CALL/S-ERR）。
- 客户端契约（C-*）：`contract/client/`——`BackendClientContractIT`（C-INIT/C-CALL）、`FaultIsolationContractIT`（C-CB-1/2、C-LIMIT-1、C-ISO-1）。
- 一致性 + 热重载 + 异步 + 故障 IT：`src/test/java/com/arthas/gateway/integration/`。
- ArchUnit 包边界：`architecture/PackageBoundaryTest.java`——gateway-core 零 K8S 依赖字节码守护。

### 原则五：可观测性与可诊断性

> 网关本身必须可被诊断。结构化日志、调用可追溯（后端标识/target/工具/结果）、错误显式传播（不吞为静默成功）、暴露状态（后端清单/健康）。

**代码实现**：
- 结构化日志：`ToolsCallRouter`/`BackendEntry`/`AsyncTaskExecutor` 关键节点 INFO/WARN 日志，含 `tool`/`target`/`taskId`/`elapsedMs`。
- 调用可追溯：每个 `tools/call` 经路由器记 `toolName`/`target`；异步返 `_meta:{toolName,target}`；任务 `task-get` 返完整元信息。
- 错误显式传播：错误转结构化 `McpError(INVALID_PARAMS, data{target,reason,available,retryAfterMs,...})`（`ToolsCallRouter.java:167-225`），不静默 200。
- 状态暴露：`/actuator/health` details 含 `backendRegistry.backends[name]={state,healthy,protocol,breaker}` + `summary`（`BackendRegistryHealthIndicator.java:40-67`），运维无需读源码。

### 原则六：Java 作为主力开发语言（不可妥协）

> 核心代码必须 Java（LTS）实现；非 Java（构建脚本/配置/前端）仅作辅助，不承载核心逻辑。引入非 Java 运行时须 plan 论证。

**代码实现**：
- 全部核心（路由/熔断/任务/K8S 编排/portal 后端）Java 21。
- 非 Java 辅助：
  - `smoke/*.sh`、`test-env/k8s/*.sh`（bash 启停/搭建脚本，不承载核心逻辑）。
  - `web/`（Vue 3 前端）—— **展示层**（fetch + render + download），核心 CRUD/导出/热重载逻辑在 Java `/admin` 后端，由 ArchUnit `INV-WEB-2` 守护。
  - `tools/arthas-boot.jar`（静态工具文件，非依赖）。
- node/vite 工具链经 `frontend-maven-plugin` 集成进 Maven 构建，论证见 `plan.md` Complexity Tracking。

### 原则七：测试驱动开发（不可妥协）

> 所有功能代码 TDD：先写失败测试（红），再实现至通过（绿），再重构。测试先于实现；实现前须观察到测试失败。

**代码实现**：
- spec-kit SDD 流程：`/speckit-specify` → `/speckit-plan` → `/speckit-tasks`（测试任务先于实现任务）→ `/speckit-implement`。
- 真实性硬约束（CLAUDE.md）：每次测试至少启动一个真实 arthas MCP + 一个真实业务服务；禁桩（WireMock/Mock 模拟成功响应）；故障类用真实故障条件（停后端/错 token/真实 sleep/真实并发越界）。

### 原则八：Arthas MCP 实现的先决研究

> 功能开发前必须研读上游 arthas MCP 实现（工具/资源/传输/报文/能力协商/运行行为）。研究作为 plan Phase 0 产出，汇入 research.md。

**代码实现**：
- 001/003 各有 `research.md`（001 R1–R12、003 R1–R8）记录先决研究：arthas 工具清单（31）、传输方式（http console `/mcp`）、报文格式、`--target-ip` 绑定行为（003 R4 经源码证据链 + A/B 实证）、arthas 4.3.0 MCP 端点无 `/mcp` 后缀（T009 实测）等。
- reference/arthas-docs/ 存放上游文档摘抄（问题定位反向索引、MCP 能力清单、后端接入契约）。

### 技术与传输约束（宪法附录）

| 项 | 约束 | 实现 |
|----|------|------|
| 语言 | Java LTS（≥17，锁 21） | `pom.xml` enforcer `[21,22)` + `maven.compiler.release=21` |
| 构建 | Maven，可复现锁定 | `./mvnw` wrapper + enforcer `[3.9.0,)` |
| MCP 传输 | stdio + Streamable HTTP，配置驱动 | MVP 仅 HTTP（stdio 互斥延后）；`spring.ai.mcp.server.protocol=STREAMABLE` |
| 并发 | 异步/非阻塞多路复用 | 虚拟线程 `Executors.newVirtualThreadPerTaskExecutor()` |
| 后端注册表 | 配置声明，不硬编码 | `config/backends.yaml` + WatchService 热重载 |
| 协议核心 | 优先官方 SDK | MCP Java SDK 2.0.0（`io.modelcontextprotocol.sdk`） |

---

## 第 3 章 能力全景：38 工具 + 4 特性

### 3.1 工具分类（`tools/list` 返回 38）

| 类别 | 数量 | 代表工具 | RoutingMode | TaskSupport | 实现入口 |
|------|------|----------|-------------|-------------|----------|
| arthas 即时诊断 | 25 | `jvm`/`thread`/`sc`/`jad`/`ognl`/`sysprop`/... | `SYNC_DIRECT` | `FORBIDDEN` | `BackendEntry.execute` 同步转发 |
| arthas 流聚合 | 1 | `dashboard` | `STREAM_AGGREGATE` | `FORBIDDEN` | 同步（SDK 聚合 SSE 多帧） |
| arthas 长任务 | 5 | `watch`/`trace`/`stack`/`tt`/`monitor` | `ASYNC_TASK` | `OPTIONAL` | `BackendEntry.admit` + `AsyncTaskExecutor.submit` |
| 网关自有 | 4 | `arthas-gateway.list-targets`/`task-get`/`task-list`/`task-cancel` | `GATEWAY_LOCAL` | null | `GatewayToolHandlers.handle` |
| K8S 编排（003） | 3 | `k8s.list-pods`/`k8s.list-services`/`k8s.ensure-arthas-mcp` | 自带闭包 | — | `K8sToolHandlers.handle`（不经 ToolsCallRouter） |
| **合计** | **38** | | | | |

完整 38 工具名清单见 [part6-config-appendix.md §工具清单](./part6-config-appendix.md)。

### 3.2 4 特性矩阵

| 特性 | 优先级 | 核心价值 | 关键产出 |
|------|--------|----------|----------|
| **001 诊断聚合** | P1 | 统一入口 + target 路由 + 异步任务 + 热重载 + 故障隔离 | 35 工具（31 arthas + 4 自有）、双侧契约、SC-001~005 |
| **002 韧性整改** | —（001 的代码评审整改） | 关闭竞态/槽泄漏/熔断线程安全/异步驱动熔断/原子握手/退役宽限/null 参数/读放大/全局背压/凭据脱敏/单一事实源/JSON 单例/配置校验/死代码 | 15 项发现修复，FR-016 全局回归不破 |
| **003 K8S 编排** | P1（003 的 MVP） | 远端 K8S pod 一键启 arthas + NodePort 暴露 + 动态纳管 | 3 工具、ensure 原子幂等、SC-001 端到端 |
| **004 portal 管理面** | P3（003 的 portal 子集，v2 Web） | 浏览器 CRUD + 任务列表 + 结果导出 | `/admin` API + Vue SPA、4 能力开关 |

### 3.3 用户视角的能力清单

**诊断（经 Claude Code 调网关 MCP 工具）**：
- 看任意 target JVM：`jvm target=order-service` → 返 MACHINE-NAME/VM-VERSION/线程/堆/GC。
- 观察方法调用：`watch target=... classPattern=... methodPattern=... numberOfExecutions=1` → 异步返 taskId，`task-get` 查结果（命中方法的 accessPoint/cost/value）。
- 追踪调用链：`trace`、查看栈：`stack`、时间隧道：`tt`、监控：`monitor`（皆异步）。
- 反编译：`jad`、查类：`sc`、改运行时：`ognl`/`sysprop`/`vmoption` 等（同步即时）。

**管理（经 portal Web 或 `/admin` API）**：
- 浏览后端：`/admin/backends`（含健康/熔断/脱敏）。
- 增删改静态后端：写 `backends.yaml` 热重载（≤30s 纳管）。
- 删动态后端：`DynamicBackendStore.unregister` 即时移除。
- 浏览任务列表：`/admin/tasks?status=&tool=&target=&page=&size=`（摘要无 frames + 过滤 + 分页 + 倒序）。
- 导出任务结果：`/admin/tasks/{id}/export`（原样 JSON 下载）。

**编排（经 Claude Code 调 K8S 工具）**：
- 列 pod：`k8s.list-pods namespace=default` → pod 清单含 hasJvm/hasShell。
- 一键纳管：`k8s.ensure-arthas-mcp server=debian pod=demo-business` → 上传 arthas + 启动 + NodePort 暴露 + 注册 → `{target, status:ready, mcpUrl}`。
- 随后用 `target=debian-demo-business` 调诊断工具，结果来自该 pod JVM。

---

## 第 4 章 技术栈（pom.xml 逐依赖）

> `pom.xml` 继承 `spring-boot-starter-parent:4.1.0`。下表逐依赖说明「是什么、版本、为什么选、用在哪儿」。

### 4.1 核心 Spring 栈

| 依赖 | 版本 | 用途 | 选型理由 |
|------|------|------|----------|
| `spring-boot-starter-parent` | 4.1.0 | 父 POM：生命周期 + 配置外化 + Actuator + 依赖管理（Jackson 3 / JUnit5 / AssertJ） | Spring AI 2.0.0 MCP server starter 硬依赖 `spring-boot-starter-web:4.1.0`，故锁定 4.1.0 |
| `spring-ai-starter-mcp-server-webmvc` | 2.0.0（spring-ai-bom） | **服务端**：自动装配 WebMVC Streamable HTTP 传输 + MCP server 生命周期 + 工具注册为 Spring bean | 官方 Spring AI MCP server starter；传递带入 `mcp-spring-webmvc`（含 mcp-core）+ `spring-ai-mcp` + `spring-boot-starter-web` |
| `spring-ai-starter-mcp-client` | 2.0.0 | **客户端**：连 arthas 后端 | 传递带入 `spring-ai-mcp` → `io.modelcontextprotocol.sdk:mcp`（含 `HttpClientStreamableHttpTransport`/`McpClient`/`McpSyncClient`）。**注意**：starter 的自动装配按静态属性创建单例客户端，不符合「动态多后端 + per-target 熔断/限流/独立连接池」需求，`BackendClient` 排除其自动装配、用其传输类手搓 per-target 客户端 |
| `spring-boot-starter-actuator` | 4.1.0 | 可观测性（健康检查 / metrics） | 宪法原则五；Actuator 端点不涉 MCP 调用 |

### 4.2 MCP 协议核心

| 依赖 | 版本 | 用途 | 备注 |
|------|------|------|------|
| `io.modelcontextprotocol.sdk:mcp` | 2.0.0（mcp-bom） | 官方 MCP Java SDK，协议核心（JSON-RPC 帧、initialize、tools、capabilities） | 宪法「优先官方 SDK，不手写 JSON-RPC 帧」；经 `mcp-bom` 锁版本 |
| `io.modelcontextprotocol.sdk:mcp-test` | 2.0.0 | 官方 MCP 测试脚手架（契约/一致性辅助） | test scope |

### 4.3 K8S 编排（003）

| 依赖 | 版本 | 用途 | 选型理由 |
|------|------|------|----------|
| `io.fabric8:kubernetes-client` | 7.6.1 | K8S 客户端：list pods/services、exec 进 pod、create NodePort Service | fabric8 fluent Java API，不 shell-out kubectl（宪法原则六「K8S 仅辅助、核心逻辑 Java」）。显式锁 7.6.1（2026-03 稳定版，Java 21 兼容，不进 BOM）。**仅 orchestration 包依赖**，gateway-core 零 K8S 依赖（ArchUnit 守护） |
| `org.apache.commons:commons-compress` | 1.28.0 | fabric8 `.file().upload()` 运行时依赖 | fabric8 `PodUpload` 用其 `TarArchiveOutputStream` 打 tar 流，fabric8 声明为 optional（不传递）→ uber jar 缺失 → `NoClassDefFoundError`。显式声明使其进 `BOOT-INF/lib`（memory `fabric8-upload-needs-commons-compress`） |

### 4.4 配置与构建

| 依赖/插件 | 版本 | 用途 |
|-----------|------|------|
| `spring-boot-configuration-processor` | 4.1.0 | `@ConfigurationProperties` 元数据（IDE 提示） |
| `maven-compiler-plugin` | — | 锁 `release=21` + 保留参数名 |
| `maven-enforcer-plugin` | — | 强制 Java `[21,22)` + Maven `[3.9.0,)` |
| `maven-surefire-plugin` | 3.5.6 | 单测 `*Test.java`（纯逻辑/状态机），排除 `*IT.java` |
| `maven-failsafe-plugin` | — | 集成测试 `*IT.java` 绑 `integration-test`/`verify` 阶段（真实 arthas + 真实业务） |
| `spring-boot-maven-plugin` | 4.1.0 | 打可执行 fat jar（repackage） |
| `com.github.eirslett:frontend-maven-plugin` | 1.15.1 | 004 前端构建：在 Maven 内跑 `npm install + build`，下载 node v22.22.0（CI 无需预装），产物落 `target/classes/static/` 内嵌 JAR |
| `com.tngtech.archunit:archunit-junit5` | 1.3.0 | 包边界守护（gateway-core 零 K8S 依赖） |

### 4.5 前端（004，`web/package.json`）

| 依赖 | 版本 | 用途 |
|------|------|------|
| `vue` | ^3.5.13 | SPA 框架 |
| `vue-router` | ^4.5.0 | history 模式路由（`/backends`、`/tasks`） |
| `vite` | ^5.4.11 | 构建 + dev HMR（proxy `/admin`→`:8761`） |
| `vitest` | ^2.1.8 | 组件测试（jsdom） |
| `@vue/test-utils` | ^2.4.6 | 组件 mount/交互 |
| `vue-tsc` | ^2.1.10 | TS 类型检查（build 前 `--noEmit`） |
| `typescript` | ^5.x | 类型 |

### 4.6 静态工具文件（非依赖）

- `tools/arthas-boot.jar`：arthas 4.3.0 fat jar，K8S `ensure` 时经 fabric8 `.file().upload()` 上传进 pod（`ArthasProvisioner.installArthas`），经 `java -jar` 使用。**不入 pom 依赖**（宪法原则：本工程不依赖 arthas）。

---

## 第 5 章 架构总览

### 5.1 分层架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Claude Code / MCP SDK client                      │
└───────────────────────────┬─────────────────────────────────────────┘
                            │ MCP / Streamable HTTP（JSON-RPC 2.0）
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│  网关（单 JVM 进程，单 fat jar，:8761/mcp）                          │
│ ┌─────────────────────────────────────────────────────────────────┐ │
│ │ MCP 服务端（spring-ai-starter-mcp-server-webmvc 自动装配）       │ │
│ │  · 38 工具 SyncToolSpecification（35 经 router + 3 K8S 自带闭包）│ │
│ │  · capabilities 锁定（仅 tools, listChanged=false）             │ │
│ └──────────────────────┬──────────────────────┬────────────────────┘ │
│                        │                      │                      │
│         ┌──────────────▼──────────┐  ┌────────▼─────────┐            │
│         │ ToolsCallRouter（诊断）  │  │ K8sToolHandlers   │            │
│         │  route → 解析 target     │  │ （编排，绕过 router│            │
│         │  → SYNC/ASYNC/GATEWAY   │  │  gateway-core 零  │            │
│         └──┬──────────────┬───────┘  │  K8S 依赖）        │            │
│            │              │          └────────┬──────────┘            │
│   ┌────────▼────┐  ┌──────▼──────────┐       │                      │
│   │ BackendEntry│  │ AsyncTaskExecutor│  ┌────▼────────────────────┐ │
│   │ （统一拦截） │  │ + TaskStore      │  │ orchestration 包         │ │
│   │ breaker+slot│  │ + GatewayTask    │  │ K8sClientFactory/        │ │
│   │ + initialize│  │ （虚拟线程）      │  │ PodExplorer/Provisioner/ │ │
│   └────┬────────┘  └──────────────────┘  │ NodePortExposer/...      │ │
│        │                                └────────────┬──────────────┘ │
│   ┌────▼────────────────────┐                        │                │
│   │ BackendRegistry          │  ┌─────────────────────▼──────────┐   │
│   │ （AtomicReference 快照）  │  │ DynamicBackendStore（动态 target）│   │
│   │ + RegistryHolder         │◄─┤ + RegistryComposer（静态∪动态）   │   │
│   │ + BackendConfigWatcher   │  └──────────────────────────────────┘   │
│   │   （WatchService 热重载） │                        │                │
│   └────┬─────────────────────┘                        │                │
│        │ BackendClient（每后端独立）                   │                │
│        ▼                                            │                │
│   ┌──────────────────────────────────────────────────┘                │
│   │                                                                    │
│   │  ┌──────────────────────────────────────────────────────────┐     │
│   │  │ admin 包（004 portal 后端，/admin REST）                  │     │
│   │  │  BackendAdminController / TaskExportController / SpaConfig│     │
│   │  └──────────────────────────────────────────────────────────┘     │
│   │                                                                    │
│   │  ┌──────────────────────────────────────────────────────────┐     │
│   │  │ web/（004 前端 SPA，内嵌 static/，同源 fetch /admin）      │     │
│   │  └──────────────────────────────────────────────────────────┘     │
└───┼────────────────────────────────────────────────────────────────────┘
    │
    ▼  MCP / HTTP（每后端独立 McpSyncClient + 连接池）
┌──────────────────────┐  ┌──────────────────────┐  ┌──────────────────────┐
│ arthas MCP 后端 1    │  │ arthas MCP 后端 2    │  │ K8S pod 内 arthas    │
│ （order-service JVM）│  │ （payment JVM）       │  │ （动态纳管，NodePort）│
└──────────────────────┘  └──────────────────────┘  └──────────────────────┘
```

### 5.2 包结构（`src/main/java/com/arthas/gateway/`）

| 包 | 职责 | 特性 | K8S 依赖 |
|----|------|------|----------|
| `config/` | 装配（组合根）：Spring `@Configuration` + `@ConfigurationProperties` | 全部 | 装配 orchestration bean（组合根允许） |
| `backend/` | 后端管理：`BackendConfig`/`Entry`/`Registry`/`Holder`/`Watcher`/`Reloader`/`Composer`/`DynamicBackendStore`/`CircuitBreaker`/`HttpBackendClient`/`BackendConfigLoader` + 域异常 | 001/002/003 | 零（gateway-core） |
| `handler/` | MCP tools/call 路由：`ToolsCallRouter`/`GatewayToolHandlers`/`DiagnosticRequest`/`McpJson`/`McpErrorCodes` | 001 | 零 |
| `tool/` | 工具元数据：`StaticToolRegistry`/`ExposedTool`/`RoutingMode`/`TaskSupport` | 001 | 零 |
| `task/` | 异步任务：`AsyncTaskExecutor`/`TaskStore`/`GatewayTask`/`TaskState`/`TaskError`/`GlobalConcurrencyLimitException` | 001/002 | 零 |
| `auth/` | 认证：`BackendAuthCustomizer`（出站头）/`GatewayAuthenticator`/`NoopGatewayAuthenticator`（入站） | 001 | 零 |
| `obs/` | 可观测：`BackendRegistryHealthIndicator` | 001 | 零 |
| `orchestration/` | K8S 编排：`K8sClientFactory`/`PodExplorer`/`ArthasProvisioner`/`NodePortExposer`/`OrchestrationRecord`/`K8sToolHandlers`/... | 003 | **依赖 fabric8** |
| `admin/` | portal 后端：`backend/`（CRUD）+ `task/`（导出/列表）+ `SpaConfig` + `AdminExceptionHandler` | 004 | 零 |

**包边界守护**（`PackageBoundaryTest.java`）：诊断核心（backend/handler/tool/task/auth/obs）→ 不得依赖 orchestration / admin / fabric8 / io.kubernetes。`config`（组合根）+ `orchestration` + `admin` 不受此限。

### 5.3 数据流总图（一次 `tools/call` 的旅程）

```
[Claude Code] tools/call watch {target:order-service, classPattern:..., methodPattern:...}
   │
   ▼ Spring AI starter（JSON-RPC 解析 + tools/list 校验工具名命中）
[GatewayMcpServerConfig.mcpToolSpecifications handler]
   │
   ▼ ToolsCallRouter.route(exposed, request)              ── handler/ToolsCallRouter.java:82
   │   ├─ routingMode=ASYNC_TASK（watch）
   │   ├─ DiagnosticRequest.parse → target=order-service, backendArgs={classPattern, methodPattern}（target 已剥离）
   │   └─ submitAsync(tool, dr)
   │       ├─ resolveTarget(dr) → BackendEntry（RegistryHolder.current().get("order-service")）
   │       ├─ entry.admit("watch")                          ── BackendEntry.java:120
   │       │   ├─ STATELESS 校验（order-service=STREAMABLE，通过）
   │       │   └─ admitCore: breaker.allowRequest + taskSlots.tryAcquire（取 1 槽）
   │       └─ asyncExecutor.submit("watch", "order-service", () -> entry.invoke(...), entry::releaseSlot)
   │           ├─ acquireGlobalInflight（全局背压）
   │           ├─ store.put(new GatewayTask(taskId=t-xxxxxx, WORKING))
   │           └─ pool.submit(orchestrate)（虚拟线程）
   └─ return asyncAcceptedResponse(task)
       → CallToolResult({taskId, status:"working", _meta:{toolName, target}}) 立即返回 Claude Code

   ── 后台虚拟线程 orchestrate ──                              ── AsyncTaskExecutor.java:172
   worker = pool.submit(() -> entry.invoke("watch", backendArgs))
       ├─ initializeOnce（DCL，首次握手 order-service arthas MCP）
       ├─ client.callTool("watch", backendArgs)             ── HttpBackendClient.java:74
       │   → McpSyncClient.callTool → POST order-service arthas /mcp（Authorization 头注入）
       │   ◄─ arthas 阻塞等 hotMethod 命中 → 返 CallToolResult
       └─ invoke 故障分类（成功→recordSuccess，基础设施→recordFailure，业务错误→recordSuccess 原样抛）
   worker.get(callTimeout=11min)
       └─ 正常 → task.markCompleted(CallToolResult)（isError=true 也原样）
   finally: onTerminal.run()（releaseSlot）+ releaseGlobalInflight()

   ── Claude Code 后续查询/管理 ──
   arthas-gateway.task-get(t-xxxxxx) → GatewayToolHandlers.taskGet → store.get → 渲染 completed+result
   arthas-gateway.task-list          → store.list → 概要列表
   arthas-gateway.task-cancel(t-...) → executor.cancel → markCancelled + 中断 supervisor
```

故障分支：
- `target=ghost`（不在册）→ `resolveTarget` 抛 `McpError(INVALID_PARAMS, data.available=[在册目标])`。
- 熔断 OPEN → `admitCore` 抛 `CircuitOpenException(retryAfterMs)` → 路由器翻译 `backend_unreachable + retryAfterMs`。
- 并发越界 → `ConcurrencyLimitException(maxConcurrentTasks)` → `concurrency_limit`。
- STATELESS 异步 → `StatelessAsyncException` → `stateless_unsupported_async`。
- 后端不可达 → `invoke` 抛 `BackendUnreachableException` → `recordFailure` + `backend_unreachable`。

### 5.4 关键设计原则汇总（代码体现）

1. **target 注入与剥离**：`StaticToolRegistry` 注入 target 到 31 arthas 工具的 `required[0]`；`DiagnosticRequest.stripTarget` 剥离，**target 永不进 backendArgs**（避免后端 INVALID_PARAMS）。
2. **每后端独立 BackendClient**：`BackendEntryFactory.create` 每次新建 `HttpBackendClient`（独立传输 + 会话 + SSE）——局部故障韧性。
3. **虚拟线程承载异步**：`AsyncTaskExecutor`/`BackendConfigWatcher` 监听/retireScheduler 皆虚拟线程。
4. **CallToolResult 原样**：4 字段（content/isError/structuredContent/_meta），网关不篡改。
5. **统一拦截层**（002 整改）：熔断守卫 + 取/还槽 + initialize + 故障分类收口到 `BackendEntry`，路由器变薄只翻译错误。
6. **错误边界分离**：BackendEntry 抛域异常（不依赖 McpError/注册表），路由器翻译结构化 McpError（含 data.available）。
7. **registry 原子替换**：`RegistryHolder` AtomicReference + 不可变 `BackendRegistry` record + `Map.copyOf`——调用全程持固定引用，热重载并发不串台。
8. **配置外置 + WatchService**：后端列表不走 `@ConfigurationProperties`（与热重载冲突），`GatewayProperties` 只持文件位置。
9. **capabilities 锁定**：`@Primary McpSyncServerCustomizer` 覆盖 starter 默认为「仅 tools、listChanged=false」。
10. **gateway-core 零 K8S 依赖**：orchestration 包承载全部 K8S，编排工具 handler 绕过 ToolsCallRouter（gateway-core 路由器不知 k8s.* 存在），ArchUnit 字节码守护。

---

> **下一步**：Part 2 深入 001 诊断聚合核心的逐类逐方法实现。
