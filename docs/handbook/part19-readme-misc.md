# Part 19 · README、CLAUDE.md、冒烟报告、spec-kit 模板

> 本附录摘录根 README/CLAUDE.md、冒烟测试报告、spec-kit 模板等剩余工程文档，补全工程参考。

## 根文档

### `README.md`

```markdown
# Arthas MCP 网关

> 聚合多个目标 JVM 的 [arthas](https://arthas.aliyun.com/) 诊断能力，向 [Claude Code](https://docs.claude.com/en/docs/claude-code) 等 MCP 客户端统一暴露为单一 MCP 服务（38 个工具）。

**Feature**: `001-arthas-mcp-gateway`（基础：35 工具诊断聚合）+ `003-k8s-arthas-mcp-launch`（K8S 编排：+3 工具，对指定 pod 一键拉起 arthas MCP + 暴露 + 动态纳管） | **版本**: 0.1.0-SNAPSHOT | **语言/文档**: 中文（技术名词保留英文）

---

## 这是什么

arthas 是 Java 生态最强的在线诊断工具，但每个目标 JVM 各起一个 MCP 端点，对 Claude Code 等客户端而言：要管理多个 server、无法跨目标编排、长任务（`watch`/`trace`/`stack`/`tt`/`monitor`）会阻塞会话。

本网关作为 **MCP 反向代理 + 编排层**：

- **统一入口**：把 N 个目标 JVM 的 arthas 聚合为**一个** MCP server，对 Claude Code 只暴露一个端点。
- **`target` 路由**：每个 arthas 工具注入必填 `target` 参数，网关据此转发到对应后端，结果原样回传。
- **异步长任务**：5 个长耗时工具（`watch`/`trace`/`stack`/`tt`/`monitor`）后台化，立即返回 `taskId`，经 `task-get/list/cancel` 跟踪。
- **故障隔离**：单后端故障不影响其他目标；熔断 + per-target 限流 + 30s 内明确错误。
- **热重载**：改 `config/backends.yaml` 免重启增删目标。
- **K8S 编排**（003）：对指定 K8S pod 一键拉起 arthas MCP（上传 arthas + 启动 + NodePort 暴露 + 动态纳管 + 健康检查），诊断结果明确来自该 pod JVM（[SC-001](./specs/003-k8s-arthas-mcp-launch/spec.md)）。

> **不依赖 arthas 工程源码**：`arthas-boot.jar` 作为静态工具文件置于 `tools/`，经 `java -jar` 使用；不入 Maven 依赖、不构建 reference 源码。

---

## 技术栈

| 项 | 版本/说明 |
|---|---|
| JDK | 21（LTS，虚拟线程承载异步长任务） |
| Spring Boot | 4.1.0 |
| Spring AI | 2.0.0（`spring-ai-starter-mcp-server-webmvc` + `-client`） |
| MCP Java SDK | 2.0.0（`io.modelcontextprotocol.sdk`，官方 SDK，[上游已通过官方 conformance 套件验证](https://github.com/modelcontextprotocol/java-sdk)） |
| 传输 | MVP 仅 Streamable HTTP（`/mcp`，默认端口 8761；stdio 与 HTTP 互斥，stdio 延后） |

---

## 构建与运行

> 📋 **新环境快速启动 + 全面验证 MCP 可用**（含**远端 K8S 调用场景**设计、测试用例分层、能力矩阵）→ 详见 **[docs/getting-started.md](./docs/getting-started.md)**。本节为极简版。

```bash
# 构建（本地与 CI 同命令）—— 含 surefire 单测 + failsafe 真实 arthas 集成测试
./mvnw clean verify

# 启动网关（默认 HTTP :8761，MCP 端点 /mcp）
./mvnw spring-boot:run
```

启动后：

- 健康检查：`GET http://localhost:8761/actuator/health` → `{"status":"UP", ...}`（details 含各后端健康）。
- MCP 端点：`http://localhost:8761/mcp`。

### 配置后端映射表

编辑 `config/backends.yaml`（实体见 [specs/.../data-model.md §2](./specs/001-arthas-mcp-gateway/data-model.md)）：

```yaml
version: 1
backends:
  - name: order-service
    url: http://10.0.0.10:8563/mcp        # arthas MCP 根 URL
    protocol: STREAMABLE
    auth: { mode: NONE }                   # NONE / BEARER / BASIC
    callTimeoutMs: 30000
    maxConcurrentTasks: 5
  - name: payment
    url: http://10.0.0.11:8563/mcp
    protocol: STREAMABLE
    auth: { mode: BEARER, token: ${PAYMENT_TOKEN} }
```

改完保存即生效（热重载，免重启）。

---

## 接入 Claude Code

MVP 为 HTTP server，写一份 MCP 配置（不污染全局）：

```json
{
  "mcpServers": {
    "arthas-gw": { "type": "http", "url": "http://localhost:8761/mcp" }
  }
}
```

```bash
claude -p "列出 arthas-gw 暴露的全部工具名，仅输出 JSON 数组" \
  --mcp-config target/smoke-mcp-config.json --strict-mcp-config
# → 38 个工具名（4 自有 arthas-gateway.* + 31 arthas + 3 K8S 编排 k8s.*；每个 arthas 工具带 target 参数）
```

> Claude Code 把工具句柄中的 `.` 显示为 `_`；`--allowedTools "mcp__arthas-gw__*"` 通配可覆盖全部 38 工具。

---

## 工具集（38 = 35 既有 + 3 K8S 编排）

| 类别 | 数量 | 路由模式 | 示例 |
|---|---|---|---|
| 即时诊断（同步转发） | 26 | `SYNC_DIRECT` | `jvm`/`thread`/`dashboard`/`ognl`/`jad`/`sc`/`heapdump` … |
| 长任务（异步） | 5 | `ASYNC_TASK` | `watch`/`trace`/`stack`/`tt`/`monitor`（立即返 `taskId`） |
| 聚合（同步多帧） | 1 | `STREAM_AGGREGATE` | `dashboard` |
| 网关自有 | 4 | `GATEWAY_LOCAL` | `list-targets`/`task-get`/`task-list`/`task-cancel` |
| K8S 编排（003） | 3 | `GATEWAY_LOCAL` | `k8s.list-pods`/`k8s.list-services`/`k8s.ensure-arthas-mcp`（自带闭包、不经路由器、无 `target` 参数） |

工具静态来自 `src/main/resources/arthas-tools.json`（31 arthas + 4 网关自有 = 35，**不随后端数变化**）；3 个 K8S 编排工具由 `K8sToolRegistry` 程序化注册。诊断类工具带必填 `target` 参数；K8S 编排工具无 `target`（目标由 `server`/`pod` 参数指定）。

---

## 管理面 portal（Web UI，004）

004 落地 003 P3 portal 的**配置管理子集**：浏览器访问网关根加载 Vue 3 SPA（内嵌单 JAR），同源调 `/admin` HTTP API。

- **后端 `/admin` API**（Java）：`/admin/backends` CRUD（静态写 `backends.yaml` 热重载 / 动态 `DynamicBackendStore`）+ `/admin/tasks` 任务列表查询（摘要无 frames + `status`/`tool`/`target` 过滤 + `page`/`size` 分页 + `createdAt` 倒序，FR-015）+ `/admin/tasks/{id}/export`（`completed` 任务原样 JSON 下载，宪法原则二）。能力各自 `@ConditionalOnProperty` 按需开关（`arthas-gateway.admin.crud.enabled` / `export.enabled`，默认开；列表与导出共用 `export.enabled`）。Noop 鉴权（受控内网）。
- **前端 SPA**（`web/`，Vue 3 + Vite + TypeScript）：后端管理页（列表 + 增删改 + 健康徽标 + 凭据脱敏）、任务导出页（最近任务列表浏览 + status 过滤 + 分页 + 点列表项衔接 taskId 导出 + 原样下载；空态/错误态自验证反馈）。前端=**展示层**（核心逻辑 Java 后端，宪法原则六对齐）。`vite build` → `target/classes/static/` 内嵌单 JAR。
- **构建**：`./mvnw verify` 经 `frontend-maven-plugin` 跑 `npm install + build`，产出**含前端 SPA 的单 JAR**（CI 可复现，开发期 `-DskipFrontend=true` 跳前端）。
- **范围**：A 后端配置 CRUD + E 异步任务列表查询（增量）+ C 异步任务结果导出。B 动态持久化 / D 操作审计 / 任务可视化 / 鉴权 后置。

详见 [004 spec](./specs/004-portal-backend-management/spec.md)。

---

## 测试（真实环境，零桩）

**TDD 硬约束**：所有测试先于实现编写；**禁桩**——真实 arthas MCP + 真实业务服务产生真实诊断，故障用真实故障条件（停 JVM=不可达、关闭端口=连接拒绝、错 token=真实 401）。

| 阶段 | 引擎 | 内容 |
|---|---|---|
| `*Test` | surefire | 纯逻辑单测（熔断状态机、限流、解析、健康指标等） |
| `*IT` | failsafe | 真实 arthas + 真实网关（路由、异步、热重载、故障隔离、限流） |

驱动分层（宪法原则四/七）：
- **工具可用性**：真实 Claude Code 走 MCP（逐工具冒烟，仅验"调通"）。
- **结果一致性 + 双侧协议契约**：官方 MCP Java SDK client（确定性断言）。

K8S 编排（003）的真实供给契约测试（`K8sListToolsContractIT`/`ArthasProvisionerIT`/`K8sEnsureContractIT`）在 `test-env/k8s/` 一键幂等起的真实 k3s 集群（debian 服务器）上跑——CI 默认 Assume 跳过、本地手跑；见 [003 Quickstart](./specs/003-k8s-arthas-mcp-launch/quickstart.md)。

端到端验证场景（A–F）与逐工具冒烟见 [001 Quickstart](./specs/001-arthas-mcp-gateway/quickstart.md)。

---

## 可观测性

- **结构化日志**（`ToolsCallRouter`）：每次路由记 `tool/target/isError/耗时`；后端业务错误带 MCP 错误码（显式传播，不吞为静默成功）；基础设施故障/熔断拒绝/并发限流各自结构化日志。
- **Actuator**：`/actuator/health` details 暴露各后端 `{state, healthy, protocol, breaker}` + `{total, healthy, unhealthy}` 汇总（运维无需读源码）。

---

## 项目结构

```
src/main/java/com/arthas/gateway/
├── GatewayApplication.java        # 启动入口
├── backend/                       # 后端配置/注册表/熔断/限流/HTTP 客户端
├── handler/                       # ToolsCallRouter（路由）+ GatewayToolHandlers（自有工具）
├── task/                          # 异步任务执行器 + TaskStore（内存）
├── tool/                          # 工具注册表 + 路由模式
├── config/                        # Spring 装配 + 热重载监听 + Bootstrap + K8sProperties
├── orchestration/                 # K8S 编排（003）：K8sClientFactory/PodExplorer/NodePortExposer/ArthasProvisioner/ToolHandlers（gateway-core 零 K8S 依赖，编排层独立）
├── auth/                          # 后端认证头注入 + 网关侧认证占位（MVP Noop）
└── obs/                           # Actuator 健康指标
specs/001-arthas-mcp-gateway/      # 规格（plan/research/data-model/contracts/quickstart/tasks）
specs/003-k8s-arthas-mcp-launch/   # 003 规格（K8S 编排：plan/research/data-model/contracts/quickstart/tasks）
test-env/k8s/                      # K8S 真实测试床（setup/teardown/Dockerfile.demo/kubeconfig——凭证 gitignored）
tools/                             # arthas-boot.jar（静态工具文件，ensure 时经 fabric8 上传到目标 pod）
```

---

## CI

[`.github/workflows/ci.yml`](./.github/workflows/ci.yml) 跑 `./mvnw clean verify`，复现本地构建（宪法"CI 必须能复现本地构建"）。

---

## 演进项（MVP 之后）

