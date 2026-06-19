# Research: Arthas MCP 网关（Phase 0）

**Feature**: 001-arthas-mcp-gateway
**Date**: 2026-06-19
**宪法依据**: v1.2.0（原则一~八、技术与传输约束、开发流程与质量门禁）

> 本文档是 `/speckit-plan` 的 Phase 0 产出。它整合三部分证据并给出每一项**决策 / 理由 / 备选方案**：
> 1. **上游 arthas MCP 先决研究**（宪法原则八）——已沉淀为 `reference/arthas-docs/03-MCP/` 五篇文档，本文索引引用、不复制。
> 2. **官方 MCP Java SDK 能力调研**（宪法"优先官方 SDK，不手写帧"硬门禁）。
> 3. **网关设计决策**（`target` 注入、热重载、故障隔离、双侧契约测试、task 策略、双传输）。
>
> 凡本文给出的结论，均标注来源；标注【已确认】者有代码或官方文档直接支撑，【部分确认】者需在实现/接入阶段补验。

---

## 0. 上游 arthas MCP 先决研究（宪法原则八，前置完成）

研究成果已落盘为 `reference/arthas-docs/03-MCP/` 下五篇中文文档，**单一事实源**，下文凡涉及 arthas 行为均引用之：

| 文档 | 角色 | 关键结论 |
|---|---|---|
| [问题定位反向索引](../../../reference/arthas-docs/03-MCP/问题定位反向索引.md) | 症状→源码定位 | 核心包 `com.taobao.arthas.core.mcp`；MCP 协议常量在 `McpSchema.java` |
| [MCP能力清单](../../../reference/arthas-docs/03-MCP/MCP能力清单.md) | 摘抄拷贝单一事实源 | 31 工具全量定义；resource=0、prompt=0；27 forbidden / 5 optional / 0 required |
| [MCP线契约](../../../reference/arthas-docs/03-MCP/MCP线契约.md) | 网关↔Claude Code 服务端契约 | 协议 `2025-11-25`；initialize/tools/task/error 线格式 |
| [后端接入契约](../../../reference/arthas-docs/03-MCP/后端接入契约.md) | 网关↔arthas 后端客户端契约 | 复用 http console `/mcp`；Bearer=后端 password；session 25min；task 并发上限 5；task TTL 30min |
| [工具传输分类表](../../../reference/arthas-docs/03-MCP/工具传输分类表.md) | 逐工具路由速查 | 同步直发 27 / 流式 1（dashboard）/ 任务型 5（watch/trace/stack/tt/monitor） |

这些事实直接驱动下文所有设计决策，不再重复论述。

---

## 1. 官方 MCP Java SDK 调研（宪法"协议核心：优先官方 SDK，不手写帧"）

### 1.1 版本、坐标、Java 版本

- **Decision**：采用官方 `io.modelcontextprotocol.sdk`，锁定 **`mcp-bom:2.0.0` GA**（发布于 2026-06-11）。
- **Rationale**：v2.0.0 GA 是当前稳定版，最低 **Java 17**、兼容 **Java 21**，原生追踪 **MCP 协议 `2025-11-25`**（与 arthas 后端一致，握手无版本鸿沟）。模块清单：`mcp`（便利包，含 `mcp-core` + Jackson3 绑定，**推荐首选**）、`mcp-core`、`mcp-json-jackson2/3`、`mcp-test`、`mcp-bom`。
- **已确认**：GitHub Releases API（v2.0.0，2026-06-11，PR #1025 "将 2025-11-25 规范版本添加到所有传输"）、根 `pom.xml`（`<java.version>17</java.version>`）。
- **重要变化**：**Spring 专用传输（WebFlux/WebMVC）已从本 SDK 迁出至 Spring AI 2.0+**，groupId 变为 `org.springframework.ai`（`mcp-spring-webmvc` / `mcp-spring-webflux`）。采用 Spring Boot 路线时，HTTP transport 通过 Spring AI 的 servlet starter 获得（见 §6）。

### 1.2 服务端能力（给 Claude Code）

- **Decision**：服务端基于官方 SDK，**不手写 JSON-RPC/MCP 帧**。
- **证据**：`StdioServerTransportProvider`（stdio）+ `HttpServletStreamableServerTransportProvider`（Streamable HTTP，原生 Servlet）为核心模块自带；编程式注册工具 `SyncToolSpecification.builder().tool(...).callHandler((exchange, request) -> ...)`；inputSchema 为纯数据驱动的 JSON Schema Map，由 SDK 在 `build()`/`addTool()` 按 SEP-1613 做 meta-schema 校验。工具执行两级错误模型：领域错误 → `CallToolResult.isError(true)`；基础设施错误 → 抛 `McpError`（JSON-RPC error）。【已确认】