- **网关侧认证**（宪法首要演进项）：`GatewayAuthenticator` 接口缝已就位（MVP Noop 放行，受控内网）；接入 Bearer/API key 时实现该接口 + 请求过滤。
- **后端 401 处理**（C-AUTH-1）：随认证后端夹具落地（当前 NONE 认证夹具无 401 路径，TDD 零桩约束下不提前实装）。
- **stdio 传输 / 双传输对等**（S-DUAL-1）：MVP 仅 HTTP（stdio/HTTP 互斥）；路由层传输无关（单一 `ToolsCallRouter`），对等性由构造保证；stdio 启用后补真机对等测试。
- **官方 conformance 套件接入**：底层 SDK [上游已验证](https://github.com/modelcontextprotocol/java-sdk)；网关协议面由 S-*/C-*/G-* 契约测试覆盖。完整跨语言 harness 接入（指向 `/mcp`）见 [modelcontextprotocol/conformance](https://github.com/modelcontextprotocol/conformance)。
```

### `CLAUDE.md`

```markdown
# 重要说明（语言规范）

- **本项目的所有文档描述必须使用中文编写。**

  适用范围包括但不限于：`.specify/memory/constitution.md`、`spec.md`、`plan.md`、`tasks.md`、`research.md`、`data-model.md`、`README`、`quickstart`，以及所有由本项目生成的说明文档与代码注释。

  技术专有名词（如 MCP、arthas、JSON-RPC、Claude Code、stdio 等）可保留英文原文，但所有叙述性、描述性内容必须为中文。

## 工作流规范

> **总原则（职责严格分离）**：SuperPower **仅用于设计阶段的头脑风暴**；**代码实施走 spec-kit 的 SDD（规格驱动开发）流程**，**不得**改用 SuperPower 的实施类技能。

- **设计阶段走 SuperPower 头脑风暴。** 任何涉及“方案设计 / 技术调研 / 报告撰写 / 选型”的工作，动手之前**必须**先调用 `superpowers:brainstorming` 与用户充分沟通，产出归档于 `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md`。未完成需求沟通，不得直接进入产出。
- **代码实施走 spec-kit SDD。** 功能代码落地**必须**通过 spec-kit 的 SDD 流程（`/speckit-implement`，以 `specs/<feature>/tasks.md` 为权威任务清单、**测试任务先于实现任务**推进），**不得**改用 SuperPower 的实施类技能。TDD 红-绿-重构（宪法原则七）由 spec-kit SDD 的「测试先于实现」落地——先写失败测试（红），再实现至通过（绿），再重构；不得“先写实现、后补测试”。
- **设计与实施时必须参考 SuperPower 设计文档**：`docs/superpowers/specs/`。

### SuperPower 技能白名单（仅以下允许，其余一律禁用）

`using-superpowers` 是技能发现基建（其“凡事皆用技能”的推力被本白名单约束），工作流技能**仅允许** `superpowers:brainstorming`（设计阶段）`superpowers:test-driven-development`(代码实施阶段)。以下 SuperPower 技能**禁用**，不得调用：

- **实施生命周期类**（与 spec-kit SDD 冲突）：`writing-plans`、`executing-plans`、`subagent-driven-development`、`dispatching-parallel-agents`。
- **Git/分支生命周期类**（git 由用户手动管控、完成由 spec-kit 收尾）：`using-git-worktrees`、`finishing-a-development-branch`。
- **代码评审流**（不在本项目范围）：`requesting-code-review`、`receiving-code-review`。
- **质量纪律类**（精神已由本文件「证据驱动，禁止臆测 / 透明记录 / 同一问题连续失败 3 次暂停」覆盖，无需再借技能）：`systematic-debugging`、`verification-before-completion`。
- **技能创作**（本项目不创作 SuperPower 技能）：`writing-skills`。
- **TDD 必须基于真实环境，禁止用桩冒充成功（不可妥协）。** 测试的真实性是硬约束，具体两条：
  1. **真实环境**：每次测试**必须**至少启动一个**真实的 arthas MCP** 与一个**真实的业务服务**；诊断数据由**触发业务服务的真实调用**产生（如调用业务接口让目标方法执行，再由 arthas 工具捕获真实调用、返回真实诊断），**不得**用桩（WireMock / Mock 等）模拟 arthas 的正常成功响应。故障类用例（401 / 超时 / 熔断 / 并发越界）用**真实故障条件**实现（停掉真实后端=不可达、错误 token=arthas 真实 401、业务方法 `Thread.sleep`=慢响应、真实发起越界并发=arthas 真实 INVALID_PARAMS），同样不得用桩。
  2. **驱动分层**：除"网关健康检查"（Actuator 端点）外，所有对网关的 MCP 调用**不得**用 curl 等方式裸打——工具**可用性**验证用**真实 Claude Code** 走 MCP（逐工具冒烟，仅验"能调通"，不做结果一致性比对）；**结果一致性**与**双侧协议契约**验证用**官方 MCP Java SDK client**（合规 MCP 客户端、走标准 MCP 协议、确定性断言）。本条与宪法原则四（双侧契约）、原则七（TDD）一致。

## 工程实践规范

### 证据驱动，禁止臆测

- 任何结论（行为判断、选型理由、问题归因）**必须**援引代码或文档证据，不得假设或猜测。
- 与宪法原则八一致：实现前先研读既有代码/文档在 ./reference 文件夹中（尤其 arthas MCP 实现），吸收现有经验。
- **查询代码优先用 `codebase-memory-mcp`**：本项目已建知识图谱（节点含调用/使用/语义关系 + 复杂度/循环热点信号）。凡查代码——找定义/实现/引用、追调用者与被调用者、看依赖与调用链、定位架构模块与热点路径——**优先**用 `codebase-memory-mcp`（`search_graph` 全文检索 / `search_code` grep+图增强 / `trace_path` 调用链 / `get_architecture` 架构聚类 / `query_graph` Cypher），而非裸 `grep`/`glob`：图能跨文件去重、按结构重要性排序、还原多跳调用链，效率与完整度优于线性文本搜索。**已知限制**：`search_code` 的原文摘录在 Windows 上以 GBK 解码中文会乱码——查中文文档/注释原文改用 `Read` 工具直接读文件，不走图谱 grep 管道（代码符号为英文，不受影响）。

### 先研究、再规划、后实现

- 编码前先分析上下文与相似实现，确认依赖、输入输出与测试约定。
- 进入实现前完成"充分性自查"：能否定义清晰的接口契约？是否理解技术选型理由？是否识别主要风险（并发 / 边界 / 性能）？是否知道如何验证？
- 实现新特性前，至少参考 3 个相似实现，理解其设计与复用方式。

### 小步迭代，保持可用

- 小步修改，每次变更保持可编译、可验证；每次提交处于可用状态。
- 发现缺陷优先修复，再扩展新功能。
- 同一问题连续失败 3 次必须暂停并重新评估策略，不得盲目重试。

### 简单性与一致性

- 单一责任；禁止过早抽象（重复出现 3 次以上再考虑通用化）；可读性优先，拒绝炫技。
- 沿用项目既有模式、约定、构建系统、测试框架与格式化/检查设置，不私自新增脚本或工具链。
- 优先复用既有库与官方 SDK（宪法：优先官方 MCP Java SDK）。

### 透明记录

- 如实报告执行结果，包括失败与问题，不得掩饰。
- 代码注释与文档使用中文，描述意图、约束与使用方式（与上方语言规范一致）。

<!-- SPECKIT START -->
如需了解本项目当前使用的技术、项目结构、shell 命令与其他重要信息，请阅读当前实现计划：
`specs/004-portal-backend-management/plan.md`（特性：portal 后端管理平台 **v2 Web 前端**——后端 Java `/admin` HTTP API + 前端 Vue 3 SPA 内嵌单 JAR；后端配置 CRUD（静态写 `backends.yaml` 热重载 / 动态 `DynamicBackendStore`）+ 异步任务结果导出，能力各自 `@ConditionalOnProperty` 按需开关；单 Maven 模块 + 新增 `admin` 包 + `web/` 前端目录，gateway-core 零 K8S 依赖不变；前端=展示层、核心逻辑 Java 后端）。
配套产出：`research.md`（R1–R13）、`data-model.md`（增量 DTO）、`contracts/admin-api-contract.md` + `contracts/admin-invariants.md`（含 I-8 前端同源）、`quickstart.md`；规格见 `spec.md`；架构决策见 `docs/superpowers/specs/2026-06-25-portal-backend-management-design.md`。
上一特性基线（回归对照）：`specs/003-k8s-arthas-mcp-launch/`（更早 `specs/002-code-review-remediation/`、`specs/001-arthas-mcp-gateway/`）。
<!-- SPECKIT END -->
```

## 冒烟/测试报告（docs/）

### `docs/getting-started.md`

```markdown
# arthas MCP 网关 — 新环境启动指南与全面验证

> 目标：全新 clone 的环境照本文 ① 构建并启动网关、② 跑测试用例快速验证「MCP 是否可用」、③ 重点验证**远端 K8S 调用场景**（网关集群外运行 + kubeconfig + NodePort 路由到 pod JVM）。
>
> **真实性约定**（CLAUDE.md 宪法原则七）：本文每一条命令均在本地真实环境实跑过，输出为实测捕获（非杜撰）；故障类用真实故障条件（停后端/删 pod/错 token），**禁桩**。复现时若输出有偏差，以你本机实际为准并反馈。

---

## 0. 网关能力全景

arthas MCP 网关把「每个目标 JVM 各起一个 arthas MCP 端点」聚合为**单 HTTP MCP 端点**，对 Claude Code 等客户端暴露 38 个工具：

| 能力域 | 来源特性 | 工具 | 说明 |
|--------|----------|------|------|
| 诊断聚合 | 001 | 31 个 arthas 工具（`jvm`/`watch`/`trace`/`stack`/`tt`/`monitor`/`dashboard`/`sc`/…） | 每个带 `target` 参数，网关路由到指定后端 |
| 网关自有 | 001 | 4 个：`arthas-gateway.list-targets`/`task-get`/`task-list`/`task-cancel` | 多目标路由、异步任务系统 |
| 韧性 | 002 | （内建，非工具）熔断/隔离/并发/超时/退避 | 单点故障不扩散 |
| **K8S 编排** | **003（重点）** | 3 个：`k8s.list-pods`/`k8s.list-services`/`k8s.ensure-arthas-mcp` | 远端 K8S pod 注入 arthas + NodePort 暴露 + 动态纳管 |
| portal 管理面 | 004 | `/admin` HTTP API + Vue SPA | 后端 CRUD + 任务列表查询 + 结果导出（Web UI） |

---

## 1. 前置环境

| 项 | 版本/要求 | 验证命令 |
|----|-----------|----------|
| JDK | **21**（LTS，虚拟线程承载异步任务） | `java -version` → `21.x` |
| Maven | 用仓库自带 wrapper（本地 mvn 3.5.3 太老须 `./mvnw`） | `./mvnw -v` |
| Node | **22.x**（前端构建，004 portal；可 `-DskipFrontend=true` 跳过） | `node -v` → `v22.x` |
| Git | 任意 | `git -v` |
| Claude Code | 真实 CC（工具可用性冒烟；驱动分层见 §7） | `claude --version` |
| **远端 K3S**（重点场景） | debian 服务器上装 k3s（见 §6.2） | `kubectl get nodes` |
| OS | 本指南实测于 Windows 11 + Git Bash（命令为 bash 语法） | — |

> 受控内网、MVP **无鉴权**（Noop）；K3S 凭证 `test-env/k8s/kubeconfig/k3s-admin.yaml` **已 gitignore**（root-admin 权限，仅本机受控内网用）。

---

## 2. 构建（产出含前端 SPA 的单 JAR）

```bash
git clone <repo> && cd arthas-mcp-gateway
./mvnw clean verify          # CI 复现：含 frontend-maven-plugin 跑 npm install + vite build
```

**实测产物**：`target/arthas-mcp-gateway-0.1.0-SNAPSHOT.jar`（fat jar，前端 static 内嵌于 `BOOT-INF/classes/static/`）。

> 跳过前端构建（仅验后端 MCP）：`./mvnw clean verify -DskipFrontend=true`。`-DskipFrontend` 不影响后端测试，仅跳 `frontend-maven-plugin`。

**实测全量测试**：`Tests run: 253, Failures: 0, Errors: 0, Skipped: 0/1`（`K8sExternalGatewaySmokeTest` 在无常驻 uber-jar 网关时 `assumeTrue` 跳过，CI 友好）。

---

## 3. 启动场景

### 3.1 场景 A — 本地双后端最小冒烟（5 分钟验 MCP 可用）

`smoke/gateway-start.sh` 一键启动：① 双真实业务后端（`SmokeDemoLauncher` 起 `order-service` + `payment`，含 `OrderService.hotMethod` 自驱动）+ ② 网关（指向 `config/backends-runtime.yaml`）。

```bash
bash smoke/gateway-start.sh
```

**实测输出**（尾部）：
```
"summary":{"total":2,"healthy":2,"unhealthy":0}
[start] ✓ 常驻就绪。MCP 端点 http://127.0.0.1:8761/mcp
       停止： bash smoke/gateway-stop.sh
```

→ 浏览器访问 `http://127.0.0.1:8761/` 加载 portal SPA；MCP 端点 `http://127.0.0.1:8761/mcp`。停止：`bash smoke/gateway-stop.sh`。

> 此场景用**本地双后端**快速验 MCP 链路；**远端 K8S 场景**见 §3.2 与第 6 章（重点）。

### 3.2 场景 B — 远端 K8S 场景（重点，见第 6 章详述）

在场景 A 启动的同一个网关上（`application.yml` 已配 `arthas-gateway.k8s.kubeconfig`），调 `k8s.*` 工具即可编排远端 K3S pod。完整拓扑/配置/验证流程见 **§6 远端 K8S 调用场景设计**。

---

## 4. 验证 MCP 可用 — 最小冒烟集（L1–L3）

> 「MCP 是否可用」= 这三步全过即可判定。每步实测输出如下。

### L1 健康检查（Actuator，唯一允许直连端点）

```bash
curl -s http://127.0.0.1:8761/actuator/health | python -m json.tool
```

实测（节选）：
```json
{
  "status": "UP",
  "components": {
    "backendRegistry": {
      "details": {
        "backends": {
          "order-service": {"state":"ACTIVE","healthy":true,"breaker":"CLOSED"},
          "payment":       {"state":"ACTIVE","healthy":true,"breaker":"CLOSED"}
        },
        "summary": {"total":2,"healthy":2,"unhealthy":0}
      },
      "status": "UP"
    }
  }
}
```

### L2 MCP 协议 — initialize + tools/list = 38

用真实 Claude Code 走 MCP（驱动分层：工具可用性验「能调通」用真实 CC）：

```bash
# 准备 MCP 配置（同源 HTTP，不污染全局）
cat > .tmp-gs-mcp.json <<'EOF'
{ "mcpServers": { "arthas-gw": { "type": "http", "url": "http://127.0.0.1:8761/mcp" } } }
EOF

# 真实 CC 经 MCP 枚举工具（= initialize + tools/list）
claude -p --mcp-config .tmp-gs-mcp.json --permission-mode bypassPermissions \
  "列出 arthas-gw 暴露的全部 MCP 工具名，仅输出按字母排序的 JSON 字符串数组。"
```

实测返回 **38 个工具**：
```
["arthas-gateway_list-targets","arthas-gateway_task-cancel","arthas-gateway_task-get",
 "arthas-gateway_task-list","classloader","dashboard","dump","getstatic","heapdump","jad",
 "jvm","k8s_ensure-arthas-mcp","k8s_list-pods","k8s_list-services","mbean","mc","memory",
 "monitor","ognl","options","perfcounter","profiler","redefine","retransform","sc","sm",
 "stack","stop","sysenv","sysprop","thread","trace","tt","version","viewfile","vmoption",
 "vmtool","watch"]
```
组成 = **4** `arthas-gateway_*`（自有）+ **3** `k8s_*`（003 编排）+ **31** arthas（诊断）= 38。

### L3 单工具真实诊断 — jvm

```bash
claude -p --mcp-config .tmp-gs-mcp.json --permission-mode bypassPermissions \
  "调用 arthas-gw 的 jvm 工具，target=order-service。返回 MACHINE-NAME/VM-VERSION/线程数/堆 used。"
```

实测（节选）：
```
| MACHINE-NAME | 282276@DESKTOP-O8RUTFP |
| VM-VERSION   | 21.0.5+9-LTS-239 (HotSpot 64-Bit Server VM) |
| 线程数       | 37（daemon 34，峰值 37，死锁 0） |
| 堆 used      | 60,529,496 B ≈ 57.7 MB（committed 68 MB / max 7.94 GB） |
```

→ **L1+L2+L3 全过 = MCP 可用 ✓**（结果来自 order-service 真实 JVM，非桩）。

---

## 5. 远端 K8S 调用场景设计（重点章节）

> 这是网关的精华场景（003 SC-001）：**网关在 K8S 集群外运行**，经 kubeconfig + NodePort 编排远端 pod，对 pod 内 JVM 注入 arthas 并诊断。

### 5.1 真实拓扑

```
┌─────────────────────────┐         kubeconfig          ┌──────────────────────────┐
│  本机（Windows / 网关）  │  ────────────────────────►  │  debian 服务器 (K3S node) │
│  arthas-mcp-gateway.jar │   https://192.168.31.92:6443 │  192.168.31.92            │
│  :8761/mcp              │                              │  pod: demo-business       │
│  kubeconfig:            │  ◄──── NodePort 30000-32767 ─│  (JVM 21, OrderService)   │
│  test-env/k8s/kubeconfig│   arthas MCP via NodePort    │  arthas-boot.jar (注入)   │
└─────────────────────────┘                              └──────────────────────────┘
        ▲
        │ MCP (Streamable HTTP)
        ▼
   Claude Code 客户端
```

要点：
- **网关集群外运行**（本机），不进 K8S——验证「集群外客户端 + 远程编排」真实拓扑。
- **kubeconfig**：`test-env/k8s/setup.sh` 从远端 root-on-node 派生 admin 凭证导出到本机（server 已改 `https://192.168.31.92:6443`，**不建 RBAC**，MVP 后置）。
- **NodePort**：ensure 时自动从 `arthas-gateway.k8s.node-port-range=30000-32767` 分配，暴露 pod 内 arthas MCP（`--target-ip 0.0.0.0` → wildcard LISTEN，NodePort 可达；详见 003 research.md R4）。
- **arthas 不在镜像内**：ensure 时经 fabric8 exec 上传 `tools/arthas-boot.jar`（静态文件，入 git）使用。

### 5.2 kubeconfig 准备（一次性，幂等）

```bash
# 0.（一次性）预置 k3s 离线资源（防 github 间歇不可达，带重试）
bash reference/k3s/fetch.sh

# 1. 一键搭建（本机 Git Bash，内部经 on-debian SSH 远程操作 debian）
#    顺序：mvn test-compile 产 demo class → ship 到 debian → 离线装 k3s（瘦身+tls-san）
#         → docker build demo 镜像 → k3s ctr import → kubectl apply demo pod → 导出 kubeconfig
bash test-env/k8s/setup.sh
```

实测预期：
- `demo-business` pod `Running`/`Ready`（含 shell+java+JVM；arthas 不在镜像）。
- 本机 `test-env/k8s/kubeconfig/k3s-admin.yaml` 生成（= root-on-node 派生 admin）。
- `kubectl --kubeconfig test-env/k8s/kubeconfig/k3s-admin.yaml get pods` 见 `demo-business`。

清理：`bash test-env/k8s/teardown.sh`。

### 5.3 启网关（K8S 配置已内建）

`src/main/resources/application.yml` 已含：
```yaml
arthas-gateway:
  k8s:
    kubeconfig: test-env/k8s/kubeconfig/k3s-admin.yaml   # 相对工程根
    namespace: default
    node-port-range: 30000-32767
    ensure-timeout: 5m
```

启动日志会打印 `KubernetesClient 已构建（kubeconfig=...k3s-admin.yaml, master=https://192.168.31.92:6443/）`。场景 A 的 `gateway-start.sh` 同一网关即带 K8S 能力（38 工具含 `k8s_*`）。

### 5.4 验证流程（list-pods → ensure → 远程诊断 → 纳管）

#### ① list-pods — 枚举可诊断 pod

```bash
claude -p --mcp-config .tmp-gs-mcp.json --permission-mode bypassPermissions \
  "调用 arthas-gw 的 k8s_list-pods 工具 namespace=default，返回 pod 名 + ready + hasJvm。"
```

实测：
```
| demo-business | ✅ ready=true | ✅ hasJvm=true（hasShell=true） |
```
→ `demo-business` 含 JVM + shell，可被 arthas 诊断。

#### ② ensure-arthas-mcp — 注入 arthas + NodePort 暴露 + 注册网关

```bash
claude -p --mcp-config .tmp-gs-mcp.json --permission-mode bypassPermissions \
  "调用 k8s_ensure-arthas-mcp，pod=demo-business, server=debian, namespace=default。返回 target/status/mcpUrl。"
```

实测（**原子幂等**：注入 arthas + 起 arthas MCP + NodePort 暴露 + 健康检查 + 动态注册进网关）：
```
| target   | debian-demo-business |
| status   | ready                |
| mcpUrl   | http://192.168.31.92:30415 |   ← NodePort 路由到 pod 内 arthas MCP
| namespace| default              |
```
→ 新 target `debian-demo-business` 进注册表（`source=DYNAMIC`），`arthas-gateway.list-targets` 立即可见。

#### ③ 远程诊断 — 经网关 → NodePort → pod JVM

```bash
claude -p --mcp-config .tmp-gs-mcp.json --permission-mode bypassPermissions \
  "用 target=debian-demo-business 调 jvm，返回 MACHINE-NAME 与 VM-VERSION。"
```

实测：
```
| MACHINE-NAME | 1@demo-business              |   ← pod 内容器真实主机名
| VM-VERSION   | 21.0.11+10-LTS（Eclipse Adoptium）| ← pod JVM 真实版本
旁证：OS=Linux/amd64、JVM-START-TIME=2026-06-22 19:34:58、statusCode=0、success=true
```
→ **远程 pod JVM 诊断完全可达**（结果来自远端 debian 上的 pod，非本机）。

#### ④ 纳管确认

```bash
curl -s http://127.0.0.1:8761/actuator/health | python -m json.tool | grep -A3 debian-demo-business
```
实测 `debian-demo-business` 出现在 `backendRegistry.details.backends`，`state=ACTIVE/healthy=true`。

### 5.5 故障韧性（SC-003，破坏性，按需）

```bash
# 模拟 K8S 驱逐：删 pod（on-debian 经 SSH 远程 kubectl）
ssh root@192.168.31.92 'kubectl delete pod demo-business'
```

预期（003 SC-003，已验证）：
- 网关 **30s 内**把 `debian-demo-business` 标 `healthy=false`（复用 001 健康监控/熔断）。
- 对该 target 诊断 → 结构化错误（INVALID_PARAMS + `reason:backend_unreachable`）。
- **其他 target（payment/order-service）不受影响**（隔离）。
- pod 重新部署后再次 `ensure-arthas-mcp` → `status:ready` 恢复纳管。

> 此步破坏性，文档引用 003 SC-003 实证；首跑可跳过。

---

## 6. 全面测试用例（分层 + 能力矩阵）

### 6.1 测试分层

| 层 | 目的 | 驱动 | 命令 | 实测结果 |
|----|------|------|------|----------|
| **L0** 构建测试 | 编译 + 单测 + 契约 IT 全绿 | `./mvnw verify` | `./mvnw clean verify` | 253 测试 / 0 失败 / 0 错误 |
| **L1** 健康检查 | 网关与后端可达 | Actuator | `curl /actuator/health` | `status:UP`，后端 healthy |
| **L2** MCP 协议 | initialize + tools/list | 真实 CC | `claude -p` 列工具 | 38 工具 |
| **L3** 单工具冒烟 | 每能力「能调通」 | 真实 CC | `claude -p` 调各工具 | jvm/watch/k8s.* 等真实返回 |
| **L4** 契约/一致性 | 双侧协议 + 结果 A/B 一致 | 官方 MCP Java SDK client | `./mvnw verify -Dtest="..contract.."` | `*ContractIT` 全绿 |
| **L5** 故障注入 | 真实故障条件（禁桩） | 真实 CC + 真实故障 | 停后端/删 pod/错 token | 结构化错误 + 熔断 + 隔离 |
| **L6** 端到端 | Claude 编排全链路 | 真实 CC | `claude -p` 多步编排 | SC-001 5min 闭环 |

> 驱动分层（CLAUDE.md）：**工具可用性**用真实 CC（仅验「能调通」）；**结果一致性 + 双侧协议契约**用官方 MCP Java SDK client（确定性断言）。除健康检查外，**不 curl 裸打 MCP**。

### 6.2 能力 → 测试用例矩阵

| 能力 | 验证用例 | 命令（要点） | 期望（实测锚点） |
|------|----------|--------------|------------------|
| 诊断 — `jvm` | L3 单工具 | `jvm target=order-service` | 返 MACHINE-NAME/VM-VERSION/线程/堆 |
| 诊断 — `watch`（异步） | L3 任务系统 | `watch target=... numberOfExecutions=1` | 立即返 `taskId`+`working`，完成→`COMPLETED` |
| 任务系统 | L3 | watch → `task-get`/`task-list` | taskId 可查、`task-list` 含该任务 |
| 多目标路由 | L3 | jvm 分别 `target=order-service`/`payment` | 结果各属正确目标 JVM |
| 热重载 | L3 | 编辑 `backends.yaml` 增/删后端 | ≤30s `list-targets` 反映变化 |
| 单点故障隔离 | L5 | 停 order-service | payment 诊断正常；order 诊断 30s 内明确错误 + 熔断 |
| **K8S list-pods** | L3 | `k8s_list-pods namespace=default` | 列 pod + ready + hasJvm |
| **K8S ensure** | L3 | `k8s_ensure-arthas-mcp pod=demo-business server=debian` | `status:ready` + mcpUrl + 注册 |
| **K8S 远程诊断** | L3 | jvm `target=debian-demo-business` | pod JVM 真实信息（1@demo-business） |
| **K8S 故障韧性** | L5 | 删 demo-business pod | 30s 隔离 + 熔断 + 其他 target 不受影响 |
| portal — 后端 CRUD | L3 | 浏览器 `/backends` 增删改 | 写 YAML 热重载 + 列表刷新 |
| portal — 任务列表 | L3 | 浏览器 `/tasks` 或 `GET /admin/tasks` | 列表摘要 + 过滤 + 分页 + 点项导出 |
| portal — 结果导出 | L3 | `GET /admin/tasks/{id}/export` | 下载 JSON（含 frames 原样） |

### 6.3 故障注入用例（L5，真实故障条件，禁桩）

| 场景 | 真实故障实现 | 期望 |
|------|--------------|------|
| 后端不可达 | 停 order-service 后端 | 诊断 → `backend_unreachable`，30s 内熔断 OPEN |
| 认证失败 | `auth.mode=BEARER` + 错误 token | 诊断 → arthas 真实 401 |
| 慢响应 | 业务方法 `Thread.sleep` | 超阈值 → 超时错误（不计熔断） |
| 并发越界 | 真实发起 >`maxConcurrentTasks` 并发 | arthas 真实 INVALID_PARAMS / 排队 |
| K8S pod 删除 | `kubectl delete pod` | 30s 隔离 + 熔断 + 隔离其他 target |

---

## 7. 排查指引

| 现象 | 检查 |
|------|------|
| Claude Code 看不到工具 | 网关是否起（L1）、`tools/list` 是否 38（L2）、mcp-config URL 是否 `http://127.0.0.1:8761/mcp` |
| `k8s_*` 工具报 kubeconfig 错 | `test-env/k8s/kubeconfig/k3s-admin.yaml` 是否存在、`application.yml` 路径、远端 192.168.31.92:6443 可达 |
| ensure 返失败 | pod 是否 `Ready` + `hasJvm` + `hasShell`（list-pods 看）、`tools/arthas-boot.jar` 是否在 |
| 远程诊断超时 | NodePort 是否可达（`curl http://192.168.31.92:<nodeport>`）、pod 内 arthas 是否 `0.0.0.0` 监听 |
| 异步任务一直 working | 后端可达性、`task-get` 的 `error.reason`、后台是否超 11min 标 failed |
| portal 页面 404（`/backends` reload） | `SpaConfig` 是否注册（Vue Router history 模式 fallback，004 INV-WEB-1） |

详细定位见 `reference/arthas-docs/03-MCP/问题定位反向索引.md`。

---

## 8. 清理

```bash
bash smoke/gateway-stop.sh          # 停本地网关 + 双后端
bash test-env/k8s/teardown.sh       # （远端 K3S）卸载 k3s + 删导出凭证
rm -f .tmp-gs-mcp.json              # 删临时 MCP 配置
```

---

## 附录：实测环境快照（2026-07-08）

| 项 | 值 |
|----|-----|
| 网关 | `arthas-mcp-gateway-0.1.0-SNAPSHOT.jar`（Spring Boot 4.1.0 / Spring AI 2.0.0 / MCP SDK 2.0.0） |
| 本机 | Windows 11 + Git Bash + JDK 21.0.5 |
| 远端 K3S | debian 13（kernel 6.12.73）+ k3s v1.35.5（node 192.168.31.92） |
| 业务 pod | `demo-business`（JVM 21.0.11+10-LTS，Eclipse Adoptium，`OrderService.hotMethod`） |
| 工具数 | 38（4 自有 + 3 K8S + 31 arthas） |
| portal | Vue 3 SPA 内嵌单 JAR，`/backends` + `/tasks`（含任务列表） |
```

### `docs/test-fixtures.md`

```markdown
# 真实测试夹具使用指南

> 测试夹具 = 冒烟/集成测试用的**真实业务后端**（真实 arthas MCP + 真实业务 JVM），**非桩**。本文档说明夹具结构、编译方式、使用场景与真实性证据。
>
> **宪法原则七（CLAUDE.md 真实性硬约束）**：每次测试至少启动一个真实 arthas MCP + 一个真实业务服务；诊断数据由触发业务服务真实调用产生；故障类用真实故障条件（停后端=不可达、错 token=arthas 真实 401、`Thread.sleep`=慢响应、真实并发越界）。夹具即承载这套真实性的代码。

---

## 1. 两层夹具结构

### 1.1 核心夹具 — `src/test/java/com/arthas/gateway/testfixtures/`

由 `mvn test-compile` 编到 `target/test-classes/`（标准 Maven 测试编译，无需额外配置）。

| 类 | 职责 |
|----|------|
| `ArthasMcpBackend` | 拉起**一个真实 arthas MCP 后端**（业务 JVM + arthas http console `/mcp`）；冒烟与契约 IT 共用 |
| `DemoBusinessApp` | 真实业务 JVM（含 `OrderService.hotMethod` 自驱动 hotLoop + `/health` + `/order` HTTP 端点） |
| `OrderService` | 真实业务类（`hotMethod` 被 arthas `watch`/`trace` 捕获） |
| `OrderResult` | 业务返回值（`watch` 结果含 `accessPoint`/`className`/`value`） |
| `McpClientHarness` | 官方 MCP Java SDK client 封装（契约 IT / 一致性 A/B 驱动） |
| `FakeBackendClient` | 测试用 backend client（**测试 client，非桩后端**） |

### 1.2 冒烟启动器 — `smoke/`

**不在 `src/`，Maven 不编译**；需手动 `javac` 到 `target/smoke-classes/`。

| 文件 | 职责 |
|------|------|
| `SmokeDemoLauncher.java` | 一键拉起**双真实后端**（order-service + payment）+ 写 `config/backends-runtime.yaml`（动态端口）+ 常驻 |
| `SmokeMcpClient.java` | MCP client 冒烟（连网关调工具） |
| `SmokeWatchAsync.java` | 异步 `watch` 冒烟 |
| `gateway-start.sh` | 一键启动：双后端 + 网关（用 `target/test-classes;target/smoke-classes`） |
| `gateway-stop.sh` | 停全部相关 java 进程 |
| `r4-bind-test.sh` | arthas NodePort 绑定地址实证（003 R4） |

---

## 2. 编译步骤（全新环境复现）

### 2.1 核心夹具（Maven 编译）

```bash
./mvnw test-compile
# 产出 target/test-classes/com/arthas/gateway/testfixtures/*.class
```

> `mvn verify` / `mvn package` 也含 `test-compile`，无需单独跑。

### 2.2 SDK classpath 快照（`target/sdk-cp.txt`）

冒烟启动器 `javac` 需 MCP SDK 依赖路径（`SmokeMcpClient` 引用 `McpSyncClient` 等）：

```bash
./mvnw -q dependency:build-classpath -Dmdep.outputFile=target/sdk-cp.txt
# 产出 target/sdk-cp.txt（~14KB，含 MCP SDK + Spring 依赖路径，分号分隔）
```

### 2.3 冒烟启动器（手动 javac）

```bash
J21="/c/Program Files/Java/jdk-21"          # JDK 21 路径（按你本机调整）
mkdir -p target/smoke-classes
CP="target/test-classes;$(cat target/sdk-cp.txt)"   # Windows/Git Bash 用 ; 分隔（Linux/Mac 用 :）
"$J21/bin/javac" -cp "$CP" -d target/smoke-classes \
  smoke/SmokeDemoLauncher.java smoke/SmokeMcpClient.java smoke/SmokeWatchAsync.java
# 产出 target/smoke-classes/com/arthas/gateway/smoke/*.class
```

> 注：`javac` 会打印「无注解处理器」提示，无害（不影响产物）。

### 2.4 一键启动（自动用上述产物）

```bash
bash smoke/gateway-start.sh
# 内部执行：
#   java -Dbasedir=<ROOT> -cp "target/test-classes;target/smoke-classes" com.arthas.gateway.smoke.SmokeDemoLauncher
#   java -jar target/arthas-mcp-gateway-0.1.0-SNAPSHOT.jar --arthas-gateway.backends-file=config/backends-runtime.yaml
```

⚠️ `gateway-start.sh` 假设 `target/smoke-classes` **已编译**。全新环境首次须先跑 §2.1–2.3。

---

## 3. K8S 场景夹具（ship 到远端 pod 镜像）

K8S 测试床把核心夹具的 **3 个运行时类**（`DemoBusinessApp` + `OrderService` + `OrderResult`）ship 到 debian 服务器，打成 docker 镜像，import k3s，apply 为 `demo-business` pod：

```bash
bash test-env/k8s/setup.sh
# 顺序：mvn test-compile 产 demo class → ship 到 debian → docker build 镜像
#      → k3s ctr import → kubectl apply demo pod → 导出 root 派生 admin kubeconfig
```

要点：
- 镜像**仅 3 个业务类**（不含 `ArthasMcpBackend`/`McpClientHarness`/`*Test`）。
- **arthas 不在镜像**：`k8s.ensure-arthas-mcp` 时经 fabric8 exec 上传 `tools/arthas-boot.jar`（静态文件，入 git）使用。
- 清理：`bash test-env/k8s/teardown.sh`。

---

## 4. 真实性证据（实测，非桩）

`jvm` 工具对夹具后端返回的诊断含**该业务 JVM 自身真实数据**：

| 字段 | 实测值（证明真实） |
|------|---------------------|
| `INPUT-ARGUMENTS` | `[-Ddemo.slowMs=0]`（`DemoBusinessApp` 启动参数） |
| `CLASS-PATH` | `…target/test-classes`（夹具 classpath） |
| `MACHINE-NAME` | `<pid>@<host>`（真实进程 ID + 主机名） |
| `VM-VERSION` | `21.0.5+9-LTS` / `21.0.11+10-LTS`（本机/远端 pod 各自 JVM） |
| `watch` 结果 | `OrderService.hotMethod` 真实 `accessPoint`/`cost`/`value` |

→ 这些数据**只能**来自运行中的真实 JVM，桩无法伪造。

---

## 5. 文件位置总览

| 路径 | 说明 | 入 git |
|------|------|--------|
| `src/test/java/com/arthas/gateway/testfixtures/` | 核心夹具**源码** | ✓ |
| `smoke/` | 冒烟启动器**源码** + 启停脚本 | ✓ |
| `tools/arthas-boot.jar` | 静态 arthas 工具（夹具 + K8S ensure 用） | ✓（例外：非构建产物的工具文件） |
| `target/test-classes/` | `mvn test-compile` 产物 | ✗ gitignored |
| `target/smoke-classes/` | 手动 `javac` 产物 | ✗ gitignored |
| `target/sdk-cp.txt` | SDK classpath 快照 | ✗ gitignored |
| `config/backends-runtime.yaml` | `SmokeDemoLauncher` 每次启动按动态端口重写 | ✗ gitignored |

> 全部产物在 `target/`（gitignored），全新环境照 §2 重编即可复现。源码（夹具 + smoke + arthas-boot.jar）全入库，clone 后即可用。
```

## spec-kit 模板与记忆（.specify/）


---

### `.specify/extensions/agent-context/commands/speckit.agent-context.update.md`

```markdown
---
description: "Refresh the managed Spec Kit section in the coding agent context file"
---

# Update Coding Agent Context

Refresh the managed Spec Kit section inside the active coding agent's context/instruction file (e.g. `CLAUDE.md`, `.github/copilot-instructions.md`, `AGENTS.md`).

## Behavior

The script reads the agent-context extension config at
`.specify/extensions/agent-context/agent-context-config.yml` to discover:

- `context_file` — the path of the coding agent context file to manage.
- `context_markers.start` / `.end` — the delimiters surrounding the managed section. Defaults to `<!-- SPECKIT START -->` and `<!-- SPECKIT END -->` when the field is missing.

It then creates, replaces, or appends the managed block so that the section points at the most recent plan path when one can be discovered (`specs/<feature>/plan.md`).

If `context_file` is empty or the file cannot be located, the command reports nothing to do and exits successfully.

## Execution

- **Bash**: `.specify/extensions/agent-context/scripts/bash/update-agent-context.sh [plan_path]`
- **PowerShell**: `.specify/extensions/agent-context/scripts/powershell/update-agent-context.ps1 [plan_path]`

When `plan_path` is omitted, the script auto-detects the most recently modified `specs/*/plan.md`.
```


---

### `.specify/extensions/agent-context/README.md`

```markdown
# Coding Agent Context Extension

This bundled extension manages the **coding agent context/instruction file** (e.g. `CLAUDE.md`, `.github/copilot-instructions.md`, `AGENTS.md`, `GEMINI.md`, …) for the active integration.

It owns the lifecycle of the managed section delimited by the configurable start/end markers (defaults: `<!-- SPECKIT START -->` / `<!-- SPECKIT END -->`).

## Why an extension?

Not every Spec Kit user wants Spec Kit to write into the coding agent's context file. Extracting this behavior into a dedicated extension lets users:

- **Opt out** entirely with `specify extension disable agent-context` — Spec Kit will then never create or modify the agent context file.
- **Customize the markers** by editing `.specify/extensions/agent-context/agent-context-config.yml` — both the Python layer and the bundled scripts honor the same `context_markers` value.
- **Refresh on demand** with `/speckit.agent-context.update`, or automatically through the hooks declared in `extension.yml` (`after_specify`, `after_plan`).

## Commands

| Command | Description |
|---------|-------------|
| `speckit.agent-context.update` | Refresh the managed section in the agent context file with the current plan path. |

## Configuration

All configuration flows through the extension's own config file at
`.specify/extensions/agent-context/agent-context-config.yml`:

```yaml
# Path to the coding agent context file managed by this extension
context_file: CLAUDE.md

# Delimiters for the managed Spec Kit section
context_markers:
  start: "<!-- SPECKIT START -->"
  end: "<!-- SPECKIT END -->"
```

- `context_file` — the project-relative path to the coding agent context file, written by `specify init` and `specify integration install`.
- `context_markers.start` / `.end` — the delimiters around the managed section. Edit these to use custom markers.

## Requirements

The bundled update scripts require **Python 3** with **PyYAML** for YAML/upsert processing (PowerShell can also use `ConvertFrom-Yaml` when available).

PyYAML ships with the `specify` CLI and is normally available via the same `python3` interpreter. If a hook reports *"PyYAML is required … not available in the current Python environment"*, it means the system `python3` differs from the one used to install Spec Kit. To resolve, run:

```bash
pip install pyyaml
# or target the specific interpreter Spec Kit uses:
/path/to/speckit-python -m pip install pyyaml
```

## Disable

```bash
specify extension disable agent-context
```

When disabled, Spec Kit skips context file creation, updates, and removal (the gates are inside `upsert_context_section()` and `remove_context_section()`).
```


---

### `.specify/memory/constitution.md`

```markdown
<!--
==============================================================================
同步影响报告（SYNC IMPACT REPORT）—— 修订日志（最新在上）

【v1.2.0 — MINOR，2026-06-19】
版本变更：1.1.0 → 1.2.0
升级依据：MINOR——细化既有原则二的"定义获取机制"，保留"透明无损聚合"核心
意图，未移除或重定义任何原则。
修改的原则：
  - 二、透明无损的聚合：将"定义来源"由"从各后端获取并原样转发"改为
    "对 arthas 源码如实摘抄（静态拷贝）、不从各后端动态发现"；原则核心
    （不改变诊断语义、调用结果原样透传、映射确定性且可被发现）不变。
触发原因：/speckit-clarify 阶段确认——网关对 tool/resource/prompt 的"定义"
  采用摘抄拷贝自 arthas 源码的方式（不从各后端动态发现），"调用"仍按
  target 路由转发到后端执行。该决策与原则二旧表述冲突，故正式修订。
对下游文档的影响：
  - spec.md FR-008 须同步修订（由"忠实转发各后端暴露的定义"改为"取自
    arthas 规范定义、静态、非逐后端动态发现"）；本次 /speckit-clarify 已一并落盘。
  - spec.md 边缘情况"工具集不一致"与相关假设须同步对齐（已落盘）。
  - 原则八（先决研究）更关键：摘抄须如实，须吃透 arthas-mcp-server 的工具/
    资源/提示词定义；原则八表述本身无需改动。
模板更新：
  - .specify/templates/plan-template.md  → ✅ 无需修改（Constitution Check 运行时读取本文件）
  - .specify/templates/spec-template.md  → ✅ 无需修改
  - .specify/templates/tasks-template.md → ✅ 无需修改
延期事项：无。

【v1.1.0 — MINOR，2026-06-19】
版本变更：1.0.0 → 1.1.0
升级依据：MINOR——新增三条核心原则，未移除或重定义任何既有原则。
新增原则：
  - 六、Java 作为主力开发语言（不可妥协）
  - 七、测试驱动开发（不可妥协）
  - 八、Arthas MCP 实现的先决研究
其它修改：将「开发流程与质量门禁」中的 TDD 表述统一指向原则七。
对下游文档的影响：plan.md 的 Constitution Check 须新增对原则六/七/八的核验；
  原则八要求 Phase 0 先决研究产出（成果汇入 research.md），该环节已内置于
  /speckit-plan 工作流，无需改模板。
模板更新：
  - .specify/templates/plan-template.md      → ✅ 无需修改（Constitution Check 运行时读取本文件）
  - .specify/templates/spec-template.md      → ✅ 无需修改
  - .specify/templates/tasks-template.md     → ✅ 无需修改
延期事项：无。

──────────────── 历史记录 ────────────────

【v1.0.0 — 语言本地化（版本不变），2026-06-19】
  将治理文档本地化为中文以符合 CLAUDE.md 语言规范，未改变任何原则语义。

【v1.0.0 — 初始填充，2026-06-19】
版本变更：(未初始化模板) → 1.0.0
升级依据：从空白模板首次填充治理文档。按 SemVer，治理文档的首次发布为 v1.0.0。
修改的原则：
  - [PRINCIPLE_1_NAME] → 一、MCP 规范符合性（不可妥协）
  - [PRINCIPLE_2_NAME] → 二、透明无损的聚合
  - [PRINCIPLE_3_NAME] → 三、连接生命周期与局部故障韧性
  - [PRINCIPLE_4_NAME] → 四、双侧契约优先的测试
  - [PRINCIPLE_5_NAME] → 五、可观测性与可诊断性
新增章节：核心原则（一至五）、技术与传输约束、开发流程与质量门禁、治理
移除章节：无（保留模板骨架）。
模板更新：plan-template / spec-template / tasks-template 均 ✅ 无需修改。
延期事项：无。
==============================================================================
-->

# Arthas MCP 网关宪法

## 核心原则

### 一、MCP 规范符合性（不可妥协）

网关同时在**两个 MCP 接口**上运作——一个是连接 N 个 arthas MCP 后端的上游
客户端，另一个是被 Claude Code 消费的下游服务端。这两个接口都必须符合已发布的
Model Context Protocol 规范。

- 线上传输格式必须是 MCP 所定义的 JSON-RPC 2.0。
- 生命周期必须被正确实现：在每种传输方式上完成 `initialize` → `initialized`、
  能力协商以及有序关闭。
- 协议原语（tools、resources、prompts）必须严格按规范处理；线上不得出现自定义
  原语。
- 项目必须锁定一个具体的 MCP 规范版本；任何偏离都必须是单独、有记录、有正当
  理由的例外，而非逐渐漂移。

**理由：** 网关的唯一价值在于成为一个透明、符合规范的中间层。任何偏离在开发
阶段都不可见，只会在生产环境中表现为 Claude Code 集成失效或 arthas 后端行为
异常。

### 二、透明无损的聚合

网关将多个 arthas 后端多路复用并聚合到一个 MCP 端点之后，且不改变诊断语义。

- 网关向调用方暴露的 tool、resource、prompt 定义来源于对 arthas 源码的如实
  摘抄（静态拷贝），不从各后端动态发现；该定义集必须忠实反映 arthas 的规范
  能力，不做无依据的改写。
- 可以通过命名空间（namespace）和/或目标选择参数来消除不同后端之间相同工具名
  造成的歧义，且必须遵循 MCP 的命名与参数规则。
- 调用结果必须原样透传——网关不得静默地改写、摘要、截断或丢弃结果内容。
- 工具/命名空间到后端的映射必须是确定性的，并且能通过网关自身的 MCP 接口被
  发现。

**理由：** 这些都是诊断数据。一个会篡改输出的网关，会破坏 Claude Code 与运维
人员所赖以工作的那份诊断信息。

### 三、连接生命周期与局部故障韧性

每个 arthas 后端都必须是受管理的一等对象，而不是一个临时的 socket。

- 每个后端都必须经历显式的注册、能力发现、健康监控、带退避的重连以及干净的
  下线。
- 某个后端的故障或不可用必须能优雅降级——该后端的工具变为不可用——而不得影响
  其他后端，也不得导致网关进程崩溃。
- 跨后端的并发调用不得相互阻塞；一个缓慢或卡死的后端不得拖慢网关或其他后端。

**理由：** 网关面向的是多个会动态增删的 JVM/进程。如果一个死掉的后端就能让
所有访问中断，那么这种聚合相比直连毫无价值。

### 四、双侧契约优先的测试

MCP 是作用于两个集成界面上的契约，而这份契约要用测试来保障。

- 测试必须针对网关↔arthas 界面与网关↔Claude Code 界面，分别断言报文符合
  MCP 规范。
- 协议关键行为（initialize 握手、能力协商、工具调用路由、命名空间、错误传播）
  必须有契约/时序测试覆盖，且这些测试要先于它们所验证的实现被编写。
- 任何对线上格式或路由的改动，都必须在本次改动中新增或更新相应的契约测试。

**理由：** 两个集成界面乘上一个不断演进的规范，使得静默回归成为默认结果。
锁定的契约测试是唯一可靠的防线。

### 五、可观测性与可诊断性

作为为诊断而建的基础设施，网关本身必须是可以被诊断的。

- 所有被路由的调用都必须有结构化日志。
- 每一次被路由的调用都必须可追溯：后端标识、目标选择参数、工具名以及结果都
  必须出现在日志记录中。
- MCP 错误码与后端错误必须被显式传播——绝不可被吞掉成静默的成功。
- 网关必须暴露足够的状态（已注册的后端、健康状况），让运维人员无需阅读源码
  就能理解它在做什么。

**理由：** 一个位于 Claude Code 与 arthas 之间的黑盒网关会让双方都变得不可
调试。沉默即缺陷。

### 六、Java 作为主力开发语言（不可妥协）

- 本项目所有核心代码必须以 Java（LTS）实现。
- Java 是本项目唯一的主力开发语言；非 Java 代码（构建脚本、配置、零星辅助
  脚本、文档生成工具等）仅作为辅助，且不得承载任何属于核心功能的逻辑。
- 引入任何非 Java 的运行时依赖或工具链必须给出正当理由，并在 `plan.md` 中
  记录。
- 具体的 Java 版本与构建工具约束见「技术与传输约束」章节。

**理由：** 网关面向 JVM 生态（arthas 本身即为 Java 诊断工具），与上游 arthas
实现同构；统一以 Java 为主力，可保证协议行为、依赖与运维体验的一致性，并避免
多语言带来的额外构建与维护负担。

### 七、测试驱动开发（不可妥协）

- 所有功能代码必须以 TDD（测试驱动开发）方式开发：先写失败的测试（红），再
  实现至通过（绿），再重构——严格遵循红-绿-重构（Red-Green-Refactor）循环。
- 测试必须先于其验证的实现被编写；实现开始前须先观察到测试失败（并经确认）。
- 本原则是全局规范，覆盖全部功能代码；它与原则四（双侧契约优先的测试）相互
  支撑——原则四规定"测什么"（双侧契约），本原则规定"怎么开发"（一律 TDD）。

**理由：** 网关横跨两个不断演进的集成界面，回归风险高；TDD 将"行为正确"内建
进开发流程，是与原则四的契约测试相互支撑的底线纪律。

### 八、Arthas MCP 实现的先决研究

- 在开始任何功能开发之前，必须先研读上游 Arthas MCP 的相关实现：其暴露的 MCP
  工具/资源、传输方式、报文格式、能力协商与实际运行行为。
- 该先决研究必须作为 `plan.md` 的 Phase 0 产出之一被记录（研究成果汇入
  research.md）；研究未完成不得进入实现阶段。
- 当上游实现与本宪法对 MCP 规范的理解出现差异时，必须以"如实对齐上游实际
  行为"为前提，差异及其处理方式必须在文档中显式记录。

**理由：** 网关的价值在于忠实聚合上游 Arthas MCP；不先理解上游实际实现，就
无法保证透明无损的聚合（原则二）与双侧契约一致（原则四）。

## 技术与传输约束

- **语言：** Java LTS（最低 Java 17 LTS）。Java 版本必须在构建中锁定。
- **构建：** 使用 Maven 或 Gradle，构建可复现且已锁定；同一条命令在本地与 CI
  中必须产生一致的构建结果。
- **MCP 传输：** 必须支持 `stdio`（本地 Claude Code 使用）以及符合规范的
  Streamable HTTP 传输；传输方式的选择必须由配置驱动。
- **并发：** 后端多路复用必须具备异步/非阻塞能力，使各后端相互隔离，不受彼此
  延迟影响。
- **后端注册表：** arthas MCP 端点、传输方式与标识必须声明在配置中（文件和/
  或环境变量），不得硬编码。
- **协议核心：** 优先使用官方/参考的 MCP Java SDK 来实现协议核心；不要手写
  JSON-RPC/MCP 的帧处理。

## 开发流程与质量门禁

- 遵循原则七（测试驱动开发）的 TDD 规范：所有功能代码先写失败的测试，再实现
  至通过。
- 合并前，构建、代码检查（lint）与完整测试套件必须全部通过。
- CI 必须能复现本地构建；不得存在"只在我机器上能跑"的步骤。
- 每项新能力都必须有文档；项目 quickstart 必须端到端演示一个可用的 Claude
  Code 集成。
- 提交应当小而内聚；每次改动只针对一个关注点。

## 治理

本宪法是项目最高权威文档。它凌驾于相互冲突的临时实践之上。

- 修订必须遵循本文件的 SemVer 版本规则：
  - **MAJOR（主版本）**：向后不兼容的治理变更——移除某项原则或重新定义某条
    核心规则。
  - **MINOR（次版本）**：新增原则/章节，或实质性扩展某项指导。
  - **PATCH（修订版本）**：澄清、措辞或非语义性的细化。
- 每次修订都必须更新本文件顶部的同步影响报告，并注明对 `plan.md`、`spec.md`
  或 `tasks.md` 的任何迁移影响。
- 每一份 `plan.md` 必须在 Phase 0 调研之前通过 Constitution Check 门禁，并在
  Phase 1 设计之后再次复核。
- 超出这些原则的复杂度必须在 `plan.md` 的 Complexity Tracking（复杂度追踪）
  表中给出正当理由；缺乏正当理由的复杂度属于范围之外。
- 日常开发的运行时指导位于 `CLAUDE.md`；本宪法管原则，`CLAUDE.md` 管日常机制。

**版本**：1.2.0 | **批准日期**：2026-06-19 | **最后修订**：2026-06-19
```


---

### `.specify/templates/checklist-template.md`

```markdown
# [CHECKLIST TYPE] Checklist: [FEATURE NAME]

**Purpose**: [Brief description of what this checklist covers]
**Created**: [DATE]
**Feature**: [Link to spec.md or relevant documentation]

**Note**: This checklist is generated by the `/speckit-checklist` command based on feature context and requirements.

<!-- 
  ============================================================================
  IMPORTANT: The checklist items below are SAMPLE ITEMS for illustration only.
  
  The /speckit-checklist command MUST replace these with actual items based on:
  - User's specific checklist request
  - Feature requirements from spec.md
  - Technical context from plan.md
  - Implementation details from tasks.md
  
  DO NOT keep these sample items in the generated checklist file.
  ============================================================================
-->

## [Category 1]

- [ ] CHK001 First checklist item with clear action
- [ ] CHK002 Second checklist item
- [ ] CHK003 Third checklist item

## [Category 2]

- [ ] CHK004 Another category item
- [ ] CHK005 Item with specific criteria
- [ ] CHK006 Final item in this category

## Notes

- Check items off as completed: `[x]`
- Add comments or findings inline
- Link to relevant resources or documentation
- Items are numbered sequentially for easy reference
```


---

### `.specify/templates/constitution-template.md`

```markdown
# [PROJECT_NAME] Constitution
<!-- Example: Spec Constitution, TaskFlow Constitution, etc. -->

## Core Principles

### [PRINCIPLE_1_NAME]
<!-- Example: I. Library-First -->
[PRINCIPLE_1_DESCRIPTION]
<!-- Example: Every feature starts as a standalone library; Libraries must be self-contained, independently testable, documented; Clear purpose required - no organizational-only libraries -->

### [PRINCIPLE_2_NAME]
<!-- Example: II. CLI Interface -->
[PRINCIPLE_2_DESCRIPTION]
<!-- Example: Every library exposes functionality via CLI; Text in/out protocol: stdin/args → stdout, errors → stderr; Support JSON + human-readable formats -->

### [PRINCIPLE_3_NAME]
<!-- Example: III. Test-First (NON-NEGOTIABLE) -->
[PRINCIPLE_3_DESCRIPTION]
<!-- Example: TDD mandatory: Tests written → User approved → Tests fail → Then implement; Red-Green-Refactor cycle strictly enforced -->

### [PRINCIPLE_4_NAME]
<!-- Example: IV. Integration Testing -->
[PRINCIPLE_4_DESCRIPTION]
<!-- Example: Focus areas requiring integration tests: New library contract tests, Contract changes, Inter-service communication, Shared schemas -->

### [PRINCIPLE_5_NAME]
<!-- Example: V. Observability, VI. Versioning & Breaking Changes, VII. Simplicity -->
[PRINCIPLE_5_DESCRIPTION]
<!-- Example: Text I/O ensures debuggability; Structured logging required; Or: MAJOR.MINOR.BUILD format; Or: Start simple, YAGNI principles -->

## [SECTION_2_NAME]
<!-- Example: Additional Constraints, Security Requirements, Performance Standards, etc. -->

[SECTION_2_CONTENT]
<!-- Example: Technology stack requirements, compliance standards, deployment policies, etc. -->

## [SECTION_3_NAME]
<!-- Example: Development Workflow, Review Process, Quality Gates, etc. -->

[SECTION_3_CONTENT]
<!-- Example: Code review requirements, testing gates, deployment approval process, etc. -->

## Governance
<!-- Example: Constitution supersedes all other practices; Amendments require documentation, approval, migration plan -->

[GOVERNANCE_RULES]
<!-- Example: All PRs/reviews must verify compliance; Complexity must be justified; Use [GUIDANCE_FILE] for runtime development guidance -->

**Version**: [CONSTITUTION_VERSION] | **Ratified**: [RATIFICATION_DATE] | **Last Amended**: [LAST_AMENDED_DATE]
<!-- Example: Version: 2.1.1 | Ratified: 2025-06-13 | Last Amended: 2025-07-16 -->
```


---

### `.specify/templates/plan-template.md`

```markdown
# Implementation Plan: [FEATURE]

**Branch**: `[###-feature-name]` | **Date**: [DATE] | **Spec**: [link]

**Input**: Feature specification from `/specs/[###-feature-name]/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command. See `.specify/templates/plan-template.md` for the execution workflow.

## Summary

[Extract from feature spec: primary requirement + technical approach from research]

## Technical Context

<!--
  ACTION REQUIRED: Replace the content in this section with the technical details
  for the project. The structure here is presented in advisory capacity to guide
  the iteration process.
-->

**Language/Version**: [e.g., Python 3.11, Swift 5.9, Rust 1.75 or NEEDS CLARIFICATION]

**Primary Dependencies**: [e.g., FastAPI, UIKit, LLVM or NEEDS CLARIFICATION]

**Storage**: [if applicable, e.g., PostgreSQL, CoreData, files or N/A]

**Testing**: [e.g., pytest, XCTest, cargo test or NEEDS CLARIFICATION]

**Target Platform**: [e.g., Linux server, iOS 15+, WASM or NEEDS CLARIFICATION]

**Project Type**: [e.g., library/cli/web-service/mobile-app/compiler/desktop-app or NEEDS CLARIFICATION]

**Performance Goals**: [domain-specific, e.g., 1000 req/s, 10k lines/sec, 60 fps or NEEDS CLARIFICATION]

**Constraints**: [domain-specific, e.g., <200ms p95, <100MB memory, offline-capable or NEEDS CLARIFICATION]

**Scale/Scope**: [domain-specific, e.g., 10k users, 1M LOC, 50 screens or NEEDS CLARIFICATION]

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

[Gates determined based on constitution file]

## Project Structure

### Documentation (this feature)

```text
specs/[###-feature]/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/           # Phase 1 output (/speckit-plan command)
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)
<!--
  ACTION REQUIRED: Replace the placeholder tree below with the concrete layout
  for this feature. Delete unused options and expand the chosen structure with
  real paths (e.g., apps/admin, packages/something). The delivered plan must
  not include Option labels.
-->

```text
# [REMOVE IF UNUSED] Option 1: Single project (DEFAULT)
src/
├── models/
├── services/
├── cli/
└── lib/

tests/
├── contract/
├── integration/
└── unit/

# [REMOVE IF UNUSED] Option 2: Web application (when "frontend" + "backend" detected)
backend/
├── src/
│   ├── models/
│   ├── services/
│   └── api/
└── tests/

frontend/
├── src/
│   ├── components/
│   ├── pages/
│   └── services/
└── tests/

# [REMOVE IF UNUSED] Option 3: Mobile + API (when "iOS/Android" detected)
api/
└── [same as backend above]

ios/ or android/
└── [platform-specific structure: feature modules, UI flows, platform tests]
```

**Structure Decision**: [Document the selected structure and reference the real
directories captured above]

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| [e.g., 4th project] | [current need] | [why 3 projects insufficient] |
| [e.g., Repository pattern] | [specific problem] | [why direct DB access insufficient] |
```


---

### `.specify/templates/spec-template.md`

```markdown
# Feature Specification: [FEATURE NAME]

**Feature Branch**: `[###-feature-name]`

**Created**: [DATE]

**Status**: Draft

**Input**: User description: "$ARGUMENTS"

## User Scenarios & Testing *(mandatory)*

<!--
  IMPORTANT: User stories should be PRIORITIZED as user journeys ordered by importance.
  Each user story/journey must be INDEPENDENTLY TESTABLE - meaning if you implement just ONE of them,
  you should still have a viable MVP (Minimum Viable Product) that delivers value.

  Assign priorities (P1, P2, P3, etc.) to each story, where P1 is the most critical.
  Think of each story as a standalone slice of functionality that can be:
  - Developed independently
  - Tested independently
  - Deployed independently
  - Demonstrated to users independently
-->

### User Story 1 - [Brief Title] (Priority: P1)

[Describe this user journey in plain language]

**Why this priority**: [Explain the value and why it has this priority level]

**Independent Test**: [Describe how this can be tested independently - e.g., "Can be fully tested by [specific action] and delivers [specific value]"]

**Acceptance Scenarios**:

1. **Given** [initial state], **When** [action], **Then** [expected outcome]
2. **Given** [initial state], **When** [action], **Then** [expected outcome]

---

### User Story 2 - [Brief Title] (Priority: P2)

[Describe this user journey in plain language]

**Why this priority**: [Explain the value and why it has this priority level]

**Independent Test**: [Describe how this can be tested independently]

**Acceptance Scenarios**:

1. **Given** [initial state], **When** [action], **Then** [expected outcome]

---

### User Story 3 - [Brief Title] (Priority: P3)

[Describe this user journey in plain language]

**Why this priority**: [Explain the value and why it has this priority level]

**Independent Test**: [Describe how this can be tested independently]

**Acceptance Scenarios**:

1. **Given** [initial state], **When** [action], **Then** [expected outcome]

---

[Add more user stories as needed, each with an assigned priority]

### Edge Cases

<!--
  ACTION REQUIRED: The content in this section represents placeholders.
  Fill them out with the right edge cases.
-->

- What happens when [boundary condition]?
- How does system handle [error scenario]?

## Requirements *(mandatory)*

<!--
  ACTION REQUIRED: The content in this section represents placeholders.
  Fill them out with the right functional requirements.
-->

### Functional Requirements

- **FR-001**: System MUST [specific capability, e.g., "allow users to create accounts"]
- **FR-002**: System MUST [specific capability, e.g., "validate email addresses"]
- **FR-003**: Users MUST be able to [key interaction, e.g., "reset their password"]
- **FR-004**: System MUST [data requirement, e.g., "persist user preferences"]
- **FR-005**: System MUST [behavior, e.g., "log all security events"]

*Example of marking unclear requirements:*

- **FR-006**: System MUST authenticate users via [NEEDS CLARIFICATION: auth method not specified - email/password, SSO, OAuth?]
- **FR-007**: System MUST retain user data for [NEEDS CLARIFICATION: retention period not specified]

### Key Entities *(include if feature involves data)*

- **[Entity 1]**: [What it represents, key attributes without implementation]
- **[Entity 2]**: [What it represents, relationships to other entities]

## Success Criteria *(mandatory)*

<!--
  ACTION REQUIRED: Define measurable success criteria.
  These must be technology-agnostic and measurable.
-->

### Measurable Outcomes

- **SC-001**: [Measurable metric, e.g., "Users can complete account creation in under 2 minutes"]
- **SC-002**: [Measurable metric, e.g., "System handles 1000 concurrent users without degradation"]
- **SC-003**: [User satisfaction metric, e.g., "90% of users successfully complete primary task on first attempt"]
- **SC-004**: [Business metric, e.g., "Reduce support tickets related to [X] by 50%"]

## Assumptions

<!--
  ACTION REQUIRED: The content in this section represents placeholders.
  Fill them out with the right assumptions based on reasonable defaults
  chosen when the feature description did not specify certain details.
-->

- [Assumption about target users, e.g., "Users have stable internet connectivity"]
- [Assumption about scope boundaries, e.g., "Mobile support is out of scope for v1"]
- [Assumption about data/environment, e.g., "Existing authentication system will be reused"]
- [Dependency on existing system/service, e.g., "Requires access to the existing user profile API"]
```


---

### `.specify/templates/tasks-template.md`

```markdown
---

description: "Task list template for feature implementation"
---

# Tasks: [FEATURE NAME]

**Input**: Design documents from `/specs/[###-feature-name]/`

**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: The examples below include test tasks. Tests are OPTIONAL - only include them if explicitly requested in the feature specification.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

- **Single project**: `src/`, `tests/` at repository root
- **Web app**: `backend/src/`, `frontend/src/`
- **Mobile**: `api/src/`, `ios/src/` or `android/src/`
- Paths shown below assume single project - adjust based on plan.md structure

<!--
  ============================================================================
  IMPORTANT: The tasks below are SAMPLE TASKS for illustration purposes only.

  The /speckit-tasks command MUST replace these with actual tasks based on:
  - User stories from spec.md (with their priorities P1, P2, P3...)
  - Feature requirements from plan.md
  - Entities from data-model.md
  - Endpoints from contracts/

  Tasks MUST be organized by user story so each story can be:
  - Implemented independently
  - Tested independently
  - Delivered as an MVP increment

  DO NOT keep these sample tasks in the generated tasks.md file.
  ============================================================================
-->

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and basic structure

- [ ] T001 Create project structure per implementation plan
- [ ] T002 Initialize [language] project with [framework] dependencies
- [ ] T003 [P] Configure linting and formatting tools

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core infrastructure that MUST be complete before ANY user story can be implemented

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

Examples of foundational tasks (adjust based on your project):

- [ ] T004 Setup database schema and migrations framework
- [ ] T005 [P] Implement authentication/authorization framework
- [ ] T006 [P] Setup API routing and middleware structure
- [ ] T007 Create base models/entities that all stories depend on
- [ ] T008 Configure error handling and logging infrastructure
- [ ] T009 Setup environment configuration management

**Checkpoint**: Foundation ready - user story implementation can now begin in parallel

---

## Phase 3: User Story 1 - [Title] (Priority: P1) 🎯 MVP

**Goal**: [Brief description of what this story delivers]

**Independent Test**: [How to verify this story works on its own]

### Tests for User Story 1 (OPTIONAL - only if tests requested) ⚠️

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T010 [P] [US1] Contract test for [endpoint] in tests/contract/test_[name].py
- [ ] T011 [P] [US1] Integration test for [user journey] in tests/integration/test_[name].py

### Implementation for User Story 1

- [ ] T012 [P] [US1] Create [Entity1] model in src/models/[entity1].py
- [ ] T013 [P] [US1] Create [Entity2] model in src/models/[entity2].py
- [ ] T014 [US1] Implement [Service] in src/services/[service].py (depends on T012, T013)
- [ ] T015 [US1] Implement [endpoint/feature] in src/[location]/[file].py
- [ ] T016 [US1] Add validation and error handling
- [ ] T017 [US1] Add logging for user story 1 operations

**Checkpoint**: At this point, User Story 1 should be fully functional and testable independently

---

## Phase 4: User Story 2 - [Title] (Priority: P2)

**Goal**: [Brief description of what this story delivers]

**Independent Test**: [How to verify this story works on its own]

### Tests for User Story 2 (OPTIONAL - only if tests requested) ⚠️

- [ ] T018 [P] [US2] Contract test for [endpoint] in tests/contract/test_[name].py
- [ ] T019 [P] [US2] Integration test for [user journey] in tests/integration/test_[name].py

### Implementation for User Story 2

- [ ] T020 [P] [US2] Create [Entity] model in src/models/[entity].py
- [ ] T021 [US2] Implement [Service] in src/services/[service].py
- [ ] T022 [US2] Implement [endpoint/feature] in src/[location]/[file].py
- [ ] T023 [US2] Integrate with User Story 1 components (if needed)

**Checkpoint**: At this point, User Stories 1 AND 2 should both work independently

---

## Phase 5: User Story 3 - [Title] (Priority: P3)

**Goal**: [Brief description of what this story delivers]

**Independent Test**: [How to verify this story works on its own]

### Tests for User Story 3 (OPTIONAL - only if tests requested) ⚠️

- [ ] T024 [P] [US3] Contract test for [endpoint] in tests/contract/test_[name].py
- [ ] T025 [P] [US3] Integration test for [user journey] in tests/integration/test_[name].py

### Implementation for User Story 3

- [ ] T026 [P] [US3] Create [Entity] model in src/models/[entity].py
- [ ] T027 [US3] Implement [Service] in src/services/[service].py
- [ ] T028 [US3] Implement [endpoint/feature] in src/[location]/[file].py

**Checkpoint**: All user stories should now be independently functional

---

[Add more user story phases as needed, following the same pattern]

---

## Phase N: Polish & Cross-Cutting Concerns

**Purpose**: Improvements that affect multiple user stories

- [ ] TXXX [P] Documentation updates in docs/
- [ ] TXXX Code cleanup and refactoring
- [ ] TXXX Performance optimization across all stories
- [ ] TXXX [P] Additional unit tests (if requested) in tests/unit/
- [ ] TXXX Security hardening
- [ ] TXXX Run quickstart.md validation

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories
- **User Stories (Phase 3+)**: All depend on Foundational phase completion
  - User stories can then proceed in parallel (if staffed)
  - Or sequentially in priority order (P1 → P2 → P3)
- **Polish (Final Phase)**: Depends on all desired user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational (Phase 2) - No dependencies on other stories
- **User Story 2 (P2)**: Can start after Foundational (Phase 2) - May integrate with US1 but should be independently testable
- **User Story 3 (P3)**: Can start after Foundational (Phase 2) - May integrate with US1/US2 but should be independently testable

### Within Each User Story

- Tests (if included) MUST be written and FAIL before implementation
- Models before services
- Services before endpoints
- Core implementation before integration
- Story complete before moving to next priority

### Parallel Opportunities

- All Setup tasks marked [P] can run in parallel
- All Foundational tasks marked [P] can run in parallel (within Phase 2)
- Once Foundational phase completes, all user stories can start in parallel (if team capacity allows)
- All tests for a user story marked [P] can run in parallel
- Models within a story marked [P] can run in parallel
- Different user stories can be worked on in parallel by different team members

---

## Parallel Example: User Story 1

```bash
# Launch all tests for User Story 1 together (if tests requested):
Task: "Contract test for [endpoint] in tests/contract/test_[name].py"
Task: "Integration test for [user journey] in tests/integration/test_[name].py"

# Launch all models for User Story 1 together:
Task: "Create [Entity1] model in src/models/[entity1].py"
Task: "Create [Entity2] model in src/models/[entity2].py"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (CRITICAL - blocks all stories)
3. Complete Phase 3: User Story 1
4. **STOP and VALIDATE**: Test User Story 1 independently
5. Deploy/demo if ready

### Incremental Delivery

1. Complete Setup + Foundational → Foundation ready
2. Add User Story 1 → Test independently → Deploy/Demo (MVP!)
3. Add User Story 2 → Test independently → Deploy/Demo
4. Add User Story 3 → Test independently → Deploy/Demo
5. Each story adds value without breaking previous stories

### Parallel Team Strategy

With multiple developers:

1. Team completes Setup + Foundational together
2. Once Foundational is done:
   - Developer A: User Story 1
   - Developer B: User Story 2
   - Developer C: User Story 3
3. Stories complete and integrate independently

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Verify tests fail before implementing
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- Avoid: vague tasks, same file conflicts, cross-story dependencies that break independence
```

## Claude Code 项目记忆（memory/）

### `arthas-0.0.0.0-auth-and-mcp-path.md`

```markdown
---
name: arthas-0-0-0-0-auth-and-mcp-path
description: arthas 4.3.0 HTTP 绑 0.0.0.0 强制鉴权 + MCP 在 /mcp（根路径是 Web Console）+ --password 仅首次 attach 生效
metadata: 
  node_type: memory
  type: reference
  originSessionId: 763c7b04-e00d-4a6b-b6b3-b9065def0b03
---

arthas 4.3.0 HTTP 服务器（`arthas-boot.jar <pid> --attach-only --http-port <p> --target-ip <ip>`）的三个非显而易见行为（003 特性 K8S 纳管实测确认）：

1. **MCP 端点在 `/mcp`，根路径 `/` 是 Web Console HTML 页**。POST 根路径返 Console HTML（非 MCP），POST `/mcp` 返正规 MCP JSON-RPC。MCP SDK 的 `HttpClientStreamableHttpTransport.builder(rootUrl)` 会自动追加 `/mcp`（故 001 夹具用根 URL `http://127.0.0.1:8563` 也 GREEN）。

2. **绑 0.0.0.0（外部访问）强制鉴权**：日志 `[ERROR] Listening on 0.0.0.0 is very dangerous!... a default password is generated`，不配密码则自动生成随机串，外部访问（经 NodePort / 非 127.0.0.1）返 **HTTP 401**，`www-authenticate: Bearer realm="arthas mcp"` + `Basic`。**回环 127.0.0.1 免鉴权**（001 夹具靠此）。鉴权方式：`Authorization: Bearer <password>`（password 直接当 token）或 `Basic base64(arthas:<password>)` 都 200；空用户 Basic 401。

3. **`--password <pwd>` 仅首次 attach 生效**：重 attach 到已存在 arthas 会话时**保留旧密码**，新 `--password` 被忽略（实测重 attach 后新 Bearer 仍 401）。要换密码须先彻底卸载旧会话（重启目标 pod / JVM）。

**对本项目含义**：K8S 纳管（003）必须 `--target-ip 0.0.0.0`（NodePort 可达）→ 须配 `--password <已知值>`（`arthas-gateway.k8s.arthas-password`，默认 `arthas-mcp-gateway`），并以 `BackendConfig.Auth(BEARER, password)` 注入动态后端 + 健康检查/probeHealthy 的 `httpRequestCustomizer(new BackendAuthCustomizer(auth))`。直接探活 mcpUrl 的测试也须带 Bearer（裸 McpClientHarness 会 401）。见 [[sdk2-vs-spec-divergences]]（transport 根 URL）。
```

### `arthas-no-dependency.md`

```markdown
---
name: arthas-no-dependency
description: 本工程不依赖 arthas；arthas-boot 可执行 fat jar 放 tools/
metadata: 
  node_type: memory
  type: project
  originSessionId: d9cb8eda-dfe3-4b06-9880-a42a2642a20e
---

本工程**不允许依赖 arthas**（用户约束 2026-06-20）：既不以 Maven 依赖引入 `com.taobao.arthas:arthas-boot`（不入 pom），也不依赖 `reference/arthas` 源码构建。arthas 启动器作为**可执行 fat jar（静态工具文件）**置于工程 `tools/arthas-boot.jar`，T009 ArthasMcpBackend 经 `java -jar tools/arthas-boot.jar <pid> --attach-only --http-port <mcpPort> --use-version 4.3.0` 使用。

**构件获取（T009 首测实测 2026-06-20）**：`tools/arthas-boot.jar` 是官方可执行 launcher fat jar（Main-Class=`com.taobao.arthas.boot.Bootstrap`、Implementation-Version=4.3.0、147977 字节），从 `https://arthas.aliyun.com/arthas-boot.jar` 下载——**非** Maven Central 的 `com.taobao.arthas:arthas-boot:4.3.0`（那是瘦 jar：仅 Bootstrap.class、无 Main-Class 清单、29788 字节，`java -jar` 报"没有主清单属性"，**不可用**）。launcher 不打包 arthas core，首跑经 `--use-version 4.3.0` 下载 core 4.3.0 运行时到 `~/.arthas/lib/4.3.0`。

**端点裁决（T009 首测 GREEN）**：arthas 4.3.0 MCP 端点 = **根 URL** `http://127.0.0.1:<mcpPort>`（无 `/mcp`）——对齐 reference `ArthasMcpJavaSdkIT` 实证；`backend-client-contract.md §1` 的 `/mcp` 是设计假设，以实测为准。SDK 2.0.0 ↔ arthas 4.3.0 握手 `2025-11-25` 互通、Windows 原生 attach 成功。

**Why**：用户明确否决 Maven test 依赖 + reference 离线构建方案，要求 arthas 与工程依赖严格隔离，仅作外部启动器工具。

**How to apply**：T009+ arthas attach 实现定位 `tools/arthas-boot.jar`（工程内相对路径，经 `System.getProperty("basedir")` 拼）。参考 reference `ArthasMcpJavaSdkIT` 的 attach/连接**范式**（学习用），不依赖其代码。详见设计文档 `docs/superpowers/specs/2026-06-20-arthas-test-fixture-design.md` §5。
```

### `codebase-memory-mcp-install.md`

```markdown
---
name: codebase-memory-mcp-install
description: codebase-memory-mcp 在本机的安装位置、配置方式与 Windows install 脚本坑
metadata: 
  node_type: memory
  type: reference
  originSessionId: 8e8bf12a-b54c-40da-b9dd-fc1624cf3002
---

codebase-memory-mcp（DeusData，C 写的单文件代码图谱 MCP，tree-sitter + Hybrid LSP，14 工具）在本机的安装事实：

- **二进制**：`C:/Users/beveph/.local/bin/codebase-memory-mcp.exe`（v0.8.1，~269MB，含 158 种 tree-sitter 语法 + Nomic embedding）。从 GitHub release `codebase-memory-mcp-windows-amd64.zip` 下载，SHA-256 校验（标准版 hash 见 release checksums.txt）。
- **配置**：写在本项目 `.mcp.json`（项目级，与 [[arthas-mcp-gateway]] 的 `arthas-gw` 并列），`command` 用绝对路径。未跑官方 `install`，未装全局 hooks/skills（避污染 spec-kit/superpowers 工作流）。
- **索引数据**：`~/.cache/codebase-memory-mcp/`（SQLite，WAL）。本项目已索引为 project `D-vibe_Coding-arthas-gateway`（3254 节点/9079 边，1 秒完成）。索引尊重 `.gitignore`（跳过 target/、reference/arthas/ 等）。
- **Windows install 脚本坑**：官方 `install.ps1` 把全局 MCP 写到 `~/.claude/.mcp.json`，但本机 Claude Code 实际读 `~/.claude.json` 顶级 `mcpServers` —— 位置不一致，install 装完 `/mcp` 看不到。所以走项目级 `.mcp.json` 手动配置最稳。
- **MCP 不热加载**：新加的 MCP server 不会注入运行中的 CC 会话（[[mcp-smoke-via-claude-p]] 同理）；改完 .mcp.json 需重启 CC 会话，`/mcp` 才能看到。
- **CLI 模式**（不进 CC 也能查）：`codebase-memory-mcp.exe cli <tool> '<json>'`，如 `cli get_architecture '{"project":"D-vibe_Coding-arthas-gateway"}'`、`cli search_graph '{"name_pattern":".*Gateway.*"}'`。
```

### `debian-docker-ssh-access.md`

```markdown
---
name: debian-docker-ssh-access
description: "on-debian 脚本走 SSH key 免密登录 root@192.168.31.92 (debian-docker, Debian 13)"
metadata: 
  node_type: memory
  type: reference
  originSessionId: 35652f1c-5e93-45bb-a7c2-8176ffb016e8
---

`192.168.31.92` = `debian-docker`(Debian 13 trixie,2 核 / 3.8Gi,Docker 26.1.5,跑 alist 等容器)。root 访问走 **SSH key 免密**:本机 `~/.ssh/id_ed25519` 公钥已部署到 `root@192.168.31.92:~/.ssh/authorized_keys`(部署时发现已存在,此前配过)。

便捷脚本 `C:\Users\beveph\bin\on-debian`(已在 PATH,`BatchMode=yes` 无密码落盘):
- `on-debian '<命令>'` —— 远端执行命令(也支持 `on-debian docker ps` 多参数)
- `on-debian` —— 进远端交互 shell
- `on-debian -f <本地脚本> [参数…]` —— 把本地脚本送远端用 bash 执行
- `on-debian -h` —— 帮助

首次引导用 `SSH_ASKPASS_REQUIRE=force` + `SSH_ASKPASS`(临时脚本 echo 密码,用完即删)自动喂密码登录;本机无 `sshpass`/`setsid`,该 force 方式是可行解。现 key 已就位,日常无需密码。密码不入记忆/不入库。
```

### `deletion-preference-whole-removal.md`

```markdown
---
name: deletion-preference-whole-removal
description: "清理\"相关项\"时用户偏好整体删除整个单元，而非保留单元做内部编辑"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: cec2425f-6d59-418a-9b53-99ac3239ed3c
---

当用户要求删除/清理"与 X 相关的内容"时，用户偏好**整体删除整个单元**（skill / 文件 / 目录），而不是保留单元并手术式编辑移除相关部分。

**Why:** 清理 MiniMax 相关 skill 时，用户明确说"只要有与 minimax 相关的 skill 整体删除，不要做 skill 修改"，并否决了我编辑 `frontend-dev/SKILL.md` 移除 minimax 段落、保留该 skill 的方案。

**How to apply:** 遇到"删除相关内容"请求，按"沾边即整体删"处理；对弱关联项（如仅 frontmatter 署名、单处引用）主动标注并说明，给用户纠正/恢复机会。删除不可逆，优先指明恢复途径。
```

### `fabric8-upload-needs-commons-compress.md`

```markdown
---
name: fabric8-upload-needs-commons-compress
description: "fabric8 .file().upload()/download 运行时需 commons-compress(optional,uber jar 会排除→NoClassDefFoundError);显式声明使其入包"
metadata: 
  node_type: memory
  type: reference
  originSessionId: 763c7b04-e00d-4a6b-b6b3-b9065def0b03
---

fabric8 `kubernetes-client` 的文件上传/下载(`client.pods()...file(path).upload(localPath)` / copyFile)运行时**强依赖 `org.apache.commons:commons-compress`**(用 `TarArchiveOutputStream`/`TarArchiveEntry`/`CountingOutputStream` 打 tar 流——javap `io.fabric8.kubernetes.client.dsl.internal.uploadable.PodUpload` 字节码铁证:有 `uploadTar()`、`addFileToTar()` 方法引用这些类)。

**两个坑(003 T028 实测踩中)**:
1. **fabric8 将 commons-compress 声明为 `<optional>true</optional>`**(kubernetes-client-project pom:`commons-compress.version=1.28.0`,与 fabric8 7.6.1 同版本)→ **不传递**。`mvn`/`mvnw` 测试 classpath 可能因别的传递路径碰巧带上(故 `*IT` 经 mvn 跑能过),但 **spring-boot uber jar 重打包会排除 optional 依赖** → `java -jar` 运行时缺 commons-compress → `installArthas` 上传 `NoClassDefFoundError` → K8S 纳管 ensure 失败于 `install_arthas`/`attach_failed`。
2. **诊断误导**:失败只现 `attach_failed`/`install_arthas` reason/stage,真因(异常类+堆栈)被吞——须在 catch 里 `log.warn("...", e)` 传 Throwable 才见。子代理曾误诊为 commons-compress+commons-io 双缺,实际 **commons-io 不是 fabric8 运行时依赖**(fabric8 注其为"Gradle Testing Toolkit"用,javap 证 PodUpload 不引用它),只缺 commons-compress。

**修复**:pom 显式声明 `<dependency>commons-compress 1.28.0</dependency>`(非 optional → 进 `BOOT-INF/lib`)。诊断走"先 javap 证依赖、再比 mvn classpath vs uber jar lib、再取 fabric8 pom 精确版本"——证据驱动,不盲信子代理。

**关联教训**:`mvn dependency:tree` 用 `mvn` 3.5.3 老版 + grep ANSI 输出会假阴性(报"无 commons-compress"实为假象);改用 `mvnw`(项目包装器,3.9.x)+ `jar tf uber.jar | grep` 直接查 BOOT-INF/lib 才可靠。本地 `mvn` 是 3.5.3,项目构建必须用 `./mvnw`。见 [[arthas-0-0-0-0-auth-and-mcp-path]](K8S 纳管上下文)、[[sdk2-vs-spec-divergences]]。
```

### `github-com-unreachable.md`

```markdown
---
name: github-com-unreachable
description: github.com access on this machine is INTERMITTENTLY unstable (CN network); recurs and recovers on its own
metadata: 
  node_type: memory
  type: project
  originSessionId: 7f3e69f1-a56f-41ab-af19-d8cc8f0fba38
---

`github.com` access on this machine is **intermittently unstable** (CN-region network). Symptom: HTTPS git clone/fetch, browser, and `uv ... --from git+https://github.com/...` hang ~20s then fail with "Failed to connect to github.com port 443".

Root cause (investigated 2026-06-19): **transient routing/QoS instability to github.com's IP — NOT DNS pollution, NOT a permanent block, NOT SNI filtering.** DNS returns real GitHub IPs (e.g. `20.205.243.166`). The same IP went from total timeout → 6/6 success with nothing changed in between. `api.github.com` and `codeload.github.com` are reliably reachable; only `github.com`/`gist.github.com` flake.

**Why:** When it hits a bad patch, installs and clones hang. It usually recovers within minutes on its own.

**How to apply, in order of effort:**
1. **Just retry / wait a few minutes** — most reliable, usually self-recovers. Re-run the failed command.
2. **Install-time fallback:** fetch the source tarball from codeload (which is reliable) instead of git clone — `curl -sL -o r.tar.gz https://codeload.github.com/<owner>/<repo>/tar.gz/refs/tags/<tag>` → extract → `uv tool install --from <dir> <pkg>`. Get latest tag via `curl -s https://api.github.com/repos/<owner>/<repo>/releases/latest`.
3. **Hosts pin (optional, needs admin):** pin `github.com` to a verified-fast stable IP `20.200.245.247` (genuine GitHub, ~0.65s; fallback `140.82.114.4`). Attempted 2026-06-19 but UAC was cancelled, so NOT applied. (Helper script since deleted on user request; regenerate if needed.)
4. **Proxy** (most robust long-term) — none currently running; a disabled Watt-Toolkit-style proxy entry `127.0.0.1:26799` exists but port not listening.

`uv` (Astral) is installed at `C:\Users\beveph\.local\bin` (on PATH) and manages its own Python 3.12, so the system Python 3.10.7 being old is not a blocker.
```

### `java-stdout-encoding-gbk-windows.md`

```markdown
---
name: java-stdout-encoding-gbk-windows
description: 本机 Java System.out 写重定向文件是 GBK（stdout.encoding=GBK），Read 按 UTF-8 读→乱码；须 -Dstdout.encoding=UTF-8（file.encoding 不够）
metadata: 
  node_type: memory
  type: reference
  originSessionId: 76f114b5-799a-4156-a16f-b8bcc3de4b55
---

本机（Windows 11 中文版，控制台代码页 936/GBK）上 Java（JDK 21）的输出编码特性：

- Java 18+（JEP 400）**分离了 `file.encoding` 与 `stdout.encoding`/`stderr.encoding`**。本机实测：`file.encoding=UTF-8`（Java 21 默认即 UTF-8，源码/编译解码正常，.class 里的中文是对的），但 **`stdout.encoding=stderr.encoding=native.encoding=GBK`**（随控制台 cp936）。
- `System.out`/`System.err`（`PrintStream`）用的是 **`stdout.encoding` 而非 `file.encoding`**。所以 `System.out.println("中文")` 把正确字符按 **GBK 字节**写进重定向文件 → 文件是 GBK 编码 → Claude Code 的 Read 工具按 UTF-8 读 → 乱码；`iconv -f UTF-8 -t UTF-8 file` 校验会报「无法转换」(invalid UTF-8 bytes)。
- **`-Dfile.encoding=UTF-8` 不能修复**（它不覆盖 stdout.encoding）。必须**显式** `-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8`。

**解法（已持久化）**：`setx JAVA_TOOL_OPTIONS "-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"`（用户级环境变量，已写入注册表 `HKCU\Environment`，对新终端/新 CC 会话生效）。验证：重跑后 `iconv -f UTF-8 -t UTF-8 target/smoke-watch.log` 通过、Read 显示正常中文。

注意：`setx` 不影响已开启的 shell/CC 会话（环境在启动时固定），当次会话需 `export JAVA_TOOL_OPTIONS=...` 内联覆盖。全 JVM 须 JDK 21（见 [[java-version-jdk21]]）。更彻底的「电脑配置不使用 GBK」可选启用 Windows「Beta：使用 Unicode UTF-8 提供全球语言支持」系统区域设置（改 ACP/OEMCP=65001，需重启、全局、可能影响老旧中文软件），非必须——上述环境变量已足够解决本工程场景。
```

### `java-version-jdk21.md`

```markdown
---
name: java-version-jdk21
description: 项目用 JDK 21（LTS），本机装在 C:\Program Files\Java\jdk-21；2026-06-19 由 JDK 8 切换
metadata: 
  node_type: memory
  type: project
  originSessionId: 4ce700f7-bc9c-4d0c-a0bc-ac284ea5c8ef
---

arthas-mcp-gateway 项目以 **JDK 21.0.5 LTS** 为开发/构建版本。宪法原则六只规定「最低 Java 17 LTS」，21 在允许范围内；具体版本将在 `pom.xml`（`<maven.compiler.source/release>21`）中锁定。

- 本机安装路径：`C:\Program Files\Java\jdk-21`（系统原有 JDK 8：`C:\Program Files\Java\jdk1.8.0_321`）。
- 切换方式（用户级覆盖，无需管理员、可逆）：
  - `setx JAVA_HOME "C:\Program Files\Java\jdk-21"`（用户级，覆盖系统级 JDK8）
  - `~/.bashrc`：`export JAVA_HOME='C:\Program Files\Java\jdk-21'` + `export PATH="/c/Program Files/Java/jdk-21/bin:$PATH"`；`~/.bash_profile` 里 `[ -f ~/.bashrc ] && . ~/.bashrc`。
  - JAVA_HOME 用 Windows 形式（供 Maven/IDE 的 Windows 子进程），PATH 用 MSYS 形式（供 bash 解析）。

**环境坑（重要）**：Claude Code 的 Bash 工具不复用用户 dotfiles——其 shell 继承 Claude 启动时的环境快照，既不读取 `setx` 新值，也不 source `~/.bashrc`。因此在该会话内 `java` 可能仍显示旧版本（JDK8）；需**重启 Claude** 或新开终端才生效，单条命令可 `source ~/.bashrc` 临时修正。cmd/PowerShell 的裸 `java` 仍可能命中系统 PATH 里的 Oracle `javapath`（JDK8）；JAVA_HOME=21 已覆盖 Maven/IDE。用户真实 Git Bash 经 bashrc 正确指向 21。

相关：宪法原则六 [[constitution-principles]]（Java 为主力、最低 17 LTS）。
```

### `mcp-smoke-via-claude-p.md`

```markdown
---
name: mcp-smoke-via-claude-p
description: 端到端冒烟本 MCP 网关的实操路径——claude -p 实调 + SmokeDemoLauncher 双后端 + 会话中期不能注入 MCP 工具
metadata: 
  node_type: memory
  type: reference
  originSessionId: d9cb8eda-dfe3-4b06-9880-a42a2642a20e
---

对本网关做「真实 Claude Code 实调」端到端冒烟（用户 2026-06-20 要求）的实操要点：

- **交互会话中期无法注入新 MCP 工具到当前进程的工具集**（工具集在会话启动时固定）。要「真实 Claude Code 调网关」，用 **`claude -p`（同一 CLI、同一鉴权的独立 CC 进程）**：
  `claude -p "<prompt>" --mcp-config <cfg.json> --strict-mcp-config --allowedTools "mcp__<server>__*"`
  （CC 把工具名里的 `.` 显示为 `_`，如 `arthas-gateway.list-targets` → `mcp__arthas-gw__arthas-gateway_list-targets`；通配 `mcp__arthas-gw__*` 覆盖全部）。`--output-format json` 的 `result` 字段含工具原样返回；`num_turns`/`permission_denials` 可验「真调通」。`claude` CLI 已装（2.1.183）。
- **项目级注册**：`.mcp.json`（根，`{"mcpServers":{"arthas-gw":{"type":"http","url":"http://127.0.0.1:8761/mcp"}}}`），下次开会话提示加载。
- **完整请求/响应 + 契约**：用官方 SDK client（`McpClientHarness`，非裸 curl）。未知 target 返 JSON-RPC error（`callTool` 抛 `McpError`，捕获打印）；缺必填 target 返 `isError=true`（schema 层）。
- **冒烟夹具**：`smoke/SmokeDemoLauncher.java`（复用 `ArthasMcpBackend` 起双真实后端 order-service/payment，写 `config/backends-runtime.yaml`，须 `-Dbasedir=<工程根>`；起网关 `java -jar target/arthas-mcp-gateway-0.1.0-SNAPSHOT.jar --arthas-gateway.backends-file=config/backends-runtime.yaml`）+ `smoke/SmokeMcpClient.java`（SDK 端遍历调用并打印请求/响应）。client 编译运行 cp：`target/test-classes;target/smoke-classes;$(cat target/sdk-cp.txt)`（`mvn dependency:build-classpath -Dmdep.outputFile=target/sdk-cp.txt` 取）。
- **全 JVM 须 JDK 21**（PATH 默认 java 是 1.8，见 [[java-version-jdk21]]），显式 `C:\Program Files\Java\jdk-21`。
- 报告模板/样例：`arthas-mcp-gateway-冒烟测试报告.md`。arthas MCP 端点为根 URL（无 `/mcp`），偏离见 [[sdk2-vs-spec-divergences]]。
```

### `MEMORY.md`

```markdown
# Memory Index

- [整体删除优于内部编辑](deletion-preference-whole-removal.md) — 清理"相关项"时用户要整体删整个单元，别手术式编辑保留
- [github.com 间歇性不可达](github-com-unreachable.md) — github.com 访问时好时坏（CN 网络，自愈）；坏时重试/等几分钟，或用 codeload tarball 离线装
- [specify init 非交互坑](specify-init-noninteractive.md) — spec-kit 的 specify init 在非 tty 卡死零输出；需先 git init + --ignore-agent-tools + --script sh
- [JDK 21 为项目 Java 版本](java-version-jdk21.md) — 装在 C:\Program Files\Java\jdk-21；用户级 JAVA_HOME(setx)+~/.bashrc 切换；Claude Bash 会话内需重启或 source bashrc 才生效
- [SDK2 vs spec 三偏离](sdk2-vs-spec-divergences.md) — MCP SDK 2.0.0 无 execution.taskSupport(仅内部路由)、starter stdio/HTTP 互斥(MVP 仅HTTP)、传输用 server.port+spring.ai.mcp.server.*；附服务端装配方案
- [本工程不依赖 arthas](arthas-no-dependency.md) — arthas-boot.jar 作静态工具文件放 tools/，不入 pom、不构建 reference 源码；T009 经 java -jar 使用
- [SB4 health 包迁移](sb4-health-package-moved.md) — Spring Boot 4.1.0 把 health 类从 actuate.health 迁到 boot.health.contributor（独立 spring-boot-health 模块）；写 HealthIndicator 用新包
- [MCP 冒烟走 claude -p](mcp-smoke-via-claude-p.md) — 会话中期不能注入 MCP 工具；真实 CC 实调用 claude -p --mcp-config；SmokeDemoLauncher 起双后端；报告模板 arthas-mcp-gateway-冒烟测试报告.md
- [Java 输出在本机是 GBK](java-stdout-encoding-gbk-windows.md) — stdout.encoding=GBK 非 file.encoding；System.out 写重定向文件是 GBK→UTF-8 读乱码；须 -Dstdout.encoding=UTF-8，已 setx JAVA_TOOL_OPTIONS 持久化
- [常驻进程走 nohup+&](resident-process-nohup-survives-session.md) — run_in_background 会被会话压缩回收；常驻用前台调用内 nohup+&（重父到 session，脱离 harness）；启停脚本 smoke/gateway-start.sh|stop.sh
- [一次性长命令别叠 &](run-in-background-no-ampersand.md) — run_in_background 跑 mvn verify 等一次性命令不要再加 `&`/nohup；否则通知在启动器退出(非完成)时触发，误判已完成
- [debian-docker SSH 免密路线](debian-docker-ssh-access.md) — on-debian 脚本(~/bin)走 SSH key 免密登录 root@192.168.31.92；BatchMode 无密码落盘
- [fabric8 upload 需 commons-compress](fabric8-upload-needs-commons-compress.md) — fabric8 .file().upload() 运行时需 commons-compress(fabric8 optional→uber jar 排除→NoClassDefFoundError)；pom 显式声明 1.28.0 入包；本地 mvn 是 3.5.3 老版须用 ./mvnw
- [codebase-memory-mcp 安装](codebase-memory-mcp-install.md) — 二进制在 ~/.local/bin/，配置在项目 .mcp.json；Windows install 脚本写错位置(~/.claude/.mcp.json CC 不读)走手动；改完需重启 CC 才生效
```

### `resident-process-nohup-survives-session.md`

```markdown
---
name: resident-process-nohup-survives-session
description: 让 java 进程常驻（不被会话压缩回收）的正确做法：前台调用内 nohup+&，而非 run_in_background
metadata: 
  node_type: memory
  type: project
  originSessionId: d9cb8eda-dfe3-4b06-9880-a42a2642a20e
---

让 arthas 网关 + 双后端**常驻**（survive 会话压缩/延续）的正确做法：

- **错误方式**：用 `run_in_background: true` 直接跑 `java -jar` / 启动器。这些是被 Claude 会话 harness 托管的子进程，会话压缩（context 超限→续接）或延续时会被**回收**（上一会话实测：8761/50xxx 端口全空闲、无 java 进程）。
- **正确方式**：写一个 bash 启动脚本，内部对每个 java 用 `nohup "$J21/bin/java" ... > log 2>&1 &`（非交互 bash 退出不发 SIGHUP，进程被重父到 Windows session，**不再被 harness 跟踪/回收**），再以**前台** Bash 工具调用 `bash smoke/gateway-start.sh`（脚本自身在 bounded 循环里 poll READY/health 后返回）。

**Why**：常驻 = 进程脱离 harness 进程树。`nohup+&` 的进程是会话的孤儿，不进 harness 的后台任务表，故不被压缩回收。

**How to apply**：
- 启动脚本 `smoke/gateway-start.sh`（幂等：先 `bash smoke/gateway-stop.sh` 清残留→起 SmokeDemoLauncher→poll `SMOKE_DEMO_READY`→起网关 jar→poll `:8761/actuator/health`）。
- 停止脚本 `smoke/gateway-stop.sh`（powershell 按命令行匹配 `GatewayApplication|SmokeDemoLauncher|DemoBusinessApp|arthas-boot` 杀相关 java）。
- 网关端口 8761 固定（`.mcp.json` 指向它，稳定）；后端 mcp/app 端口每次动态（SmokeDemoLauncher 写入 `config/backends-runtime.yaml`，已加 .gitignore）。
- JDK21 路径 `/c/Program Files/Java/jdk-21`（脚本内 `J21` 变量）；`JAVA_TOOL_OPTIONS` 已持久 `-Dfile.encoding=UTF-8`。
- 仍受交互会话 MCP 生命周期约束（见 [[mcp-smoke-via-claude-p]]）：进程常驻≠我当前会话能直接 `mcp__arthas-gw__*`（项目级 MCP 需 `/mcp` 一次性授权 + 新会话热载入工具集）；交互式内调用走 `claude -p --mcp-config .mcp.json`。
```

### `run-in-background-no-ampersand.md`

```markdown
---
name: run-in-background-no-ampersand
description: "run_in_background 的 Bash 不要再叠 `&`/nohup;否则任务通知在启动器退出(非命令完成)时触发"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 35652f1c-5e93-45bb-a7c2-8176ffb016e8
---

用 Bash 工具 `run_in_background: true` 跑**一次性长命令**(如 `./mvnw verify`)时,**不要**在命令里再叠 `nohup ... &`。

**Why:** `&` 把 mvn 从启动 shell 分离,启动 shell 立即返回 → harness 的「completed exit 0」通知是**启动器**退出、不是 mvn 完成。本轮 verify 就被误判「完成」,实际日志停在某个 IT 启动;需二次查进程/日志增长才发现 mvn 仍在跑。

**How to apply:** 一次性长构建/测试直接 `run_in_background: true` + 裸命令(无 `&`/nohup),harness 会跟踪真实进程并在真正完成时通知。`nohup+&` 仅用于**常驻**进程(网关/后端),让进程脱离会话托管——那是另一回事,见 [[resident-process-nohup-survives-session]]。
```

### `sb4-health-package-moved.md`

```markdown
---
name: sb4-health-package-moved
description: Spring Boot 4.x 把 actuator health 类迁出 actuate.health，独立 spring-boot-health 模块
metadata: 
  node_type: memory
  type: reference
  originSessionId: d9cb8eda-dfe3-4b06-9880-a42a2642a20e
---

Spring Boot 4.1.0（本项目用）把 actuator 的 health 类从 `org.springframework.boot.actuate.health` **迁移**到 `org.springframework.boot.health.contributor`，并拆到独立模块 `spring-boot-health`（经 `spring-boot-starter-actuator` 传递可用，jar 名 `spring-boot-health-4.1.0.jar`）。`spring-boot-actuator-4.1.0.jar` 里**完全没有** health 类。

正确导入（自定义 HealthIndicator）：
- `org.springframework.boot.health.contributor.Health`
- `org.springframework.boot.health.contributor.HealthIndicator`
- `org.springframework.boot.health.contributor.Status`

仍可用 `Health.up().withDetail(k,v).build()`、`Status.UP`（API 形状不变，只换了包）。落地见 `src/main/java/com/arthas/gateway/obs/BackendRegistryHealthIndicator.java`。

**Why**：SB4 重组了 actuator 包结构，旧 `actuate.health` 导入会编译报「程序包不存在」。我第一次写 HealthIndicator 用旧包名，编译失败一次才发现。
**How to apply**：在 SB4 项目写自定义 HealthIndicator / 用 Health/Status 时，直接用 `boot.health.contributor` 包，别用 SB3 时代的 `actuate.health`。相关版本偏离见 [[sdk2-vs-spec-divergences]]。
```

### `sdk2-vs-spec-divergences.md`

```markdown
---
name: sdk2-vs-spec-divergences
description: MCP SDK 2.0.0 / Spring AI 2.0.0 与 spec(基于 arthas 端 SDK 0.17.0)的三个偏离及服务端装配方案
metadata: 
  node_type: memory
  type: project
  originSessionId: d9cb8eda-dfe3-4b06-9880-a42a2642a20e
---

spec 001-arthas-mcp-gateway 基于对 **arthas 端 MCP SDK 0.17.0** 的研究写就，但网关用 **官方 MCP Java SDK 2.0.0 GA + Spring AI 2.0.0**。Phase 2 取证发现三处硬偏离（已与用户确认处理方式）：

1. **McpSchema.Tool 无 `execution.taskSupport` 字段**（SDK 2.0.0 移除了该 draft 扩展；0.17.0 才有）。
   - 处理（用户确认 A1）：taskSupport **仅内部路由用**——保留在 `arthas-tools.json` + `ExposedTool`，驱动 routingMode（5 个 optional → ASYNC_TASK），**不发射进 tools/list 协议**。异步行为不变（调用立即返 taskId）。S-TL-4 改为在注册表/路由层断言（T013 StaticToolRegistryTest 已覆盖）。

2. **Spring AI 2.0.0 starter 的 stdio 与 Streamable HTTP 互斥**（一个 McpServer 构造时绑定一个 transport provider，1:1；stdio 单会话 vs HTTP 多会话语义不同；同开会两个 McpSyncServer bean 冲突）。
   - 处理（用户确认 B1）：**MVP 仅 HTTP Streamable `/mcp`**，本地+远程 Claude Code 都走 HTTP。stdio 延后为独立 profile 的手动装配任务，不推翻已有工作。T016 降为单传输 + stdio 预留。

3. **传输配置属性名**：实际传输用 `server.port`/`server.address` + `spring.ai.mcp.server.streamable-http.mcp-endpoint`，**不是**自定义 `arthas-gateway.transports.http.*`（后者不接线）。已从 GatewayProperties 移除 Transports 段。

4. **Spring AI starter 默认广播全部能力 + tools.listChanged=true**（T014 RED 实测发现）：starter 的 `McpServerProperties$Capabilities`（boolean tool/prompt/resource/completion）**默认全 true**，且 `tools(Boolean)` 把布尔映射到 `ToolCapabilities.listChanged`，starter 硬调 `tools(isTool())=tools(true)` → `listChanged=true`。结果 initialize 回显全能力，与契约 S-INIT-2「仅 tools、listChanged=false、不声明 prompts/resources」冲突。
   - 处理：`GatewayMcpServerConfig#gatewayCapabilitiesCustomizer`（`@Bean @Primary McpSyncServerCustomizer`）在 `spec.capabilities(...)`（字节码 offset 557）之后、`spec.build()`（595）之前执行（offset 590 `customizer.ifPresent`），用 `spec.capabilities(ServerCapabilities.builder().tools(false).build())` 覆盖为仅 `tools[listChanged=false]`、prompts/resources/completions=null。
   - **必须 `@Primary` + 保留 `immediateExecution(true)`**：starter 自带 `servletMcpSyncServerCustomizer`（`@ConditionalOnWebApplication`、非 `@ConditionalOnMissingBean`、唯一职责 `spec.immediateExecution(true)`），与本 bean 并存成两候选；若不标 `@Primary`，`Optional<McpSyncServerCustomizer>` 多候选解析为空 → **两个都不执行**（含 starter 的 immediateExecution）。标 `@Primary` 后本 bean 赢得注入，须自行 `spec.immediateExecution(true)` 保留 WebMVC 即时执行。
   - **`logging=LoggingCapabilities[]` 残留**：即便 capabilities 锁到仅 tools，线上仍带空 logging——SDK 在 build/serve 时自动追加（非我所设 ServerCapabilities）。契约 §3 标 `logging:{}` 为可选，故接受。
   - 验证：T014 S-INIT-2 全绿。

**服务端装配方案**（Spring AI starter 原生路径）：
- 提供 `@Bean List<io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification>`，循环 `StaticToolRegistry.tools()` 构建：`new SyncToolSpecification(McpSchema.Tool, BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult>)`。
- `McpSchema.Tool.builder().name().description().inputSchema(Map<String,Object>).build()`（inputSchema 直接传 verbatim Map；无 execution）。
- handler 从 `CallToolRequest.name()` + `.arguments()` 拿工具名与入参，返回 `McpSchema.CallToolResult` → 全部委托 `ToolsCallRouter`。
- 配置：`spring.ai.mcp.server.{name=arthas-mcp-gateway, version, capabilities.tool=true(其余默认false), protocol=STREAMABLE}` + `spring.ai.mcp.server.streamable-http.mcp-endpoint=/mcp` + `server.port`。
- 协议版本 accept-list：SDK 内硬编码 `["2024-11-05","2025-03-26","2025-06-18","2025-11-25"]`（S-INIT-1 协商由 SDK 默认处理）。
- Jackson 3：包名 `tools.jackson.databind`（非 com.fasterxml），`new ObjectMapper()` 仍可用，readValue/readTree/convertValue 签名同 v2。annotations 残留 `com.fasterxml.jackson.core:annotations:2.21`。

详见 [[java-version-jdk21]]（构建环境）。```

### `specify-init-noninteractive.md`

```markdown
---
name: specify-init-noninteractive
description: spec-kit 的 specify init 在非交互会话会卡死零输出；正确调用需先 git init + --ignore-agent-tools
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 83329015-264c-4f76-a66f-cae5be1d25e6
---

跑 `specify init` 给项目初始化 GitHub spec-kit 时，在非 tty 环境（Claude Code 的 Bash 工具、CI、piped）下会**卡住且零输出**——卡在它的第一步 "Check required tools"。本次排查花了 150s+ 才定位。

**Why:** specify init 的流程依赖 git 仓库（会建分支等），且非交互下 coding-agent 工具检测会挂起等待。

**How to apply:** 在目标项目目录先 `git init`，再跑：
`specify init --here --integration claude --force --ignore-agent-tools --script sh`
- `--ignore-agent-tools`：跳过会挂起的 agent 工具检测（**不影响**安装 claude integration 命令）
- `--script sh`：适配 git bash 环境（Windows 下避免 ps 脚本）
- specify 可执行文件在 `C:\Users\beveph\.local\bin\specify.exe`；PATH 未刷新的会话里用完整路径调用，或用 `python -m uv tool run specify`
- spec-kit (0.11.x) 默认是 **skills 模式**（落在 `.claude/skills/`），命令是**连字符** `/speckit-specify` 等，**不是** README/旧文档里的点号 `/speckit.specify`
- skills 要**重启 Claude Code 会话**才会被识别（启动时才加载技能列表）

相关：[[github-com-unreachable]]
```