### 1.3 客户端能力（连 arthas 后端）

- **Decision**：客户端用 `HttpClientStreamableHttpTransport`（核心模块自带，基于 JDK HttpClient），自定义 header 经 `httpRequestCustomizer(...)` 注入（**注意：旧 `customizeRequest()` 已弃用，Issue #788**）。SSE 流解析与 `Mcp-Session-Id` 头由 SDK transport 自动管理，**网关无需手写 SSE 帧或会话头**。
- **已确认**：官方 2.0.0 client 文档、Issue #788/#458。
- **互操作风险（部分确认，需接入阶段实测）**：① 无状态后端不支持服务端→客户端通知（logging/progress/订阅），网关不得依赖 arthas 后端推送；② v2.0.0 启用"严格规范字段"（PR #928），若后端 JSON-RPC 含非规范自定义字段需实测是否被拒，必要时用 PR #927 前后向兼容开关；③ 尚未用真实 arthas MCP 后端跑通端到端握手——在 Phase 0 末尾用 `mcp-test` + 最小 smoke test 实测（见 §7）。

### 1.4 Tasks 原语——明确 GAP（关键）

- **结论**：官方 SDK **v2.0.0 GA 尚未实现 tasks 原语**（`tasks/get`/`tasks/result`/`tasks/cancel`/`notifications/tasks/status`/`TaskSupportMode`）。Issue #668 open、PR #671 closed-not-merged、PR #755 open（进行中）。【已确认】
- **网关应对**：见 §4 的"方案 C"，**用应用层普通工具模拟 task 异步语义**，底层全程走官方 SDK 的 `tools/call`，**一行 task 协议帧都不手写**——完全规避宪法"不手写 JSON-RPC 帧"硬约束，且未来 SDK 合入 task 后可平滑替换。
- **明确排除**：在 transport 层手写 `tasks/*` JSON-RPC 帧——违反宪法硬约束、与未来官方实现冲突。

### 1.5 测试脚手架

- **Decision**：复用官方 `mcp-test` 模块 + **conformance-tests 一致性套件**（Server 40/40、Client 9/10、Auth 98.9%）。
- **价值**：网关双端各跑一遍——服务端用 `server-servlet` 验对 Claude Code 暴露面，客户端用 `client-jdk-http-client` 验对 arthas 后端调用面。落地宪法"CI 必须能复现本地构建"。

---

## 2. 技术栈与构建决策

### 2.1 主力语言与版本

- **Decision**：**Java 21（LTS）**，构建中 enforce 锁定。
- **Rationale**：宪法技术与传输约束要求 Java LTS 最低 17；用户 JDK 21 已就绪（`C:\Program Files\Java\jdk-21`）；官方 SDK 兼容 21。
- **Alternatives**：Java 17——可行但放弃 21 的虚拟线程等收益；本项目后台异步任务（§4）正适合虚拟线程，故选 21。

### 2.2 构建工具

- **Decision**：**Maven**。
- **Rationale**：与官方 SDK（Maven 项目）一致；CI 复现性最好；宪法要求"同一条命令本地与 CI 一致"。锁定 `mcp-bom:2.0.0` + `spring-ai-bom`。
- **Alternatives**：Gradle（BOM 经 `platform()` 导入，完全可行）——未采用，因 SDK 与团队默认栈一致用 Maven 更稳。

### 2.3 服务端形态

- **Decision**：**Maven + Spring Boot**（Spring AI 的 `mcp-spring-webmvc` 提供 Streamable HTTP transport + Actuator 提供可观测性健康/metrics，契合宪法原则五）。
- **Rationale**：运维省力（健康检查/metrics 开箱即用），且网关本身是长期运行的基础设施，Spring Boot 的生命周期管理、配置外化、Actuator 与宪法原则五"暴露足够状态"高度契合。
- **Trade-off**：HTTP transport 经 Spring AI servlet starter（多一层依赖与版本耦合，需跟随 `spring-ai-bom` 版本）——接受。
- **Alternatives**：裸 JDK + Servlet + 官方 `mcp` 核心模块（依赖最轻、与 SDK 路径完全一致）——未采用，因运维便利性收益更大。

---

## 3. `target` 参数注入与静态工具注册表（FR-008、FR-003、宪法原则二）

- **Decision**：启动期构建**静态工具注册表**（31 工具，schema 逐字摘抄自 [MCP能力清单](../../../reference/arthas-docs/03-MCP/MCP能力清单.md)），每个工具在对外 inputSchema 中**额外注入顶层 `target`（string, required=true）**；运行时 `tools/call` 从 `arguments` 取出 `target` 并剥离，剩余键作为后端 `tools/call` 的 arguments；路由判定不查后端，纯靠静态表 + 注册表。
- **Rationale**：宪法原则二要求"定义来源是 arthas 源码如实摘抄、不逐后端动态发现"且"映射确定且可被发现"；`target` 作为命名空间消除歧义。保留 `additionalProperties:false`（对齐 arthas schema 恒定结构）。**target 永不透传给后端**（避免污染后端 INVALID_PARAMS）。
- **schema 来源防错**：31 条 schema 推荐从源码生成脚本产出而非纯手抄，并由契约测试逐条比对 arthas 真实 `tools/list`（见 §5）。
- **无命名冲突**：已核对 31 工具参数表，无 `target` 命名冲突。
- **关于 `execution.taskSupport`**：照实暴露（dashboard=forbidden；watch/trace/stack/tt/monitor=optional；其余 forbidden），让调用方知晓哪些是流式/可长任务。

---

## 4. Task 异步策略——方案 C：应用层异步任务（用户决策）

> 这是本次规划的核心架构决策，由用户在 `/speckit-plan` 澄清阶段拍板。它同时满足了"长任务必须异步"的需求与"不手写 task 帧"的宪法硬约束。

### 4.1 机制：阻塞转异步

- 5 个 optional 工具（watch/trace/stack/tt/monitor）对 Claude Code **默认即异步**：网关**立即**返回 `{ taskId, status: "working" }`；同时在**后台**对后端发**普通同步** `tools/call`（走后端自动轮询路①，阻塞等最终结果，后端上限 10 分钟）。
- 26 个即时工具（含 dashboard）仍**同步直发**：秒级返回，无需异步。
- 后台任务完成后，结果存入网关内存 `taskStore`（taskId → 状态/结果），标记 `completed`。

### 4.2 4 个网关自有工具（应用层模拟 task，全走标准 tools/call）

| 工具 | 参数 | 行为 | 路由 |
|---|---|---|---|
| `arthas-gateway.list-targets` | 无 | 返回当前后端注册表（逻辑名 + 健康状态） | 不转发后端（网关自有） |
| `arthas-gateway.task-get` | `taskId` | 查任务状态：working/completed/failed；completed 时返回最终 `CallToolResult` 内容；failed 时返回错误 | 不转发后端 |
| `arthas-gateway.task-list` | 无 | 返回所有任务状态列表 | 不转发后端 |
| `arthas-gateway.task-cancel` | `taskId` | 取消后台任务（关 future + 标 cancelled） | 不转发后端 |

- **关键合规点**：这 4 个工具对 Claude Code 都是**普通 MCP 工具**，走官方 SDK 的 `tools/call`；"task"只是网关内存里的一张表，**不存在于 MCP 协议层，无任何手写 task 帧**。
- **满足 FR-009**：`list-targets` 让"目标集合变化可被发现"，且因 target 是动态值而非静态 schema，热重载后立即反映（见 §6.2 取舍）。
- **复杂度治理**：这 4 个网关自有工具是"超出 arthas 规范集的额外能力"，属宪法治理"超出原则的复杂度须在 plan.md Complexity Tracking 给出正当理由"——已记录（见 plan.md）。理由：FR-009 要求目标可发现 + 长任务需异步生命周期，且实现方式不违反任何原则。

### 4.3 任务状态机（精简版）

`working → completed | failed | cancelled`（不做 arthas 的 INPUT_REQUIRED 多档复杂态，因为协议层不暴露真 task）。
- **后台超时**：等后端兜底 11 分钟（> 后端 10 分钟上限），超时标 `failed`。
- **存储与清理**：内存 + TTL（完成后保留可查询，如 1 小时；过期清理），避免无限增长。
- **可追溯**：每条任务记录 target/toolName/createdAt/status（宪法原则五）。

### 4.4 备选方案（已否决）

- **方案 A 纯透明同步**：网关对 5 个工具也同步阻塞返回。**否决原因**：长任务（>30s）会被网关转发超时截断，且占用连接。用户明确要求异步能力。
- **方案 B 端到端 task 透传（手写 task 帧）**：**否决原因**：违反宪法"不手写 JSON-RPC 帧"硬约束 + SDK 无 task API + 与未来官方实现冲突。
- **方案 C（采纳）**：兼顾异步体验与宪法合规，架构预留——未来 SDK 合入 task 时，仅需把后台分支替换为真 task 转发，4 个自有工具可保留或迁移。

---

## 5. 测试体系（宪法原则四 + 原则七 TDD；用户 TDD 真实性硬约束）

> **TDD 真实性硬约束**（用户规范，落地于 CLAUDE.md 工程实践）：
> 1. **真实环境，零桩**：每次测试启动**真实的 arthas MCP** + **真实的业务服务**；诊断数据由**触发业务服务真实调用**产生（调业务接口让目标方法执行 → arthas 工具捕获真实调用 → 真实诊断返回）。**禁止用桩（WireMock 等）模拟 arthas 的正常成功响应**。故障场景用**真实故障条件**实现（见 §5.1 表）。
> 2. **驱动按验证目标分层**：工具**可用性**用**真实 Claude Code** 驱动（逐工具冒烟，**不做**结果一致性校验）；**结果一致性 + 双侧协议契约**用**官方 MCP Java SDK client** 驱动（合规 MCP 客户端，走标准协议、**非 curl 裸 HTTP**，确定性断言）。

### 5.1 环境层（真实，零桩）

- **真实业务服务**：示例 Java/Spring Boot 应用，暴露可调用接口（如 `/api/order`），含可被 watch/trace 的方法；方法可注入 `sleep` 用于慢响应测试。
- **真实 arthas MCP**：业务 JVM attach arthas 并暴露 `/mcp`；多目标 = 多组（业务服务 + arthas）。
- **编排**：Testcontainers 拉起（每测试隔离）；CI 与本地同命令（宪法"CI 复现本地构建"）。
- **故障条件（真实，非桩）**：

| 场景 | 真实实现 |
|---|---|
| 后端不可达 | 停掉目标 arthas 容器 / 指向未监听端口 |
| 401 认证失败 | 配置错误 token（arthas 真按错误 password 返 401 + `WWW-Authenticate`） |
| 超时 | 业务方法 `Thread.sleep` + 短 `callTimeout` 配置 |
| 熔断 | 连续触发"不可达" N 次 |
| 并发越界(>5) | 真实发起 6 个并发 task → arthas 真返 INVALID_PARAMS |

### 5.2 工具可用性验证（Claude Code 驱动）

真实 Claude Code 注册网关为 MCP server → 逐个调用 35 工具（31 arthas 用真实 target + 真实业务数据；4 自有用合参）。断言：每个工具**调用成功**（无 JSON-RPC error、无网关故障）；**不**做响应内容一致性比对。价值：在真实 AI 客户端场景下证明工具端到端可用。

### 5.3 结果一致性验证（SDK client 驱动，SC-005）

官方 SDK client 分别连 ① 网关、② 直连目标 arthas，**同一诊断操作**各执行一次。断言：两路 `CallToolResult`（content/isError/_meta）**完全一致**（逐字段确定性比对）。覆盖代表性工具（jvm 同步类、watch 异步类、dashboard 流式类）。

### 5.4 双侧协议契约（SDK client 驱动 + 真实 arthas）

服务端契约（gateway↔Claude Code）与客户端契约（gateway↔arthas）的协议级断言详见 `contracts/`（server-contract §7 / backend-client-contract §8 / gateway-tools-contract §6）。关键覆盖：initialize 握手与协议版本回显、tools/list 的 35 工具与 target 注入、tools/call 按 target 路由与 target 剥离、错误原样传播、熔断/限流/异步任务状态机。**全真实环境**，断言**先于实现**编写（TDD 红绿重构）。

### 5.5 技术栈

JUnit 5 + AssertJ + 官方 MCP Java SDK client（驱动）+ Testcontainers（真实 arthas + 真实业务服务）+ 官方 conformance-tests 子套件（协议一致性补充）。**无 WireMock**。

### 5.6 TDD 节奏

每个用户故事至少 1 个端到端测试（故事1→路由+一致性 A/B；故事2→热重载+list-targets；故事3→错误传播/隔离）。先写失败测试（红）→ 最小路由层（绿）→ 加熔断/限流/热重载/异步（重构）。

---

## 6. 关键工程机制

### 6.1 故障隔离（FR-006、SC-003、宪法原则三）

- **per-target 独立资源**：每个后端独立 `BackendClient`（独立连接池 + 独立 McpClient 会话 + 独立 SSE 解析），互不阻塞。
- **per-target 超时**：connect 5s / call 30s（对齐 SC-003）；async 后台任务兜底 11 分钟（§4.3）。
- **熔断 + 退避重连**：CLOSED→OPEN（连续 3 次失败）→HALF_OPEN（退避探测）→CLOSED。**不**把后端业务错误（`isError=true`/INVALID_PARAMS）计入熔断（那是正常响应）。OPEN 期间立即返回明确错误（不等 30s）。
- **per-target 限流**：`Semaphore`（task 并发上限 5，对齐后端硬约束），避免调用方触发后端 INVALID_PARAMS。

### 6.2 后端注册表热重载（FR-005、SC-002、30s 内生效）

- **实现**：`WatchService` 监听配置目录（防抖 500ms）→ 解析校验 → diff（added/removed/unchanged，unchanged 复用已热连接池）→ 构造不可变 `BackendRegistry` → `AtomicReference` 原子替换 → 异步优雅下线旧 client。
- **并发安全**：一次 `tools/call` 全程持有固定的 `BackendEntry` 引用（final），重载替换 registry 不影响 in-flight 调用——满足边缘情况"热重载进行中并发请求不串台"。
- **是否发 `notifications/tools/list_changed`？** **不发**。理由：工具集（31+4）**不随 target 增减变化**，变的是 target 可选值；发 list_changed 是噪声。target 的发现由 `list-targets` 工具承担（§4.2）。
- **端到端时效**：事件→swap 目标 < 5s；30s 是含 GC/IO 抖动的保守上限。

### 6.3 双传输 stdio + Streamable HTTP（宪法技术与传输约束）

- **配置驱动**：`transports.stdio.enabled` / `transports.http.{enabled,host,port,endpoint}`，启动期按配置挂 0~2 个 transport。
- **共享协议核心**：`GatewayMcpHandler`（initialize/tools/list/tools/call/list-targets/task-* 处理）传输无关，stdio 与 http 共用同一份逻辑。优先复用官方 SDK 的 transport 实现，不手写帧解析。
- **生命周期**：每客户端独立握手；网关对 Claude Code 侧的 `Mcp-Session-Id` 自行分配（不复用后端 session id）。有序关闭：停接新会话→等 in-flight 完成（30s 兜底）→对后端 DELETE/关连接池→关 transport。

---

## 7. 待实测 / 演进项（透明记录）

- **待接入阶段实测**（部分确认项）：① 真实 arthas MCP 后端端到端 initialize+tools/list+tools/call smoke test；② 严格规范字段（PR #928）是否拒绝后端非规范字段；③ 无状态后端通知限制确认。Phase 0 末尾用 `mcp-test` + WireMock 已覆盖协议层；真实后端实测留作实现阶段首个集成任务。
- **演进项**（MVP 后）：
  - 主动健康检查（ping 探测 + HALF_OPEN）——被动熔断恢复慢时启用。
  - **官方 SDK 合入 task 后**（PR #755）：评估把 §4 后台分支替换为真 task 转发；4 个自有工具可保留或迁移。
  - 网关↔Claude Code 侧认证（宪法列认证为演进首要项；MVP 受控内网、无认证）。
  - resources/prompts 聚合（arthas 当前为 0，预留）。

---

## 决策汇总（一图速览）

| 维度 | 决策 |
|---|---|
| 语言 | Java 21（LTS） |
| 构建 | Maven（锁 `mcp-bom:2.0.0` + `spring-ai-bom`） |
| 形态 | Spring Boot + Spring AI `mcp-spring-webmvc` + Actuator |
| MCP 实现 | 官方 SDK 双端，**不手写帧** |
| 协议版本 | `2025-11-25` |
| 工具暴露 | 31 arthas 工具（静态摘抄 + target 注入）+ 4 网关自有（list-targets/task-get/task-list/task-cancel）= 35 |
| task | 方案 C：应用层异步任务（后台同步路① + 内存 taskStore），无手写 task 帧 |
| 传输 | stdio + Streamable HTTP，配置驱动 |
| 测试 | 真实 arthas + 真实业务服务（Testcontainers，**零桩**）；Claude Code 验可用性 + 官方 SDK client 验一致性/契约；TDD 红绿重构 |
| 故障隔离 | per-target 独立资源 + 30s 超时 + 熔断退避 + Semaphore(5) 限流 |
| 热重载 | WatchService + AtomicReference，30s 内生效，不发 list_changed |
