# Part 16 · 5 特性 spec-kit SDD 全套文档

> 本附录摘录 001/002/003/004 四特性的 spec/plan/research/data-model/contracts/quickstart/tasks 全套 SDD 文档，作为规格级参考。


---

## `specs/001-arthas-mcp-gateway/checklists/requirements.md`

```markdown
# Specification Quality Checklist: Arthas MCP 网关

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-06-19
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- 全部检查项通过（头脑风暴阶段已澄清所有关键决策：部署形态、规模、目标区分方式、认证、技术栈）。
- 技术实现细节（Java 17 / Spring Boot 3 / Spring AI MCP / Maven）刻意未写入本 spec，留给 `/speckit-plan` 阶段（plan.md）承载，符合 spec-kit「spec 写 WHAT/WHY、plan 写 HOW」的分层。
- MCP 与 HTTP 作为用户可见的接口契约（调用方如何接入）出现在 spec，属 WHAT 范畴，非实现技术栈。
- 已记录的待办（非本 spec 范围）：宪法原则八要求的 Phase 0 先决研究——深入研读 arthas-mcp-server 源码并输出研究报告，以及验证 Spring AI MCP 网关模式的扩展可行性。
```


---

## `specs/001-arthas-mcp-gateway/contracts/backend-client-contract.md`

```markdown
# 契约：网关 ↔ arthas 后端（客户端契约）

**Feature**: 001-arthas-mcp-gateway | **Date**: 2026-06-19
**界面角色**：网关作为 **MCP 客户端**，连接并管理多个 arthas MCP 后端（每后端对应一个目标 JVM）。
**宪法依据**：原则一、原则三（连接生命周期与局部故障韧性）、原则四。

> 后端行为事实的**权威定义**见上游 [后端接入契约](../../reference/arthas-docs/03-MCP/后端接入契约.md) 与 [工具传输分类表](../../reference/arthas-docs/03-MCP/工具传输分类表.md)；本文聚焦**网关作为客户端的发送承诺与可测断言点**（WireMock 模拟后端）。实现用官方 SDK `HttpClientStreamableHttpTransport`，**不手写帧**。

---

## 1. 端点与传输

- **端点**：复用 arthas http console 端口下的 `/mcp`（**无独立 MCP 端口**）。形如 `http://host:8563/mcp`，来自 `BackendConfig.url`。
- **协议**：`STREAMABLE`（有状态，SSE，支持 task）或 `STATELESS`（无状态，纯 JSON 一来一回，**不支持 task**）。由 `BackendConfig.protocol` 决定。
- **客户端实现**：官方 SDK `HttpClientStreamableHttpTransport`，自动管理 SSE 解析与 `Mcp-Session-Id` 会话头。

---

## 2. HTTP 头承诺

每次请求网关保证发送：

| 头 | 值 | 说明 |
|---|---|---|
| `Accept` | `application/json, text/event-stream` | 允许后端返回 JSON 或 SSE（Streamable） |
| `Content-Type` | `application/json` | JSON-RPC 请求体 |
| `Authorization` | `Bearer <token>`（BEARER）/ `Basic <base64(user:pass)>`（BASIC）/ 无（NONE） | token == 后端 password |
| `X-User-Id` | 调用方标识（若有） | **仅追踪**，不参与认证 |
| `Mcp-Session-Id` | initialize 响应返回的会话 id | 后续请求自动回带（SDK 管理） |

> 自定义头经官方 SDK 的 `httpRequestCustomizer(...)` 注入（**非已弃用的 `customizeRequest()`**）。

---

## 3. initialize 握手（网关作为客户端→后端）

1. POST `/mcp` `initialize`，`protocolVersion`=`2025-11-25`，`Accept` 含 json+SSE。
2. 后端响应 `InitializeResult` + 头 `Mcp-Session-Id` → 网关保存。
3. 网关发 `notifications/initialized`（带 `Mcp-Session-Id`）。
4. 之后 `tools/call` 均带该 session id。

- `STATELESS` 后端：无 `Mcp-Session-Id`，纯 JSON 一来一回。
- initialize 超时对齐后端 `initializationTimeout=30s`；连接超时默认 5s。

---

## 4. tools/call 转发

### 4.1 同步路①（即时工具 + dashboard + **optional 工具的异步后台**）

- POST `tools/call`，`params.name` == 原工具名（如 `jvm`/`watch`），`params.arguments` == **剥离 target 后**的剩余键。
- **optional 工具异步后台**：不带 `task` 字段 → 后端自动轮询，阻塞同步返回最终 `CallToolResult`（后端上限 10 分钟，网关后台兜底 11 分钟）。
- dashboard：响应为 SSE 多帧 → 网关聚合为一次 `CallToolResult`。

### 4.2 结果处理

- 后端返回的 `CallToolResult`（content/isError/_meta）**原样**存入/返回（FR-004）。
- 后端 JSON-RPC error（如 INVALID_PARAMS）原样透传给调用方。

---

## 5. 错误与故障处理

| 后端响应/事件 | 网关行为 |
|---|---|
| 401 + `WWW-Authenticate: Bearer/Basic` | 标记 target 不可用（熔断 OPEN），对调用方返 JSON-RPC error（**不透传 HTTP 错**，因网关是 MCP 服务端） |
| 连接拒绝/超时/initialize 失败/读超时/SSE 中断 | 计入熔断（连续 3 次→OPEN），OPEN 期间立即返明确错误，退避后 HALF_OPEN 探测 |
| 后端 `isError=true` 或 INVALID_PARAMS | **不**计入熔断（正常响应）；原样透传/存入 task.result |
| 并发 task 超后端上限 5 | 网关前置 `Semaphore(5)` 限流，避免越界触发后端 INVALID_PARAMS |

- **超时**：connect 5s / call 30s（对齐 SC-003）；异步后台兜底 11 分钟。
- **重连**：带退避（base 1s×2，cap 30s）。

---

## 6. 并发与会话

- 每 target 独立 `BackendClient`（独立连接池 + 独立 McpClient 会话），互不阻塞（宪法原则三）。
- 后端 session TTL 25 分钟（[后端接入契约](../../reference/arthas-docs/03-MCP/后端接入契约.md)）；网关长连接需处理 session 过期（过期后重新 initialize）。
- task 并发硬上限 5（后端）；网关 `Semaphore` 守护。

---

## 7. 优雅下线

- 后端移除（热重载）或网关闭闭：对 Streamable 后端发 `DELETE /mcp`（关 session）或直接关连接池；`STATELESS` 直接关连接池。
- in-flight 调用让其完成（callTimeout 兜底）。

---

## 8. 契约测试断言点（客户端 · 官方 SDK client 驱动 + 真实 arthas/业务服务，零桩）

| ID | 断言 |
|---|---|
| C-INIT-1 | 网关 POST `/mcp` `initialize`，`Accept` 含 `application/json`+`text/event-stream` |
| C-INIT-2 | 启用认证时带 `Authorization: Bearer <token>`（token==password） |
| C-INIT-3 | 后端响应头 `Mcp-Session-Id` 被保存，后续请求（initialized/tools/call）回带 |
| C-CALL-1 | 同步工具 `tools/call` 的 `params.arguments` **不含 target**（剥离），`name` 正确 |
| C-CALL-2 | optional 工具后台 `tools/call` **不带** `task` 字段（走自动轮询路①） |
| C-CALL-3 | dashboard SSE 多帧被聚合为一次结果 |
| C-STATELESS-1 | `STATELESS` 后端：无 `Mcp-Session-Id`，纯 JSON 一来一回 |
| C-AUTH-1 | 后端 401+`WWW-Authenticate` → 网关标记不可用 + 对调用方返 JSON-RPC error（不透传 HTTP） |
| C-CB-1 | 连续 3 次连接失败 → 熔断 OPEN，立即返明确错误（不等 30s） |
| C-CB-2 | 后端 `isError=true`/INVALID_PARAMS **不**计入熔断 |
| C-LIMIT-1 | 并发超 5（task）→ 网关前置限流返 INVALID_PARAMS，不越界打后端 |
| C-RESULT-1 | 后端 `CallToolResult` 原样透传（content/isError/_meta 不改写） |
| C-RESULT-2 | 后端 JSON-RPC error 原样透传 code/message/data |
| C-ISO-1 | target A 后端延迟，target B 后端即时 → B 不被 A 拖慢（SC-004） |

> 这些测试**先于实现编写**（TDD），全真实环境（真实 arthas + 真实业务服务，**零桩**）；故障场景用**真实故障条件**（停容器=不可达 / 错误 token=真实 401 / 业务方法 `sleep`=超时 / 连续失败=熔断 / 6 并发 task=真实越界 INVALID_PARAMS），**非桩**。观测网关→后端 outbound 报文（如 C-CALL-1/2 的 target 剥离）经网关侧测试钩子/结构化日志断言。与 `server-contract.md` 的服务端契约共同构成宪法原则四的"双侧契约"。
```


---

## `specs/001-arthas-mcp-gateway/contracts/gateway-tools-contract.md`

```markdown
# 契约：网关自有工具（list-targets / task-get / task-list / task-cancel）

**Feature**: 001-arthas-mcp-gateway | **Date**: 2026-06-19
**界面角色**：网关对 Claude Code 暴露的 **4 个自有工具**（超出 arthas 规范 31 工具集），均为 `routingMode=GATEWAY_LOCAL`（不转发后端，网关本地处理）。
**宪法依据**：原则二（映射可被发现）、原则四。复杂度正当理由见 [plan.md](../plan.md) Complexity Tracking、决策见 [research.md §4](../research.md)。

> 这些工具对 Claude Code 都是**普通 MCP 工具**，走官方 SDK 的标准 `tools/call`——"task"只是网关内存态，**无任何手写 task 协议帧**（规避宪法"不手写帧"硬约束）。

---

## 1. `arthas-gateway.list-targets`（发现可用目标，满足 FR-009）

**用途**：返回当前后端注册表，让调用方知道有哪些 `target` 可用（含健康状态）。因 target 是动态值（随热重载变化），不能写进静态 schema enum，故独立入口。

**inputSchema**：
```jsonc
{ "type": "object", "properties": {}, "additionalProperties": false }
```
无参数。

**返回**（`CallToolResult`，TextContent 为 JSON）：
```jsonc
{
  "targets": [
    { "name": "order-service", "state": "ACTIVE", "healthy": true, "protocol": "STREAMABLE" },
    { "name": "payment",        "state": "ACTIVE", "healthy": false, "protocol": "STREAMABLE" },
    { "name": "inventory",      "state": "RETIRED", "healthy": false, "protocol": "STATELESS" }
  ],
  "version": 12
}
```

- `healthy=false` 表示熔断 OPEN 或不可达（仍列出，便于诊断）。
- `state`：`ACTIVE`（在册）/ `RETIRED`（热重载移除中）。
- 热重载后调用立即反映新表（FR-009、SC-002）。

---

## 2. `arthas-gateway.task-get`（查询异步任务状态/结果）

**用途**：查询一个异步诊断任务（watch/trace/stack/tt/monitor 触发）的状态；完成时返回最终结果。

**inputSchema**：
```jsonc
{
  "type": "object",
  "properties": {
    "taskId": { "type": "string", "description": "异步工具调用返回的 taskId" }
  },
  "required": ["taskId"],
  "additionalProperties": false
}
```

**返回**（按 `status` 分支，TextContent 为 JSON）：

`working`：
```jsonc
{ "taskId": "t-7f3a9c", "status": "working", "toolName": "watch", "target": "order-service", "createdAt": "<iso>" }
```

`completed`：
```jsonc
{
  "taskId": "t-7f3a9c", "status": "completed", "toolName": "watch", "target": "order-service",
  "completedAt": "<iso>",
  "result": { /* 后端返回的原始 CallToolResult，原样（含 isError） */ }
}
```

`failed`：
```jsonc
{
  "taskId": "t-7f3a9c", "status": "failed",
  "error": { "reason": "backend_timeout" | "backend_unreachable" | "circuit_open", "message": "..." }
}
```

`cancelled`：
```jsonc
{ "taskId": "t-7f3a9c", "status": "cancelled" }
```

**错误**：
- `taskId` 不存在 → INVALID_PARAMS(-32602)（data 可附现存 taskId 列表或提示用 task-list）。

---

## 3. `arthas-gateway.task-list`（列出所有任务）

**用途**：返回当前所有异步任务的概要（状态总览）。

**inputSchema**：
```jsonc
{
  "type": "object",
  "properties": {
    "status": { "type": "string", "description": "可选过滤：working|completed|failed|cancelled；不传则全部" }
  },
  "additionalProperties": false
}
```

**返回**（TextContent 为 JSON 数组）：
```jsonc
{
  "tasks": [
    { "taskId": "t-7f3a9c", "status": "working",   "toolName": "watch", "target": "order-service", "createdAt": "<iso>" },
    { "taskId": "t-b2101e", "status": "completed", "toolName": "trace", "target": "payment",        "createdAt": "<iso>", "completedAt": "<iso>" }
  ]
}
```

---

## 4. `arthas-gateway.task-cancel`（取消异步任务）

**用途**：取消一个仍在 `working` 的异步任务。

**inputSchema**：
```jsonc
{
  "type": "object",
  "properties": {
    "taskId": { "type": "string", "description": "要取消的 taskId" }
  },
  "required": ["taskId"],
  "additionalProperties": false
}
```

**返回**（TextContent 为 JSON）：
```jsonc
{ "taskId": "t-7f3a9c", "status": "cancelled" }
```

**行为**：关掉后台 future；已 in-flight 的后端请求由 callTimeout 兜底回收；任务标 `cancelled`（终态，不可逆）。

**错误**：
- `taskId` 不存在 → INVALID_PARAMS(-32602)。
- 任务已终态（completed/failed/cancelled）→ 返回当前状态（幂等，不报错；data 注明已是终态）。

---

## 5. 异步任务全链路（方案 C 时序）

```text
Claude Code ──tools/call watch target=order──► 网关
                                              ├─ 立即返回 { taskId:"t-7f3a9c", status:"working" }
                                              └─ 后台: 对后端 tools/call watch(无 task) 阻塞等结果 ──► arthas
Claude Code ──task-get t-7f3a9c──► 网关 ──► { status:"working" }
                          ...
Claude Code ──task-get t-7f3a9c──► 网关 ──► { status:"completed", result:<CallToolResult> }
（或 task-cancel t-7f3a9c ──► 网关 ──► { status:"cancelled" }）
```

---

## 6. 契约测试断言点（自有工具 · 官方 SDK client 驱动 + 真实 arthas/业务服务，零桩）

| ID | 断言 |
|---|---|
| G-LT-1 | `list-targets` 返回当前注册表（多 target 含健康状态）；热重载后内容更新 |
| G-LT-2 | `list-targets` 无需 target 参数 |
| G-TG-1 | `task-get` working → 返回 working；completed → 返回 result；failed → 返回 error |
| G-TG-2 | 后端 `isError=true` 的结果在 completed 的 `result` 中**原样**保留（不转 failed） |
| G-TG-3 | 未知 taskId → INVALID_PARAMS |
| G-TL-1 | `task-list` 返回所有任务；`status` 过滤生效 |
| G-TC-1 | `task-cancel` working 任务 → status 变 cancelled；后台 future 被取消 |
| G-TC-2 | 对终态任务 cancel 幂等（返回当前状态，不报错） |
| G-ASYNC-1 | optional 工具调用立即返回 taskId（不阻塞 30s+） |
| G-ASYNC-2 | 后台超时（>11min）→ task 标 failed |

> 全真实环境（真实 arthas + 真实业务服务，**零桩**）；可用性冒烟另由真实 Claude Code 驱动。与 `server-contract.md`/`backend-client-contract.md` 共同构成完整双侧契约。所有断言先于实现（TDD）。
```


---

## `specs/001-arthas-mcp-gateway/contracts/server-contract.md`

```markdown
# 契约：网关 ↔ Claude Code（服务端契约）

**Feature**: 001-arthas-mcp-gateway | **Date**: 2026-06-19
**界面角色**：网关作为**标准 MCP 服务端**，被 Claude Code 等 AI 客户端消费。
**宪法依据**：原则一（MCP 规范符合性）、原则二（透明无损聚合）、原则四（双侧契约测试）。

> 线格式（JSON-RPC 帧、initialize/tools/task/error 报文结构）的**权威定义**见上游 [MCP线契约](../../reference/arthas-docs/03-MCP/MCP线契约.md)；本文不重复帧定义，聚焦**网关在此界面上的行为承诺与可测断言点**。设计决策见 [research.md](../research.md)。

---

## 1. 传输

| 传输 | 用途 | 端点 |
|---|---|---|
| stdio | 本地 Claude Code | 进程 stdin/stdout |
| Streamable HTTP | 远程/多客户端 | 默认 `/mcp`（可配） |

- **配置驱动**：`transports.stdio.enabled` / `transports.http.{enabled,host,port,endpoint}`。
- **双传输一致**：同一 `GatewayMcpHandler`，`tools/list` 等返回在两路完全相同。
- 生命周期：每客户端独立 initialize→initialized；有序关闭（停接新会话→等 in-flight→关 transport）。

---

## 2. 协议版本协商

- 网关锁定 **`2025-11-25`**，接受列表 `[2024-11-05, 2025-03-26, 2025-06-18, 2025-11-25]`。
- `initialize.params.protocolVersion` 在列表内 → **原样回显**。
- 不在列表 → 回最高 `2025-11-25`。

---

## 3. initialize 握手

**请求**（Claude Code→网关）：标准 `initialize`，含 `protocolVersion`、`capabilities`、`clientInfo`。
**响应**（网关→Claude Code）：
```jsonc
{
  "protocolVersion": "2025-11-25",
  "serverInfo": { "name": "arthas-mcp-gateway", "version": "<项目版本>" },
  "capabilities": {
    "tools": { "listChanged": false },   // 工具集静态，不随 target 变化
    "logging": {}                          // 可选：结构化日志通知
    // 不声明 tasks（方案 C 不暴露协议层 task）
    // 不声明 resources/prompts（arthas 恒空）
  }
}
```
- Claude Code 发 `notifications/initialized` 后进入正常调用。

---

## 4. tools/list

返回 **35 个工具**的不可变快照（启动期构建，含 `target` 注入）。`nextCursor == null`。

- **31 arthas 工具**：inputSchema = arthas 原始 schema **逐字拷贝** + 注入 `target`（string, required, 进顶层 `required`）；`additionalProperties:false`；`execution.taskSupport` 照实（dashboard=forbidden；watch/trace/stack/tt/monitor=optional；其余 forbidden）。schema 摘抄单一事实源：[MCP能力清单](../../reference/arthas-docs/03-MCP/MCP能力清单.md)。
- **4 网关自有工具**：见 `gateway-tools-contract.md`。

`target` 注入示例（watch 片段）：
```jsonc
{
  "name": "watch",
  "inputSchema": {
    "type": "object",
    "properties": {
      "target": { "type": "string", "description": "目标 JVM 逻辑名（见 list-targets），决定路由到哪个 arthas 后端" },
      "classPattern": { ... },   // arthas 原参数，逐字保留
      "express": { ... }         // arthas 原参数（注意保留 arthas 的命名，不归一化）
    },
    "required": ["target", "classPattern"],   // target 加入 required
    "additionalProperties": false
  },
  "execution": { "taskSupport": "optional" }
}
```

---

## 5. tools/call 路由与结果

### 5.1 解析与路由

1. `params.name` 命中注册表 → 否则 INVALID_PARAMS(-32602)。
2. 从 `params.arguments` 取 `target`（string）并**剥离** → 缺失/空 → INVALID_PARAMS；不在册 → INVALID_PARAMS + `data.available` 附当前可用 target 列表。
3. 按工具 `routingMode` 分流（见下）。

### 5.2 同步工具（26 即时 + dashboard，SYNC_DIRECT / STREAM_AGGREGATE）

- 剥离 target 后的 `backendArgs` → 路由到 `target` 后端 → 等返回 `CallToolResult`（dashboard 聚合 SSE 多帧）→ **原样透传**给 Claude Code。
- `isError=true` 原样透传（不吞为成功）。

### 5.3 异步工具（5 optional：watch/trace/stack/tt/monitor，ASYNC_TASK）

- **立即返回**（不阻塞）：
```jsonc
{
  "content": [{ "type": "text", "text": "已提交异步诊断任务" }],
  "isError": false,
  "_meta": { "taskId": "t-7f3a9c", "status": "working", "toolName": "watch", "target": "order-service" }
}
```
- 后台对后端发**同步** `tools/call`（走后端自动轮询路①，阻塞等结果，上限 11 分钟）→ 结果存 `TaskStore`。
- Claude Code 经 `task-get`/`task-list`/`task-cancel` 查询/管理（见 `gateway-tools-contract.md`）。

### 5.4 网关自有工具（GATEWAY_LOCAL）

`list-targets`/`task-get`/`task-list`/`task-cancel`：不转发后端，网关本地处理。

---

## 6. 错误传播

| 场景 | JSON-RPC error code | data |
|---|---|---|
| `target` 缺失/空/未知工具 | INVALID_PARAMS(-32602) | — |
| `target` 不在册 | INVALID_PARAMS(-32602) | `{ available: ["order-service", ...] }` |
| 后端 `isError=true` | 非 error——原样 `CallToolResult.isError=true` 透传 | — |
| 后端 JSON-RPC error | **原样透传** code/message/data | — |
| 失效 target（不可达/熔断 OPEN） | INVALID_PARAMS(-32602) 或本地错误 | `{ target, reason:"backend_unreachable", available:[...], retryAfterMs }` |
| 未实现 method | METHOD_NOT_FOUND(-32601) | — |
| 协议解析错误 | PARSE_ERROR(-32700) / INVALID_REQUEST(-32600) | — |

> 仅用 arthas 实现的 5 个标准 code（`MCP线契约` §5.1）；不使用 -32xxx 自定义 code。30s 内对失效 target 返回明确错误（SC-003）。

---

## 7. 契约测试断言点（服务端 · 官方 SDK client 驱动 + 真实 arthas/业务服务，零桩）

| ID | 断言 |
|---|---|
| S-INIT-1 | initialize 协议版本回显（在列表内原样回显；不在列表回 `2025-11-25`） |
| S-INIT-2 | `serverInfo` 非空；`capabilities.tools` 存在；不声明 tasks/resources/prompts 非空 |
| S-INIT-3 | `notifications/initialized` 后网关不报错 |
| S-TL-1 | `tools/list` 工具数 == 35 |
| S-TL-2 | 每个 arthas 工具 `inputSchema.properties` 含 `target` 且 ∈ `required`；`additionalProperties==false` |
| S-TL-3 | 除 target 外，inputSchema **逐字等于** arthas 原始 schema（与 fixture/能力清单比对） |
| S-TL-4 | `execution.taskSupport` 符合预期（dashboard forbidden；5 optional；其余 forbidden） |
| S-TL-5 | `nextCursor == null` |
| S-CALL-1 | 多 target 路由：`jvm target=A` 仅 A 后端收到，B 零请求；结果来自正确后端（SC-001） |
| S-CALL-2 | `arguments` 到后端**不含 target**（剥离验证） |
| S-CALL-3 | 并发多客户端结果正确归属（SC-004） |
| S-CALL-4 | optional 工具调用**立即**返回 taskId + status:working，不阻塞 |
| S-ERR-1 | target 缺失/空 → INVALID_PARAMS |
| S-ERR-2 | target 不在册 → INVALID_PARAMS + data.available |
| S-ERR-3 | 未知工具 → INVALID_PARAMS |
| S-ERR-4 | 后端 isError=true / JSON-RPC error 原样透传（不吞/不改写） |
| S-ERR-5 | 失效 target 30s 内返回明确错误（SC-003） |
| S-DUAL-1 | stdio 与 http 两路 S-TL/S-CALL 断言一致（参数化） |

> 这些测试**先于实现编写**（TDD，宪法原则七），全真实环境（真实 arthas + 真实业务服务，**零桩**）；官方 SDK client 为合规 MCP 客户端（走标准协议、非 curl 裸 HTTP），承担**结果一致性 + 协议契约**的确定性断言。工具**可用性**（逐工具冒烟、不做一致性比对）由真实 **Claude Code** 驱动，见 [quickstart.md](../quickstart.md) §5。客户端契约（对 arthas 后端）见 `backend-client-contract.md`。
```


---

## `specs/001-arthas-mcp-gateway/data-model.md`

```markdown
# Data Model: Arthas MCP 网关（Phase 1）

**Feature**: 001-arthas-mcp-gateway
**Date**: 2026-06-19

> 本文档定义网关运行期的**核心实体、字段、关系、状态机与校验规则**。网关无持久化存储（宪法无要求），所有实体为**运行期内存态**或**配置态**。MCP 协议层实体（Tool/CallToolResult/JSON-RPC error 等）沿用官方 SDK 定义，不在此重复——本文聚焦网关**自有**领域模型。决策依据见 [research.md](./research.md)，行为契约见 `contracts/`。

---

## 1. 实体总览

| 实体 | 类型 | 职责 | 生命周期 |
|---|---|---|---|
| `BackendConfig` | 配置态 | 单后端的声明（逻辑名/地址/认证/超时/并发） | 配置文件，热重载 |
| `BackendEntry` | 运行期 | 单后端的运行对象（client + 熔断 + 限流） | 注册表替换时优雅下线 |
| `BackendRegistry` | 运行期 | 全部后端的不可变快照（逻辑名→Entry） | AtomicReference 原子替换 |
| `ExposedTool` | 启动期 | 对调用方暴露的单个工具（arthas 工具 + target 注入 / 或网关自有工具） | 进程级只读 |
| `GatewayTask` | 运行期 | 一次异步诊断任务（taskId→状态/结果） | TTL 清理 |
| `DiagnosticRequest` | 瞬态 | 一次 tools/call 的解析结果（路由用） | 单次调用 |

---

## 2. BackendConfig（配置态）

单后端声明，源自 `config/backends.yaml`。

| 字段 | 类型 | 必填 | 说明 / 校验 |
|---|---|---|---|
| `name` | string | 是 | 逻辑名，唯一标识（如 `order-service`）；`target` 参数取值即此。注册表内唯一，重复→加载失败保留旧表 |
| `url` | string | 是 | 后端 MCP 端点，复用 arthas http console，形如 `http://host:8563/mcp`；须合法 URL |
| `protocol` | enum | 是 | `STREAMABLE` \| `STATELESS`；决定是否可用 task（STATELESS 不支持）。默认 `STREAMABLE` |
| `auth.mode` | enum | 是 | `BEARER` \| `BASIC` \| `NONE`；MVP 受控内网多为 NONE/BEARER |
| `auth.token` | string | BEARER 时必填 | Bearer token == 后端配置 password（[后端接入契约](../../reference/arthas-docs/03-MCP/后端接入契约.md) §2.3） |
| `auth.username`/`password` | string | BASIC 时必填 | base64(user:pass) |
| `connectTimeoutMs` | int | 否 | TCP + initialize 握手超时，默认 5000 |
| `callTimeoutMs` | int | 否 | 单次同步 `tools/call` 超时，默认 30000（对齐 SC-003） |
| `maxConcurrentTasks` | int | 否 | task 并发上限，默认/硬上限 5（后端约束） |

**校验规则**：name 唯一、url 合法、auth 与 mode 对应（BEARER 须 token / BASIC 须 user+pass）；校验失败→**保留旧注册表**，记 ERROR 日志，不半替换（原子性）。

**配置版本**：`version`（单调递增整数），热重载去重（重复 version 忽略）。

---

## 3. BackendEntry（运行期）

| 字段 | 类型 | 说明 |
|---|---|---|
| `config` | BackendConfig | 不可变声明 |
| `client` | BackendClient | 官方 SDK `HttpClientStreamableHttpTransport` 封装，独立连接池 + 独立 McpClient 会话 + 独立 SSE 解析 |
| `breaker` | CircuitBreaker | 熔断器（CLOSED/OPEN/HALF_OPEN） |
| `taskSlots` | Semaphore | `maxConcurrentTasks`（默认 5）许可；异步任务 acquire/release |
| `state` | enum | `ACTIVE` \| `RETIRED`（热重载移除时标 RETIRED，in-flight 调用可完成） |

**不变量**：一次 `tools/call` 全程持有固定的 `BackendEntry` 引用（final）；registry 替换不影响 in-flight 调用（满足边缘情况"热重载并发不串台"）。

---

## 4. BackendRegistry（运行期，不可变快照）

| 字段 | 类型 | 说明 |
|---|---|---|
| `version` | long | 配置版本号 |
| `byName` | Map<String, BackendEntry> | 逻辑名→Entry |

**操作**：`get(name)`、`names()`；由 `RegistryHolder` 经 `AtomicReference` 持有，热重载时整体替换（`getAndSet` + 异步优雅下线旧 Entry）。

---

## 5. ExposedTool（启动期，对调用方暴露）

两类，统一模型：

### 5.1 arthas 工具（31 个）

| 字段 | 类型 | 说明 |
|---|---|---|
| `name` | string | 工具名（如 `watch`），摘抄自 [MCP能力清单](../../reference/arthas-docs/03-MCP/MCP能力清单.md) |
| `description` | string | 摘抄自 `@Tool.description` |
| `inputSchema` | JSON Schema | arthas 原始 schema **逐字拷贝** + 注入 `target`（string, required, 进 properties.required 与顶层 required）；`additionalProperties:false` 不变 |
| `taskSupport` | enum | `forbidden`（27）/ `optional`（5）；照实暴露 |
| `routingMode` | enum（内部） | `SYNC_DIRECT`（26 即时）/ `STREAM_AGGREGATE`（dashboard）/ `ASYNC_TASK`（5 optional，方案 C）；不进协议 |

### 5.2 网关自有工具（4 个）

| 字段 | 类型 | 说明 |
|---|---|---|
| `name` | string | `arthas-gateway.list-targets` / `task-get` / `task-list` / `task-cancel` |
| `description` | string | 中文说明用途 |
| `inputSchema` | JSON Schema | 各自参数（见 `contracts/gateway-tools-contract.md`） |
| `routingMode` | enum | `GATEWAY_LOCAL`（不转发后端） |

**总数**：35。`tools/list` 返回不可变快照，启动期构建。

---

## 6. GatewayTask（运行期，应用层异步任务）

方案 C 的核心实体。

| 字段 | 类型 | 说明 |
|---|---|---|
| `taskId` | string | 网关生成，唯一（如 `t-7f3a...`） |
| `status` | enum | 见 §7 状态机 |
| `toolName` | string | 发起任务的原工具（watch/trace/...） |
| `target` | string | 目标逻辑名 |
| `createdAt` | instant | 创建时间（传入，非进程内取时） |
| `completedAt` | instant? | 完成时间 |
| `result` | CallToolResult? | 后端返回的最终结果（completed 时） |
| `error` | object? | 失败原因（failed 时，含 reason/message） |
| `backendFuture` | Future | 后台阻塞等后端路①的句柄（cancel 用） |

**存储**：`TaskStore`（内存 `Map<taskId, GatewayTask>`），TTL 清理（完成后保留可查询，如 1 小时；过期移除）。

**状态查询**：`task-get(taskId)` → 返回 status +（completed）result /（failed）error；`task-list()` → 返回全部任务概要；`task-cancel(taskId)` → 关 future + 标 cancelled。

---

## 7. GatewayTask 状态机

```text
                 ┌───────── 后端返回结果 ─────────┐
                 │                                  ▼
  working ──────────────────► completed
     │
     ├─── 后台超时(11min)/后端错误 ─► failed
     │
     └─── task-cancel 调用 ──────► cancelled
```

| 状态 | 含义 | 终态？ |
|---|---|---|
| `working` | 后台阻塞等后端中 | 否 |
| `completed` | 后端返回，结果已存 result | 是 |
| `failed` | 后台超时/后端错误/熔断 | 是 |
| `cancelled` | 调用方经 task-cancel 取消 | 是 |

**精简说明**：不做 arthas 的 `INPUT_REQUIRED` 多档复杂态（协议层不暴露真 task，无两阶段输入交互）。

**转换规则**：
- `working→completed`：后台 future 正常返回 CallToolResult（**原样存入 result，包括 `isError=true`**——后端业务错误不转为 failed，failed 仅限基础设施故障，对齐宪法原则五"错误显式传播"）。
- `working→failed`：后台超时（>11min）/ 连接错误 / 熔断 OPEN。
- `working→cancelled`：`task-cancel` 调用，关 future（已 in-flight 的后端请求由 callTimeout 兜底回收）。
- 终态不可逆。

---

## 8. CircuitBreaker 状态机（故障隔离）

```text
  CLOSED ──连续 N(=3) 次失败──► OPEN ──退避后──► HALF_OPEN ──探测成功──► CLOSED
                                  ▲                               │
                                  └────────探测失败───────────────┘
```

| 状态 | 行为 |
|---|---|
| `CLOSED` | 正常转发 |
| `OPEN` | 立即返回明确错误（不等 30s），data 含 `reason=backend_unreachable` + `available` 列表 + `retryAfterMs` |
| `HALF_OPEN` | 放 1 个探测请求，成功→CLOSED，失败→OPEN |

**失败计入**：连接拒绝/超时、initialize 失败、读超时、SSE 异常中断。**不计入**：后端业务错误（`isError=true`/INVALID_PARAMS）——那是正常响应（宪法原则五）。退避：base 1s、×2、cap 30s。

---

## 9. DiagnosticRequest（瞬态，路由用）

`tools/call` 解析后的中间对象。

| 字段 | 类型 | 说明 / 校验 |
|---|---|---|
| `toolName` | string | 工具名；未命中注册表 → INVALID_PARAMS(-32602) |
| `target` | string? | 从 arguments 取出并剥离；缺失/空 → INVALID_PARAMS；不在册 → INVALID_PARAMS + data 附可用列表 |
| `backendArgs` | Map | 剥离 target 后的剩余键（原样转发后端） |
| `routingMode` | enum | 由工具的 routingMode 决定（SYNC_DIRECT/STREAM_AGGREGATE/ASYNC_TASK/GATEWAY_LOCAL） |

---

## 10. 关系图

```text
  config/backends.yaml
        │ 加载/热重载
        ▼
  BackendConfig ──► BackendEntry ──┐ (client, breaker, taskSlots)
        │                          │
        ▼                          ▼
  BackendRegistry (AtomicReference) ◄── RegistryHolder

  StaticToolRegistry (启动期) ──► ExposedTool[] (35)
        │
        ▼
  ToolsCallRouter
        │  解析
        ▼
  DiagnosticRequest ──target──► BackendEntry
        │                          │ client.callTool
        │  (ASYNC_TASK)            ▼
        └──► AsyncTaskExecutor ──► 后端(同步路①) ──► GatewayTask (TaskStore)
                                                  ▲
                                  task-get/list/cancel 查询
```

---

## 11. 校验与边界规则汇总

1. `target` 缺失/空/不在册 → INVALID_PARAMS(-32602)；不在册时 data 附当前可用列表（边缘情况）。
2. 未知工具名 → INVALID_PARAMS（对齐 arthas `McpNettyServer.java:398-411`）。
3. `target` 永不进 `backendArgs`（避免后端 INVALID_PARAMS）。
4. 后端 `isError=true` 原样透传（同步）/ 原样存入 task.result（异步）——不吞为成功。
5. 后端 JSON-RPC error 原样透传 code/message/data。
6. per-target task 并发 > `maxConcurrentTasks`(5) → 网关前置限流，返回 INVALID_PARAMS（避免触发后端越界错误）。
7. 配置加载/校验失败 → 保留旧注册表，记 ERROR，不半替换。
8. 热重载替换 registry → in-flight 调用持有旧 Entry 可完成；新调用才见新表。
```


---

## `specs/001-arthas-mcp-gateway/plan.md`

```markdown
# Implementation Plan: Arthas MCP 网关

**Branch**: `001-arthas-mcp-gateway` | **Date**: 2026-06-19 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-arthas-mcp-gateway/spec.md`

**Note**: 本文件由 `/speckit-plan` 命令填写。所有技术决策的**证据与理由**见 [research.md](./research.md)；上游 arthas 行为事实见 `reference/arthas-docs/03-MCP/` 五篇文档。

## Summary

构建一个 Java 实现的 **arthas MCP 网关**：作为标准 MCP 服务端（stdio + Streamable HTTP）向 Claude Code 等 AI 客户端暴露统一的 arthas 诊断能力，同时作为 MCP 客户端连接并管理**多个** arthas MCP 后端（每个对应一个目标 JVM）。

核心机制：向调用方暴露**静态摘抄自 arthas 源码的 31 个诊断工具**（每个注入 `target` 参数选择目标 JVM）+ **4 个网关自有工具**（`list-targets`/`task-get`/`task-list`/`task-cancel`）；调用按 `target` 路由到对应后端，结果原样透传。长任务（watch/trace/stack/tt/monitor）采用**应用层异步任务**（方案 C）：立即返回 taskId，后台阻塞等后端，调用方经 `task-get` 轮询取结果——全程走官方 MCP Java SDK 的标准 `tools/call`，**不手写任何 JSON-RPC/MCP 帧**。

技术栈：Java 21 + Maven + Spring Boot，官方 `io.modelcontextprotocol.sdk:mcp-bom:2.0.0`（双端）+ Spring AI `mcp-spring-webmvc`（HTTP transport）+ Actuator（可观测性）。详见 [research.md](./research.md)。

## Technical Context

**Language/Version**: Java 21（LTS）。宪法约束最低 Java 17 LTS；选用 21（已就绪，且虚拟线程利于后台异步任务）。构建中 enforce 锁定。

**Primary Dependencies**:
- `io.modelcontextprotocol.sdk:mcp-bom:2.0.0`（官方 MCP Java SDK，BOM 统一版本；`mcp` 便利包 + 客户端 `HttpClientStreamableHttpTransport`）
- `org.springframework.ai:spring-ai-bom`（含 `mcp-spring-webmvc`，提供 Streamable HTTP 服务端 transport；WebFlux/WebMVC 传输已自官方 SDK 迁出至 Spring AI 2.0+）
- Spring Boot（生命周期、配置外化、Actuator 健康检查/metrics）
- 测试：JUnit 5 + AssertJ + 官方 `mcp-test` + conformance-tests 子套件 + Testcontainers（真实 arthas + 真实业务服务，**零桩**）；**无 WireMock**

**Storage**: N/A（无持久化）。运行期内存态：后端注册表（不可变快照 + `AtomicReference`）、taskStore（异步任务 taskId→状态/结果，TTL 清理）。配置文件：后端映射表（`config/backends.yaml`，热重载）。

**Testing**: JUnit 5 + AssertJ。**真实环境，零桩**（Testcontainers 拉起真实 arthas + 真实业务服务；故障用真实条件，无 WireMock）。**驱动分层**：工具可用性用真实 Claude Code（冒烟、不做一致性）；结果一致性 + 双侧协议契约用官方 SDK client（确定性，非 curl 裸 HTTP）。TDD 红绿重构（宪法原则七 + 用户 TDD 真实性硬约束）。详见 [research.md §5](./research.md)。

**Target Platform**: 受控内网部署的 JVM 服务（Linux 为主，开发期 Windows 11）。MVP 无认证，依靠网络隔离；认证为演进首要项。

**Project Type**: web-service（长期运行的 MCP 聚合网关，双传输：stdio 本地 + Streamable HTTP 远程）。

**Performance Goals**:
- 单次同步诊断转发开销可忽略（SC-005：与直连一致）。
- 失效目标调用 30s 内返回明确错误（SC-003）。
- 后端配置热重载 30s 内生效（SC-002）。
- 多客户端并发互不干扰（SC-004），per-target 连接/线程隔离。

**Constraints**:
- 跨后端并发不得相互阻塞（异步非阻塞多路复用，宪法原则三）。
- 协议核心不得手写 JSON-RPC/MCP 帧（宪法"协议核心"约束）。
- 不得静默改写/摘要/截断/吞掉结果与错误（宪法原则二、五）。
- CI 必须能复现本地构建。

**Scale/Scope**: 目标 JVM 从少量起步、逐步增长；MVP 以静态配置满足，架构预留向更大规模演进。单网关聚合多后端，工具数固定 35（不随后端数膨胀）。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

**宪法版本**：v1.2.0（2026-06-19）。逐原则核验：

| # | 原则 | 状态 | 依据 / 落地方式 |
|---|---|---|---|
| 一 | MCP 规范符合性（不可妥协） | ✅ 通过 | 双端均用官方 SDK 实现 JSON-RPC 2.0；锁定协议 `2025-11-25`；initialize→initialized、能力协商、有序关闭由 SDK 保障；协议层只出现 tools 原语（resource/prompt 恒空返回、不声明自定义原语） |
| 二 | 透明无损的聚合 | ✅ 通过 | 31 工具静态摘抄自 arthas 源码（[MCP能力清单](../../reference/arthas-docs/03-MCP/MCP能力清单.md)）；`target` 注入消除歧义；调用结果原样透传（不篡改/截断/摘要）；映射确定性且可发现（`list-targets` 工具） |
| 三 | 连接生命周期与局部故障韧性 | ✅ 通过 | per-target 一等对象（注册/能力发现/健康监控/退避重连/干净下线）；熔断降级不波及其他后端；per-target 独立连接池 + 异步非阻塞，互不阻塞 |
| 四 | 双侧契约优先的测试 | ✅ 通过 | 双侧契约测试（research §5.4）先于实现编写（TDD），**全真实环境**（真实 arthas + 业务服务，零桩）；覆盖握手/能力协商/路由/命名空间/错误传播/一致性 A/B；任何线格式或路由改动同步更新契约测试 |
| 五 | 可观测性与可诊断性 | ✅ 通过 | 结构化日志（每次路由调用记录 target/tool/结果状态/耗时，可追溯）；MCP 错误码与后端错误显式传播；Actuator 暴露已注册后端与健康状况 |
| 六 | Java 主力（不可妥协） | ✅ 通过 | 核心代码全 Java 21；构建脚本/配置为辅助；无引入非 Java 运行时依赖 |
| 七 | 测试驱动（不可妥协） | ✅ 通过 | 所有功能代码 TDD 红绿重构；调用 `superpowers:test-driven-development` 落地；**TDD 真实性硬约束**：每测试真实 arthas + 真实业务服务（禁桩模拟成功）、可用性走 Claude Code、一致性/契约走 SDK client（详见 CLAUDE.md 工程实践） |
| 八 | Arthas MCP 先决研究 | ✅ 通过 | Phase 0 已完成：`reference/arthas-docs/03-MCP/` 五篇文档为单一事实源；[research.md](./research.md) 整合；差异已显式记录（如 protocol 2025-11-25 对齐、无状态后端通知限制） |

**技术与传输约束**：Java 21 LTS ✅；Maven 锁定可复现 ✅；stdio + Streamable HTTP 双传输、配置驱动 ✅；异步非阻塞多路复用 ✅；后端注册表配置声明（不硬编码）✅；**协议核心优先官方 SDK、不手写帧** ✅。

**质量门禁**：TDD ✅；构建+lint+测试合并前全绿 ✅；CI 复现本地构建 ✅；每项能力有文档、quickstart 端到端演示 ✅；提交小而内聚 ✅。

**结论**：✅ **门禁通过，无原则冲突**。下方 Complexity Tracking 仅记录"有正当理由的例外"（4 个网关自有工具），非宪法违规。

## Project Structure

### Documentation (this feature)

```text
specs/001-arthas-mcp-gateway/
├── plan.md              # 本文件（/speckit-plan 产出）
├── research.md          # Phase 0 产出（/speckit-plan）
├── data-model.md        # Phase 1 产出（/speckit-plan）
├── quickstart.md        # Phase 1 产出（/speckit-plan）
├── contracts/           # Phase 1 产出（/speckit-plan）
│   ├── server-contract.md     # 网关↔Claude Code 服务端契约
│   ├── backend-client-contract.md  # 网关↔arthas 后端客户端契约
│   └── gateway-tools-contract.md   # 4 个网关自有工具契约
└── tasks.md             # Phase 2 产出（/speckit-tasks，本命令不创建）
```

### Source Code (repository root)

```text
arthas-gateway/
├── pom.xml                       # Maven 根 POM：锁 mcp-bom:2.0.0 + spring-ai-bom + spring-boot
├── config/
│   └── backends.yaml             # 后端映射表（逻辑名→地址/认证/超时/并发），热重载源
├── src/main/java/com/arthas/gateway/
│   ├── GatewayApplication.java            # Spring Boot 入口
│   ├── transport/                         # 双传输装配（stdio + Streamable HTTP），配置驱动
│   ├── handler/                           # GatewayMcpHandler：传输无关的协议核心
│   │   ├── InitializeHandler.java         # initialize/能力协商
│   │   ├── ToolsListHandler.java          # tools/list（返回 35 工具静态快照）
│   │   ├── ToolsCallRouter.java           # tools/call 路由（target 剥离 + 分流同步/异步）
│   │   └── GatewayToolHandlers.java       # list-targets / task-* 自有工具处理
│   ├── tool/                              # 静态工具注册表
│   │   ├── StaticToolRegistry.java        # 31 arthas 工具（schema 摘抄 + target 注入）
│   │   ├── ExposedTool.java               # 暴露给调用方的工具模型
│   │   └── resources/arthas-tools.json    # 31 工具 schema（从源码生成，契约测试比对基准）
│   ├── backend/                           # 后端注册表 + 客户端
│   │   ├── BackendRegistry.java           # 不可变快照（AtomicReference）
│   │   ├── BackendEntry.java              # 单后端：client + 熔断器 + Semaphore(5)
│   │   ├── BackendClient.java             # 官方 SDK HttpClientStreamableHttpTransport 封装
│   │   ├── CircuitBreaker.java            # 熔断 + 退避重连
│   │   ├── RegistryHolder.java            # AtomicReference 持有 + 优雅替换
│   │   └── BackendConfigLoader.java       # YAML 解析 + WatchService 热重载
│   ├── task/                              # 应用层异步任务（方案 C）
│   │   ├── TaskStore.java                 # 内存 taskId→状态/结果，TTL 清理
│   │   ├── AsyncTaskExecutor.java         # 后台阻塞等后端路①（虚拟线程）
│   │   └── TaskState.java                 # working/completed/failed/cancelled
│   ├── auth/                              # 后端认证（Bearer/Basic header 注入），MVP 预留
│   └── obs/                               # 结构化日志 + Actuator 端点（健康/后端状态）
└── src/test/java/com/arthas/gateway/
    ├── contract/
    │   ├── server/                        # 服务端契约（官方 SDK client 驱动）
    │   └── client/                        # 客户端契约（WireMock 模拟 arthas）
    ├── integration/                       # 双传输端到端、热重载、并发隔离
    └── unit/                              # 注册表/熔断/路由/任务状态机单测
```

**Structure Decision**: 单 Maven 模块（web-service）。按职责分包：`transport`（双传输装配）/ `handler`（传输无关协议核心）/ `tool`（静态注册表）/ `backend`（注册表+客户端+熔断+热重载）/ `task`（应用层异步任务）/ `obs`（可观测性）。测试镜像分 `contract`（双侧，宪法原则四）/ `integration` / `unit`。静态 schema 与 classpath 资源 JSON 分离，便于生成与契约比对。

## Complexity Tracking

> 本表记录"超出 arthas 规范集的额外能力"，属宪法治理要求的"有正当理由的例外"，**非宪法原则违规**（Constitution Check 已全部通过）。

| 复杂度项 | 为何需要 | 被否决的更简单方案及其否决理由 |
|---|---|---|
| 4 个网关自有工具（list-targets / task-get / task-list / task-cancel，超出 arthas 规范 31 工具集） | ① FR-009 要求"目标集合变化可被调用方发现"——但 target 是动态值（随热重载变化），不能写进静态 schema enum，故需独立入口 `list-targets`；② 长任务（watch/trace/stack/tt/monitor）需异步生命周期，官方 SDK v2.0.0 GA 不支持 tasks 原语（手写 task 帧违反宪法"不手写帧"），故用 `task-get/list/cancel` 在**应用层**模拟异步——全走标准 tools/call，零手写帧 | ① **纯透明同步转发**（不暴露 task）：长任务 >30s 被网关超时截断、占用连接，且用户明确要求异步能力——否决；② **端到端 task 透传**：违反宪法"不手写 JSON-RPC 帧"硬约束 + SDK 无 task API + 与未来官方实现冲突——否决；③ **target 写进 enum**：与热重载（target 动态增减）冲突——否决 |

**演进注记**：当官方 SDK 合入 tasks 原语（PR #755）后，可把 `task/` 后台分支替换为真 task 转发；4 个自有工具可保留或迁移，架构已预留。
```


---

## `specs/001-arthas-mcp-gateway/quickstart.md`

```markdown
# Quickstart: Arthas MCP 网关（端到端验证指南）

**Feature**: 001-arthas-mcp-gateway | **Date**: 2026-06-19

> 本文是**可运行的端到端验证指南**，证明网关按 spec 的 SC-001~SC-005 与用户故事工作。实现细节归属 `tasks.md`（/speckit-tasks 产出）与实现代码，本文不含。线行为承诺见 `contracts/`，实体定义见 `data-model.md`，决策见 `research.md`。

---

## 1. 前置条件

| 项 | 要求 |
|---|---|
| JDK | 21（LTS），构建中 enforce 锁定 |
| 构建工具 | Maven（`mvnw` wrapper 随仓库） |
| 目标 JVM | ≥1 个已运行 arthas 并暴露 MCP 端点的 Java 进程（arthas http console `/mcp`） |
| Claude Code | 用于下游集成验证（或官方 SDK client / MCP inspector 代替） |
| 网络 | 受控内网（MVP 无认证，靠网络隔离；见 spec 假设） |

> 单目标即可验证核心链路；多目标（2~3）用于验证路由/隔离/热重载/并发。

---

## 2. 构建与运行

```bash
# 构建（本地与 CI 同命令，宪法"CI 复现本地构建"）
./mvnw clean verify
```

### 2.1 配置后端映射表

编辑 `config/backends.yaml`（实体见 [data-model.md §2](./data-model.md)）：

```yaml
version: 1
backends:
  - name: order-service
    url: http://10.0.0.10:8563/mcp
    protocol: STREAMABLE
    auth: { mode: NONE }
    callTimeoutMs: 30000
    maxConcurrentTasks: 5
  - name: payment
    url: http://10.0.0.11:8563/mcp
    protocol: STREAMABLE
    auth: { mode: BEARER, token: ${PAYMENT_TOKEN} }
```

### 2.2 启动网关

> **MVP 仅 Streamable HTTP**（stdio 与 HTTP 互斥，stdio 延后；见 memory `sdk2-vs-spec-divergences`）。
> 传输由 Spring AI starter 自动装配：端口走 `server.port`、端点走 `spring.ai.mcp.server.streamable-http.mcp-endpoint`（见 `application.yml`，默认值即 `8761` + `/mcp`，无需命令行覆盖）。

```bash
# 默认即 HTTP，监听 8761、MCP 端点 /mcp（取自 application.yml）
./mvnw spring-boot:run

# 覆盖端口（如远程/多客户端时）
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Dserver.port=8761"
```

启动后 `/actuator/health` 返回 `{"status":"UP"}`，MCP 端点为 `http://localhost:8761/mcp`。

---

## 3. 接入 Claude Code

MVP 仅 HTTP：把网关注册为 Streamable HTTP server，URL `http://<gateway-host>:8761/mcp`（stdio 接入待 stdio 传输启用后补）。

**最简方式（不污染全局配置）**——写 MCP 配置文件 `target/smoke-mcp-config.json`：

```json
{
  "mcpServers": {
    "arthas-gw": { "type": "http", "url": "http://localhost:8761/mcp" }
  }
}
```

随后用 `claude -p ... --mcp-config target/smoke-mcp-config.json --strict-mcp-config` 即可让真实 Claude Code 连上网关（`--strict-mcp-config` 仅用本配置）。接入后 `tools/list` 应见 **35 个工具**（31 arthas，每个带 `target`；4 网关自有）——验证见 §4 场景 A 与 §5.1 实证。

---

## 4. 端到端验证场景

每个场景映射一条成功标准或用户故事，可独立观察通过/失败。

### 场景 A · 工具集静态暴露（→ SC-001 前置、用户故事 1）

1. Claude Code 请求 `tools/list`。
2. **期望**：35 个工具；每个 arthas 工具含 `target` 参数（required）；工具数量**不随**后端数变化。
3. 断言依据：`server-contract.md` S-TL-1/2/3。

### 场景 B · 单网关多目标路由（→ SC-001、SC-005、用户故事 1）

1. 配置 3 个目标（order / payment / inventory）。
2. 经 Claude Code 调 `jvm` 工具，分别 `target=order-service` / `payment` / `inventory`。
3. **期望**：每次结果来自正确目标 JVM，且与直连该 arthas 后端一致（无篡改）。
4. 断言依据：S-CALL-1、C-RESULT-1/2。

### 场景 C · 异步长任务（→ 方案 C、用户故事 1 长任务）

1. 调 `watch` 工具 `target=order-service`（默认参数）。
2. **期望**：**立即**返回 `taskId` + `status:working`（不阻塞）。
3. 调 `task-get <taskId>`：未完成→working；完成→返回最终结果（原样）。
4. 调 `task-list`：见该任务；调 `task-cancel` 可取消。
5. 断言依据：`gateway-tools-contract.md` G-ASYNC-1/2、G-TG-1/2、G-TC-1。

### 场景 D · 热重载免重启（→ SC-002、用户故事 2）

1. 网关运行中、已有 2 目标。
2. 编辑 `backends.yaml` 新增第 3 个目标并保存。
3. **期望**：**30 秒内**（实测通常 <5s）`list-targets` 含新目标，对其诊断成功。
4. 移除某目标并保存：30s 内该目标从 `list-targets` 消失，对其调用返回明确错误。
5. 断言依据：G-LT-1、S-ERR-2。

### 场景 E · 单点故障隔离（→ SC-003、用户故事 3）

1. 使 `order-service` 不可达（停后端 / 断网）。
2. **期望**：对 `target=payment` 的诊断仍正常；对 `target=order-service` 的诊断 **30 秒内**返回明确错误（不超时、不静默成功）。
3. 断言依据：S-ERR-5、C-CB-1、C-ISO-1。

### 场景 F · 多客户端并发（→ SC-004）

1. 多个 Claude Code 客户端同时经同一网关对**不同** target 诊断。
2. **期望**：互不干扰，结果正确归属各自 target；某慢后端不拖慢其他。
3. 断言依据：S-CALL-3、C-ISO-1。

---

## 5. 测试（真实环境，零桩；TDD 真实性硬约束）

测试分两类驱动，**都跑在真实 arthas + 真实业务服务上**（Testcontainers 拉起，故障用真实故障条件，**无 WireMock 桩**）：

### 5.1 工具可用性（Claude Code 驱动）

真实 Claude Code 注册网关为 MCP server → 调用工具 → 断言**调用成功**（无 JSON-RPC error、无网关故障）。**不做**结果一致性比对（一致性交 §5.2 SDK 断言）。

**已实证（T036，2026-06-20，网关 + 真实 Claude Code v2.1.183 跑通）**：

```bash
# 1) 启网关（默认 HTTP :8761，见 §2.2），待 /actuator/health=UP
./mvnw spring-boot:run &

# 2) 真实 Claude Code 经 MCP 枚举工具（--strict-mcp-config 仅用本配置，不污染全局）
claude -p "列出 'arthas-gw' 暴露的全部工具名，仅输出 JSON 字符串数组" \
  --mcp-config target/smoke-mcp-config.json --strict-mcp-config
# → 实测返回 35 个工具名（4 自有 arthas-gateway.* + 31 arthas），SC-001 经真实客户端验证

# 3) 真实 Claude Code 调用网关自有工具（--allowedTools 授权 MCP 工具句柄）
claude -p "调用 arthas-gw 的 list-targets，原样输出结果" \
  --mcp-config target/smoke-mcp-config.json --strict-mcp-config \
  --allowedTools "mcp__arthas-gw__*"
# → 实测返回 {"targets":[{"name":"order-service","state":"ACTIVE","healthy":true,"protocol":"STREAMABLE"},
#                          {"name":"payment",...}],"version":1}
```

> **arthas 工具逐工具冒烟**：每个 arthas 工具需真实 target——先在 `config/backends.yaml` 配真实可达的 arthas MCP 后端（或经热重载 §场景 D 注入），再用 `claude -p --allowedTools "mcp__arthas-gw__<工具句柄>"` 逐个调用断言成功。arthas 路由正确性已由 §5.2 真实 arthas SDK 集成测试覆盖（`jvm`/`watch`/`dashboard` 等经网关返回真实诊断、与直连一致）；Claude Code 与 SDK client 走**同一** Streamable HTTP 传输，故 arthas 工具在 Claude Code 下同等可用。
>
> 注：Claude Code 把工具句柄中的 `.` 显示为 `_`（如 `mcp__arthas-gw__arthas-gateway_list-targets`），`--allowedTools` 通配 `mcp__arthas-gw__*` 即可覆盖全部 35 工具。

### 5.2 结果一致性 + 双侧协议契约（官方 SDK client 驱动）

```bash
# 一致性 A/B（网关 vs 直连目标 arthas）+ 双侧协议契约，全真实环境
./mvnw verify -Dtest="com.arthas.gateway.**"
```

- **结果一致性**（SC-005）：SDK client 分别连 网关 / 直连目标 arthas，同一诊断 A/B 对照，逐字段断言 `CallToolResult` 完全一致。
- **服务端契约**：`server-contract.md` §7 的 S-* 断言。
- **客户端契约**：`backend-client-contract.md` §8 的 C-* 断言（故障用真实条件：停容器/错 token/sleep/6 并发越界）。
- **自有工具契约**：`gateway-tools-contract.md` §6 的 G-* 断言。
- **官方一致性**：可额外跑官方 conformance-tests 子套件。

> 除"网关健康检查"（Actuator `/actuator/health`）外，所有 MCP 调用**不 curl 裸打**——可用性走 Claude Code，一致性/契约走 SDK client。所有测试**先于实现编写**（TDD，宪法原则七），`./mvnw verify` 合并前须全绿。

---

## 6. 排查指引

| 现象 | 检查 |
|---|---|
| Claude Code 看不到工具 | 网关是否启动、传输配置、`tools/list` 是否返回 35（场景 A） |
| 路由到错误目标 | `target` 是否正确、`list-targets` 是否含期望目标、`arguments` 是否含 target（应被剥离，C-CALL-1） |
| 异步任务一直 working | 后端是否可达、后台是否超 11min 标 failed（G-ASYNC-2）、`task-get` 的 error.reason |
| 热重载未生效 | `config/backends.yaml` 路径、WatchService 事件、`version` 是否递增、加载失败是否保留旧表 |
| 401 / 认证失败 | `auth.mode` 与 token/user+pass 是否匹配后端 password（C-AUTH-1） |

详细定位见上游 [问题定位反向索引](../../reference/arthas-docs/03-MCP/问题定位反向索引.md)。
```


---

## `specs/001-arthas-mcp-gateway/research.md`

```markdown
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
| [问题定位反向索引](../../reference/arthas-docs/03-MCP/问题定位反向索引.md) | 症状→源码定位 | 核心包 `com.taobao.arthas.core.mcp`；MCP 协议常量在 `McpSchema.java` |
| [MCP能力清单](../../reference/arthas-docs/03-MCP/MCP能力清单.md) | 摘抄拷贝单一事实源 | 31 工具全量定义；resource=0、prompt=0；27 forbidden / 5 optional / 0 required |
| [MCP线契约](../../reference/arthas-docs/03-MCP/MCP线契约.md) | 网关↔Claude Code 服务端契约 | 协议 `2025-11-25`；initialize/tools/task/error 线格式 |
| [后端接入契约](../../reference/arthas-docs/03-MCP/后端接入契约.md) | 网关↔arthas 后端客户端契约 | 复用 http console `/mcp`；Bearer=后端 password；session 25min；task 并发上限 5；task TTL 30min |
| [工具传输分类表](../../reference/arthas-docs/03-MCP/工具传输分类表.md) | 逐工具路由速查 | 同步直发 27 / 流式 1（dashboard）/ 任务型 5（watch/trace/stack/tt/monitor） |

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

- **Decision**：启动期构建**静态工具注册表**（31 工具，schema 逐字摘抄自 [MCP能力清单](../../reference/arthas-docs/03-MCP/MCP能力清单.md)），每个工具在对外 inputSchema 中**额外注入顶层 `target`（string, required=true）**；运行时 `tools/call` 从 `arguments` 取出 `target` 并剥离，剩余键作为后端 `tools/call` 的 arguments；路由判定不查后端，纯靠静态表 + 注册表。
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
```


---

## `specs/001-arthas-mcp-gateway/spec.md`

```markdown
# Feature Specification: Arthas MCP 网关

**Feature Branch**: `001-arthas-mcp-gateway`

**Created**: 2026-06-19

**Status**: Draft

**Input**: User description: "依据头脑风暴结论生成"（arthas MCP 网关——统一管理多个 arthas 后端，通过标准 MCP 接口供 Claude Code 等 AI 客户端使用）

## Clarifications

### Session 2026-06-19

- Q: SC-003 中"合理时间内"应量化为多少秒？ → A: 30 秒
- Q: 网关聚合的 MCP 原语范围？tool/resource/prompt 定义从何而来？ → A: 聚合 tools+resources+prompts 全部三类；定义取自 arthas 源码（静态摘抄、不从各后端动态发现），调用仍按 `target` 路由转发到后端执行。（据此修订宪法原则二至 v1.2.0，并同步更新 FR-008 与相关边缘情况/假设。）

## 用户场景与测试 *(mandatory)*

### 用户故事 1 - 通过统一入口诊断多个目标 JVM（Priority: P1）

运维或开发人员希望通过 Claude Code（或其他支持 MCP 的 AI 客户端），使用**一个固定的网关地址**，对**多个**目标 Java 进程执行 arthas 诊断。无需为每个目标单独配置连接，只需在调用诊断工具时用 `target`（逻辑名，如 `order-service`）指明对哪个目标操作，网关自动路由到对应的目标 JVM。

**为何这个优先级**：这是网关存在的核心价值——把"多个 arthas、多个目标"统一成"一个入口、一个工具集"。没有它，网关就失去意义。

**独立测试**：配置 2-3 个目标后端后，通过 Claude Code 调用一个诊断工具（如观察方法）并指定不同 `target`，确认每次调用的结果都来自正确的目标 JVM。

**验收场景**：

1. **Given** 网关已配置 3 个目标后端（order / payment / inventory），**When** 用户经 Claude Code 调用"观察方法"工具并指定 `target=order-service`，**Then** 返回的结果来自 order-service 这个 JVM。
2. **Given** 同一网关与配置，**When** 用户对 `target=payment` 调用同一工具，**Then** 结果来自 payment JVM，与 order-service 的结果相互独立。
3. **Given** 网关已就绪，**When** Claude Code 请求可用工具列表，**Then** 看到 arthas 的诊断工具（每个工具带 `target` 参数），且工具数量不随后端数量膨胀。

---

### 用户故事 2 - 动态管理目标后端，无需重启（Priority: P2）

运维人员通过编辑配置（后端映射表：逻辑名 → 目标地址）来**新增或移除**目标 JVM，网关**热重载**配置、无需重启，且 Claude Code 的可用目标列表随之自动更新。

**为何这个优先级**：目标 JVM 会动态增减；要求重启网关才能生效会严重影响可用性与体验。

**独立测试**：在网关运行中修改配置新增一个目标，确认短时间内 Claude Code 的工具 `target` 可选项包含新目标；移除一个目标后，该目标不再可选。

**验收场景**：

1. **Given** 网关运行中且已有 2 个目标，**When** 运维在配置中新增第 3 个目标并保存，**Then** 短时间内（无需重启）Claude Code 调用工具时 `target` 可选新目标，且对新目标的诊断成功。
2. **Given** 配置含某目标，**When** 运维从配置移除该目标并保存，**Then** 该目标从可选列表消失，对它的调用返回明确错误。

---

### 用户故事 3 - 单点故障不影响整体可用（Priority: P3）

当某个目标 JVM 不可达（宕机、网络中断）时，网关**隔离**该故障：对其他目标的诊断**不受影响**，而对失效目标的调用返回**明确的错误信息**（而非模糊失败或长时间挂起）。

**为何这个优先级**：网关面向多目标，单点故障若波及全局则失去聚合意义。

**独立测试**：使一个目标后端不可达，确认对其他目标的诊断仍正常，对失效目标的调用返回清晰错误。

**验收场景**：

1. **Given** 网关配置 3 个目标，其中 order-service 不可达，**When** 用户对 `target=payment` 调用诊断，**Then** 正常返回结果。
2. **Given** 同上，**When** 用户对 `target=order-service` 调用诊断，**Then** 收到明确错误（目标不可用），而非超时或空结果。

---

### 边缘情况

- `target` 指定的逻辑名不存在于当前配置 → 返回明确错误，并给出当前可用目标列表。
- 多个 Claude Code 客户端同时使用同一网关 → 互不干扰，结果正确归属各自目标。
- 配置热重载进行中恰好有并发诊断请求 → 不出现错乱，请求路由到重载前后一致的目标。
- 目标后端返回错误 → 错误信息被如实传递给调用方，不被吞没或改写。
- 不同目标后端的诊断工具集不完全一致 → 网关向调用方暴露的工具集为 arthas 的规范集（静态、取自源码）；若某目标的后端实际不支持被调用的工具，该调用的错误被如实传递给调用方（不静默成功）。
- `target` 未提供或为空 → 返回明确错误（缺少必要参数）。

## 需求 *(mandatory)*

### 功能需求

- **FR-001**: 网关必须作为标准 MCP 服务端，通过 HTTP 接口向 Claude Code 等 AI 客户端暴露 arthas 诊断能力。
- **FR-002**: 网关必须能同时连接并管理**多个** arthas MCP 后端（每个对应一个目标 JVM）。
- **FR-003**: 调用诊断工具时，用户必须能通过 `target` 参数（逻辑名）指定对哪个目标 JVM 执行操作。
- **FR-004**: 网关必须按 `target` 将诊断请求路由到对应后端，并把后端返回的结果**原样**传递给调用方，不篡改、不截断、不摘要。
- **FR-005**: 后端映射表（逻辑名 → 目标地址）必须可通过配置文件维护，且支持**热重载**（修改后无需重启即生效）。
- **FR-006**: 当某个目标后端不可用时，网关必须**隔离**故障——其他目标后端的诊断不受影响；对失效目标的调用必须返回明确错误（不长时间挂起、不静默成功）。
- **FR-007**: 每次诊断操作必须**可追溯**：记录目标、操作、结果状态，便于排查。
- **FR-008**: 网关向调用方暴露的工具/资源/提示词定义必须忠实反映 arthas 的规范能力——取自 arthas 的规范定义（静态、不从各后端动态发现），不做无依据的改写；各诊断调用按 `target` 路由转发到对应后端执行，调用结果原样透传。
- **FR-009**: 当目标后端集合发生变化（增减后端）时，对调用方暴露的目标列表必须相应更新并通知调用方。

### 关键实体

- **目标后端（Target Backend）**：代表一个被诊断的目标 JVM。关键属性：逻辑名（唯一标识，如 `order-service`）、目标地址（连接用）、健康状态（可用 / 不可用）。
- **后端映射表（Backend Registry）**：所有目标后端的集合，即"逻辑名 → 目标地址"的映射；通过配置维护，可热重载。
- **诊断工具（Diagnostic Tool）**：arthas 后端暴露的诊断能力（如观察方法、追踪调用、查看线程等），每个工具带 `target` 参数以选择执行目标。

## 成功标准 *(mandatory)*

### 可度量结果

- **SC-001**: 用户通过**单一网关地址**，可在至少 3 个不同目标 JVM 上各自完成一次诊断操作，每个操作均返回来自正确目标的结果。
- **SC-002**: 新增或移除一个目标 JVM 后，**无需重启**网关，Claude Code 的可用目标列表在 30 秒内自动反映变化。
- **SC-003**: 当某个目标 JVM 不可达时，对其他目标的诊断**全部正常**；对失效目标的调用在 **30 秒内**返回**明确的**错误提示。
- **SC-004**: 多个 Claude Code 客户端**同时**使用同一网关执行诊断时，互不干扰、结果正确归属各自的目标。
- **SC-005**: 网关转发的诊断结果与直连对应 arthas 后端获得的结果**一致**（无篡改）。

## 假设

- 网关部署在**受控内网**环境；MVP 阶段不实现认证，依靠网络隔离保障安全（认证列为后续演进首要项）。
- 各目标 JVM 已运行 arthas 并暴露可用的 MCP 端点。
- 各目标后端运行同版本 arthas，诊断工具集基本一致；网关向调用方暴露 arthas 规范工具集，个别后端缺失某工具时由调用报错如实体现。
- 目标 JVM 数量从少量起步并逐步增长；MVP 以静态配置满足，架构预留向更大规模演进的接口。
- 单次诊断操作的耗时主要取决于目标 JVM 与 arthas 本身，网关转发开销可忽略。
```


---

## `specs/001-arthas-mcp-gateway/tasks.md`

```markdown
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
```


---

## `specs/002-code-review-remediation/checklists/requirements.md`

```markdown
# Specification Quality Checklist: 代码评审发现修复

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-06-21
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs) — 行为级描述，修复机制留给 /speckit-plan
- [x] Focused on user value and business needs — 面向网关运维/开发可观测行为
- [x] Written for non-technical stakeholders — WHAT/WHY 表述
- [x] All mandatory sections completed — 用户场景/需求/成功标准/假设齐备

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain — 评审报告已详尽给出修复方向，用户明确"修全部"，无需澄清
- [x] Requirements are testable and unambiguous — 每条 FR 有可观测断言
- [x] Success criteria are measurable — SC-001~SC-008 均可度量/可验证
- [x] Success criteria are technology-agnostic (no implementation details) — 未指定框架/语言/具体机制
- [x] All acceptance scenarios are defined — 每个用户故事含 Given/When/Then
- [x] Edge cases are identified — 含正常运行路径不受影响、误熔断、同步调用不受影响等
- [x] Scope is clearly bounded — 明确排除 REFUTED 候选；修复方式留给设计阶段
- [x] Dependencies and assumptions identified — 假设章含范围/真实性/逐步验证/基线

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria — FR-001~017，来源映射表逐条可追溯
- [x] User scenarios cover primary flows — 5 个用户故事覆盖 P0~P3 全部 15 项
- [x] Feature meets measurable outcomes defined in Success Criteria — SC-008 为回归门禁
- [x] No implementation details leak into specification — 行为级表述，HOW 留待 plan

## Notes

- 完整性核验：15 项发现（P0-1/2、P1-1/2/3/4、P2-1/2/3/4、P3-1/2/3/4/5）已 100% 映射至 FR-001~015，无遗漏；评审驳回候选（REFUTED-1/2/3）显式排除。
- 用户两条全局约束已固化：FR-016（不影响现有功能）+ FR-017/SC-008/假设「逐步验证」。
- 设计 HOW 决策（尤其 P1-1/P1-3/P1-4 的层级归属）未在本规范预设，留给 `/speckit-plan` 裁定。
- Items marked incomplete require spec updates before `/speckit-clarify` or `/speckit-plan`
```


---

## `specs/002-code-review-remediation/contracts/remediation-invariants.md`

```markdown
# Contracts: 整改不变量与契约点（Phase 1）

**Feature**: 002-code-review-remediation
**Date**: 2026-06-21

> 本特性为**只读评审整改**：对外可观测 MCP 契约须**逐项保持**。本文档分两部分：
> - **A. 保持的契约点**（FR-016）——整改不得改变的行为/报文。
> - **B. 新增/收紧的不变量**——评审要求修复带来的**预期行为修正**（非回归，须在契约测试显式断言）。
>
> 对外 MCP 报文与工具/资源定义的权威契约沿用 [001 contracts/](../../001-arthas-mcp-gateway/contracts/)（`server-contract.md`/`backend-client-contract.md`/`gateway-tools-contract.md`），本文不重复其 S-*/C-*/G-* 断言，只标注本次需**重跑**与**新增**的断言落点。

---

## A. 保持的契约点（不得改变 · FR-016）

### A.1 工具与资源定义（不变）

- `tools/list` 仍返回 **35 个工具**（31 arthas 各带 `target` required + 4 网关自有）；数量、name、description、inputSchema **逐字不变**。
- P3-5 死代码删除（`gatewayOwned`/`wireValue`/`REASON_CIRCUIT_OPEN`）不影响任何对外 schema——这些是无调用点的内部成员。
- **断言落点**：001 `server-contract.md` S-TL-1/2/3（重跑，须仍全绿）。

### A.2 路由语义（不变）

- `target` 参数仍被剥离、永不进 `backendArgs`；按 `target` 路由到对应后端。
- 同步/异步分流（26 SYNC_DIRECT / 1 STREAM_AGGREGATE / 5 ASYNC_TASK / 4 GATEWAY_LOCAL）不变。
- **断言落点**：S-CALL-1、C-CALL-1（重跑）。

### A.3 结果原样透传（不变 · 原则二）

- `invoke` 返回 `client.callTool()` 的原始 `CallToolResult`，不篡改/截断/摘要。
- 后端 `isError=true` 仍原样透传（同步）/ 原样存入 task.result（异步）。
- 后端 JSON-RPC error（含 code/message/data）原样透传。
- **断言落点**：C-RESULT-1/2、G-ASYNC-2（重跑）。

### A.4 错误传播结构（字段不变）

整改后错误仍以结构化 `McpError` 传播，**字段集逐字不变**：

| 错误情形 | code | data 字段 |
|---|---|---|
| 目标熔断中 | `backend_unreachable` | `reason`、`retryAfterMs`、`available`（全部目标名） |
| 目标不可达 | `backend_unreachable` | `reason`、`available` |
| 并发越界 | INVALID_PARAMS(-32602) | `maxConcurrentTasks`、`available` |
| target 缺失/不在册 | INVALID_PARAMS | `available` |
| 未知工具 | INVALID_PARAMS | — |

> 路由器翻译域异常→`McpError` 时，`data.available`/`retryAfterMs`/`reason`/错误码**必须与修复前逐字一致**（这是重构的安全边界）。
- **断言落点**：S-ERR-*、C-CB-1/2、C-ISO-1（重跑，报文逐字段断言）。

### A.5 热重载与健康（语义不变，实现收敛）

- 热重载增删目标、配置版本去重、加载失败保留旧表——语义不变。
- `list-targets` 返回的每目标 `healthy` 字段语义不变（ACTIVE 且非熔断 OPEN = healthy）；P3-2 仅把"三处重复判定"收敛为**单一事实源** `BackendEntry.isHealthy()`，**输出值不变**。
- `/actuator/health` 状态判定不变。
- **断言落点**：G-LT-1、S-ERR-2、HealthIndicator 断言（重跑）。

---

## B. 新增/收紧的不变量（预期行为修正 · 须断言）

> 这些是评审要求修复的行为变化，属**修正**而非回归。每条对应一条 FR 与契约断言。

### B.1 STATELESS 异步前置拒绝（P1-2 / FR-004）

- **变更**：对 STATELESS 后端的**异步类**诊断调用，**前置**返回 INVALID_PARAMS（`reason=stateless_unsupported_async`），不提交后台、不耗尽兜底超时。
- **保持**：STATELESS 后端的**同步**调用仍正常工作（FR-004 边缘情况）。
- **断言**：新增 `ToolsCallRouterStatelessTest`——STATELESS 异步立即收到结构化错误；同步调用正常。

### B.2 异步路径驱动熔断（P1-3 / FR-005）

- **变更**：纯异步负载下后端基础设施不可达，计入熔断 `recordFailure`；达阈值熔断 OPEN，后续调用被隔离。
- **保持**：人为取消（`InterruptedException`）**不计**失败；后端业务错误（`McpError`/`isError=true`）**不计**失败。
- **断言**：新增/更新 `BackendEntryInterceptionLayerTest`——异步 invoke 基础设施异常触发 `recordFailure`；取消中断不触发。

### B.3 熔断线程安全（P1-1 / FR-003）

- **新增不变量**：默认并发 N（N>1）下，多线程并发 `recordFailure`，连续失败计数**原子累加**，达阈值如期 OPEN；不出现"丢失更新导致该断不断"、不出现"HALF_OPEN 放行多个探测"。
- **断言**：新增 `CircuitBreakerConcurrencyTest`——真实多线程并发记录失败，断言熔断在阈值点精确开启。

### B.4 initialize 原子（P1-4 / FR-006）

- **新增不变量**：并发首次路由同一后端，仅一次握手（`initialize` 真正执行一次）。
- **断言**：新增 `HttpBackendClientInitializeCasTest`——并发调用 `initialize`，握手副作用恰好一次。

### B.5 关闭竞态无僵尸/无槽泄漏（P0-1/P0-2 / FR-001/002）

- **新增不变量**：后台池已关闭时提交异步任务——任务**不**长期驻留 WORKING（被 remove 或 markFailed）；已取的槽被释放（目标不锁死）。
- **新增不变量**：内层提交失败（orchestrate）——任务标终态（无僵尸）；槽经 `onTerminal` 释放。
- **断言**：新增 `AsyncTaskExecutorShutdownRaceTest`——关闭池后提交，断言 store 无残留 WORKING、槽许可全数回收。

### B.6 退役不切断 in-flight（P2-1 / FR-007）

- **新增不变量**：目标退役时，其上 in-flight 异步任务能在退役宽限（默认=backendTimeout）内完成，不被强制标 failed。
- **断言**：退役 + in-flight 异步任务场景，断言任务 completed（非 failed）。

### B.7 null 可选参数（P2-2 / FR-008）

- **新增不变量**：含 null value 的合法调用被正常路由转发，不因防御拷贝抛 NPE/内部错误。
- **断言**：新增 `DiagnosticRequestNullArgTest`——`backendArgs` 含 null value，构造与转发正常。

### B.8 单点查询 O(1)（P2-3 / FR-009）

- **新增不变量**：`TaskStore.get(taskId)` 不触发全表清理；单查开销与存储规模无关。
- **断言**：新增 `TaskStoreGetReadAmplificationTest`——大规模存储下 get 不扫全表（如用计数探针断言清理未被触发）。

### B.9 全局背压（P2-4 / FR-010）

- **新增不变量**：跨 target 累计 inflight 有全局上限；超限被拒绝/排队，不无界增长。
- **断言**：多 target 高并发，断言全局 inflight ≤ 上限。

### B.10 凭据脱敏（P3-1 / FR-011）

- **新增不变量**：`BackendConfig.Auth.toString()` 仅含 mode + 掩码，不含明文凭据。
- **断言**：新增 `BackendConfigAuthMaskingTest`——各 mode 下 toString 不含明文 token/username/password。

### B.11 健康单一事实源（P3-2 / FR-012）

- **新增不变量**：`list-targets`/`HealthIndicator`/`admit` 守卫三处健康判定均委托 `BackendEntry.isHealthy()`。
- **断言**：三处对同一后端返回一致的 healthy 值；改 `isHealthy` 一处，三处联动。

### B.12 配置解析拒截断（P3-4 / FR-014）

- **新增不变量**：`asInt` 对浮点/超大整数报错且错误信息保留原始值；`readVersion` 拒浮点。
- **断言**：新增 `BackendConfigLoaderParsingTest`——`5.0`/超 int 范围 Long 报错并保留原值。

### B.13 死代码清除（P3-5 / FR-015）

- **不变量**：`gatewayOwned`/`wireValue`/`REASON_CIRCUIT_OPEN` 已删；`asNullableString` 合并入 `asString`；编译通过、行为不变。
- **断言**：全套既有测试 + 冒烟全绿（无调用点丢失）。

---

## C. 契约测试执行（回归门禁）

| 套件 | 动作 | 来源 |
|---|---|---|
| 001 全部双侧契约（S-*/C-*/G-*） | **重跑**，须全绿（A 组保持点） | 001 `contracts/` |
| 本特性新增单测/契约（B.1~B.13） | **新增**，先于实现编写（TDD） | 本特性 `src/test/...` |
| 官方 conformance-tests 子套件 | 重跑（可选加固） | MCP SDK |
| 端到端冒烟（真实 arthas + 真实业务服务） | **重跑**，行为与修复前逐项一致（SC-008） | `quickstart.md` |

> 任何对外报文字段/行为的偏差即视为 FR-016 回归，须立即停下修复而非继续堆叠（用户约束）。
```


---

## `specs/002-code-review-remediation/data-model.md`

```markdown
# Data Model: 代码评审发现修复（Phase 1）

**Feature**: 002-code-review-remediation
**Date**: 2026-06-21

> 本特性**不新增数据实体**（spec「关键实体」已声明）。本文聚焦对既有实体的**方法/不变量级变更**，作为实现期的落点清单与契约依据。实体字段定义沿用 [001 data-model.md](../001-arthas-mcp-gateway/data-model.md)，此处只列「变了什么」。决策依据见 [research.md](./research.md)，架构见 [设计文档](../../docs/superpowers/specs/2026-06-21-code-review-remediation-design.md)，对外契约点见 [contracts/remediation-invariants.md](./contracts/remediation-invariants.md)。

---

## 1. 变更总览

| 实体 | 变更类型 | 关联发现 | 摘要 |
|---|---|---|---|
| `BackendEntry` | 新增方法 + 重构 | P0/P1/P3-2 | 成为统一拦截层：`execute`/`admit`/`invoke`/`isHealthy`/`releaseSlot` |
| `CircuitBreaker` | 方法签名不变、加锁 | P1-1 | `allowRequest`/`recordSuccess`/`recordFailure` 全 `synchronized` |
| `HttpBackendClient` | 方法不变、守卫化 | P1-4 | `initialize` 以 `AtomicBoolean.compareAndSet` 守卫，CAS 成功者才握手 |
| `AsyncTaskExecutor` | 方法签名扩展 + 新增背压 | P0/P2-4 | `submit` 增 `onTerminal` 参数；增全局 `Semaphore` |
| `TaskStore` | 方法行为微调 | P2-3 | `get` 只判查到那一条（不再全表清理） |
| `DiagnosticRequest` | 防御拷贝改法 | P2-2 | `unmodifiableMap(new LinkedHashMap<>(…))`（容忍 null value） |
| `BackendConfig.Auth` | 新增 `toString` | P3-1 | 脱敏（mode + 掩码） |
| `BackendConfigLoader` | `asInt` 语义收紧 + 合并方法 | P3-4/P3-5 | 拒浮点/超界保留原值；`asNullableString`→`asString` |
| `BackendConfigWatcher` | 退役宽限 + 线程管理 | P2-1 | `retirementGrace` 默认=backendTimeout；`retireAll` 用 `ScheduledExecutorService` |
| `ToolsCallRouter` | 重构（两路径变薄） | P0/P1 | 委托 `execute`/`admit`；翻译域异常→`McpError`；移除手动 `releaseSlot` |
| `GatewayToolHandlers` / `BackendRegistryHealthIndicator` | healthy 委托 | P3-2 | 委托 `BackendEntry.isHealthy()` |
| `McpJson`（新类） | 新增 | P3-3 | `handler` 包内 JSON 序列化单例 |
| 4 个域异常类（新类） | 新增 | P0/P1 | `CircuitOpenException`/`ConcurrencyLimitException`/`StatelessAsyncException`/`BackendUnreachableException` |
| `ExposedTool`/`TaskError`/`TaskSupport` | 删除死代码 | P3-5 | 删 `gatewayOwned()`/`REASON_CIRCUIT_OPEN`/`wireValue()` |

---

## 2. BackendEntry（统一拦截层 · 核心重构）

> 设计文档 §3.2 的目标架构。原散在 `ToolsCallRouter` 两路径的"熔断守卫 + 取/还槽 + 故障分类"收口到此。

### 2.1 新增/变更方法

| 方法 | 签名 | 行为 | 关联 |
|---|---|---|---|
| `execute` | `CallToolResult execute(String tool, Map<String,Object> args)` | **同步入口**：`admit` → `try { invoke } finally { releaseSlot }`。RAII 保证槽配对 | P0-2（同步路径槽不漏） |
| `admit` | `void admit(String tool)` | **异步前置准入**：① 协议校验（STATELESS 抛 `StatelessAsyncException`）→ ② 熔断守卫读（OPEN 抛 `CircuitOpenException(retryAfterMs)`）→ ③ 取槽（满抛 `ConcurrencyLimitException(maxConcurrentTasks)`）。**不调后端** | P1-2/P1-3(读)/P0-2 |
| `invoke` | `CallToolResult invoke(String tool, Map<String,Object> args)` | **真正调后端 + 故障分类**：`initializeOnce`(CAS) → `callTool`；成功/业务错误→`recordSuccess`；基础设施故障→`recordFailure` 并抛 `BackendUnreachableException`。返回原始 `CallToolResult`（原样透传） | P1-4/P1-3(写)/原则二 |
| `isHealthy` | `boolean isHealthy()` | **单一事实源**：`state==ACTIVE && breaker.state()!=OPEN` | P3-2 |
| `releaseSlot` | `void releaseSlot()` | 释放 per-target 槽（供异步 `onTerminal` 调） | P0-2 |
| `initializeOnce` | private `void initializeOnce()` | `if (initialized.compareAndSet(false,true)) client.initialize()` | P1-4 |

### 2.2 不变量

- **槽 RAII**（同步）：`execute` 的 `admit` 取槽与 `finally releaseSlot` 在同一作用域，任意路径必配对 → 同步路径槽**结构性不漏**。
- **槽 RAII**（异步）：`admit` 取槽；释放**唯一**由 `AsyncTaskExecutor.onTerminal` 负责；路由器闭包**不再**手动 `releaseSlot` → 异步路径槽**结构性不漏**。
- **故障分类一致性**：成功与业务错误（`McpError`）一律 `recordSuccess`，仅基础设施故障（`RuntimeException` 非 `McpError`）`recordFailure`。同步/异步共用同一 `invoke`，分类规则单点。
- **错误边界**：`admit`/`invoke` 抛**域异常**（携带 `retryAfterMs`/`maxConcurrentTasks`/`cause`），**不**依赖 `McpError`/注册表；结构化 `McpError`（含 `data.available`）由路由器翻译（`data.available` 需 `RegistryHolder`）。

---

## 3. CircuitBreaker（P1-1 线程安全）

状态机（CLOSED/OPEN/HALF_OPEN）与字段**不变**（见 001 data-model §8），仅三方法加锁：

| 方法 | 变更 |
|---|---|
| `allowRequest()` | 加 `synchronized` |
| `recordSuccess()` | 加 `synchronized` |
| `recordFailure()` | 加 `synchronized` |

**不变量**：默认 `maxConcurrentTasks=5` 下多线程同时记录失败/读守卫，计数与状态转换**原子一致**——无"丢失更新"、无"半开放行多个探测"。

> 熔断非热路径（每次 tools/call 的守卫/记录各一次），`synchronized` 开销可忽略（research.md §2.1）。

---

## 4. HttpBackendClient（P1-4 原子初始化）

| 字段 | 变更 |
|---|---|
| `initialized` | 新增 `AtomicBoolean initialized = new AtomicBoolean(false)`（替代原 volatile check-then-act） |

| 方法 | 变更 |
|---|---|
| `initialize()` | 改为 `initializeOnce` 语义：`if (initialized.compareAndSet(false,true)) { 真正握手 }` |

**不变量**：并发首次路由同一后端，仅 CAS 成功的单一线程发起握手；其余线程 CAS 失败直接跳过。无重复握手、无会话状态紊乱。

---

## 5. AsyncTaskExecutor（P0 / P2-4）

### 5.1 `submit` 签名扩展

```
submit(String tool, String target, Callable<CallToolResult> work, Runnable onTerminal)
```

新增 `onTerminal` 参数（执行器在任务到达**任意终态**或**提交失败**时调用，负责释放 per-target 槽与全局背压）。

| 路径 | 行为 |
|---|---|
| 外层 `pool.submit` 抛 `RejectedExecutionException` | `store.remove(taskId)`（P0-1 无僵尸）+ `onTerminal.run()`（P0-2 释放槽/背压）+ 原样抛出 |
| 正常 | 入存储 → 入池 → 返回 `GatewayTask` |

### 5.2 `orchestrate` 内层提交纳入 try

| 路径 | 行为 |
|---|---|
| 内层 `pool.submit(work)` 抛 `RejectedExecutionException` | `task.markFailed(BACKEND_UNREACHABLE)`（P0-1 标终态，无僵尸） |
| `finally` | `supervisorFutures.remove(taskId)` + `onTerminal.run()`（**任一**终态都释放槽/背压 → P0-2） |

### 5.3 全局背压（P2-4）

| 字段 | 类型 | 说明 |
|---|---|---|
| `globalInflight` | `Semaphore` | 跨 target 累计 inflight 上限（配置驱动，如 `gateway.async.global-max-inflight`） |

- `submit` 前 `globalInflight.tryAcquire()`（失败→拒绝/排队策略由配置决定，至少不无界增长）；`onTerminal` 时 `release()`。

### 5.4 不变量

- **无僵尸**（P0-1）：任务一旦进存储，要么成功驱动到终态，要么在提交失败时被 `remove`/`markFailed`——绝不长期驻留 WORKING。
- **槽/背压必释放**（P0-2/P2-4）：`onTerminal` 在外层拒绝、内层拒绝、正常完成、超时、取消、异常**所有**路径都执行。
- **取消不计熔断**：`InterruptedException` 在 `orchestrate` 层判别（`markCancelled`），不进 `invoke` 的 `recordFailure`。

---

## 6. TaskStore（P2-3）

| 方法 | 变更 |
|---|---|
| `get(taskId)` | 只判**查到的那一条**：存在且未过期→返回；过期→`remove(taskId)` 返 empty；不存在→返 empty。**不再**触发全表 `cleanExpired()` |
| `list()` | 保持：返回全部任务（顺带触发全表过期清理） |

**不变量**：单点查询 O(1)，与存储规模无关；过期清理由 `list` + 后台 `cleaner` 兜底，不放大单查开销。

---

## 7. DiagnosticRequest（P2-2）

| 方法 | 变更 |
|---|---|
| 防御拷贝构造 | 由 `Map.copyOf(backendArgs)` 改为 `Collections.unmodifiableMap(new LinkedHashMap<>(backendArgs))` |

**不变量**：容忍 null value（arthas 可选参数合法可 null）；保留不可变性 + 稳定迭代序（`LinkedHashMap`）。

---

## 8. BackendConfig.Auth（P3-1 脱敏）

新增方法：

| 方法 | 行为 |
|---|---|
| `toString()` | 仅含 `mode` + 凭据掩码（如 `Auth[mode=BEARER, token=****XX]`，末 2 位）；**不含**明文 token/username/password |

**不变量**：任何代码路径（日志/异常/调试）把 `Auth` 转文本，输出均脱敏。

---

## 9. BackendConfigLoader（P3-4 / P3-5）

### 9.1 `asInt` 语义收紧（P3-4）

| 输入 | 旧行为 | 新行为 |
|---|---|---|
| `Integer` | 通过 | 通过 |
| `Long`（在 int 范围内） | 截断 | 通过（校验范围） |
| `Long`（超 int 范围） | 截断为负/错值 | **报错并保留原始值** |
| `Double`/`Float`（如 `5.0`） | 截断为 5 | **报错并保留原始值** |
| 其他 | 通过/默认 | 报错并保留原始值 |

`readVersion` 同理拒浮点。

**不变量**：非整数/越界值**明确报错且错误信息含原始值**，绝不静默截断为看似合法的错值。

### 9.2 合并等价方法（P3-5）

`asNullableString` 删除，其 3 处调用点改用 `asString`（二者字节级等价，research.md §0 已确认）。

---

## 10. BackendConfigWatcher（P2-1）

| 字段/方法 | 变更 |
|---|---|
| `retirementGrace` 默认值 | 由固定 60s 改为 `= backendTimeout`（配置可覆盖） |
| `retireAll` 宽限执行 | 由裸 `Thread.startVirtualThread(sleep)` 改用可追踪 `ScheduledExecutorService`（容器关闭 graceful + `awaitTermination`） |

**不变量**：退役宽限 ≥ 异步兜底超时，保证 in-flight 异步任务可在退役窗口内完成、不被强制切断为 failed。

---

## 11. ToolsCallRouter（重构 · 两路径变薄）

| 方法 | 变更 |
|---|---|
| `forwardSync` | 调 `e.execute(tool, args)`；catch 4 类域异常翻译为结构化 `McpError`（含 `data.available`/`retryAfterMs`/`reason`）；`McpError`（后端业务错误）原样向上抛 |
| `submitAsync` | 调 `e.admit(tool)`（catch 翻译）；`asyncExecutor.submit(tool, target, () -> e.invoke(tool,args), e::releaseSlot)` |

**移除**：原两路径内联的 `guardCircuit`/`tryAcquireSlot`/`recordSuccess`/`recordFailure`/`releaseSlot`（全部下沉到 `BackendEntry`）。

**不变量**：路由器不再直接操作熔断/槽；错误翻译保持对外 `McpError` 字段与修复前**逐字一致**（`available`/`retryAfterMs`/`reason`/错误码）。

---

## 12. McpJson（新类 · P3-3）

| 成员 | 说明 |
|---|---|
| `static final ObjectMapper MAPPER` | 全局单例（Jackson 3.x 线程安全） |
| `static String json(ObjectNode/JsonNode)` | 序列化封装 |

消费方（原三处各自 `new ObjectMapper()`）改为委托 `McpJson`。

---

## 13. 域异常类（新类 · P0/P1）

均置于 `backend/` 包，携带翻译所需最小数据，**不**依赖 `McpError`/注册表：

| 类 | 携带数据 | 抛出点 | 路由器翻译为 |
|---|---|---|---|
| `StatelessAsyncException` | — | `admit`（STATELESS） | INVALID_PARAMS, `reason=stateless_unsupported_async` |
| `CircuitOpenException` | `long retryAfterMs` | `admit`（breaker OPEN 读） | `backend_unreachable`，data 含 `retryAfterMs` |
| `ConcurrencyLimitException` | `int maxConcurrentTasks` | `admit`（槽满） | INVALID_PARAMS，data 含 `maxConcurrentTasks` |
| `BackendUnreachableException` | `Throwable cause` | `invoke`（基础设施故障） | `backend_unreachable`，data 含 `available` |

---

## 14. 删除（P3-5 死代码）

| 位置 | 删除项 | 依据 |
|---|---|---|
| `ExposedTool` | `gatewayOwned()` | 全库无调用点 |
| `TaskError` | `REASON_CIRCUIT_OPEN` | 全库无引用 |
| `TaskSupport` | `wireValue()` | 全库无调用点 |
| `BackendConfigLoader` | `asNullableString`（合并入 `asString`） | 与 `asString` 字节级等价 |

**不变量**：删除后编译通过、行为不变（既有测试 + 冒烟全绿）。

---

## 15. 关系图（重构后）

```text
ToolsCallRouter
  │ forwardSync ──► BackendEntry.execute  ──(admit→invoke→finally releaseSlot)
  │ submitAsync ──► BackendEntry.admit ──► AsyncTaskExecutor.submit(work=invoke, onTerminal=releaseSlot)
  │                                          │ 外层 reject → store.remove + onTerminal
  │                                          │ orchestrate ── work=invoke ──► recordSuccess/Failure
  │                                          │ finally ──► onTerminal(releaseSlot)
  │ catch 域异常 ──► 翻译为 McpError(available/retryAfterMs/reason)

CircuitBreaker (synchronized: allowRequest/recordSuccess/recordFailure)
HttpBackendClient.initialize  (AtomicBoolean CAS)
BackendEntry.isHealthy ◄── listTargets / HealthIndicator / admit 守卫 (单一事实源)
```
```


---

## `specs/002-code-review-remediation/plan.md`

```markdown
# Implementation Plan: 代码评审发现修复

**Branch**: `002-code-review-remediation` | **Date**: 2026-06-21 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/002-code-review-remediation/spec.md`

**Note**: 本文件由 `/speckit-plan` 命令填写。架构层决策（深度重构 + 统一拦截层）见 [设计文档](../../docs/superpowers/specs/2026-06-21-code-review-remediation-design.md)；逐项技术决策与备选见 [research.md](./research.md)；先决研究事实（arthas 行为）见 `reference/arthas-docs/03-MCP/`。

## Summary

对 `001-arthas-mcp-gateway` MVP 整库评审的 **15 项发现**（P0×2 / P1×4 / P2×4 / P3×5）做整改，**不影响对外可观测 MCP 行为**、**每步验证既有功能完好**。

技术总路线（设计文档已定）：**深度重构（altitude）**——把"熔断守卫 + 槽管理（RAII）+ 故障分类"下沉为 `BackendEntry` 的**统一拦截层**（`execute`/`admit`/`invoke`/`isHealthy` 四原语），路由器同步/异步两路径变薄、都委托；`AsyncTaskExecutor` 增 `onTerminal` 回调，把槽释放/全局背压释放收归执行器单点保证（结构性消灭 P0-2 槽泄漏）。P2/P3 在此之上做健壮性与清理。无新增运行时依赖（全 JDK 内置 + 复用 Jackson）。

## Technical Context

**Language/Version**: Java 21（与 001 基线一致，不变）。

**Primary Dependencies**: **不变**（沿用 001）。`io.modelcontextprotocol.sdk:mcp-bom:2.0.0` + Spring Boot 4.1.0 + Spring AI `mcp-spring-webmvc` + Actuator。测试 JUnit 5 + AssertJ。**无新增依赖**——`synchronized`/`AtomicBoolean`/`Semaphore`/`ScheduledExecutorService`/`Collections.unmodifiableMap`/`LinkedHashMap` 均 JDK 内置；`McpJson` 复用既有 Jackson。

**Storage**: N/A（与 001 一致，无持久化）。内存态后端注册表 / taskStore / 熔断器状态语义不变。

**Testing**: JUnit 5 + AssertJ。**真实环境、零桩**（宪法原则四/七 + CLAUDE.md 硬约束）。驱动分层（与 001 一致）：网关自身并发/资源逻辑用**真实 JVM 并发原语** + DIP 缝注入受控 callable/client 触发**真实失败条件**（非 arthas 成功桩）；与 arthas 交互——可用性走真实 Claude Code MCP、一致性/双侧契约走官方 MCP Java SDK client；故障用真实条件（停后端/错 token/sleep/6 并发越界）。TDD 红绿重构。

**Target Platform**: 与 001 一致（受控内网 JVM 服务）。

**Project Type**: web-service（沿用 001 单 Maven 模块；本特性**不新增包**，改既有包内类）。

**Performance Goals**:
- 槽/熔断同步开销可忽略（熔断非热路径；`synchronized` 仅护熔断状态机三方法）。
- 单点任务查询 O(1)（FR-009，与存储规模无关）。
- 其余沿用 001（同步转发开销可忽略、失效目标 30s 内明确错误、热重载 30s 内生效、多客户端互不干扰）。

**Constraints**:
- **不影响对外可观测 MCP 行为**（FR-016）：报文结构/字段（`available`/`retryAfterMs`/`reason`）、工具与资源定义、路由、原样透传、热重载、错误传播、健康状态逐项一致。
- 每步 TDD + 既有测试 + 冒烟全绿（FR-017）。
- 无新增运行时依赖；沿用既有构建/格式化/检查设置。

**Scale/Scope**: 15 项发现全修；不动协议契约结构、不动并发上限语义（同步+异步一起挡 5，保持现状）、不做评审驳回候选（REFUTED-1/2/3）。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

**宪法版本**：v1.2.0。逐原则核验（本特性为整改，对各原则的影响标「保持/强化」）：

| # | 原则 | 状态 | 依据 / 落地方式 |
|---|---|---|---|
| 一 | MCP 规范符合性 | ✅ 保持 | 统一拦截层不触碰 JSON-RPC 帧；`execute`/`invoke` 仅围绕官方 SDK `callTool` 做生命周期包装，不新增协议原语 |
| 二 | 透明无损聚合 | ✅ 保持 | `invoke` 返回 `client.callTool()` 原始 `CallToolResult` 原样透传；错误仍以结构化 `McpError` 传播，字段不变 |
| 三 | 局部故障韧性 | ✅ **强化** | P1-1 熔断线程安全（synchronized）、P1-3 异步驱动熔断、P2-4 全局背压——纯异步负载与集群规模下故障隔离更可靠；per-target 独立不变 |
| 四 | 双侧契约 | ✅ 保持 | 核心 `tools/call` 路径重写后重跑全部双侧契约测试；P1-2（STATELESS 前置拒绝）、P1-3（异步驱动熔断）为评审要求的**预期行为修正**，新增/更新对应契约测试显式断言 |
| 五 | 可观测性 | ✅ 保持 | 结构化日志（tool/target/isError/duration）不变；MCP 错误码与后端错误显式传播不变；P3-2 健康判定单一事实源使仪表盘与实际行为一致 |
| 六 | Java 主力 | ✅ 保持 | 全 Java 21；无引入非 Java 运行时依赖 |
| 七 | 测试驱动 | ✅ 保持 | 每项 TDD 红绿重构；真实性分层（网关自身逻辑用真实并发原语、与 arthas 交互遵循驱动分层），零桩 |
| 八 | 先决研究 | ✅ 保持 | 精读 15 项发现涉及的全部源码逐项核对（research.md §0）；arthas 5 并发上限语义（`DEFAULT_MAX_CONCURRENT_TASK_SESSIONS=5`）已查证 |

**技术与传输约束**：Java 21 ✅；Maven 锁定可复现 ✅；异步非阻塞多路复用（虚拟线程）保持 ✅；后端注册表配置声明（不硬编码）保持 ✅；协议核心优先官方 SDK、不手写帧 ✅。

**质量门禁**：TDD ✅；每步既有测试 + 冒烟全绿 ✅；CI 复现本地构建 ✅；FR-016 回归门禁（全套测试 + 端到端冒烟逐项一致）✅。

**结论**：✅ **门禁通过，无原则冲突**。本特性整体为"强化与保持"，不存在需 Complexity Tracking 记录的违规项。

## Project Structure

### Documentation (this feature)

```text
specs/002-code-review-remediation/
├── plan.md              # 本文件（/speckit-plan 产出）
├── research.md          # Phase 0 产出（/speckit-plan）
├── data-model.md        # Phase 1 产出（/speckit-plan）
├── quickstart.md        # Phase 1 产出（/speckit-plan）
├── contracts/
│   └── remediation-invariants.md   # Phase 1 产出：保持的契约点 + 新增不变量
└── tasks.md             # Phase 2 产出（/speckit-tasks，本命令不创建）
```

### Source Code (repository root)

> 本特性**不新增包、不新增模块**，在 001 既有结构内改既有类。下图标注每项改动落点（`△`=修改、`+`=新增方法/类、`−`=删除死代码）。

```text
src/main/java/com/arthas/gateway/
├── backend/
│   ├── BackendEntry.java           △ 统一拦截层：execute/admit/invoke/isHealthy/releaseSlot(+)
│   ├── BackendClient.java          (不变，接口)
│   ├── HttpBackendClient.java      △ initialize CAS 守卫(P1-4)（AtomicBoolean+）
│   ├── CircuitBreaker.java         △ allowRequest/recordSuccess/recordFailure synchronized(P1-1)
│   ├── Protocol.java               (不变；STATELESS 契约语义由 admit 校验)
│   ├── BackendConfig.java          △ Auth.toString 脱敏(P3-1)
│   ├── BackendConfigLoader.java    △ asInt 拒浮点/超界保留原值(P3-4)；asNullableString→asString 合并(P3-5)
│   └── BackendConfigWatcher.java   △ retirementGrace 默认=backendTimeout(P2-1)；retireAll 用 ScheduledExecutorService
├── handler/
│   ├── ToolsCallRouter.java        △ forwardSync/submitAsync 委托 execute/admit；翻译域异常→McpError；移除手动 releaseSlot(P0-2/P1-2/P1-3)
│   ├── GatewayToolHandlers.java    △ listTargets healthy 委托 BackendEntry.isHealthy(P3-2)
│   ├── DiagnosticRequest.java      △ 防御拷贝容忍 null(P2-2)（unmodifiableMap(LinkedHashMap)）
│   └── McpJson.java                + JSON 序列化单例(P3-3)（handler 包内 static MAPPER）
├── task/
│   ├── AsyncTaskExecutor.java      △ submit 增 onTerminal 参数；全局 Semaphore(P2-4)；外层 RejectedExecution→remove+onTerminal(P0-1/P0-2)；orchestrate 内层 submit 纳入 try(P0-2)
│   └── TaskStore.java              △ get 只判查到那一条(P2-3)
├── obs/
│   └── BackendRegistryHealthIndicator.java  △ healthy 委托 BackendEntry.isHealthy(P3-2)
├── tool/
│   ├── ExposedTool.java            − gatewayOwned()(P3-5 死代码)
│   └── TaskSupport.java            − wireValue()(P3-5 死代码)
└── task/
    └── TaskError.java              − REASON_CIRCUIT_OPEN(P3-5 死代码)

src/main/java/com/arthas/gateway/backend/   (新增域异常类，统一拦截层抛出、路由器翻译)
├── CircuitOpenException.java        + (retryAfterMs)
├── ConcurrencyLimitException.java   + (maxConcurrentTasks)
├── StatelessAsyncException.java     +
└── BackendUnreachableException.java + (cause)

src/test/java/com/arthas/gateway/
├── backend/
│   ├── CircuitBreakerConcurrencyTest.java      + 真实并发记录失败(P1-1/FR-003)
│   ├── HttpBackendClientInitializeCasTest.java + 并发首次握手只一次(P1-4/FR-006)
│   ├── BackendEntryInterceptionLayerTest.java  + execute/admit/invoke 统一拦截(P0/P1-2/P1-3/FR-012)
│   └── BackendConfigAuthMaskingTest.java       + toString 脱敏(P3-1/FR-011)
├── task/
│   ├── AsyncTaskExecutorShutdownRaceTest.java  + 池关闭竞态：无僵尸/无槽泄漏(P0-1/P0-2/FR-001/002)
│   └── TaskStoreGetReadAmplificationTest.java  + get 不扫全表(P2-3/FR-009)
├── handler/
│   ├── ToolsCallRouterStatelessTest.java       + STATELESS 异步前置拒绝(P1-2/FR-004)
│   ├── DiagnosticRequestNullArgTest.java       + null 可选参数(P2-2/FR-008)
│   └── BackendConfigLoaderParsingTest.java     + 浮点/超界报错保留原值(P3-4/FR-014)
└── contract/                       (沿用 001 既有双侧契约套件，新增/更新针对 P1-2/P1-3 的断言)
```

**Structure Decision**: 沿用 001 单 Maven 模块、按职责分包。本特性不新增包结构，新增的 4 个域异常类归入 `backend/`（与抛出方 `BackendEntry` 同包，体现"域异常属后端域"）；`McpJson` 归 `handler/`（与三处消费方同包）。改动严格限定在评审点，不做无关重构（CLAUDE.md「简单性、沿用既有模式」）。

## Complexity Tracking

> Constitution Check 全部通过，无违规项需记录。本表为空。

（无。深度重构属评审点 P1 的根因整治，非超出范围的新增能力——其"复杂度"已由「逐步 TDD + 每步冒烟 + 重写后重跑双侧契约测试」对冲，记录于设计文档 §八风险表，不在此重复。）
```


---

## `specs/002-code-review-remediation/quickstart.md`

```markdown
# Quickstart: 代码评审发现修复（端到端验证指南）

**Feature**: 002-code-review-remediation | **Date**: 2026-06-21

> 本文是**可运行的端到端验证指南**，证明 15 项发现的整改达成 spec 的 SC-001~SC-008，且**不影响对外 MCP 行为**（FR-016）。实现细节归属 `tasks.md`（/speckit-tasks 产出）与实现代码。基线运行方式与 [001 quickstart](../001-arthas-mcp-gateway/quickstart.md) 一致，本文聚焦"整改专项验证 + 全量回归"。实体变更见 [data-model.md](./data-model.md)，对外契约点见 [contracts/remediation-invariants.md](./contracts/remediation-invariants.md)。

---

## 1. 前置条件

与 001 一致（JDK 21 / Maven / ≥1 个真实 arthas MCP 后端 + 真实业务服务 / Claude Code / 受控内网）。整改不改变前置条件。

> **真实性硬约束**（CLAUDE.md）：网关自身并发/资源逻辑用真实 JVM 并发原语测，**零桩**；与 arthas 交互遵循驱动分层（可用性走 Claude Code、一致性/契约走官方 SDK client）。

---

## 2. 构建与运行（与 001 一致）

```bash
./mvnw clean verify          # 本地与 CI 同命令
./mvnw spring-boot:run       # 默认 HTTP :8761，端点 /mcp
```

启动后 `/actuator/health` 返回 `{"status":"UP"}`。配置后端映射表 `config/backends.yaml`、接入 Claude Code 方式同 [001 quickstart §2~§3](../001-arthas-mcp-gateway/quickstart.md)。

---

## 3. 整改专项验证场景（每项映射 FR/SC）

> 这些场景证明 15 项发现已修复。网关自身逻辑（A/B/C/D/F）用单测/集成测；与 arthas 交互（E）用真实后端。

### 场景 A · 关闭竞态无僵尸、无槽泄漏（→ FR-001/002，SC-001）

1. 使后台执行池进入关闭/拒绝提交的竞态（如注入一个会在 submit 时抛 `RejectedExecutionException` 的受控执行池，触发**真实失败条件**）。
2. 此时提交异步任务。
3. **期望**：任务**不**长期驻留 WORKING（被 `store.remove` 或 `markFailed`）；已取的 per-target 槽被 `onTerminal` 释放（许可全数回收）；该目标重启后仍可被正常调用。
4. 断言：`AsyncTaskExecutorShutdownRaceTest`。

### 场景 B · 熔断线程安全 + 异步驱动熔断 + 取消不计（→ FR-003/005，SC-002）

1. 默认并发 N>1，对同一目标并发记录连续基础设施失败（受控 client 抛连接异常=**真实失败条件**）。
2. **期望**：达阈值（连续 3 次）熔断如期 OPEN，无"丢失更新"。
3. 对纯异步路径发起不可达调用：**期望**失败计入熔断，后续被隔离。
4. 人为取消一个进行中异步任务：**期望**不 `recordFailure`、不误熔断。
5. 断言：`CircuitBreakerConcurrencyTest`、`BackendEntryInterceptionLayerTest`。

### 场景 C · STATELESS 异步前置拒绝（→ FR-004，SC-003）

1. 配置一个 STATELESS 后端（`protocol: STATELESS`）。
2. 对其调用一个异步类工具（如 `watch`）。
3. **期望**：**立即**收到 INVALID_PARAMS（`reason=stateless_unsupported_async`），不提交后台、不耗尽兜底超时。
4. 对照：对其**同步**类工具调用仍正常（边缘情况）。
5. 断言：`ToolsCallRouterStatelessTest`。

### 场景 D · initialize 原子（→ FR-006）

1. 并发首次路由同一后端（受控 client 计数握手副作用）。
2. **期望**：握手副作用**恰好一次**。
3. 断言：`HttpBackendClientInitializeCasTest`。

### 场景 E · 与真实 arthas 的回归（→ FR-016，SC-008）

> 用真实 arthas MCP 后端 + 真实业务服务，确认整改后**对外行为与修复前逐项一致**。沿用 001 场景 B/C/D/E/F。

1. 工具集：`tools/list` 仍 35 个、每个 arthas 工具带 `target`（场景 A）。
2. 路由：`jvm target=order-service` 结果来自正确 JVM、与直连一致（场景 B）。
3. 异步长任务：`watch` 立即返回 taskId→`task-get` 取最终结果原样（场景 C）。
4. 热重载：增删目标 30s 内生效（场景 D）。
5. 故障隔离：停 `order-service`，对 `payment` 仍正常、对 `order-service` 30s 内明确错误（场景 E）。
6. 多客户端并发互不干扰（场景 F）。
7. 驱动：可用性走 `claude -p --mcp-config target/smoke-mcp-config.json --strict-mcp-config`；一致性/双侧契约走 `./mvnw verify`。

### 场景 F · 健壮性四项（→ FR-007/008/009/010，SC-004/005）

1. **退役不切断**（FR-007）：目标有 in-flight 异步任务时退役，任务能在退役宽限（默认=backendTimeout）内 completed（非 failed）。
2. **null 参数**（FR-008）：调用诊断工具传入含 null 的可选参数，正常转发不报内部错误。断言：`DiagnosticRequestNullArgTest`。
3. **单查 O(1)**（FR-009）：大规模 task 存储下高频 `task-get`，单查不扫全表。断言：`TaskStoreGetReadAmplificationTest`。
4. **全局背压**（FR-010）：多 target 高并发，跨 target 累计 inflight ≤ 全局上限。

### 场景 G · 安全卫生与清理（→ FR-011/012/013/014/015，SC-006/007）

1. **凭据脱敏**（FR-011）：配置带凭据后端，`Auth.toString()` 仅 mode + 掩码。断言：`BackendConfigAuthMaskingTest`。
2. **健康单一事实源**（FR-012）：`list-targets`/`/actuator/health`/路由守卫三处 healthy 一致，改 `isHealthy` 一处联动。
3. **序列化单例**（FR-013）：`McpJson` 单例被三处复用（编译期/结构断言）。
4. **配置拒截断**（FR-014）：`backends.yaml` 写 `maxConcurrentTasks: 5.0` 或超大整数，加载报错且错误信息保留原值。断言：`BackendConfigLoaderParsingTest`。
5. **死代码清除**（FR-015）：`gatewayOwned`/`wireValue`/`REASON_CIRCUIT_OPEN` 已删，编译通过、全套测试全绿。

---

## 4. 测试（真实环境，零桩；TDD）

### 4.1 单元/集成（网关自身逻辑，真实 JVM 并发原语）

```bash
./mvnw verify -Dtest="com.arthas.gateway.**"
```

- 全部**先于实现编写**（TDD 红绿重构，宪法原则七）。
- 关闭竞态/熔断并发/槽 RAII/CAS/读放大——真实执行池、真实线程、真实并发计数，**非 arthas 成功桩**；受控 callable/client 触发**真实失败条件**（抛基础设施异常、拒绝提交、中断）。

### 4.2 双侧契约 + 结果一致性（官方 SDK client 驱动 · 真实 arthas）

```bash
./mvnw verify -Dtest="com.arthas.gateway.**"   # 含 001 既有双侧契约套件
```

- **重跑** 001 全部 S-*/C-*/G-* 断言（FR-016 保持点），须全绿。
- **新增** 针对 P1-2（STATELESS）/P1-3（异步熔断）的契约断言（[contracts/remediation-invariants.md](./contracts/remediation-invariants.md) B 组）。
- 故障用**真实条件**：停真实后端=不可达、错 token=arthas 真实 401、`Thread.sleep`=慢响应、真实发起 6 并发越界=arthas 真实 INVALID_PARAMS。

### 4.3 端到端冒烟（真实 Claude Code · SC-008）

```bash
# 启网关（默认 HTTP :8761）
./mvnw spring-boot:run &

# 真实 Claude Code 经 MCP 验证工具可用性（逐工具冒烟，仅验"能调通"）
claude -p "列出 'arthas-gw' 暴露的全部工具名，仅输出 JSON 数组" \
  --mcp-config target/smoke-mcp-config.json --strict-mcp-config
# → 35 个工具名（与修复前一致）

claude -p "调用 arthas-gw 的 list-targets，原样输出" \
  --mcp-config target/smoke-mcp-config.json --strict-mcp-config \
  --allowedTools "mcp__arthas-gw__*"
# → 每目标 healthy 值与修复前一致
```

> 报告模板：`arthas-mcp-gateway-冒烟测试报告.md`（memory `mcp-smoke-via-claude-p`）。

---

## 5. 逐步验证纪律（用户约束 · FR-017）

每修一项发现，按此顺序推进下一项前必须：

1. 该项的失败测试（红）→ 实现（绿）→ 重构。
2. **既有测试全绿**（`./mvnw verify`，确认未引入回归）。
3. **端到端冒烟全绿**（§4.3，确认对外行为完好）。
4. 任一步失败 → **停下**该修复链，先排查（CLAUDE.md「同一问题连续失败 3 次暂停」）。

推进顺序（契合设计文档 §七）：A 组核心（P1-1→P1-4→execute/admit/invoke/onTerminal→router 委托+STATELESS）→ B 组健壮性（P2-2→P2-1→P2-3→P2-4）→ C 组清理（P3 批量）→ 回归门禁（全套 + 冒烟）。

---

## 6. 排查指引（整改专项）

| 现象 | 检查 |
|---|---|
| 槽泄漏重现（目标被永久拒绝） | 路由器闭包是否还残留手动 `releaseSlot`；`onTerminal` 是否覆盖所有终态路径（场景 A） |
| 熔断"该断不断" | `CircuitBreaker` 三方法是否都已 `synchronized`；异步 invoke 是否真的 `recordFailure`（场景 B） |
| STATELESS 异步仍挂起 | `admit` 是否在提交后台前校验协议（场景 C） |
| in-flight 被退役切断 | `retirementGrace` 是否 ≥ backendTimeout（场景 F.1） |
| null 参数报错 | `DiagnosticRequest` 是否仍用 `Map.copyOf`（应已改 unmodifiableMap+LinkedHashMap）（场景 F.2） |
| 对外报文字段偏差 | 路由器翻译域异常→`McpError` 的 `available`/`retryAfterMs`/`reason`/code 是否逐字一致（FR-016 回归） |
```


---

## `specs/002-code-review-remediation/research.md`

```markdown
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
```


---

## `specs/002-code-review-remediation/spec.md`

```markdown
# Feature Specification: 代码评审发现修复

**Feature Branch**: `002-code-review-remediation`

**Created**: 2026-06-21

**Status**: Draft

**Input**: User description: "参考 `docs/code-review/2026-06-20-business-code-review.md`，在不影响现有功能的前提下，修复其中提到的所有问题"

## 背景与范围说明

本特性是对 `001-arthas-mcp-gateway`（arthas MCP 网关 MVP）业务代码的一次**只读评审整改**。评审报告（`docs/code-review/2026-06-20-business-code-review.md`，max effort，整库审查）共提出 **15 项发现**，覆盖正确性/并发缺陷（P0）、契约违背/设计代价（P1）、健壮性/效率/资源（P2）、安全卫生/清理（P3）四个严重等级。

本特性的核心约束有两条，贯穿全部需求与验收：

1. **不影响现有功能**：所有修复均为缺陷修复/健壮性增强/代码清理，**不得改变**网关对外的可观测 MCP 行为——工具与资源定义、按 `target` 路由、调用结果原样透传、热重载、错误传播、健康状态，均须与修复前**逐项一致**。
2. **每完成一步必须验证现有功能完好**：每修一项发现，都须先确认既有测试与端到端冒烟仍全绿，再推进下一项（详见「成功标准」与「假设」）。

> 说明：评审报告对每项发现已给出「修复方向」，但这些「方向」属于**实现/设计决策（HOW）**，归 `/speckit-plan` 阶段决策（尤其 P1-1/P1-3/P1-4 围绕"熔断/限流应位于哪一层"，报告明确标注「需决策」）。本规范仅描述修复后应满足的**可观测行为（WHAT）**，不规定具体实现机制。

## 用户场景与测试 *(mandatory)*

### 用户故事 1 - 关闭竞态下网关不泄漏资源、不锁死目标（Priority: P1）

当容器正在关闭（关闭网关进程）时，恰好有新的异步诊断调用到达，或已有异步任务进入后台编排阶段。此时网关**不得**因为后台执行池已关闭而遗留**永久处于"进行中"（WORKING）的僵尸任务**，也**不得**因为后台提交失败而**泄漏该目标的并发槽**（导致该目标后续所有调用被并发上限永久拒绝、即"目标锁死"）。资源一旦分配，要么成功驱动到终态，要么被显式回滚/释放。

**为何这个优先级**：这两项（评审 P0-1、P0-2）同源于"关闭路径未清理已分配资源"，风险高、触发即可能导致目标不可用或任务无界泄漏，是本次整改的首要对象。

**独立测试**：在后台执行池被关闭的竞态条件下发起异步任务，确认任务不会永久停留在"进行中"，且目标的并发槽不累积泄漏——目标在关闭/重启后仍可被正常调用。

**验收场景**：

1. **Given** 网关正在关闭、后台执行池已拒绝新提交，**When** 此时有异步任务到达，**Then** 该任务要么不进入任务存储，要么被显式置为可回收的终态，**绝不**长期保持"进行中"出现在任务列表里。
2. **Given** 一个目标已有进行中的后台编排任务、网关此时关闭，**When** 后台提交因池关闭而失败，**Then** 该目标已占用的并发槽被释放，后续对该目标的调用**不被**因槽泄漏而永久拒绝。
3. **Given** 正常运行（非关闭），**When** 连续发起多次异步任务并完成/取消，**Then** 目标的并发占用准确反映实际进行中任务数，无累积泄漏。

---

### 用户故事 2 - 熔断与并发控制在默认配置下正确覆盖全部调用路径（Priority: P2）

网关对每个目标后端维护"熔断器"与"并发槽"两类故障隔离机制。在**默认配置**（同一目标允许一定并发，而非串行）下，熔断器必须能**正确累加连续失败计数、按时开启/半开/恢复**，不会因多路并发同时操作熔断状态而"该断不断"或"状态撕裂"。同时，熔断**必须覆盖异步调用路径**——纯异步负载下后端基础设施不可达时，熔断器应能感知并隔离，而非长期驻留"闭合"状态让每次异步调用耗尽兜底超时；取消中断（人为取消）**不得**被计为失败。

**为何这个优先级**：评审 P1-1（熔断器非线程安全、默认并发使其前提不成立）、P1-3（异步路径不驱动熔断，纯异步绕过故障隔离）属故障隔离的核心语义。虽报告标注 P1-1"作者已声明取舍"、P1-3"有意设计"，但用户要求修复报告中提及的全部问题，故纳入。

**独立测试**：在默认并发度下，对同一目标并发发起连续失败的调用，确认熔断器在达到连续失败阈值时正确开启；对纯异步路径发起基础设施不可达的调用，确认熔断器能据此隔离后续调用；人为取消一个异步任务，确认不计为失败、不误熔断。

**验收场景**：

1. **Given** 目标允许并发 N（N>1），**When** 多路并发同时记录连续失败，**Then** 失败计数正确累加，达阈值时熔断如期开启（无"丢失更新"导致的该断不断）。
2. **Given** 某目标仅被异步类工具高频调用、后端基础设施不可达，**When** 异步任务因不可达而失败，**Then** 该失败被计入熔断，后续调用被隔离，而非每次耗尽兜底超时。
3. **Given** 目标曾因故障进入熔断开启，**When** 退避期满后异步调用成功，**Then** 熔断器能向恢复方向转换（不会因异步路径不记录成功而永久误拒）。
4. **Given** 一个进行中的异步任务被人为取消，**When** 取消发生，**Then** 该取消不计入熔断失败，不触发误熔断。

---

### 用户故事 3 - 异步调用遵循后端协议契约（Priority: P2）

后端按协议分为"有状态"与"无状态（STATELESS）"两类；无状态后端无法承载带任务语义、需轮询的异步诊断调用。当调用方对**无状态**后端发起本应走异步路径的诊断工具调用时，网关必须**前置返回明确的参数错误**（结构化错误、可被调用方识别的原因），而**不得**将其提交后台后阻塞至兜底超时才失败。

**为何这个优先级**：评审 P1-2 属明确的契约违背（协议定义声明无状态后端不可走异步，但路由未在异步路径校验），修复代价小、收益明确。

**独立测试**：配置一个无状态后端，对其调用一个异步类诊断工具，确认立即收到明确的参数错误，而非长时间挂起后超时失败。

**验收场景**：

1. **Given** 配置中存在一个无状态后端，**When** 调用方对其发起异步类诊断工具调用，**Then** 网关返回明确的结构化错误（原因可被调用方识别为"该后端不支持异步任务"），**不**提交后台、**不**耗尽兜底超时。

---

### 用户故事 4 - 长生命周期与边界条件下保持健壮（Priority: P3）

若干健壮性问题需收敛：① 目标**退役**（热重载移除）时，其上正在进行的异步任务应能**正常完成**，而非被固定宽限期强制切断为失败；② 合法但**含空值（null）可选参数**的诊断调用须能正常路由，不因防御拷贝拒绝 null 而报内部错误；③ 单点任务查询不应为查找一条记录而付出全表扫描代价；④ 异步执行的跨目标累计并发应有全局背压，避免集群规模下后端连接先于单目标限流触顶。

**为何这个优先级**：评审 P2-1～P2-4，影响的是边界/规模场景下的健壮性与资源效率，非默认部署高频路径，故优先级低于 P0/P1。

**独立测试**：分别构造退役目标含进行中异步任务、含 null 可选参数的合法调用、高频单点任务查询、跨目标高并发的场景，确认各自行为符合预期。

**验收场景**：

1. **Given** 某目标有进行中异步任务，**When** 该目标因热重载被退役，**Then** 进行中的异步任务仍能正常完成并返回诊断，**不**被退役宽限期强制切断为失败。
2. **Given** 调用方对一个诊断工具传入合法的、含 null 可选参数的调用（null 表示该可选参数缺省），**When** 网关路由该调用，**Then** 调用被正常转发，**不**因 null 值报内部错误。
3. **Given** 任务存储中存在一定量任务记录，**When** 调用方高频查询单个任务状态，**Then** 每次单查开销稳定（与存储规模基本无关），不为查一条扫全表。
4. **Given** 多个目标各自发起并发异步调用，**When** 跨目标累计并发上升，**Then** 系统对总并发有全局上限保护，避免后端连接/资源无界增长。

---

### 用户故事 5 - 凭据不泄漏、关键判定有单一事实源、代码卫生收敛（Priority: P3）

若干安全卫生与可维护性问题需统一收口：① 后端凭据（令牌/用户名/密码）**不得**经任何对象的文本表示（toString 等）泄漏到日志或可观测系统；② "某目标是否健康"这一判定在多处使用时必须有**单一事实源**，避免一处改、他处漏改导致仪表盘与实际行为不一致；③ JSON 序列化器应作为全局单例复用，不重复构造；④ 配置解析对"写成浮点的整数"或"超大整数"必须**明确报错并保留原始值**，不得静默截断或给出误导信息；⑤ 死代码与等价复制（已定义未用、与既有实现字节级等价的重复）应清理。

**为何这个优先级**：评审 P3-1～P3-5，属安全卫生与可维护性清理，当前未直接触发但属潜在风险与技术债，低风险、可批量收口。

**独立测试**：检查凭据对象的所有文本表示输出不含明文凭据；变更健康判定语义时确认所有使用处一致；提交非整数形式或越界的配置值确认收到保留原始值的明确错误；确认无已定义未用/等价复制的残留。

**验收场景**：

1. **Given** 配置中含带凭据的后端，**When** 任何代码路径将该后端配置/凭据对象转为文本（日志、异常、调试输出），**Then** 输出**不含**明文凭据（仅模式 + 掩码）。
2. **Given** 健康判定语义需要调整（如半开视为降级），**When** 修改，**Then** 仅需改一处，所有依赖健康判定的行为（目标列表、健康端点、路由守卫）保持一致。
3. **Given** 配置值写成浮点（如 `5.0`）或超大整数（超 int 范围），**When** 加载配置，**Then** 收到明确错误且错误信息**保留原始值**，**不**静默截断为看似合法但错误的值。
4. **Given** 代码库，**When** 审查，**Then** 不存在已定义但全库无调用的方法/常量，不存在与既有实现等价的重复副本。

---

### 边缘情况

- 修复与"关闭竞态清理资源"相关项时，必须保证**正常运行路径**（非关闭）的任务提交、编排、并发槽获取/释放行为与修复前完全一致。
- 熔断相关修复不得引入新的"误熔断"或"误恢复"——尤其取消中断、业务类错误（非基础设施故障）不计入熔断，与现有语义一致。
- 无状态后端的同步（非异步）调用行为不受 P1-2 修复影响——同步一来一回调用仍正常工作。
- 退役宽限期调整须兼顾"尽快释放退役后端资源"与"in-flight 可完成"两者，不得为迁就 in-flight 而无限拖延后端关闭。
- 任何代码清理（单例化、去重、死代码删除）不得改变运行时行为与对外契约。

## 需求 *(mandatory)*

### 功能需求

> 每条功能需求对应评审报告一项发现，编号在「需求来源映射」中标注。所有需求须满足"不影响现有功能"与"每步验证"两条全局约束。

**资源与关闭竞态（P0）**

- **FR-001**：当一个异步任务已被记录但尚未成功进入后台执行时，若后台入池失败（如执行池正在关闭），系统必须将该任务**回滚至不可达或可回收终态**，**不得**遗留永久处于"进行中"的僵尸任务（评审 P0-1）。
- **FR-002**：当后台编排阶段的后台提交失败时，系统必须**释放该目标已占用的并发槽**，**不得**因提交失败跳过释放而泄漏槽位、导致目标后续调用被并发上限永久拒绝（评审 P0-2）。

**熔断与并发控制（P1）**

- **FR-003**：在默认并发配置（同一目标允许并发）下，熔断器必须正确累加连续失败计数、按时开启/半开/恢复，**不得**因多路并发同时操作熔断状态而出现丢失更新、状态撕裂或半开放行多个探测（评审 P1-1）。
- **FR-004**：异步调用路径在解析目标后、提交后台前，必须校验后端协议；对**无状态**后端的异步类诊断调用，须前置返回明确的结构化错误（原因可被调用方识别），不得提交后台后阻塞至兜底超时（评审 P1-2）。
- **FR-005**：熔断必须**覆盖异步调用路径**的基础设施类故障——纯异步负载下后端不可达须计入熔断；同时**人为取消（cancel 中断）不计为失败**，不得误熔断（评审 P1-3）。
- **FR-006**：对同一后端的初始化（首次握手）必须**原子化**——并发首次路由同一后端时，只发起一次握手，不得因非原子的检查-执行而重复握手导致会话状态紊乱（评审 P1-4）。

**长生命周期与健壮性（P2）**

- **FR-007**：目标退役时，其上**进行中的异步任务必须能正常完成**，不得被固定的退役宽限期在任务仍在执行时强制切断为失败（评审 P2-1）。
- **FR-008**：诊断请求必须**接受合法的含空值（null）可选参数**的调用（null 表示可选参数缺省），不得因防御性拷贝拒绝 null 值而报内部错误（评审 P2-2）。
- **FR-009**：单点任务查询（按 ID 查一条）的开销须与存储规模基本无关（不应为查一条扫全表），过期清理交由后台兜底（评审 P2-3）。
- **FR-010**：异步执行必须对**跨目标累计并发**有全局上限保护，避免集群规模下后端连接/资源先于单目标限流触顶（评审 P2-4）。

**安全卫生与清理（P3）**

- **FR-011**：后端凭据（令牌/用户名/密码）**不得**经任何对象的文本表示（toString 等）泄漏；文本表示仅含模式与掩码（评审 P3-1）。
- **FR-012**："目标是否健康"这一判定必须有**单一事实源**，目标列表、健康端点、路由守卫三处统一委托，不得各自内联重复（评审 P3-2）。
- **FR-013**：JSON 序列化器须作为**全局单例**复用，不得在多处重复构造（评审 P3-3）。
- **FR-014**：配置解析对"写成浮点的整数"或"超大整数"必须**明确报错并保留原始值**，不得静默截断或给出丢失原始值的误导信息（评审 P3-4）。
- **FR-015**：必须清理死代码与等价复制——已定义但全库无调用的方法/常量、与既有实现字节级等价的重复副本（评审 P3-5）。

**全局回归约束**

- **FR-016**（全局）：全部修复完成后，网关对外的可观测 MCP 行为——工具/资源定义、按 `target` 路由、调用结果原样透传、热重载、错误传播、健康状态——必须与修复前**逐项一致**；现有测试与端到端冒烟全部通过。
- **FR-017**（全局）：每完成一项发现的修复，必须先确认既有测试与端到端冒烟全绿、现有功能完好，再推进下一项。

### 关键实体

本特性不新增数据实体，针对以下既有实体的行为进行整改（实体定义不变）：

- **目标后端（Backend Entry）**：健康判定、并发槽、熔断状态的归属对象（FR-003/005/012）。
- **熔断器（Circuit Breaker）**：按目标维护连续失败计数与开启/半开/恢复状态（FR-003/005）。
- **异步任务（Async Task）**：具有"进行中/终态"状态机的诊断任务（FR-001/002/007）。
- **诊断请求（Diagnostic Request）**：带可选参数的调用请求（FR-008）。
- **后端配置（Backend Config）**：含凭据与协议属性的配置对象（FR-011/014）。

### 需求来源映射

> 用于"修复报告中提到的全部问题"的完整性核验：每条 FR 可追溯至评审发现 ID。

| 功能需求 | 评审发现 | 主题 | 严重等级 |
|----------|----------|------|----------|
| FR-001 | P0-1 | 异步任务提交失败遗留僵尸"进行中"任务 | P0 |
| FR-002 | P0-2 | 后台编排提交失败致并发槽泄漏 | P0 |
| FR-003 | P1-1 | 熔断器非线程安全（默认并发使其前提不成立） | P1 |
| FR-004 | P1-2 | 无状态后端未在异步路径校验 | P1 |
| FR-005 | P1-3 | 异步路径不驱动熔断 | P1 |
| FR-006 | P1-4 | 后端初始化双重检查非原子 | P1 |
| FR-007 | P2-1 | 退役宽限与异步超时脱节、切断 in-flight | P2 |
| FR-008 | P2-2 | 防御拷贝拒绝 null 值可选参数 | P2 |
| FR-009 | P2-3 | 单点任务查询触发全表清理（读放大） | P2 |
| FR-010 | P2-4 | 异步执行线程池无全局背压 | P2 |
| FR-011 | P3-1 | 凭据对象文本表示含明文凭据 | P3 |
| FR-012 | P3-2 | 健康判定三处重复、缺单一事实源 | P3 |
| FR-013 | P3-3 | JSON 序列化器多处重复构造 | P3 |
| FR-014 | P3-4 | 配置解析静默截断浮点/超大整数 | P3 |
| FR-015 | P3-5 | 死代码与等价复制 | P3 |
| FR-016/017 | — | 全局回归与逐步验证约束 | 横切 |

## 成功标准 *(mandatory)*

### 可度量结果

- **SC-001**：容器关闭竞态下，无"永久进行中"的僵尸任务残留（任务列表不出现永不结束的进行中任务），且无目标因并发槽泄漏被永久锁死——关闭/重启后所有目标仍可被正常调用。
- **SC-002**：默认并发配置下，对同一目标并发记录连续失败时，熔断器在达到连续失败阈值时**如期开启**（不因并发计数丢失而该断不断）；纯异步路径下后端不可达时熔断能据此隔离；人为取消不计为失败。
- **SC-003**：对无状态后端发起异步类诊断调用，**立即**收到明确的结构化错误（可被调用方识别原因），不耗尽兜底超时。
- **SC-004**：含 null 可选参数的合法诊断调用被正常路由转发，不报内部错误。
- **SC-005**：退役目标上的进行中异步任务能正常完成返回诊断，不被退役宽限期强制切断。
- **SC-006**：凭据不出现在任何日志、异常文本或对象文本表示中（仅模式 + 掩码）。
- **SC-007**：写成浮点/超大整数的配置值收到保留原始值的明确错误，不被静默截断。
- **SC-008**（回归门禁）：全部修复完成后，现有测试套件 100% 通过，端到端冒烟（真实 arthas MCP 后端 + 真实业务服务）行为与修复前逐项一致。

## 假设

- **范围边界**：本特性仅整改评审报告所列 15 项发现；评审中"已驳回候选"（REFUTED-1/2/3）**不在范围**，无需处理。
- **修复方式留待设计阶段**：评审报告对 P1-1/P1-3/P1-4 等给出多条"修复方向"（含"下沉到统一层"的深度方案与"最小改动"方案）；选用哪条属实现/设计决策，由 `/speckit-plan` 阶段裁定，本规范不预设。
- **不影响功能**：所有修复须保持对外可观测 MCP 行为不变；若某修复不可避免地改变行为，须在该修复的设计阶段显式记录并经确认，不得静默改变。
- **测试真实性（宪法原则四/七 + CLAUDE.md 不可妥协约束）**：所有修复以 TDD 推进（先写失败测试，再实现至通过）。其中**网关自身并发/资源逻辑**（关闭竞态、熔断线程安全、槽泄漏、初始化原子性、读放大等）用**真实的 JVM 并发原语**（真实执行池、真实线程/虚拟线程、真实并发）测试，**不得用桩模拟成功**；涉及与 arthas 后端交互的行为验证遵循既有驱动分层——工具可用性走真实 Claude Code MCP，结果一致性与双侧契约走官方 MCP Java SDK client。
- **逐步验证（用户约束）**：每完成一项修复，必须先确认既有测试与端到端冒烟全绿、现有功能完好，再推进下一项；任一步引入回归须立即停下修复而非继续堆叠。
- **现有功能基线**：`001-arthas-mcp-gateway` 已提交（commit 4de3812）的 MVP 行为为回归基线；本特性不回退该基线。
```


---

## `specs/002-code-review-remediation/tasks.md`

```markdown
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
```


---

## `specs/003-k8s-arthas-mcp-launch/checklists/requirements.md`

```markdown
# Specification Quality Checklist: K8S 目标 arthas MCP 启动与纳管

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-06-22
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs) — 仅保留宪法强制约束（Java，原则六），属治理约束而非实现选型
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain — 3 项骨架澄清（portal 定位/范围分期/交互形态）已在 Clarifications 解决
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable（含 5 分钟/30 秒等量化）
- [x] Success criteria are technology-agnostic (no implementation details) — K8S/service/pod 为需求固有领域名词，非框架/语言/数据库
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- 宪法对齐：原则一（MCP 规范，经 service 暴露的仍是标准 MCP）、原则二（透明聚合，动态 target 原样透传）、原则三（故障韧性，pod 起伏可隔离）、原则五（可观测，编排可追溯）、原则六（Java 主力，portal 核心逻辑 Java）、原则八（K8S 发行版先决研究，留 plan Phase 0）。
- 待 plan/research 决策（非 spec 范围）：K8S 轻量发行版选型、arthas MCP 容器镜像与部署拓扑（独立 pod vs 注入）、**原子 MCP 工具粒度（instantiate/expose/register 拆分 vs 合并）**、portal 与网关的通信与配置同步机制、Windows agent 语言形态。
- 架构原则（2026-06-22 /speckit-clarify 澄清）：系统以**最小化原子 MCP 能力**暴露、由**大模型编排组合**、**不内建过程式编排模块**；**模块化单体打包**（依赖管理可裁剪）；动态 target **自动命名** `{服务器名}-{Pod名}`；LLM 上下文推断为 **north-star**（P1 由人指定目标）。详见 spec Clarifications Q1~Q4。
- 按 CLAUDE.md 工作流，进入 plan 阶段前建议先走 `superpowers:brainstorming` 产出方案设计（归档 `docs/superpowers/specs/2026-06-22-k8s-arthas-mcp-launch-design.md`），再以 spec-kit SDD 推进 plan/tasks/实现。
```


---

## `specs/003-k8s-arthas-mcp-launch/contracts/dynamic-registration-invariants.md`

```markdown
# 契约：程序化动态注册不变量（BackendConfig.source / DynamicBackendStore / RegistryComposer）

**Feature**: 003-k8s-arthas-mcp-launch | **Date**: 2026-06-22
**界面角色**：定义"动态 target 如何进入网关注册表并与静态种子 + 热重载正确共存"的**内部不变量**。非对外 MCP 工具契约（工具契约见 [k8s-orchestration-tools-contract.md](./k8s-orchestration-tools-contract.md)）；本文件约束 `ensure-arthas-mcp` 内部注册子行为 + 与 001 既有热重载的并发正确性。
**宪法依据**：原则二（透明无损聚合——动态 target 与静态 target 路由等价、可发现、可区分）、原则三（局部故障韧性——动态 target 同等纳管）、原则七（TDD）。
**设计依据**：[设计 §6](../../../docs/superpowers/specs/2026-06-22-k8s-arthas-mcp-launch-design.md)。数据实体见 [data-model §2–§5](../data-model.md)，决策见 [research.md R8](../research.md)。

---

## 1. 来源标记（BackendConfig.source）

每个 `BackendConfig` 带 `source ∈ {STATIC, DYNAMIC}`：

| source | 来源 | 受热重载增删？ | 受 register/unregister？ |
|---|---|---|---|
| `STATIC` | `config/backends.yaml` 种子 | 是（`BackendRegistryReloader` 重读 YAML） | 否 |
| `DYNAMIC` | 程序化 API（`ensure-arthas-mcp` 触发） | 否（热重载只重读 static） | 是（`DynamicBackendStore`） |

**向后兼容**：YAML 不写 `source` 视为 STATIC；001 既有 `backends.yaml` **零改动**即可用。

**可发现 + 可区分（原则二）**：`list-targets` 读 `RegistryHolder.current()`（effective = static ∪ dynamic），动态 target 与静态 target 共同可见；可选用 source 标记区分来源（边缘情况"命名冲突可区分"）。

---

## 2. 注册表结构（静态∪动态合并）

```
static 来源（YAML）──── BackendConfigLoader ──► BackendRegistryReloader(diff) ──┐
                                                                                  │
dynamic 来源 ──────── DynamicBackendStore(register/unregister/list) ────────────┤
                                                                                  ▼
                                                              RegistryComposer.compose(static, dynamic)
                                                                                  │ effective = static ∪ dynamic
                                                                                  ▼ RegistryHolder.getAndSet（原子替换）
                                                              RegistryHolder (AtomicReference) ── current() ── ToolsCallRouter
```

**effective registry = 静态快照 ∪ 动态快照**，经 `RegistryHolder.getAndSet` **原子替换**（复用 001 §4 的 AtomicReference 整体替换语义）。

---

## 3. 不变量（I-*）

### I-1 原子替换（复用 001 §3）
effective registry 经 `AtomicReference.getAndSet` 整体替换（非增量修改）。一次 `tools/call` 全程持有固定的 `BackendEntry` 引用——registry 在调用中途被替换不影响 in-flight 调用（**热重载/动态注册并发不串台**）。

### I-2 热重载不误删动态 target（research.md R8 关键正确性）
静态热重载（`BackendRegistryReloader`）**只**重读 YAML、更新 static 来源；compose 用「新 static + 现有 dynamic」重算 effective。故：**热重载后动态 target 仍在注册表**（不会被误删）。反之，动态 register/unregister 只更新 dynamic 来源、不触达 YAML 文件。

### I-3 命名冲突策略（设计 §6.2）
- 动态注册名 ∩ **静态种子名** → **拒绝**注册（抛 `BackendConfigException`），保护静态配置。→ `ensure` 返 `reason:name_conflict`。
- 动态注册名 ∩ **既有动态名**：
  - 同名 **同 URL** 且后端健康 → **幂等复用**（`status:reused`，零副作用）。
  - 同名 **异 URL** → **拒绝**注册（`reason:name_conflict`）。
- 静态种子内部重名 → 001 既有行为（保留旧表、记 ERROR）。

### I-4 ensure 原子性（设计 §4.1）
`ensure-arthas-mcp` 任一子步失败 → **不**调用 `DynamicBackendStore.register`（注册表不含该 target，不半注册）。仅全部子步成功才 register + compose swap。已打的 pod label / 已建的 NodePort Service 作为可清理副作用记录于 `OrchestrationRecord.error`。

### I-5 动态 target 同等纳管（原则三）
动态 target 经 `BackendEntryFactory.create` 生成与静态 target **同构**的 `BackendEntry`（含 `HttpBackendClient` + `CircuitBreaker` + `taskSlots`）。诊断复用 `ToolsCallRouter` + `AsyncTaskExecutor`，熔断/限流/健康/异步任务语义**完全一致**。pod 消亡 → 该 target 熔断隔离，不影响其他 target（K-COEXIST-2）。

### I-6 复用减少重连（复用 001 reloader diff）
`RegistryComposer.compose` 复用 `BackendRegistryReloader` 的 diff 思路：static∪dynamic 中同 name 同 config 的 Entry **复用旧实例**（保连接池/session），仅 added/changed 新建、toRetire 下线。

### I-7 version 单调（去重）
effective registry 的 `version` 单调递增（static.version 与 dynamic 序列号的合成 max）。重复触发 compose（无实际变更）→ 调用方据此跳过 getAndSet（复用 001 §2 version 去重）。

---

## 4. 操作语义

### 4.1 `DynamicBackendStore.register(BackendConfig cfg)`
1. 强制 `cfg.source = DYNAMIC`（非 DYNAMIC 拒绝）。
2. 冲突检测（I-3）：与 static 种子名冲突 / 与既有动态同名异 URL → 抛 `BackendConfigException`。
3. 写入 `ConcurrentHashMap`（覆盖/新增）。
4. 通知 `RegistryComposer.compose()` → 原子 swap effective。

### 4.2 `DynamicBackendStore.unregister(String name)`
1. 仅 DYNAMIC 可移（STATIC 经热重载；试图移 STATIC → 拒绝/无操作）。
2. 从 `ConcurrentHashMap` 移除（不存在 → 幂等无操作）。
3. 通知 compose → 原子 swap（被移 target 的 Entry 进 toRetire 优雅下线，in-flight 可完成）。

### 4.3 `RegistryComposer.compose(staticReg, dynamicCfgs)`
1. 合并：static 的 Entry 全保留 + dynamic 每个 cfg 经 `BackendEntryFactory.create`（unchanged 复用旧 Entry，I-6）。
2. version = 合成单调值（I-7）。
3. 返回新 `BackendRegistry`；调用方 `RegistryHolder.getAndSet`（I-1）+ 异步下线未复用旧 Entry。

### 4.4 静态热重载集成（既有 `BackendRegistryReloader` 调整）
- 原：`reloader.reload` → 直接 `holder.getAndSet`。
- 改：`reloader.reload` 产出新 static registry → 交 `composer.compose(newStatic, dynamicStore.list())` → `holder.getAndSet`。
- diff/复用/下线逻辑**不变**；仅 swap 前多一步合并 dynamic（I-2）。

---

## 5. 契约测试断言点（动态注册层 · surefire 波次 A，纯逻辑零 K8S）

| ID | 断言 |
|---|---|
| D-REG-1 | `register(DYNAMIC cfg)` 后 `RegistryHolder.current()` 含该 target；`list-targets` 可见 |
| D-REG-2 | `register` 与**静态种子同名** → 抛 `BackendConfigException`（拒绝，I-3） |
| D-REG-3 | 同名同 URL 二次 `register` → 幂等（无异常，target 仍在，Entry 复用） |
| D-REG-4 | 同名**异** URL `register` → 抛 `BackendConfigException`（I-3） |
| D-UNREG-1 | `unregister(DYNAMIC)` 后 effective 不含该 target；其 Entry 进优雅下线 |
| D-UNREG-2 | `unregister` 一个 STATIC 名 → 拒绝/无操作（静态只经热重载） |
| D-UNREG-3 | `unregister` 不存在的名 → 幂等无操作 |
| D-COEXIST-1 | 动态 target 存在时，模拟静态 YAML 热重载（`reloader.reload` 新 static）→ **动态 target 仍在** effective（I-2，关键） |
| D-COEXIST-2 | 静态热重载 + 动态 register 并发 → effective 始终为合法 static∪dynamic 合并；无半合并、无丢失（I-1/I-2） |
| D-ATOMIC-1 | compose 前后，一次模拟 `tools/call` 持有的 `BackendEntry` 引用不变（in-flight 不串台，I-1） |
| D-SOURCE-1 | YAML 解析缺省 `source` → STATIC；显式 `source: STATIC` → STATIC；动态注册强制 DYNAMIC |
| D-VERSION-1 | 无变更的重复 compose → 跳过 getAndSet（version 去重，I-7） |

> 波次 A 全程 surefire（纯逻辑、无 K8S、无 arthas），与 001 的 `BackendRegistryTest`/`BackendRegistryReloaderTest` 同范式。**这些不变量是动态纳管正确性的地基，须先于波次 B/C 的真实供给测试通过**（TDD 测试先于实现）。

---

## 6. 与 001 既有语义的兼容性

- `BackendRegistry`/`BackendEntry`/`RegistryHolder`/`BackendRegistryReloader`/`BackendEntryFactory` **语义不变**；仅 `BackendRegistryReloader` 的 swap 前增 composer 合并一步（I-2）。
- `BackendConfig` 增 `source` 字段，缺省 STATIC → 001 既有 YAML / 测试**零改动**通过（向后兼容）。
- `list-targets` 读 `RegistryHolder.current()` 自动反映动态 target，无需改 handler（可在 view 增 source 字段作可选增强）。
- 既有 35 工具、双侧契约、回归测试**不得回归**（D-COEXIST-* 守护并发正确性，既有 `BackendRegistryReloaderTest`/`HotReloadIT` 继续通过）。
```


---

## `specs/003-k8s-arthas-mcp-launch/contracts/k8s-orchestration-tools-contract.md`

```markdown
# 契约：K8S 编排工具（k8s.list-pods / k8s.list-services / k8s.ensure-arthas-mcp）

**Feature**: 003-k8s-arthas-mcp-launch | **Date**: 2026-06-22
**界面角色**：网关对 Claude Code 暴露的 **3 个 K8S 编排工具**（与既有 31 arthas + 4 网关自有共 38 工具同处单 MCP 端点），`routingMode=GATEWAY_LOCAL`（无 target 参数），**handler 自带闭包、不经 `ToolsCallRouter`**（gateway-core 路由零 K8S 感知，[research.md R6](../research.md)）。
**宪法依据**：原则二（编排工具为独立 MCP 面、不污染聚合落点）、原则四（双侧契约）、原则五（错误显式传播）、原则六（K8S 操作走 fabric8 Java API）。
**设计依据**：[设计 §4](../../../docs/superpowers/specs/2026-06-22-k8s-arthas-mcp-launch-design.md)。数据实体见 [data-model §7/§8/§9/§10](../data-model.md)。

> 这 3 个工具对 Claude Code 是**普通 MCP 工具**，走官方 SDK 的标准 `tools/call`。它们**不**转发到 arthas 后端（不诊断），属编排面。`ensure-arthas-mcp` 产出的 target 名供后续**既有**诊断工具的 `target` 参数使用。

---

## 1. `k8s.list-pods`（枚举集群 pod）

**用途**：枚举指定 namespace 的 pod，供 Claude 据上下文推断目标 pod（north-star）/ 供人据清单选定目标 pod（P1）。

**inputSchema**：
```jsonc
{
  "type": "object",
  "properties": {
    "namespace": { "type": "string", "description": "K8S 命名空间，缺省 default" }
  },
  "additionalProperties": false
}
```

**返回**（TextContent 为 JSON）：
```jsonc
{
  "pods": [
    { "name": "order-service-abc", "namespace": "default", "ready": true,
      "hasJvm": true,   // 含可被 arthas attach 的 JVM（探测 java 进程；非保证）
      "hasShell": true  // 含 shell（exec 注入前提）
    }
  ],
  "namespace": "default"
}
```

- `hasJvm`/`hasShell` 标记帮助 Claude/人过滤"可诊断"pod（设计 §4：目标须 shell+java+JVM）。
- K8S API 不可达 / RBAC 不足 → INVALID_PARAMS + `data.reason` ∈ {`k8s_unreachable`, `k8s_forbidden`}（真实错误显式传播，原则五）。

---

## 2. `k8s.list-services`（枚举集群 service）

**用途**：枚举指定 namespace 的 service。

**inputSchema**：
```jsonc
{
  "type": "object",
  "properties": {
    "namespace": { "type": "string", "description": "K8S 命名空间，缺省 default" }
  },
  "additionalProperties": false
}
```

**返回**（TextContent 为 JSON）：
```jsonc
{
  "services": [
    { "name": "order-svc", "namespace": "default", "type": "ClusterIP",
      "clusterIp": "10.96.x.x", "ports": [{"port": 8080, "nodePort": null}] }
  ],
  "namespace": "default"
}
```

---

## 3. `k8s.ensure-arthas-mcp`（幂等供给黑盒，核心）

**用途**：对指定目标 pod **原子幂等**完成：注入 arthas（exec 进 pod、attach 该 pod JVM PID）→ 启动绑 `0.0.0.0` 的 arthas MCP → label pod + 建 NodePort Service 暴露 → 内部健康检查 → 动态注册进网关。对 Claude 是**单步机械黑盒**（无 LLM 决策点，故合并为 1 工具，设计 §4.2）。

**inputSchema**：
```jsonc
{
  "type": "object",
  "properties": {
    "server":    { "type": "string", "description": "Linux 服务器名（逻辑名前缀 + 来源标识）" },
    "pod":       { "type": "string", "description": "目标 pod 名（须含 shell+java+JVM）" },
    "namespace": { "type": "string", "description": "K8S 命名空间，缺省 default" }
  },
  "required": ["server", "pod"],
  "additionalProperties": false
}
```

**行为（幂等、原子）**：
1. 派生 `logicalName = "{server}-{pod}"`。
2. 查 `DynamicBackendStore`：已有且后端健康 → **零副作用复用**，返回该 target（status=reused）。
3. 否则执行供给（ensuring）：
   - fabric8 exec 进 pod：定位 JVM PID（无 JVM → failed）；上传/获取 `arthas-boot.jar`（无 shell/传输失败 → failed）。
   - exec 启动：`java -jar /tmp/arthas-boot.jar <pid> --attach-only --http-port <mcpPort> --target-ip 0.0.0.0 --use-version 4.3.0`（attach 超时/端口占用 → failed）。
   - label pod（`arthas-mcp-gateway/target=<logicalName>`）+ create NodePort Service（selector 匹配该 label、port→mcpPort）；可达 URL = `http://<nodeIP>:<nodePort>`（根 URL，无 `/mcp`）。
   - 内部健康检查：轮询 `<mcpUrl>` 的 initialize/listTools 确认 arthas MCP 就绪（超时未就绪 → failed）。
   - `DynamicBackendStore.register(BackendConfig{source=DYNAMIC, name=logicalName, url=mcpUrl, ...})` + `RegistryComposer` 原子 swap effective registry。
4. 任一子步失败 → **不注册**（status=failed），返回结构化错误。
5. 全部成功 → status=ready。

**返回**（TextContent 为 JSON）：
```jsonc
// ready / reused
{ "target": "prod-order-server-order-service-abc",
  "status": "ready",            // ready（新供给完成）| reused（幂等复用）
  "mcpUrl": "http://192.168.31.92:31234",   // 根 URL，无 /mcp
  "namespace": "default" }
```
```jsonc
// failed（INVALID_PARAMS + data）
{ "target": "prod-order-server-order-service-abc",
  "status": "failed",
  "error": {
    "reason": "no_jvm" | "no_shell" | "attach_failed" | "health_check_timeout"
            | "nodeport_alloc_failed" | "name_conflict" | "k8s_unreachable" | "k8s_forbidden",
    "stage": "locate_jvm" | "install_arthas" | "start_arthas" | "expose_nodeport"
           | "health_check" | "register",
    "message": "..."
  } }
```

**幂等细则**（设计 §4.1）：
- 注册表有 `{server}-{pod}` 且后端健康 → 复用，零副作用。
- 注册表有但后端已死（pod 重启/arthas 挂） → 重供给（重装/重 attach/重暴露），更新条目（status=ready）。
- **原子性**：任一子步失败 → 失败、**不注册**（不污染注册表）。已打的 label/已建的 Service 记录于 `OrchestrationRecord.error` 供清理。

**成功后续**：Claude 用返回的 `target` 调**既有**诊断工具（watch/trace/sc/...）——经 `gateway-core` 既有路由管线，结果原样透传（宪法原则二）。

---

## 4. 端到端时序（Claude 编排，P1 人指定 pod）

```text
人提供：kubeconfig（指向 k3s @ debian）+ 服务器名
  │
[1] Claude ──k8s.list-pods──► 网关 ──► pod 清单（hasJvm/hasShell 过滤可诊断 pod）
[2] 人/Claude 选定目标 pod
[3] Claude ──k8s.ensure-arthas-mcp(server, pod)──► 网关
       └─ 原子幂等供给黑盒（注入+暴露+健康+注册）──► { target, status:"ready" }
[4] Claude ──watch(target={server}-{pod}, ...)──► 网关 ──► 经 gateway-core 路由 ──► 该 pod JVM
```

系统只暴露 [1]/[2 的工具能力] + 既有诊断；**[1]→[3]→[4] 的串联由 Claude 完成**，系统不内建过程式编排（设计 §五）。

---

## 5. 契约测试断言点（编排工具 · 官方 SDK client 驱动 + 真实 k3s/业务 pod，零桩）

| ID | 断言 |
|---|---|
| K-LP-1 | `k8s.list-pods` 返回真实集群 pod 清单（含 hasJvm/hasShell 标记）；namespace 过滤生效 |
| K-LS-1 | `k8s.list-services` 返回真实集群 service 清单 |
| K-ENS-1 | `ensure-arthas-mcp` 对含 JVM 的真实 pod：返回 `status:ready` + 可达 `mcpUrl` + 该 target 进注册表（`list-targets` 可见、source=DYNAMIC） |
| K-ENS-2 | 对 ready target 重复 `ensure` → `status:reused`、零副作用（pod 未重装、Service 未重建） |
| K-ENS-3 | `ensure` 后用返回的 target 调 `watch`/`trace` → 经网关捕获**该 pod JVM** 的真实诊断（与直连 arthas 一致，原样透传） |
| K-ENS-4 | 对**无 JVM** 的 pod `ensure` → INVALID_PARAMS + `reason:no_jvm` + `stage:locate_jvm`，且**未注册**（注册表不含该名） |
| K-ENS-5 | 对**无 shell** 的 pod `ensure` → INVALID_PARAMS + `reason:no_shell`（真实故障条件，非桩） |
| K-ENS-6 | kubeconfig 无 exec 权限 → INVALID_PARAMS + `reason:k8s_forbidden`（真实 403，非桩） |
| K-ENS-7 | arthas MCP 绑 loopback（`--target-ip 127.0.0.1`）→ NodePort 不可达、健康检查超时 → `reason:health_check_timeout`（验证 `0.0.0.0` 要求，[research.md R4](../research.md)） |
| K-ENS-8 | `ensure` 期间 `target` 命名 = `{server}-{pod}`（确定性派生） |
| K-ENS-9 | 动态 target 与静态种子同名 → `reason:name_conflict`（拒绝，保护静态） |
| K-ATOMIC-1 | `ensure` 任一子步失败 → 注册表**不含**该 target（不半注册） |
| K-COEXIST-1 | 动态 target 纳管后，`backends.yaml` 热重载 → 动态 target **仍在**（热重载不误删，[research.md R8](../research.md)） |
| K-COEXIST-2 | 动态 target 不可达（pod 删除）→ `list-targets` 标 unhealthy、对其诊断返明确错误；其他 target 不受影响（复用 001 故障隔离） |

> 全真实环境（真实 k3s @ debian + 真实业务 pod 容器镜像 + 真实 arthas 注入，**零桩**）；可用性冒烟另由真实 Claude Code（`claude -p --mcp-config`）驱动。断言先于实现（TDD，宪法原则七）。波次 B 的 `K8sEnsureContractIT`/`ArthasProvisionerIT` 覆盖 K-ENS-*；波次 A 的 `DynamicBackendStoreTest`/`RegistryComposerTest` 覆盖 K-ENS-9/K-COEXIST-1。
```


---

## `specs/003-k8s-arthas-mcp-launch/data-model.md`

```markdown
# Data Model 增量：K8S 目标 arthas MCP 启动与纳管

**Feature**: 003-k8s-arthas-mcp-launch | **Date**: 2026-06-22

> 本文档定义本特性相对 [001 data-model](../001-arthas-mcp-gateway/data-model.md) 的**增量实体/字段/状态机/校验**。001 既有的 `BackendConfig`/`BackendEntry`/`BackendRegistry`/`RegistryHolder`/`BackendRegistryReloader`/`ExposedTool`/`GatewayTask`/`CircuitBreaker` 等语义**不变**，本文仅记新增与受影响处。决策依据见 [research.md](./research.md)，行为契约见 `contracts/`。

---

## 1. 实体增量总览

| 实体 | 类型 | 职责 | 生命周期 |
|---|---|---|---|
| `BackendConfig.source` | 配置态（字段增量） | 后端来源标记（STATIC/DYNAMIC） | 随 BackendConfig |
| `Source` | enum | `STATIC` \| `DYNAMIC` | — |
| `DynamicBackendStore` | 运行期 | 动态 target 内存态（register/unregister/list） | 进程级，优雅关闭 |
| `RegistryComposer` | 运行期 | static ∪ dynamic 合并 → effective `BackendRegistry`，原子替换 | 进程级 |
| `K8sToolRegistry` | 启动期 | 3 个编排工具的 `ExposedTool`（不可变快照） | 进程级只读 |
| `K8sToolHandlers` | 运行期 | `list-pods`/`list-services`/`ensure-arthas-mcp` 处理器 | 进程级 |
| `OrchestrationRecord` | 运行期 | 一次 ensure 供给的结构化可观测记录（原则五） | 进程级（可加 TTL） |
| `OrchestrationRecordStore` | 运行期 | 供给记录内存态（Map） | 进程级 |

> 既有 `BackendRegistry`/`BackendEntry`/`RegistryHolder`/`BackendRegistryReloader`/`BackendEntryFactory` **语义不变**；`RegistryHolder` 持有的 effective 快照现由 `RegistryComposer` 产出（静态∪动态合并）。

---

## 2. BackendConfig 字段增量：`source`

在 001 `BackendConfig`（name/url/protocol/auth/超时/并发）之上**新增**：

| 字段 | 类型 | 必填 | 说明 / 校验 |
|---|---|---|---|
| `source` | `Source` | 否（缺省 `STATIC`） | `STATIC`（`backends.yaml` 种子）/ `DYNAMIC`（程序化 API 注册）。不参与 equals 的"复用判定"核心但参与可观测（list-targets 区分来源）。**向后兼容**：YAML 不写 source 视为 STATIC。 |

**校验**：`source` 非 null（紧凑构造器缺省 STATIC）；动态注册路径必为 DYNAMIC（由 `DynamicBackendStore.register` 强制）。其余字段校验（name 唯一、url 合法、auth 对应、超时正、并发 ∈[1,5]）复用 001 §2/§11，**不变**。

**`BackendConfigLoader` 增量**：解析 YAML 时读可选 `source`，缺省 STATIC；与 001 解析/校验逻辑其余一致。

---

## 3. Source（enum，新）

| 值 | 含义 |
|---|---|
| `STATIC` | 源自 `config/backends.yaml` 种子，受热重载增删 |
| `DYNAMIC` | 源自程序化 `register` API（`ensure-arthas-mcp` 触发），不受 YAML 热重载直接影响（热重载只重读 static） |

---

## 4. DynamicBackendStore（运行期，动态 target 内存态，新）

程序化写入动态 target 的唯一入口（`ensure-arthas-mcp` 经此注册；`RegistryComposer` 经此读动态集合）。

| 操作 | 说明 |
|---|---|
| `register(BackendConfig cfg)` | 写入/更新一个动态 target（cfg.source 强制 DYNAMIC）；与静态种子名冲突 → 抛 `BackendConfigException`（拒绝，保护静态）；与既有动态同名同 URL → 幂等（健康复用）；同名异 URL → 抛冲突错。写入后触发 `RegistryComposer.compose()` |
| `unregister(String name)` | 移除一个动态 target（仅 DYNAMIC 可移；STATIC 经热重载）；不存在 → 幂等无操作。触发 compose |
| `list()` | 当前动态 `BackendConfig` 不可变快照 |
| `get(String name)` | 取单个（缺失返 empty） |

**线程安全**：内部 `ConcurrentHashMap`；变更后通知 composer 重算（compose 内部用 `RegistryHolder.getAndSet` 原子替换，复用 001 原子性保证）。

---

## 5. RegistryComposer（运行期，静态∪动态合并，新）

把 static 来源（热重载维护）与 dynamic 来源（`DynamicBackendStore`）合并为 effective `BackendRegistry`，经 `RegistryHolder.getAndSet` 原子替换。

| 操作 | 说明 |
|---|---|
| `compose(BackendRegistry staticReg, Collection<BackendConfig> dynamic)` | 合并为新 effective registry：static 的 Entry 全保留 + dynamic 每个 cfg 经 `BackendEntryFactory.create` 新建 Entry（unchanged 者复用旧 Entry，保连接池）。version 取 `max(static.version, dynamicSeq++)` 单调递增。返回新 `BackendRegistry` |
| `swap(BackendRegistry next)` | `RegistryHolder.getAndSet(next)` 原子替换；旧快照的未复用 Entry 异步优雅下线（复用 001 退役宽限语义） |

**触发时机**：
- 静态热重载（`BackendRegistryReloader` 产出新 static registry）→ composer 用新 static + 现有 dynamic 重算。
- 动态 register/unregister → composer 用现有 static + 新 dynamic 重算。

**不变量（复用 001 §3）**：一次 `tools/call` 全程持有固定的 `BackendEntry` 引用；effective registry 替换不影响 in-flight 调用。

> **既有 `BackendRegistryReloader` 改动**：原直接 `RegistryHolder.getAndSet` 改为先交 composer（携当前 dynamic）合并再 swap。diff/复用逻辑不变。

---

## 6. 关系图（在 001 §10 之上）

```text
  config/backends.yaml                         ensure-arthas-mcp 调用
        │ 加载/热重载                                  │ register(cfg)
        ▼                                              ▼
  BackendConfigLoader ──► static Backends        DynamicBackendStore
        │ BackendRegistryReloader (diff)                │
        ▼                                              │
  static BackendRegistry ─────────────┐    ┌───────────┘
                                       ▼    ▼
                                 RegistryComposer ── compose(static, dynamic)
                                       │ effective = static ∪ dynamic
                                       ▼ RegistryHolder.getAndSet（原子替换）
                                 RegistryHolder (AtomicReference)
                                       │ current()
                                       ▼
                                 ToolsCallRouter（gateway-core，零 K8S 感知）

  K8sToolRegistry (启动期, 3 工具) ──► GatewayMcpServerConfig
        │                              （合并 StaticToolRegistry 35 + K8sToolRegistry 3 = 38 specs）
        ▼
  K8sToolHandlers ─┐                   ensure 内部: ArthasProvisioner + NodePortExposer + K8sClientFactory
   list-pods       │  handler 闭包        │
   list-services   │  （不经路由器）        ▼
   ensure-arthas-mcp ──────────────► OrchestrationRecordStore（供给记录, 原则五）
```

---

## 7. K8sToolRegistry 与 3 个编排工具（启动期，新）

3 个 `ExposedTool`，`routingMode=GATEWAY_LOCAL`（无 target 参数；实际不经路由器，handler 自带闭包，见 [research.md R6](./research.md)）。schema/行为见 [contracts/k8s-orchestration-tools-contract.md](./contracts/k8s-orchestration-tools-contract.md)。

| 工具名 | 参数 | 返回 | 处理器 |
|---|---|---|---|
| `k8s.list-pods` | `namespace?`（缺省 default） | pod 清单（名称/命名空间/含可诊断 JVM 标记） | `K8sToolHandlers#listPods`（fabric8 list） |
| `k8s.list-services` | `namespace?` | service 清单 | `K8sToolHandlers#listServices` |
| `k8s.ensure-arthas-mcp` | `server`, `pod`, `namespace?` | `{server}-{pod}`（target 名）；原子幂等供给 | `K8sToolHandlers#ensureArthasMcp` → `ArthasProvisioner` |

**tools/list 并集**：`GatewayMcpServerConfig#mcpToolSpecifications` 合并 StaticToolRegistry（35）+ K8sToolRegistry（3）= **38** specs，注入同一 MCP server（单端点）。capabilities 仍 `tools(listChanged=false)`（工具集静态）。

---

## 8. OrchestrationRecord（运行期，可观测性，新）

一次 `ensure-arthas-mcp` 供给的结构化记录（宪法原则五：网关可被诊断）。

| 字段 | 类型 | 说明 |
|---|---|---|
| `logicalName` | string | `{server}-{pod}`（与注册的 target 名一致） |
| `server` | string | 供给来源服务器名 |
| `pod` | string | 目标 pod 名 |
| `namespace` | string | K8S namespace（缺省 default） |
| `mcpUrl` | string? | 暴露端点 `http://<nodeIP>:<nodePort>`（arthas MCP 根 URL，无 `/mcp`） |
| `serviceRef` | string? | NodePort Service 引用（name/nodePort） |
| `status` | enum | 见 §9 状态机 |
| `error` | object? | 失败原因（failed 时，含 reason/message/阶段） |
| `createdAt` | instant | 供给发起时间（**传入**，非进程内取时——与 001 `GatewayTask.createdAt` 一致，便于测试） |
| `completedAt` | instant? | 完成/失败时间 |

**存储**：`OrchestrationRecordStore`（内存 `ConcurrentHashMap<logicalName, OrchestrationRecord>`，按 logicalName 覆盖最新状态；可加 TTL）。供运维经结构化日志/未来 portal 查询。

---

## 9. OrchestrationRecord 状态机

```text
                  ┌──── 全部子步成功（注入+暴露+健康+注册）─────┐
                  │                                              ▼
  ensuring ───────────────────────────────────────────► ready
     │   │
     │   ├─── 注册表已有且健康（零副作用）──► reused
     │
     └─── 任一子步失败（装失败/attach 超时/NodePort 分配失败/健康检查不过/注册冲突）──► failed
```

| 状态 | 含义 | 终态？ |
|---|---|---|
| `ensuring` | 供给进行中（注入/暴露/健康检查/注册） | 否 |
| `ready` | 新供给完成、已注册且健康 | 是 |
| `reused` | 命中幂等复用（注册表已有且健康） | 是 |
| `failed` | 任一子步失败；**未注册**（不污染注册表） | 是 |

**原子性（设计 §4.1）**：`ensuring→failed` 时**不**写入 `DynamicBackendStore`（不注册）；已打的 pod label / 已建的 Service 作为可清理副作用记录于 `error` 字段供运维追溯。`ensuring→ready` 才注册。

**转换规则**：
- `ensuring→reused`：compose 前查 `DynamicBackendStore` + 健康检查，已存在且健康 → 零副作用返回该 target。
- `ensuring→ready`：全部子步成功，`DynamicBackendStore.register` 完成、composer swap 完成、NodePort 健康检查通过。
- `ensuring→failed`：任一子步抛异常 → 记录失败阶段与原因，不注册。

---

## 10. ensure-arthas-mcp 内部子行为实体（瞬态，编排用）

`ArthasProvisioner.ensure(server, pod, namespace)` 编排的中间产物（非持久实体，记录于此明确语义）：

| 子步 | 产物 | 失败 → |
|---|---|---|
| 查注册表（幂等） | 命中 → reused | — |
| fabric8 exec 进 pod 定位 JVM PID | `pid` | failed（无 JVM/无 java/jps 缺失） |
| 上传/获取 arthas-boot.jar 进 pod | pod 内 `/tmp/arthas-boot.jar` | failed（无 shell/传输失败） |
| exec 启动 arthas MCP（`--attach-only --http-port --target-ip 0.0.0.0`） | arthas agent 注入目标 JVM、服务 mcpPort | failed（attach 超时/端口占用） |
| label pod + create NodePort Service | `serviceRef`、`mcpUrl` | failed（label 冲突/NodePort 分配失败） |
| 内部健康检查（轮询 initialize/listTools） | 就绪确认 | failed（超时未就绪） |
| `DynamicBackendStore.register` + composer swap | effective registry 含新 target | failed（命名冲突） |

**命名派生**：`logicalName = "{server}-{pod}"`（确定性、非人工，spec Q2）。namespace 不进 logicalName（MVP 单集群；多集群后置时再议）。

---

## 11. 校验与边界规则增量

1. **动态注册命名冲突**：动态名 ∩ 静态名 → 拒绝（`BackendConfigException`，保护静态配置）；动态名之间同名同 URL → 幂等复用；同名异 URL → 拒绝。（设计 §6.2）
2. **ensure 原子性**：任一子步失败 → `failed` 且**不注册**（不污染注册表、不半注册）。（设计 §4.1）
3. **热重载不误删动态 target**：`BackendRegistryReloader` 只更新 static 来源；effective 经 composer 合并，动态 target 在热重载后仍存在。（research.md R8）
4. **source 向后兼容**：YAML 不写 `source` 视为 STATIC；既有 `backends.yaml` 零改动即可用。
5. **编排工具不经路由器**：`k8s.*` 工具 handler 闭包直调 `K8sToolHandlers`，`ToolsCallRouter`/`GatewayToolHandlers` 无 `k8s.*` 分支（gateway-core 零 K8S 感知）。
6. **K8S 真实错误显式传播**：无权限（403）/不可达/无 shell/无 JVM → `ensure` 返回结构化错误（INVALID_PARAMS + data.reason），绝不静默成功。（宪法原则五）
7. **诊断对象归属**：`ensure` 经 kubectl exec 进入**目标 pod**注入 arthas、attach **该 pod** 的 JVM PID；诊断结果明确来自被选 pod（spec FR-003、边缘情况"未运行 JVM → 启动失败"）。
8. **tools/list = 38**：35（既有）+ 3（编排），单端点并集；`listChanged=false` 不变。

---

## 12. 受影响的既有实体（语义不变，仅装配路径调整）

- `RegistryHolder`：仍持有 effective `BackendRegistry`、`getAndSet` 原子替换。effective 现由 `RegistryComposer` 产出。
- `BackendRegistryReloader`：diff/复用逻辑不变；swap 前经 composer 合并 dynamic。
- `BackendEntryFactory`：不变；composer 与静态加载共用其 `create`。
- `StaticToolRegistry`：不变（仍 35）；`GatewayMcpServerConfig` 额外合并 `K8sToolRegistry` 的 3 个。
- `GatewayToolHandlers.listTargets`：读 `RegistryHolder.current()`（含动态 target）——**自动**反映动态纳管，无需改动；可选用 source 标记区分来源（增量字段，可选增强）。
```


---

## `specs/003-k8s-arthas-mcp-launch/plan.md`

```markdown
# Implementation Plan: K8S 目标 arthas MCP 启动与纳管

**Branch**: `003-k8s-arthas-mcp-launch` | **Date**: 2026-06-22 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/003-k8s-arthas-mcp-launch/spec.md`
**Design**: [2026-06-22-k8s-arthas-mcp-launch-design.md](../../docs/superpowers/specs/2026-06-22-k8s-arthas-mcp-launch-design.md)（SuperPower 头脑风暴产出，九项核心决策）

> **范围声明**：本特性 spec 覆盖 P1–P4 四块渐进能力；**本计划实施范围 = P1（US1）**——对指定 K8S 目标 pod 幂等拉起 arthas MCP + NodePort 暴露 + 动态纳管 + 经网关诊断。P2（离线构造脚本）/ P3（portal）/ P4（Windows agent）后置，参照 001「集群整体后置」的先例不在本计划任务内。P1 的全部供给机制与 north-star 共享，差异仅在"谁指定目标"（P1 人指定 / north-star LLM 推断）。

## Summary

把"对任意指定 K8S 目标 pod 按需启动诊断 MCP 并纳入现有网关使用"的闭环跑通。架构沿用 [设计](../../docs/superpowers/specs/2026-06-22-k8s-arthas-mcp-launch-design.md)：**模块化单体 + 单 MCP 端点**——在现有网关（001/002）之上新增一组独立的 K8S 编排 MCP 能力（`k8s.list-pods` / `k8s.list-services` / 幂等 `k8s.ensure-arthas-mcp`），与既有 35 工具装配进**同一个** MCP server（Claude 单入口、tools/list 为并集）。`ensure-arthas-mcp` 经 **kubectl exec 进入目标 pod 注入 arthas**（attach 该 pod JVM PID）→ 启动绑 `0.0.0.0` 的 arthas MCP → 建 NodePort Service 暴露 → 内部健康检查 → **程序化动态注册**进 `BackendRegistry`（静态种子 ∪ 动态注册的合并快照）。注册后该 target 经 `gateway-core` 既有路由管线诊断，结果原样透传（宪法原则二落点不受污染）。

**关键新增**（在 001/002 之上）：
- **3 个编排 MCP 工具**（新 `orchestration` 包），handler 自带闭包、**不经 `ToolsCallRouter`**，故 gateway-core 路由零 K8S 感知（设计 §3.1/§3.2 的内聚性纪律）。
- **程序化动态注册层**：`BackendConfig.source`（STATIC/DYNAMIC）+ `DynamicBackendStore` + `RegistryComposer`（静态∪动态合并 → 原子替换），与既有热重载共存（热重载不再误删动态 target）。
- **`OrchestrationRecord`**：结构化追溯每次 ensure 供给（宪法原则五）。
- **fabric8 K8S 客户端**：list/exec/create-service 全走 Java API，不 shell-out 到 `kubectl` 二进制（宪法原则六"K8S 仅辅助"）。

## Technical Context

> 全部技术未知在 [research.md](./research.md)（Phase 0）解决；下表为结论摘要。

**Language/Version**: Java 21 LTS（Spring Boot 4.1.0，复用 001/002 既有构建，`pom.xml` 锁定 `maven.compiler.release=21`）。

**Primary Dependencies**:
- 既有（复用）：Spring AI 2.0.0 MCP（server-webmvc + client starter）、官方 MCP Java SDK 2.0.0、Actuator。
- **新增**：`io.fabric8:kubernetes-client`（K8S list/exec/create-service，见 research.md R2）。
- arthas：仍作静态工具文件 `tools/arthas-boot.jar`（不入 pom），`ensure` 经 fabric8 exec `java -jar` 使用（与 001 夹具设计 §5 一致）。

**Storage**: 无持久化（与 001 一致）。`OrchestrationRecord` + `DynamicBackendStore` 为运行期内存态。

**Testing**: JUnit5 + AssertJ（surefire 纯逻辑 + failsafe `*IT.java` 真实夹具）。波次 A（纯逻辑，无 K8S）/ B（真实 K8S 夹具）/ C（端到端）。**零桩**（CLAUDE.md 真实性硬约束）——成功路径不得用桩模拟 arthas/K8S 成功；故障用真实故障条件。驱动分层：可用性走真实 Claude Code MCP（`claude -p --mcp-config`），一致性/双侧契约走官方 MCP Java SDK client。

**Target Platform**: 网关 JVM（Linux/Windows 均可，开发期 Windows）+ 远程 K8S 集群。**测试集群 = debian-docker 服务器（192.168.31.92）上的 k3s**（research.md R3：本机 Windows 无 Docker，debian 有 Docker 26.1.5 + SSH 免密）。

**Project Type**: 模块化单体 web-service（单 Maven 模块 + 包级边界，见下"Project Structure"与 research.md R1）。

**Performance Goals**: SC-001——选定 pod 后 **5 分钟内**完成"ensure + 暴露 + 纳管"并完成一次诊断；SC-003——pod 重启/驱逐后网关 **30 秒内**标记该 target 不可用（复用 001 健康监控/熔断）。

**Constraints**: 受控内网、无认证（沿用 001 MVP 安全假设）；目标 pod 须含 shell+java+JVM；单目标 JVM/pod（多 JVM PID 选择后置）。

**Scale/Scope**: 单 Linux 服务器、单 K8S 集群起步；多服务器/多集群预留接口不在 MVP。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*（宪法 v1.2.0）

| 原则 | 核验 | 结论 |
|------|------|------|
| **一 MCP 规范符合性** | 3 个新工具是标准 MCP 工具（tools/list + tools/call）；NodePort 暴露的仍是标准 arthas MCP；`app` 装配的 server 遵守 initialize/能力协商/JSON-RPC 2.0。工具集仍静态（35+3=38），`listChanged=false` 继续成立 | ✅ 通过 |
| **二 透明无损聚合** | 动态 target 诊断仍走 `gateway-core` 既有 `ToolsCallRouter`、结果原样透传；定义仍静态摘抄自 arthas；编排工具为独立 MCP 面（设计 §3.2）——**gateway-core 包零 K8S 感知**（编排工具 handler 自带闭包、不经路由器） | ✅ 通过（关键落点见 [contracts/dynamic-registration-invariants.md](./contracts/dynamic-registration-invariants.md)） |
| **三 局部故障韧性** | 动态 target 经 `BackendEntryFactory` 创建 `BackendEntry`（含熔断/限流/健康），与静态 target 同等纳管；pod 消亡 → 该 target 熔断隔离，不影响其他；`ensure` 的"重供给"可恢复纳管 | ✅ 通过（复用 001 故障隔离） |
| **四 双侧契约** | 新增契约：3 个编排工具（gateway↔Claude）+ ensure→诊断路径双侧断言；TDD 红-绿-重构，测试先于实现（原则七） | ✅ 通过（[contracts/k8s-orchestration-tools-contract.md](./contracts/k8s-orchestration-tools-contract.md)） |
| **五 可观测性** | `OrchestrationRecord` 结构化追溯供给（logicalName/server/pod/mcpUrl/status/createdAt）；ensure 全程结构化日志（tool/target/status/duration）；K8S 真实错误（无权限/不可达/无 shell）显式传播，绝不静默成功 | ✅ 通过 |
| **六 Java 主力** | 编排核心、网关、注册层全 Java；K8S 操作全走 **fabric8 Java API**（list/exec/create-service），**不 shell-out `kubectl` 二进制**；K8S 清单/脚本仅辅助（P2 离线脚本才出现） | ✅ 通过 |
| **七 TDD** | 波次 A/B/C 全程真实环境、零桩（[research.md R7](./research.md)、设计 §八） | ✅ 通过 |
| **八 先决研究** | Phase 0 先决研究已产出 [research.md](./research.md)：K8S 轻量发行版（k3s）、arthas-boot.jar 远程绑定（`0.0.0.0`，**R4 已源码证据链 + 本机 A/B 实证双重确认、不再为风险**）、NodePort selector 策略、fabric8 选型、动态注册与热重载并发 | ✅ 通过 |

**门禁结论**：无违反项。设计 §3.2 关于"端点多挂编排工具不破原则二"的论证已落地为本计划的包级边界（gateway-core 零 K8S 感知）。Complexity Tracking 无需填写。

## Project Structure

### Documentation (this feature)

```text
specs/003-k8s-arthas-mcp-launch/
├── plan.md              # 本文件
├── spec.md              # /speckit-specify 产出（已按设计 §10 reconcile）
├── research.md          # Phase 0 先决研究（R1–R8 决策）
├── data-model.md        # 数据模型增量（在 001 data-model 之上）
├── quickstart.md        # 端到端验证指南
├── contracts/
│   ├── k8s-orchestration-tools-contract.md   # 3 个编排工具契约 + 断言点
│   └── dynamic-registration-invariants.md    # 动态注册不变量（source/合并/冲突/热重载共存）
└── tasks.md             # /speckit-tasks 产出（不在本命令范围）
```

### Source Code (repository root)

```text
# 单 Maven 模块（research.md R1：包级边界实现"模块化单体"，Maven 多模块后置 P3）
src/main/java/com/arthas/gateway/
├── GatewayApplication.java
├── auth/  config/  handler/  task/  tool/  obs/   # ===== gateway-core（既有，零改动语义）=====
├── backend/                                          # gateway-core 后端域（增量：BackendConfig.source）
│   ├── BackendConfig.java            # 增字段 source: Source
│   ├── BackendConfigLoader.java      # 解析 source（缺省 STATIC）
│   ├── Source.java                   # 新：enum STATIC | DYNAMIC
│   ├── RegistryHolder.java           # 既有（持有 effective 合并快照）
│   ├── DynamicBackendStore.java      # 新：动态 target 内存态（register/unregister/list）
│   └── RegistryComposer.java         # 新：static∪dynamic 合并 → RegistryHolder.getAndSet 原子替换
├── orchestration/                                    # ===== orchestration（新包，纯 K8S，不碰 arthas 路由）=====
│   ├── K8sToolRegistry.java          # 3 个编排工具的 ExposedTool（routingMode=GATEWAY_LOCAL）
│   ├── K8sToolHandlers.java          # list-pods/list-services/ensure-arthas-mcp 处理器
│   ├── K8sClientFactory.java         # kubeconfig → fabric8 KubernetesClient
│   ├── K8sPodExplorer.java           # list pods/services（fabric8）
│   ├── ArthasProvisioner.java        # ensure 核心：exec 注入 + NodePort 暴露 + 健康检查 + 注册
│   ├── NodePortExposer.java          # label pod + create NodePort Service → 可达 URL
│   ├── OrchestrationRecord.java      # 供给记录实体 + 状态机
│   └── OrchestrationRecordStore.java # 内存态供给记录（原则五可观测）
└── config/
    └── GatewayMcpServerConfig.java   # 增量：mcpToolSpecifications 合并 35 + 3（见 data-model §5）

src/main/resources/
├── application.yml                   # 增 arthas-gateway.k8s.* （kubeconfig/context/namespace/端口范围）
└── arthas-tools.json                 # 不动（31 arthas 工具单一事实源）

src/test/java/com/arthas/gateway/
├── backend/                          # 增 DynamicBackendStoreTest / RegistryComposerTest / SourceParsingTest（波次 A）
├── orchestration/                    # 新：ArthasProvisionerIT / K8sEnsureContractIT / OrchestrationRecordTest（波次 B/C）
└── ...                               # 既有测试不动（回归对照 = 001/002）

config/
└── backends.yaml                     # 增量：静态种子可标注 source: STATIC（缺省即 STATIC，向后兼容）

tools/
└── arthas-boot.jar                   # 既有静态工具文件（ensure 经 fabric8 exec 上传/cp 进 pod 使用）
```

**Structure Decision（research.md R1）**：**单 Maven 模块 + 包级边界**实现设计的"模块化单体"——`orchestration` 包承载全部 K8S 编排逻辑，`backend`/`handler`/`config`/`tool` 等 gateway-core 包**零 K8S 依赖**（依赖方向单向：orchestration → backend 允许，反向禁止）。设计的 Maven 多模块（gateway-core/orchestration/app/portal）作为 P1 的**逻辑命名**沿用，其**物理 Maven 模块拆分后置到 P3**（portal 真正需要按需裁剪 orchestration 时再拆）——理由：宪法"禁止过早抽象"+"小步迭代"，且 P1 不行使"模块裁剪"这一多模块唯一收益。从干净的包边界机械迁移到 Maven 模块在 P3 是低风险的。详见 [research.md §1](./research.md)。

## Complexity Tracking

> 无宪法违反项需正当化。设计文档与宪法一致；唯一与设计字面措辞（"Maven 模块"）的偏离已以包级边界落地，理由为**遵循**宪法"禁止过早抽象/小步迭代"（非违反），记录于 [research.md R1](./research.md) 供用户复核。

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| （无） | — | — |
```


---

## `specs/003-k8s-arthas-mcp-launch/quickstart.md`

```markdown
# Quickstart：K8S 目标 arthas MCP 启动与纳管（P1 端到端验证）

**Feature**: 003-k8s-arthas-mcp-launch | **Date**: 2026-06-22

> 本文是 P1 能力的**端到端验证指南**（证明"对指定 K8S 目标 pod 启 arthas MCP + 暴露 + 纳管 + 经网关诊断"闭环跑通）。实现细节归 `tasks.md` 与实现阶段；本文只给**可运行的验证场景 + 预期结果**。
> 契约见 [contracts/k8s-orchestration-tools-contract.md](./contracts/k8s-orchestration-tools-contract.md)、[contracts/dynamic-registration-invariants.md](./contracts/dynamic-registration-invariants.md)；数据实体见 [data-model.md](./data-model.md)；环境/选型决策见 [research.md](./research.md)。

---

## 0. 前置环境

| 项 | 要求 | 说明 |
|---|---|---|
| **K8S 集群** | debian-docker 服务器（192.168.31.92）上装 **k3s**（[research.md R3](./research.md)） | 一键 `bash test-env/k8s/setup.sh`（幂等：离线装 k3s + build 镜像 + import + apply + 导出 kubeconfig）；node IP = 192.168.31.92 |
| **kubeconfig** | root-on-node 派生的 admin kubeconfig，本机供网关读取 | 落 `test-env/k8s/kubeconfig/k3s-admin.yaml`（server 已改 `https://192.168.31.92:6443`）；**不建 RBAC**（K-ENS-6 后置） |
| **业务 pod 镜像** | 001 夹具 `DemoBusinessApp` 打成容器镜像（**纯 app，无 arthas**），入 k3s | Dockerfile + 在 debian 上 build 由 setup.sh 完成；含 `OrderService.hotMethod`（[data-model §7](./data-model.md)、[research.md R7](./research.md)） |
| **网关** | 本特性构建的 `arthas-mcp-gateway`（38 工具）| 开发期在本机 Windows 运行、经 kubeconfig + NodePort 远程连 k3s（验证"网关集群外运行"） |
| **Claude Code** | 真实 Claude Code（`claude -p --mcp-config`）做可用性冒烟 | 工具可用性走真实 CC；一致性/双侧契约走官方 MCP Java SDK client（CLAUDE.md 驱动分层） |

> 本机 Windows 无 Docker（memory），故 k3s 跑在 debian；网关集群外运行 + 远程 kubeconfig/NodePort 正是 SC-001 真实拓扑。

---

## 1. 场景 A：波次 A 纯逻辑（无 K8S，CI 可跑）

验证动态注册层的正确性地基（先于真实供给）。

```bash
mvn -pl . test -Dtest='DynamicBackendStoreTest,RegistryComposerTest,SourceParsingTest,OrchestrationRecordTest'
```

**预期**（断言见 [dynamic-registration-invariants.md §5](./contracts/dynamic-registration-invariants.md)）：
- `register`/`unregister` 正确、命名冲突拒绝（D-REG-2/4）。
- 动态 target 存在时模拟静态热重载 → 动态 target **仍在**（D-COEXIST-1，关键）。
- compose 前后 in-flight 调用持有的 Entry 引用不变（D-ATOMIC-1）。

> 这一层不依赖 k3s/arthas，CI 默认跑（与 001 surefire 同范式）。

---

## 2. 场景 B：波次 B 真实 K8S 夹具（需 k3s @ debian，本地手跑）

### 2.1 备好集群与镜像（一次性，幂等）

完整方案见 [K8S 测试环境设计](../../docs/superpowers/specs/2026-06-23-k8s-test-env-setup-design.md)；此处为验证指南的一键入口。

```bash
# 0.（一次性）预置 k3s 离线资源到 reference/k3s/（防 github 间歇不可达；带重试）
bash reference/k3s/fetch.sh

# 1. 一键幂等搭建（本机 Git Bash 运行；内部经 on-debian 远程操作 debian）
#    顺序：mvn test-compile 产 demo class → ship 到 debian → 离线装 k3s（瘦身 + tls-san）
#         → docker build demo 镜像 → k3s ctr import → kubectl apply demo pod → 导出 root 派生 admin kubeconfig
bash test-env/k8s/setup.sh
```

**预期**：
- `demo-business` pod `Running`/`Ready`（含 shell+java+JVM；**arthas 不在镜像内**，ensure 时经 fabric8 exec 上传 `tools/arthas-boot.jar` 使用——与 001 夹具设计 §5 一致）。
- 本机 `test-env/k8s/kubeconfig/k3s-admin.yaml` 生成（= root-on-node 派生 admin，server 已改 `https://192.168.31.92:6443`）。
- `kubectl --kubeconfig test-env/k8s/kubeconfig/k3s-admin.yaml get pods` 见 `demo-business`。

> 故障用例（无 JVM / 无 shell / 无 exec）的 pod 按 TDD 进度逐步补（设计 §1.2），非本一次性步骤。

**清理**：`bash test-env/k8s/teardown.sh`（debian 上 k3s-uninstall + 删导出凭证）。

### 2.2 启网关（38 工具）

```bash
# 配置网关读 kubeconfig（application.yml: arthas-gateway.k8s.kubeconfig）
./smoke/gateway-start.sh
# 或 mvn spring-boot:run
```

确认 `tools/list` 含 **38** 工具（35 既有 + `k8s.list-pods`/`k8s.list-services`/`k8s.ensure-arthas-mcp`）。

### 2.3 跑真实供给契约测试

```bash
# 需 k3s 环境；CI 默认 Assume 跳过，本地手跑
mvn -pl . verify -Dit.test='K8sEnsureContractIT,ArthasProvisionerIT' -DfailIfNoTests=false
```

**预期**（断言见 [k8s-orchestration-tools-contract.md §5](./contracts/k8s-orchestration-tools-contract.md)）：
- K-ENS-1：对含 JVM 的真实 pod `ensure` → `status:ready` + 可达 `mcpUrl` + target 进注册表（`list-targets` 可见、source=DYNAMIC）。
- K-ENS-2：重复 `ensure` → `status:reused`、零副作用。
- K-ENS-3：用返回 target 调 watch/trace → 经网关捕获**该 pod JVM** 的真实诊断。
- K-ENS-4/5/6/7：真实故障条件（无 JVM / 无 shell / 无 exec 权限 / 绑 loopback）→ 结构化错误，且未注册。

> **R4（已解决，2026-06-22）**：arthas `--target-ip 0.0.0.0` 经 NodePort 的可达性已由**源码证据链 + 本机 A/B 实证双重确认**（[research.md R4](./research.md)）：`0.0.0.0` → netstat `0.0.0.0:<port> LISTENING`（wildcard，可达）；`127.0.0.1` → `127.0.0.1:<port> LISTENING`（loopback，不可达）。故 K-ENS-7 在此基础上转为**回归守护**（锁定 loopback 经 NodePort 不可达），非风险首测；**无需 socat/iptables 降级方案**。

---

## 3. 场景 C：端到端（真实 Claude Code 编排）

人提供服务器名 + kubeconfig；Claude 编排枚举→供给→诊断。

```bash
# MCP 配置指向本网关（/mcp 端点），真实 Claude Code 走 MCP
claude -p --mcp-config .mcp.json "枚举 default 命名空间的 pod，选含 JVM 的目标，"
     "启动 arthas MCP 并纳管，然后 watch 该 pod 的 OrderService.hotMethod"
```

**预期**（SC-001）：
1. Claude 调 `k8s.list-pods` → 选定含 JVM 的 demo pod。
2. Claude 调 `k8s.ensure-arthas-mcp(server=<服务器名>, pod=<demo pod>)` → 返 `{target, status:ready}`。
3. Claude 用返回 `target` 调 `watch` → 经网关捕获该 pod JVM 的 `hotMethod` 真实调用。
4. 全程"启动 + 暴露 + 纳管 + 使用"在 **5 分钟内**完成（SC-001）。

> 可用性冒烟走真实 CC（仅验"能调通"）；结果一致性 + 双侧协议契约由官方 MCP Java SDK client 驱动的 `*ContractIT` 确定性断言（CLAUDE.md 驱动分层）。

---

## 4. 故障韧性验证（SC-003）

```bash
# ensure 成功纳管后，删除目标 pod（模拟 K8S 驱逐/重启）
on-debian 'kubectl delete pod <demo-pod>'
```

**预期**（SC-003、K-COEXIST-2）：
- 网关在 **30 秒内**把该 target 标 unhealthy（复用 001 健康监控/熔断）。
- 对该 target 诊断 → 明确错误（INVALID_PARAMS + `reason:backend_unreachable`）。
- **其他 target 不受影响**。
- pod 恢复（重新部署）→ 重新 `ensure` 可恢复纳管（重供给，status:ready）。

---

## 5. 回归对照（不得回归 001/002）

```bash
mvn -pl . verify
```

**预期**：既有 35 工具 tools/list、双侧契约（`InitializeAndToolsListContractTest`/`GatewayToolsContractTest`/`ToolsCallRoutingContractIT` 等）、热重载（`HotReloadIT`）、异步任务全部继续通过。动态注册层的 D-COEXIST-* 守护"热重载不误删动态 target"不破坏既有热重载语义。

---

## 6. 验证清单（Done Definition）

- [X] 场景 A（波次 A 纯逻辑）全绿，CI 可跑。
- [X] 场景 B（波次 B 真实夹具）本地 k3s 全绿，含 K-ENS-7 回归守护（`0.0.0.0` 经 NodePort 可达 / loopback 不可达，R4 已先期实证）。
- [X] 场景 C（端到端）真实 Claude Code 编排枚举+供给+诊断，结果来自指定 pod JVM，5 分钟内（SC-001）。
- [X] 故障韧性 SC-003：pod 删除 → 30 秒内隔离、明确错误、不影响其他 target。
- [X] 回归：001/002 既有测试全绿，38 工具 tools/list。
- [X] tools/list = 38（35 + 3），`listChanged=false` 不变。
- [X] gateway-core 包零 K8S 依赖（编排工具不经路由器，[research.md R6](./research.md)）。
```


---

## `specs/003-k8s-arthas-mcp-launch/research.md`

```markdown
# Research — K8S 目标 arthas MCP 启动与纳管

> **Phase 0 产出**（`/speckit-plan`，宪法原则八先决研究）。本文档汇总 P1 实施的**技术决策**（决策/理由/备选），解决全部技术未知。
> 架构层决策（模块化单体 + 单 MCP 端点 + kubectl exec 拓扑 + 3 工具收敛 + NodePort）详见 [设计文档](../../docs/superpowers/specs/2026-06-22-k8s-arthas-mcp-launch-design.md)（SuperPower 头脑风暴产出）；本文不重复其架构论述，仅落"实施期技术决策"。
> 输入：[spec](./spec.md)、[设计](../../docs/superpowers/specs/2026-06-22-k8s-arthas-mcp-launch-design.md)、[宪法](../../.specify/memory/constitution.md) v1.2.0、[001 data-model](../001-arthas-mcp-gateway/data-model.md)、[arthas 测试夹具设计](../../docs/superpowers/specs/2026-06-20-arthas-test-fixture-design.md)、既有源码（`backend/*` / `config/GatewayMcpServerConfig` / `handler/ToolsCallRouter` / `tool/StaticToolRegistry`）。

## 0. 先决研究结论（宪法原则八）

精读全部相关既有源码与参考，逐项核对设计文档的九项决策与风险，**全部确认可实施**（无臆测）。关键事实（证据驱动）：

- **既有注册表是不可变快照**：`BackendRegistry` = `record(version, byName Map)`，`Map.copyOf` 防御拷贝；`RegistryHolder` 经 `AtomicReference.getAndSet` 整体替换；`BackendRegistryReloader.reload` 做 diff（unchanged 复用旧 Entry、added/changed 新建、toRetire 下线）。**动态注册不能简单"加方法到 record"**（record 不可变），须引入合并层（见 R8）。
- **既有 MCP 工具装配是单 bean**：`GatewayMcpServerConfig#mcpToolSpecifications` 把 `StaticToolRegistry`（35）的每个 `ExposedTool` 转成 `SyncToolSpecification`（handler 全委托 `ToolsCallRouter.route`）。Spring AI starter 收集此 `List<SyncToolSpecification>` bean 注册到 server。**编排工具可作第二组 specs 合并进同一 bean**（见 R6），无需多 bean 规避 Spring 歧义。
- **`ToolsCallRouter` 按 `routingMode` 分流**：`GATEWAY_LOCAL`（4 自有工具）在 target 解析前委托 `GatewayToolHandlers.handle`（switch 工具名，未知抛 `IllegalStateException`）。编排工具**应绕过路由器**（handler 自带闭包），使 gateway-core 路由零 K8S 感知（设计 §3.2 的内聚性纪律，见 R6）。
- **既有热重载会清空非 YAML 来源**：`BackendRegistryReloader` 只认 `LoadedBackends`（来自 `backends.yaml`）。若动态 target 与静态 target 同存于一个 registry，热重载会**误删动态 target**——故动态 target 须独立存储 + 合并层（见 R8），不能并入 YAML 加载结果。
- **arthas-boot.jar 启动范式已验证**（001 夹具设计 §5）：`java -jar tools/arthas-boot.jar <pid> --attach-only --http-port <port> --target-ip <ip> --use-version 4.3.0`，arthas 4.3.0 MCP 端点为**根 URL**（无 `/mcp`），attach 后 `arthas-boot.jar` 进程 exit 0、arthas agent 常驻目标 JVM 内服务端口。本地用 `--target-ip 127.0.0.1`（loopback）；**K8S 远程经 NodePort 须 `--target-ip 0.0.0.0`**（绑 pod 网络接口）——此项**已由 arthas 4.3.0 源码证据链 + 本机真实 A/B 实证双重确认（见 R4 RESOLVED）**，不再是风险、不阻塞波次 B。
- **本机环境约束**：开发机 Windows 11、**无 Docker**（memory `arthas-no-dependency` 基建）；`debian-docker`（192.168.31.92，Debian 13，Docker 26.1.5，2 核/3.8Gi，SSH key 免密，memory `debian-docker-ssh-access`）是唯一可承载 K8S 的真实资源。

## 1. NEEDS CLARIFICATION 处置

spec 经 `/speckit-clarify` 已澄清 Q1–Q4；设计文档（brainstorming）已收敛 Q4 工具粒度（5→3）、部署拓扑（kubectl exec）、暴露方式（NodePort）、注册机制（程序化 + 静态种子）。spec.md 已按设计 §10 reconcile（FR-002 改述为幂等供给、FR-004/005 降为子行为、增 `OrchestrationRecord` 实体、NodePort 暴露）。

本文档解决的**实施期**技术未知 = R1–R8（下表）。均无 `[NEEDS CLARIFICATION]` 残留。

## 2. 实施期技术决策（R1–R8）

### R1. 构建结构：单 Maven 模块 + 包级边界（Maven 多模块后置 P3）

| 维度 | 决策 |
|------|------|
| **决策** | 单 Maven 模块（沿用 001/002 `arthas-mcp-gateway`），用**包级边界**实现设计的"模块化单体"：新 `com.arthas.gateway.orchestration` 包承载全部 K8S 编排；既有 `backend/handler/config/tool/task/auth/obs` 包（gateway-core）**零 K8S 依赖**。Maven 多模块（gateway-core/orchestration/app/portal）作为**逻辑命名**沿用，**物理拆分后置 P3**。 |
| **理由** | ① 宪法「禁止过早抽象」「小步迭代」——多模块拆分是显著的构建复杂度（parent pom、模块间依赖、测试 classpath 共享、`app` bootstrap 装配），其唯一收益"按需裁剪模块"在 P1 **不行使**（P1 同时需要 gateway-core + orchestration）。② 设计 §3.2 的核心诉求"gateway-core 不含 K8S 编排逻辑、可独立编译/测试"在 P1 由**包级边界 + 单向依赖**即可达成（orchestration → backend 允许；反向禁止），独立编译的增量价值（enforce 依赖方向）在 P1 可由 review + 可选 ArchUnit 守护。③ 从干净的包边界迁移到 Maven 模块在 P3 是机械操作（若届时包边界已被遵守）。④ 既有一百多个类全在扁平包内，迁入 `core.*` 子包是高风险纯机械重构，不符合"小步迭代、每次改动只针对一个关注点"。 |
| **备选（未选）** | **(A) 立即拆 Maven 多模块**：更早 enforce 边界，但 P1 无裁剪需求、徒增构建复杂度，违反禁止过早抽象。**(B) 既包扁平、又把 gateway-core 迁入 `core.*` 子包**：机械重构全库，高风险零收益。**选包级边界**（最小改动、满足 §3.2 诉求、保留 P3 拆模块的清晰路径）。 |
| **强制手段（可选）** | 可加 ArchUnit 测试断言"`com.arthas.gateway.backend..` 不依赖 `com.arthas.gateway.orchestration..`"等单向规则。属 test-scope 轻量依赖，直接服务设计 §3.2 的边界诉求。是否引入留 tasks.md（先 review 把关，ArchUnit 作为可选项）。 |

### R2. K8S 客户端：fabric8 kubernetes-client

| 维度 | 决策 |
|------|------|
| **决策** | `io.fabric8:kubernetes-client`（Apache 2.0）。list pods/services、exec 进 pod、create NodePort Service、（备选）文件上传全走其 fluent Java API。**不 shell-out `kubectl` 二进制**（宪法原则六"K8S 仅辅助、核心逻辑 Java"）。 |
| **理由** | ① fabric8 是 Java K8S 生态事实标准（Red Hat 支持），fluent DSL 对 list/exec/create 最简洁。② exec API（`client.pods().inNamespace(ns).withName(pod).exec(...)`）与文件操作（`redirectOutput`/tar）覆盖 `ensure` 全部需求。③ 纯 Java，符合原则六。④ 与 kubeconfig/in-cluster 两种鉴权均原生支持。 |
| **备选（未选）** | **官方 `io.kubernetes:client-java`**（Kubernetes-sig）：亦合规、功能等价，但 DSL 较 fabric8 啰嗦（exec 尤甚）。**shell-out `kubectl`**：违反原则六（核心编排逻辑落 shell），且依赖目标机装 kubectl、错误处理粗糙——**排除**。fabric8 vs 官方最终选型留波次 B 首测裁决（exec 体验），默认 fabric8。 |
| **版本** | 随 Spring Boot 4.1.0 BOM 兼容的最新稳定 fabric8（`pom.xml` 显式声明版本，锁定可复现构建）。 |

### R3. K8S 测试集群：debian-docker 上的 k3s

| 维度 | 决策 |
|------------|
| **决策** | 真实测试集群 = **debian-docker 服务器（192.168.31.92）上安装 k3s**（轻量 K8S 发行版）。网关开发期在 Windows 运行、经 kubeconfig 远程连接 k3s API + 经 NodePort 远程访问暴露的 arthas MCP（验证"网关可集群外运行"）。 |
| **理由** | ① 本机 Windows 无 Docker，无法本地跑 kind/k3d（设计 §波次 B 候选排除本地项）。② debian-docker 有 Docker 26.1.5 + SSH key 免密（memory），是唯一现成承载点；k3s 单二进制、资源占用低（2 核/3.8Gi 足够）、符合设计"轻量发行版"与原则八先决研究。③ 设计 §波次 B 已列 on-debian 服务器为候选。④ 网关集群外运行 + 远程 kubeconfig/NodePort 正是 SC-001 真实拓扑。⑤ P2（离线构造脚本）顺势 = "在全新 debian 上脚本化装 k3s"，与本决策一致。 |
| **备选（未选）** | **kind/k3d on debian**（容器内跑 K8S）：Docker-in-Docker 套娃，NodePort 路由多层、复杂度高，不如 k3s 直接。**生产 K8S 集群**：超出 MVP 受控实验范围。**k3s on 本机 Windows (WSL2)**：本机无 Docker/WSL 约束未知，弃。 |
| **运维（实施细节）** | 完整方案见 [K8S 测试环境设计](../../docs/superpowers/specs/2026-06-23-k8s-test-env-setup-design.md)：`test-env/k8s/` 幂等 setup 脚本（本机 Git Bash 运行、内部经 `on-debian` 远程操作 debian，debian 已有 Docker 26.1.5）+ `reference/k3s/` 离线资源预置（k3s **v1.35.5+k3s1** airgap，防 github 间歇不可达，memory）。**离线 airgap 装**（`INSTALL_K3S_SKIP_DOWNLOAD=true` + 瘦身 `--disable traefik/servicelb/metrics-server` + `--tls-san 192.168.31.92`）。**身份 = root-on-node 派生 admin kubeconfig**（不建 ServiceAccount/Role；K-ENS-6 后置），落 `test-env/k8s/kubeconfig/k3s-admin.yaml`（server 改 `https://192.168.31.92:6443`，文件 600、`sudo cat` 导出）。一键入口 = `bash reference/k3s/fetch.sh` → `bash test-env/k8s/setup.sh`；清理 = `teardown.sh`。k3s node IP = 192.168.31.92，NodePort 在其上对外可达（内网）。 |

### R4. arthas MCP 远程绑定：`--target-ip 0.0.0.0`（✅ RESOLVED：源码证据链 + 本机 A/B 实证双重确认）

> **状态：已解决（2026-06-22）。** 经 arthas 4.3.0 源码逐级追踪 + 本机真实 A/B 实证，`--target-ip` 的值**直接决定** arthas MCP HTTP 监听 socket 的绑定地址；`0.0.0.0` 产出 wildcard 绑定、NodePort 可达。**不再是风险、不阻塞波次 B**；契约 K-ENS-7 由"风险首测"转为"回归守护"（loopback 经 NodePort 不可达）。

| 维度 | 决策 |
|------|------|
| **决策** | `ensure` 启动 arthas MCP 时用 `--target-ip 0.0.0.0`（绑 pod 所有网络接口），使 NodePort 能路由到容器内 arthas MCP 端口。本地夹具的 `127.0.0.1`（loopback）在 K8S 远程场景**不可达**（NodePort 路由不进 loopback）。 |
| **源码证据链（arthas 4.3.0，逐级数据流）** | ① `Bootstrap.java:522-525`——`--target-ip <ip>` 透传为 agent 参数 `-target-ip <ip>`。② `Arthas.java:68-69`——`-target-ip` 选项值 → `configure.setIp(value)`。③ `ArthasBootstrap.java:457-460`——`new HttpTermServer(configure.getIp(), configure.getHttpPort(), ...)`。④ `HttpTermServer.java:50`——`new NettyWebsocketTtyBootstrap(...).setHost(hostIp).setPort(port)`（hostIp=configure.getIp）。⑤ `NettyWebsocketTtyBootstrap.java:76`——`b.bind(host, port)`（Netty ServerBootstrap 以 host:port 绑定监听）。⑥ **MCP `/mcp` 端点搭乘同一 Netty 管线**：`ArthasBootstrap.java:493` 存 `mcpRequestHandler`；`HttpRequestHandler.java:52` 取之；`HttpRequestHandler.java:80-86` 对 `path==mcpEndpoint` 的请求委托 `mcpRequestHandler.handle(ctx, request)`。故 MCP 端点由绑定 `configure.getIp()` 的 HTTP term server 同一 Netty 服务——**`--target-ip` 直接决定 MCP 监听绑定地址**（`ArthasMcpServer` 构造 `McpServerProperties`/传输层时未设 bindAddress/port，仅产出注入该管线的 handler）。 |
| **本机 A/B 实证（2026-06-22，真实 DemoBusinessApp JVM + 真实 arthas-boot.jar 4.3.0，零桩）** | 以同一夹具分别 attach 两个真实业务 JVM：**A 组** `--target-ip 0.0.0.0 --http-port 39182` → netstat `0.0.0.0:39182 LISTENING` + `[::]:39182 LISTENING`（IPv4/IPv6 双 wildcard，NodePort 可达）；**B 组** `--target-ip 127.0.0.1 --http-port 39184` → netstat `127.0.0.1:39184 LISTENING`（仅 loopback，NodePort 不可达）。**结论：`--target-ip` 值即监听绑定地址，`0.0.0.0` 满足 NodePort 可达性要求。** 实证脚本 [`smoke/r4-bind-test.sh`](../../smoke/r4-bind-test.sh)（A/B 对照，可复现）。 |
| **对 K8S 拓扑的影响** | pod 内 arthas MCP 监听 `0.0.0.0:<mcpPort>` → NodePort Service（selector 命中该 pod、`port→targetPort=mcpPort`）将其映射到 `<nodeIP>:<nodePort>` → 网关经 NodePort 可达。**无需 socat/iptables 转发降级方案**（备选方案已撤销）。 |
| **备选（未选）** | **arthas MCP 独立 pod + 跨 pod attach**：arthas 4.3.0 attach 走 Java Attach API（本机 PID namespace），跨 pod attach 需共享 PID ns/主机网络，复杂且偏离 attach 语义——排除，坚持 exec 注入目标 pod（设计决策 #5）。 |

### R5. NodePort Service selector 策略：label pod + selector

| 维度 | 决策 |
|------------|
| **决策** | `ensure` 为目标 pod 打唯一 label（如 `arthas-mcp-gateway/target=<logicalName>`），再 create 一个 NodePort Service 用 `selector` 匹配该 label、`port→targetPort=mcpPort`。可达 URL = `http://<nodeIP>:<nodePort>`（arthas MCP 根 URL，无 `/mcp`）。 |
| **理由** | ① 业务 pod 通常无唯一 label，直接 selector 无法精确路由到"这一个 pod"。打唯一 label 后 selector 精确命中，且 pod 重启 IP 变化时 selector 自动重解析（Service 持续可达新 pod）。② K8S 原生、最简单。③ NodePort 在固定范围（默认 30000–32767）对外可达，符合"网关集群外运行"。 |
| **备选（未选）** | **headless Service + 手写 Endpoints（指定 pod IP）**：控制更精细，但 pod 重启 IP 变即失效、需重供给——MVP 不选。**复用业务 pod 既有的某个 Service**：业务 Service 端口语义不符、不通用——排除。**hostNetwork + hostPort**：绕过 Service 抽象、端口冲突风险高——排除。 |
| **label 打标副作用** | 对业务 pod 打 label 是写操作（轻量、可逆）。MVP 受控环境可接受；`ensure` 失败时记录已打 label 便于清理（`OrchestrationRecord`）。 |
| **端口选择** | NodePort 由 K8S 在范围内自动分配（避免冲突）；或显式指定（受限范围）。默认自动分配，返回实际 nodePort。 |

### R6. 3 个编排工具的装配：第二组 specs，handler 自带闭包，绕过 ToolsCallRouter

| 维度 | 决策 |
|------------|
| **决策** | `orchestration` 包提供 `K8sToolRegistry`（3 个 `ExposedTool`，`routingMode=GATEWAY_LOCAL` 因无 target 参数）与 `K8sToolHandlers`。`GatewayMcpServerConfig#mcpToolSpecifications` **合并** StaticToolRegistry（35）+ K8sToolRegistry（3）= 38 specs 进**同一个** `List<SyncToolSpecification>` bean。编排工具的 handler 闭包**直接调用 `K8sToolHandlers.handle`**，**不经 `ToolsCallRouter`**。 |
| **理由** | ① 设计 §3.1/§3.2：两组 @Tool 装配进同一 MCP server，tools/list 为并集；gateway-core 路由**零 K8S 感知**。让编排工具绕过路由器（handler 自带闭包），`ToolsCallRouter`/`GatewayToolHandlers` 完全不知 `k8s.*` 工具存在——内聚性纪律落地。② 合并进同一 bean 避免 Spring 多 `List<SyncToolSpecification>` bean 歧义（starter 收集单 bean）。③ 38 工具仍静态 → `listChanged=false` 继续成立（capabilities 锁定不变）。 |
| **备选（未选）** | **(A) 编排工具也走 `ToolsCallRouter`**：需在路由器加 `k8s.*` 分支 → gateway-core 路由器出现 K8S 概念，污染原则二落点——排除。**(B) 路由器按 name 前缀分发到多 handler 注册表**：更通用但过早抽象（禁止过早抽象），3 个工具不值得——选最小改动（第二组 specs + 自带闭包）。**(C) 多个 `List<SyncToolSpecification>` bean + `@Qualifier`**：需验证 starter 是否聚合多 bean，不确定性高——合并单 bean 最稳。 |
| **路由模式** | 编排工具虽标 `GATEWAY_LOCAL`，但**不进 `ToolsCallRouter` 分流**（其 handler 闭包在 bean 构建时即绑定，绕过路由器）。`routingMode` 字段仅供 `ExposedTool` 完整性，实际不参与路由。 |

### R7. 测试波次与真实性硬约束（零桩、真实环境）

| 维度 | 决策 |
|------|------|
| **决策** | 沿用 arthas 测试夹具设计的波次范式 + CLAUDE.md 真实性硬约束：**波次 A（纯逻辑，surefire，无 K8S）**——命名派生 `{server}-{pod}`、`DynamicBackendStore` register/unregister、`RegistryComposer` 静态∪动态合并与命名冲突、`BackendConfig.source` 解析、`OrchestrationRecord` 状态机。**波次 B（真实 K8S 夹具，failsafe `*IT.java`）**——真实 k3s + 真实业务 pod（DemoBusinessApp 打镜像入集群）+ 真实 `ensure`（fabric8 exec 注入 + NodePort 暴露 + 健康检查）+ 真实诊断（触发业务方法 → 经网关 watch/trace 捕获）。**波次 C（端到端）**——人指定 pod → Claude（`claude -p --mcp-config`）编排枚举+供给+诊断 → 结果来自该 pod JVM。 |
| **真实性约束** | 成功路径**不得用桩**模拟 arthas/K8S 成功响应；故障用**真实故障条件**：停 pod=不可达、无 shell pod=真实 ensure 失败、kubeconfig 无 exec 权限=真实 403、arthas 绑 loopback=真实不可达（验证 R4）。驱动分层：可用性走真实 Claude Code MCP（逐工具冒烟）；一致性 + 双侧契约走官方 MCP Java SDK client（确定性断言）。 |
| **DemoBusinessApp 容器化** | 001 夹具 `DemoBusinessApp` 是宿主子进程；K8S 场景须**打成容器镜像**（`jdk-21` 基础镜像 + demo 类）入 k3s。这是波次 B 的夹具增量（见 data-model §7、quickstart）。 |
| **CI 策略** | 波次 A 进 CI（无 K8S 依赖）；波次 B/C 依赖 debian k3s，CI 默认跳过（`Assume` 守护，标注需 k3s 环境本地手跑），与 001 真实夹具 IT 同策略。 |

### R8. 程序化动态注册层：独立动态存储 + 合并层（与热重载共存）

| 维度 | 决策 |
|------------|
| **决策** | 新增 `DynamicBackendStore`（动态 target 内存态，`register(BackendConfig)`/`unregister(name)`/`list()`）与 `RegistryComposer`（把 static store ∪ dynamic store 合并为 effective `BackendRegistry`，经 `RegistryHolder.getAndSet` 原子替换）。**静态热重载**（`BackendRegistryReloader`）只重读 YAML 更新 static store；**动态 register/unregister** 只更新 dynamic store；二者任一变更均触发 `RegistryComposer.compose()` → 原子替换 effective registry。`BackendConfig` 增 `source` 字段（STATIC/DYNAMIC），静态种子缺省 STATIC（向后兼容）。 |
| **理由** | ① 既有 `BackendRegistry` 是不可变 record，"加 register 方法"不成立；不可变快照 + AtomicReference 整体替换是既定架构（001 data-model §4），动态注册须顺应而非破坏。② 关键正确性：热重载（`BackendRegistryReloader`）只认 YAML，若动态 target 并入同一 registry，热重载会**误删动态 target**——故动态 target 须独立 store，effective = 合并视图。③ 设计 §6.2"快照 = 静态种子 ∪ 动态注册，经既有 AtomicReference 原子替换"正是此意；原子替换保留 001 的 in-flight 不变量（热重载并发不串台）。④ `source` 标记让 `list-targets` 可区分来源（设计 §六、边缘情况"命名冲突可区分"）。 |
| **命名冲突策略** | 动态名 ∩ 静态名 → **拒绝注册**并报明确错误（保护静态配置，设计 §6.2）；动态名之间冲突 → 幂等以"健康复用"优先，URL 不同则报错。 |
| **合并复用** | `RegistryComposer` 复用 `BackendRegistryReloader` 的 diff 思路：unchanged（static∪dynamic 中同 name 同 config）复用旧 `BackendEntry`（保连接池/session），减少重连。 |
| **备选（未选）** | **(A) 动态 target 并入 YAML 加载结果**：热重载误删动态 target，正确性缺陷——排除。**(B) 两个 RegistryHolder（static + dynamic），路由时查两个**：路由热路径多一次查询、且熔断/限流 per-entry 语义割裂——选单一 effective registry 更简洁正确。**(C) 把 register/unregister 加到 RegistryHolder**：Holder 职责膨胀（现仅持有快照），破坏单一责任——独立 store + composer 更清晰。 |

## 3. 残留风险（实现期首测裁决）

| 风险 | 对冲（已内置 tasks.md） |
|------|------------------------|
| ~~arthas `--target-ip 0.0.0.0` 远程行为未知（R4，原最大风险）~~ ✅ **已解决** | **2026-06-22 源码证据链 + 本机 A/B 实证双重确认**（见 §R4）：`0.0.0.0` 产出 wildcard 绑定、NodePort 可达；**不再阻塞波次 B**；K-ENS-7 转为回归守护，无需 socat/iptables 降级方案 |
| fabric8 exec/file-upload 在目标 pod（非标准基础镜像）的兼容性 | 波次 B 首测确认 exec + 文件传输；备选 HTTP-serve + pod 内 `wget` 拉 arthas-boot.jar |
| k3s NodePort 在 debian 内网防火墙可达性 | 部署期 `on-debian` 验证 nodeIP:nodePort 可达；SC-001 度量 |
| 动态注册与热重载并发正确性 | 波次 A `RegistryComposerTest` 断言线程安全（AtomicReference 原子替换）+ 热重载不误删动态 target |
| 业务 pod 镜像（DemoBusinessApp）构建/入 k3s | ✅ 方案已定：`test-env/k8s/setup.sh` 幂等完成（本机 mvn 编译 → ship debian → docker build → k3s ctr import → apply demo pod），见 [K8S 测试环境设计](../../docs/superpowers/specs/2026-06-23-k8s-test-env-setup-design.md) §2；镜像纯 app（arthas 不入镜像，ensure 时上传） |

## 4. 与既有特性的复用关系

- **复用 001 全部路由/熔断/限流/健康/异步任务**：动态 target 经 `BackendEntryFactory.create` 生成与静态 target 同构的 `BackendEntry`，诊断复用 `ToolsCallRouter` + `AsyncTaskExecutor`，**零新诊断代码**。
- **复用 001 夹具**：`DemoBusinessApp`/`OrderService` 业务逻辑 → K8S 场景容器化入集群（同一业务方法供 watch/trace 捕获）。
- **复用 001 配置外化**：`GatewayProperties` 增 `k8s.*` 子段（kubeconfig/context/namespace/NodePort 范围/ensure 超时）。
- **回归对照** = 001/002：38 工具 tools/list、既有 35 工具行为、热重载、双侧契约全部不得回归（tasks.md 设回归任务）。
```


---

## `specs/003-k8s-arthas-mcp-launch/spec.md`

```markdown
# Feature Specification: K8S 目标 arthas MCP 启动与纳管

**Feature Branch**: `003-k8s-arthas-mcp-launch`

**Created**: 2026-06-22

**Status**: Draft

**Input**: 用户描述："新需求需要支持在目标的K8S容器里面启动arthas MCP 并通过service的端口将MCP的IP暴露给外部……构建一套可以基于当前网关，去对指定目标启动一个 MCP 容器并使用的能力。"（完整需求涉及：① K8S 实验环境离线脚本化构造；② 给定 Linux 服务器→发现 pod/service→选 pod 启 arthas MCP→经 service 暴露→网关感知使用；③ portal 管理平台；④ Windows 直启 agent。）

## Clarifications

### Session 2026-06-22

- Q: portal 在架构上如何定位？ → A: **独立 Java 管理后端**，与现有 arthas MCP 网关并列协作——portal 管 Linux 服务器/K8S 编排/配置；网关保持诊断聚合职责不变。
- Q: 本特性范围与分期？ → A: 一个 spec 覆盖全部 4 块，按优先级渐进：P1=②核心（对指定 K8S 目标启动 MCP 容器并经网关使用）、P2=①K8S 实验环境离线脚本化、P3=③portal 管理平台、P4=④Windows 直启 agent。MVP=P1。
- Q: portal 交互形态？ → A: **CLI/API 优先**（Java 后端），MVP 不含 Web UI；Web UI 列为后续增强。
- Q: MVP（P1）阶段，"枚举/启动/暴露/纳管"编排逻辑承载在哪个组件（portal 尚未存在）？ → A: **模块化单体（modular monolith）**——编排核心、portal、网关聚合为**微服务式边界的独立 Java 模块**，但最终编译为**一个完整的可部署包**；通过**依赖管理**（Maven 模块排除 / optional 依赖 / starter 模式）可随时排除不需要的功能模块（默认全量、按需裁剪）。故 P1 即构建可复用的 Java 编排核心模块，portal（P3）为同包内并列模块包裹该核心，而非独立部署的微服务。
- Q: 动态 target 的逻辑名（身份）如何确立？ → A: **自动命名**——命名规则 = **服务器名 + Pod 名**（如 `{server}-{pod}`），非人工命名。系统**支持自动实例化**：触发后系统自动实例化 arthas MCP、按上述规则自动命名并自动纳管，无需人工逐项命名。初始化由管理系统（编排核心/portal）承载；不连接未实例化的 MCP（先实例化再连接）。
- Q（Q2 消歧·自动实例化触发范围）：是人选定后系统自动，还是全自动？ → A: **由大模型（Claude）经上下文推断需要哪个目标并触发自动初始化，非人工选择 pod**；人只提供**初始的目标 Linux 服务器**。即交互模型为：人提供服务器 → 大模型从对话上下文推断所需目标 → 系统自动实例化+自动命名+自动纳管 → 大模型经网关诊断。实现含义：本工程（网关/编排核心）需把"发现/自动实例化/自动纳管"做成**可被 Claude 经 MCP 调用的工具**；"上下文推断"是 Claude 的原生能力，本工程不实现推断逻辑、只提供确定性工具（标准 MCP agentic 模式）。
- Q（Q3·分期定位）："大模型上下文推断+自动初始化"的 agentic 流是 P1 还是后续？ → A: **后续（north-star）**。**P1 MVP = 自动实例化+自动命名（`{服务器名}-{Pod名}`）+自动纳管的"机制"**（给定明确目标 pod 即自动完成，作为垫脚石）；**north-star（后续增强）= 大模型经上下文推断目标并触发自动初始化（取代人工指定目标）**。即：自动供给机制 P1 与 north-star 共用，差异在"谁指定目标"——P1 由人显式指定目标 pod，north-star 由 Claude 推断。故 P1 用户故事 1 仍保留"人指定目标 pod"，仅把"启动/命名/纳管"三步自动化；Q2 消歧所述"非人工选择 pod"系指 north-star 终态。
- Q（Q4·P1 触发界面 + 架构原则）：P1 的"自动供给"如何触发？ → A: **不构建过程式编排模块**——只暴露**最小化的原子 MCP 能力**（细粒度、各自独立可调），**大模型（Claude）是编排者**，从上下文推断、组合原子工具自己把"发现→启动→暴露→纳管→诊断"串起来。据此修正 Q3："启动/命名/纳管三步自动化"实为"由 LLM 编排原子工具完成"，无过程式编排模块。P1 与 north-star 共享此架构，差异仅在目标来源（P1 人指定 / north-star LLM 推断）。编排原子工具属**独立于网关诊断聚合的 MCP 面**（不污染宪法原则二）。
- Q（Q4 收敛·工具粒度，2026-06-22 设计 [brainstorming](../../docs/superpowers/specs/2026-06-22-k8s-arthas-mcp-launch-design.md) §4 确认）：原"原子工具集（粒度待 plan/design 确认）"现已收敛——判断"原子工具该不该合并"的标准 = **LLM 是否需要在两步之间推理**：「枚举 ↔ 选 pod」之间 LLM 要推理 → `list-pods`/`list-services` 保持独立；而「装→启→暴露→注册→健康检查」之间**无 LLM 决策点**、是一条机械的"确保就绪"操作 → 合并为**幂等 `ensure-arthas-mcp`**（含实例化 + service 暴露 + 动态注册 + 内部健康检查）。故编排 MCP 面最终为 **3 个工具**：`k8s.list-pods` / `k8s.list-services` / `k8s.ensure-arthas-mcp`，外加既有网关诊断工具。FR-002 改述为"幂等供给"，FR-004/FR-005 降为 `ensure` 内部子行为（仍是有效需求，但不再是独立工具）。

## 用户场景与测试 *(mandatory)*

### 用户故事 1 - 对指定 K8S 目标启动 arthas MCP 并经网关使用（Priority: P1）

运维或开发人员给定一台已具备 K8S 环境的 Linux 服务器后，系统能枚举该集群里的 pod 与 service；用户选择一个运行 JVM 的目标 pod，系统为其启动一个 arthas MCP 诊断服务，并通过 K8S service 把该 MCP 的端点对外暴露；暴露就绪后，系统将该端点作为一个新的目标后端**动态注册到现有 arthas MCP 网关**，用户随后经网关（用 `target` 指定该目标）即可对所选 pod 的 JVM 执行 arthas 诊断——"启动 + 暴露 + 纳管 + 使用"链路打通。

**实现机制（Q4 澄清）**：上述链路由**大模型（Claude）经网关组合原子 MCP 能力**完成（枚举→实例化→暴露→纳管→诊断），系统**不内建过程式编排流程**；P1 目标 pod 由人（经对话）指定，north-star 由大模型上下文推断。

**为何这个优先级**：这是本特性存在的核心价值——把"对任意指定 K8S 目标按需启动诊断 MCP 并纳入现有网关使用"的闭环跑通。没有它，后续的环境脚本、portal、Windows agent 都失去依托。这也正是需求总结句"基于当前网关，去对指定目标启动一个 MCP 容器并使用的能力"的落地。

**独立测试**：在一台已具备 K8S 的 Linux 服务器上，选择一个运行 JVM 的 pod，触发"启动→暴露→纳管"，确认经网关对该 `target` 的诊断结果来自所选 pod 的 JVM。

**验收场景**：

1. **Given** 一台已具备 K8S 的 Linux 服务器且集群内有运行 JVM 的业务 pod，**When** 用户触发"枚举 pod/service"，**Then** 返回该集群当前 pod 与 service 清单。
2. **Given** 上述清单，**When** 用户选定一个运行 JVM 的目标 pod 并触发"启动 arthas MCP"，**Then** 系统**自动实例化** arthas MCP、按 `{服务器名}-{Pod名}` **自动命名**、并通过 K8S service 暴露一个可访问端点。
3. **Given** arthas MCP 已暴露且就绪，**When** 系统将其动态注册到网关，**Then** 网关 target 列表新增该目标，且经网关对该 target 的诊断调用返回来自所选 pod 的结果（与直连该 arthas MCP 一致，无篡改）。
4. **Given** 该 target 已纳管，**When** 用户经网关对该 target 执行诊断，**Then** 网关按既有 target 路由规则转发并原样返回结果，行为与现有静态 target 一致（宪法原则二）。

---

### 用户故事 2 - 离线脚本化构造最小 K8S 实验环境（Priority: P2）

用户拿到一台全新的 Linux 服务器（仅装好 OS），执行一套**脚本化、可离线**的构造步骤，即可得到一个最小可用的 K8S 实验环境，无需联网逐步拉取；脚本可重复执行，换一台新机器也能快速复现同一环境，为用户故事 1 提供可复现的实验底座。

**为何这个优先级**：用户故事 1 依赖一个可用的 K8S 环境；脚本化、离线、可复现的构造方式让"任意新机器快速起环境"成为可能，是核心能力可演示、可测试的前提。但它可在"已有 K8S"的假设下后置（故 P2 而非 P1）。

**独立测试**：在一台全新 Linux 服务器上执行构造脚本，完成后确认得到一个可用的最小 K8S 环境，并立即用于用户故事 1 的全链路。

**验收场景**：

1. **Given** 一台仅装好 OS 的全新 Linux 服务器，**When** 用户执行构造脚本，**Then** 在可接受的时间内得到一个可用的最小 K8S 实验环境（含可调度 pod 的基本能力），且过程无需联网拉取外部依赖。
2. **Given** 已构造的环境，**When** 用户再次执行同一脚本，**Then** 脚本幂等、不破坏既有状态、不重复污染环境。
3. **Given** 另一台全新 Linux 服务器，**When** 执行同一脚本，**Then** 得到等价的最小 K8S 环境（可复现）。

---

### 用户故事 3 - portal 独立管理后端统一纳管（Priority: P3）

用户通过一个**独立的管理后端（Java，CLI/API）**作为统一入口，管理：(a) Linux 服务器清单、(b) K8S 编排操作（发现 pod/service、启动 arthas MCP、暴露、纳管）、(c) 现有网关的后端配置。无需直接操作 K8S 命令或手改网关配置文件，即可完成"加服务器→发现→启 MCP→网关纳管→诊断"全链路。

**为何这个优先级**：portal 把分散的能力收敛为统一管理面，提升可用性与可治理性；但它建立在 P1 核心能力之上（编排逻辑可被 portal 调用），故 P3。MVP 不含 Web UI（CLI/API 优先）。

**独立测试**：经 portal 的 CLI/API 完成"添加一台 Linux 服务器→枚举其 K8S pod/service→选 pod 启 arthas MCP→网关纳管→经网关诊断"全链路，无需直接触达 K8S 或网关配置文件。

**验收场景**：

1. **Given** portal 已就绪，**When** 用户经 CLI/API 添加一台 Linux 服务器并枚举其 K8S 资源，**Then** 返回该服务器的 pod/service 清单。
2. **Given** 上述清单，**When** 用户经 CLI/API 选 pod 并触发启动 arthas MCP，**Then** 完成启动、暴露、网关纳管，且该 target 经网关可诊断。
3. **Given** portal，**When** 用户经 CLI/API 查询/管理现有网关的后端配置（逻辑名→地址映射），**Then** 配置变更可生效并被网关感知（复用 001 的热重载能力）。

---

### 用户故事 4 - Windows 直启 arthas MCP 并连接（Priority: P4）

在 Windows 开发机上，用户可一键启动一个 arthas MCP（attach 指定 JVM），并将其作为目标后端注册到网关，从而在 Windows 本地也能经网关使用 arthas 诊断，便于开发与调试。

**为何这个优先级**：Windows 直启是开发/调试便利工具，扩展目标来源（本地 JVM），但非核心 K8S 场景，故 P4。

**独立测试**：在 Windows 机器上一键启动 arthas MCP 并注册到网关，确认经网关对该 target 的诊断成功。

**验收场景**：

1. **Given** 一台 Windows 机器运行着目标 JVM，**When** 用户执行一键启动，**Then** 本地启动 arthas MCP 并注册到网关成为一个 target。
2. **Given** 该 target 已纳管，**When** 用户经网关对其诊断，**Then** 返回来自该 Windows 本地 JVM 的结果。

---

### 边缘情况

- 选中的目标 pod 内**未运行 JVM**或 JVM 不可被 arthas attach → 启动失败，返回明确错误（不静默成功，宪法原则五）。
- 目标 JVM **已存在 arthas 会话**或端口冲突 → 明确提示或幂等处理。
- **K8S API 不可达 / RBAC 权限不足** → 连接、枚举或编排失败，返回明确错误。
- 启动的 **arthas MCP pod 未就绪 / 崩溃** → 经 service 暴露但端点不可用，网关健康检查将其隔离并返回明确错误（宪法原则三）。
- **同一目标重复触发启动** → 幂等（复用既有）或明确提示已存在，不重复创建。
- **service 暴露端口被占用 / 端口范围受限** → 明确错误或自动选择可用端口。
- 目标 pod 被 **K8S 驱逐/重启/删除** → 经 service 暴露的端点变化，网关将该 target 标记不可用并返回明确错误，恢复后可重新纳管（宪法原则三）。
- **动态 target 与网关静态 target 命名冲突** → 明确错误或加限定，二者可共存可区分。
- **多 Linux 服务器 / 多集群** → MVP 以单服务器单集群起步，管理面预留多服务器扩展（见假设）。
- **离线构造脚本**在已存在 K8S 的机器上执行 → 幂等不破坏；在版本/架构不满足的机器上 → 明确前置依赖错误。

## 需求 *(mandatory)*

### 功能需求

**核心：目标 pod 启 arthas MCP + service 暴露 + 网关纳管（P1）**

> **架构原则（Q4 澄清）**：本节能力均以**最小化的原子 MCP 能力**形式暴露（细粒度、各自独立可调），由**大模型（Claude）编排组合**完成「启动→暴露→纳管→诊断」链路；系统**不内建过程式编排流程**。目标 pod 的选择在 P1 由人（经对话）指定、在 north-star 由大模型上下文推断。编排原子工具属独立于网关诊断聚合的 MCP 面（不污染宪法原则二）。

- **FR-001**: 系统必须能凭给定 Linux 服务器的访问方式连接其上的 K8S 环境，并枚举该集群的 pod 与 service（`k8s.list-pods` / `k8s.list-services`）。
- **FR-002**: 系统必须暴露「**对指定目标 pod 幂等供给 arthas MCP（`k8s.ensure-arthas-mcp`）**」的原子 MCP 能力——一次调用**原子地**完成：在目标 pod 内实例化 arthas MCP（自动命名 `{服务器名}-{Pod名}`、自动 attach 该 pod 内 JVM）+ 通过 K8S service（NodePort）暴露一个可达端点 + 内部健康检查确认就绪 + 动态注册到网关；任一子步失败则整体失败且不注册（不污染注册表）；已就绪则零副作用复用。目标 pod 的选择由编排者（大模型；P1 经人指定、north-star 经上下文推断）完成，系统不内建过程式选择/启动流程。
- **FR-003**: 启动的 arthas MCP 必须能诊断**被选中目标 pod 内的 JVM**（诊断对象明确归属被选 pod——`ensure` 经 kubectl exec 进入目标 pod 注入 arthas，attach 该 pod 的 JVM PID）。
- **FR-004**: （`ensure` 内部子行为）启动的 arthas MCP 必须通过 K8S service 对外暴露一个可访问的网络端点（经 NodePort 的节点 IP/端口可达）；arthas MCP 须绑 pod 网络接口（`0.0.0.0`）方经 NodePort 远程可达。
- **FR-005**: （`ensure` 内部子行为）arthas MCP 启动并就绪后，系统必须将其作为新的目标后端**动态注册到现有 arthas MCP 网关**，使网关 target 列表新增该目标并立即可用（与 001 FR-009 动态目标更新一致，符合宪法原则二/三）。
- **FR-006**: 经 service 暴露并由网关纳管后，对该 target 的诊断调用必须经网关按 target 路由、结果原样透传（透明无损，宪法原则二）。

**K8S 实验环境离线脚本化（P2）**

- **FR-007**: 必须提供脚本化的方式，在一台给定的 Linux 服务器上**离线**（无需联网拉取外部依赖）构造一个最小可用的 K8S 实验环境，可全新机器复现。
- **FR-008**: 构造脚本必须**幂等可重复**——对已存在环境重新执行不破坏既有状态；并在可接受的时间内完成。

**portal 独立管理后端（P3）**

- **FR-009**: 必须提供一个**独立的管理后端**（与现有网关并列），把上述**原子能力**（发现/实例化/暴露/纳管）以 API+CLI 暴露给**人类直接操作**（非 agentic 入口，与原子 MCP 工具并存），并管理：(a) Linux 服务器清单、(c) 现有网关的后端配置。
- **FR-010**: 管理后端必须以 **API + 命令行（CLI）**方式提供上述能力；MVP 不提供 Web UI。
- **FR-011**: 管理后端的**核心逻辑必须以 Java 实现**（宪法原则六）；K8S 清单/编排脚本仅作辅助，不承载核心功能逻辑。

**Windows 直启 agent（P4）**

- **FR-012**: 必须支持在 Windows 机器上一键启动一个 arthas MCP（attach 指定 JVM），并将其作为目标后端注册到网关，供经网关诊断使用。

**贯穿：可观测与故障韧性（宪法原则三/五）**

- **FR-013**: 动态注册的 target 后端必须纳入网关既有的**健康监控与故障隔离**；该 target 不可达时，网关对其他 target 的诊断不受影响，对该 target 返回明确错误。
- **FR-014**: 所有编排操作（发现、启动、暴露、注册、移除）必须**结构化可追溯**：记录目标 pod、操作类型、结果状态（宪法原则五）。

### 关键实体

- **Linux 主机（Linux Host）**：一台被管理的 Linux 服务器。关键属性：标识、访问方式（凭据/连接）、其上的 K8S 环境。
- **K8S 集群（K8S Cluster）**：Linux 主机上运行的 K8S 环境，含 pod/service 集合。
- **目标 Pod（Target Pod）**：被选中启动 arthas 诊断的业务 pod。关键属性：名称、命名空间、是否运行可诊断 JVM。
- **arthas MCP 实例（arthas MCP Instance）**：为目标 pod 启动的 arthas MCP 诊断服务。关键属性：所属目标 pod、就绪状态。
- **暴露服务（Exposed Service）**：把 arthas MCP 端点对外暴露的 K8S service（**NodePort**）。关键属性：可访问端点（节点 IP + NodePort → pod 内 arthas MCP 端口）、Service selector/label。
- **动态目标后端（Dynamic Target Backend）**：经暴露、被网关动态纳管的 arthas MCP，作为网关 target（与 001 静态配置 target 共存可区分，带 `source=DYNAMIC` 来源标记）。其逻辑名**自动派生** = `{服务器名}-{Pod名}`（确定性、非人工命名）。
- **供给记录（Orchestration Record）**：一次 `ensure-arthas-mcp` 供给的结构化可观测记录（宪法原则五）。关键属性：逻辑名、来源（server/pod/namespace）、暴露端点（mcpUrl/serviceRef）、状态（ensuring/ready/reused/failed）、供给时间。
- **管理后端（Management Portal）**：独立 Java 管理服务，统一管主机/集群/编排/网关配置。

## 成功标准 *(mandatory)*

### 可度量结果

- **SC-001**: 给定一台已具备 K8S 的 Linux 服务器，用户选定一个运行 JVM 的目标 pod 后，在 **5 分钟内**完成"启动 arthas MCP + service 暴露 + 网关动态纳管"，并经网关对该 pod 完成一次诊断，结果来自该 pod 的 JVM。
- **SC-002**: 在一台**全新（仅装好 OS）的 Linux 服务器**上离线执行构造脚本，在可接受的时间内得到可用的最小 K8S 实验环境，并立即用于 SC-001 全链路；同一脚本换一台新机器可复现等价环境。
- **SC-003**: 当某动态 target 对应的 arthas MCP pod 被 K8S 重启/驱逐，网关在 **30 秒内**将其标记为不可用并对调用返回**明确错误**，不影响其他 target；环境恢复后可重新纳管。
- **SC-004**: 经 portal（CLI/API）可完成"添加 Linux 服务器 → 发现 pod/service → 启动 arthas MCP → 网关纳管 → 经网关诊断"**全链路**，期间无需直接操作 K8S 或手改网关配置文件。
- **SC-005**: 在 Windows 机器上可**一键**启动 arthas MCP 并注册到网关，经网关对其诊断成功。
- **SC-006**: 动态纳管的 target 与网关**原有静态 target 共存**，互不干扰，命名可区分；经网关对二者的诊断各自路由正确、结果原样透传。

## 假设

- **实验级/MVP 环境**：单 Linux 服务器、单 K8S 集群、受控内网、无认证（沿用 001 MVP 安全假设）；多服务器/多集群预留接口，不在 MVP 范围。
- 目标 pod 内运行**可被 arthas attach 的 JVM**；部署拓扑已由 [设计](../../docs/superpowers/specs/2026-06-22-k8s-arthas-mcp-launch-design.md) 决策 #5 定为 **kubectl exec 进入目标 pod 注入 arthas**（attach 该 pod 内 JVM PID，非独立 pod）——目标 pod 须含 shell + java + JVM；distroless/ephemeral 无 shell pod 超出 MVP。本 spec 约束"诊断对象 = 被选 pod 的 JVM"与"经 NodePort service 暴露"。
- **复用现有网关（001/002）**作为诊断聚合层；本特性不改变网关对外 MCP 行为（宪法原则一/二），仅新增"动态 target 来源"。
- portal 与网关均为 **Java**（宪法原则六）；K8S 构造脚本/清单为辅助（非核心逻辑）；**Web UI 列为后续增强**，不在 MVP。
- **模块化单体打包**：编排核心、portal、网关聚合为**微服务式边界的独立 Java 模块**，但最终编译为**一个完整的可部署包**；通过**依赖管理**（Maven 模块排除 / optional 依赖 / starter 模式）可随时排除不需要的功能模块（默认全量、按需裁剪）。故 P1 即构建可复用的 Java 编排核心模块，portal（P3）为同包内并列模块包裹该核心，而非独立部署的微服务。
- **north-star（非 MVP）**：终极愿景是"大模型经对话上下文推断所需目标并触发自动初始化"的 agentic 流（人只提供 Linux 服务器，无需人工选 pod）；P1 先交付"自动实例化+自动命名+自动纳管"的供给机制（人显式指定目标 pod），agentic 推断编排留后续增强（见 Clarifications Q3）。
- Windows agent 复用 arthas 与网关客户端能力，作为开发/调试便利工具（语言选型留 plan，倾向 Java CLI 以符原则六）。
- K8S 实验环境采用**轻量发行版**（具体选型留 plan，宪法原则八先决研究：须先研读候选发行版的离线安装、资源占用与 pod/service 行为）。
```


---

## `specs/003-k8s-arthas-mcp-launch/tasks.md`

```markdown
# Tasks: K8S 目标 arthas MCP 启动与纳管

**Input**: Design documents from `/specs/003-k8s-arthas-mcp-launch/`

**Prerequisites**: plan.md（权威技术栈/结构）、spec.md（用户故事与优先级）、research.md（R1–R8 实施期决策）、data-model.md（实体增量）、contracts/（编排工具契约 + 动态注册不变量）、quickstart.md（端到端验证指南）、`.specify/memory/constitution.md`（宪法 v1.2.0）。

**实施范围声明**：本计划**仅实施 P1 = 用户故事 1**（US1，MVP）。P2（离线构造脚本）/ P3（portal）/ P4（Windows agent）后置，不在本任务清单内（参照 001「集群整体后置」先例）。US1 的供给机制与 north-star 共享，差异仅在"谁指定目标"（P1 人指定 / north-star LLM 推断）。

**TDD 硬约束（不可妥协，宪法原则七 + CLAUDE.md）**：模板"Tests OPTIONAL"在此**被项目宪法推翻**——所有功能代码**必须** TDD：先写失败测试（红）、再实现至通过（绿）、再重构。本清单中**测试任务一律先于其实现任务**出现（测试→实现对成组推进）。**真实性硬约束（零桩）**：成功路径不得用桩模拟 arthas/K8S 成功；故障用真实故障条件。波次 A（纯逻辑 surefire，CI 可跑）/ 波次 B（真实 k3s 夹具 failsafe `*IT.java`，CI 默认 Assume 跳过、本地手跑）/ 波次 C（端到端真实 Claude Code）。

**波次映射**：Phase 2 Foundational = 波次 A（动态注册层纯逻辑地基，阻塞 US1）；Phase 3 US1 = 波次 B + C（K8S 编排层 + 3 工具 + 真实供给 + 端到端）。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成任务依赖）
- **[Story]**: 仅 Phase 3（US1）任务带 `[US1]`；Setup/Foundational/Polish 不带
- 每条任务含精确文件路径

## Path Conventions

- 单 Maven 模块，Java 源 `src/main/java/com/arthas/gateway/...`，测试 `src/test/java/com/arthas/gateway/...`
- 配置 `src/main/resources/`（`application.yml`）+ 仓库根 `config/backends.yaml`
- K8S 测试床脚本在仓库根 `reference/k3s/`、`test-env/k8s/`（新增）

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 构建依赖、配置外化与 K8S 真实测试床（波次 B/C 的前置基建）

- [X] T001 [P] Add `io.fabric8:kubernetes-client` dependency to `pom.xml`（锁定与 Spring Boot 4.1.0 BOM 兼容的最新稳定版，显式声明版本保证可复现构建；research.md R2）
- [X] T002 [P] Add `arthas-gateway.k8s.*` config section to `src/main/resources/application.yml`（kubeconfig 路径、context、namespace、NodePort 范围、ensure 超时；默认指向 `test-env/k8s/kubeconfig/k3s-admin.yaml`）
- [X] T003 [P] Add `K8sProperties`（k8s 子段绑定）to `src/main/java/com/arthas/gateway/config/GatewayProperties.java`（kubeconfig/context/namespace/nodePortRange/ensureTimeout 字段）
- [X] T004 [P] Document optional `source: STATIC` field in `config/backends.yaml`（向后兼容：缺省即 STATIC；既有种子零改动可用，data-model §2）
- [X] T005 [P] Create `reference/k3s/fetch.sh`（离线预置 k3s v1.35.5+k3s1 airgap 资源到 `reference/k3s/`；带重试防 github 间歇不可达，memory `github-com-unreachable`；research.md R3）
- [X] T006 [P] Create `test-env/k8s/Dockerfile.demo`（纯 app 镜像：jdk-21 基础镜像 + 001 夹具 `DemoBusinessApp` 编译产物；**arthas 不入镜像**，ensure 时上传；research.md R7）
- [X] T007 [P] Create `test-env/k8s/demo-pod.yaml`（`demo-business` pod 清单：含 shell+java+JVM，供 ensure 经 fabric8 exec 注入）
- [X] T008 Create `test-env/k8s/setup.sh`（幂等一键：`mvn test-compile` 产 demo class → ship debian → 离线装 k3s 瘦身 + tls-san → docker build demo 镜像 → k3s ctr import → apply demo pod → 导出 root-on-node 派生 admin kubeconfig 到 `test-env/k8s/kubeconfig/k3s-admin.yaml`，server 改 `https://192.168.31.92:6443`，文件 600）（依赖 T005/T006/T007）
- [X] T009 Create `test-env/k8s/teardown.sh`（debian 上 `k3s-uninstall` + 删导出凭证；幂等清理）（依赖 T008）
- [X] T010 [P] Verify test-bed bootstrap：运行 `reference/k3s/fetch.sh` + `test-env/k8s/setup.sh`，确认 `demo-business` pod `Running`/`Ready` 且本机 `k3s-admin.yaml` 可 `kubectl get pods`（波次 B/C 前置门禁）

**Checkpoint**: 依赖就位、配置外化完成、K8S 真实测试床可一键幂等起/拆——波次 B/C 测试可运行

---

## Phase 2: Foundational (Blocking Prerequisites — 波次 A 纯逻辑动态注册层)

**Purpose**: 动态注册层正确性地基（`ensure` 注册子行为的阻塞前置；纯逻辑、无 K8S、CI 可跑）。**⚠️ CRITICAL**：US1 的 ensure 供给依赖此层（register 进 `DynamicBackendStore` + `RegistryComposer` 合并），须先全绿。

> **TDD（宪法原则七）**：下列测试任务先写并确认失败（红），再实现至通过（绿）。断言 ID 见 `contracts/dynamic-registration-invariants.md §5`。

- [X] T011 [P] Write failing `SourceParsingTest` in `src/test/java/com/arthas/gateway/backend/SourceParsingTest.java`（断言 D-SOURCE-1：YAML 缺省 `source` → STATIC、显式 `source: STATIC` → STATIC、动态注册路径强制 DYNAMIC）
- [X] T012 Implement `Source` enum + `BackendConfig.source` 字段 + `BackendConfigLoader` source 解析 in `src/main/java/com/arthas/gateway/backend/Source.java`、`src/main/java/com/arthas/gateway/backend/BackendConfig.java`、`src/main/java/com/arthas/gateway/backend/BackendConfigLoader.java`（紧凑构造器缺省 STATIC，向后兼容；green for T011）
- [X] T013 [P] Write failing `OrchestrationRecordTest` in `src/test/java/com/arthas/gateway/orchestration/OrchestrationRecordTest.java`（断言 data-model §9 状态机：`ensuring→ready/reused/failed` 转换、`failed` 不注册原子性、`createdAt` 传入非进程取时）
- [X] T014 Implement `OrchestrationRecord` + `OrchestrationRecordStore` in `src/main/java/com/arthas/gateway/orchestration/OrchestrationRecord.java`、`src/main/java/com/arthas/gateway/orchestration/OrchestrationRecordStore.java`（内存态 `ConcurrentHashMap<logicalName, record>`；green for T013）
- [X] T015 Write failing `DynamicBackendStoreTest` in `src/test/java/com/arthas/gateway/backend/DynamicBackendStoreTest.java`（断言 D-REG-1..4、D-UNREG-1..3：register/unregister、与静态种子同名拒绝、同名同 URL 幂等、同名异 URL 拒绝、仅 DYNAMIC 可移、不存在幂等）（依赖 T012）
- [X] T016 Implement `DynamicBackendStore` in `src/main/java/com/arthas/gateway/backend/DynamicBackendStore.java`（`ConcurrentHashMap`、强制 source=DYNAMIC、冲突检测 I-3、变更触发 `RegistryComposer.compose()`；green for T015）（依赖 T012）
- [X] T017 Write failing `RegistryComposerTest` in `src/test/java/com/arthas/gateway/backend/RegistryComposerTest.java`（断言 D-COEXIST-1/2、D-ATOMIC-1、D-VERSION-1 + 不变量 I-1..I-7：热重载不误删动态 target、原子替换 in-flight 不串台、version 去重）（依赖 T016）
- [X] T018 Implement `RegistryComposer` + adjust `BackendRegistryReloader` to compose(static∪dynamic) before `RegistryHolder.getAndSet` in `src/main/java/com/arthas/gateway/backend/RegistryComposer.java`、`src/main/java/com/arthas/gateway/backend/BackendRegistryReloader.java`（复用 reloader diff/复用逻辑，仅 swap 前合并 dynamic；既有 `BackendRegistryReloaderTest`/`HotReloadIT` 须继续通过；green for T017）（依赖 T016）

**Checkpoint**: 动态注册层全绿（`mvn -pl . test -Dtest='DynamicBackendStoreTest,RegistryComposerTest,SourceParsingTest,OrchestrationRecordTest'`），US1 编排层可在此之上构建

---

## Phase 3: User Story 1 - 对指定 K8S 目标启动 arthas MCP + service 暴露 + 网关纳管 + 经网关诊断 (Priority: P1) 🎯 MVP

**Goal**: 人提供 Linux 服务器名 + kubeconfig → Claude 经 3 个编排 MCP 工具（`k8s.list-pods`/`k8s.list-services`/幂等 `k8s.ensure-arthas-mcp`）+ 既有诊断工具，编排"枚举→供给→暴露→纳管→诊断"全链路；结果明确来自所选 pod 的 JVM（SC-001）。

**Independent Test**: 在 debian k3s 集群选一个运行 JVM 的 `demo-business` pod，触发 ensure → 网关 `list-targets` 新增该 target（source=DYNAMIC）→ 经网关 watch/trace 该 target 返回该 pod JVM 真实诊断（SC-001，5 分钟内）。

> **TDD（宪法原则七）+ 真实性硬约束（零桩）**：波次 B 契约 IT 先写（红，引用尚未存在的行为），再实现至通过（绿）。IT 由官方 MCP Java SDK client 驱动（确定性断言），走真实 k3s + 真实业务 pod + 真实 arthas 注入（**零桩**），CI 默认 Assume 跳过、本地手跑（quickstart §2.3）。断言 ID 见 `contracts/k8s-orchestration-tools-contract.md §5`。

### 波次 B：K8S 编排层 + 3 工具（真实 k3s 夹具）

- [X] T019 [US1] Implement `K8sClientFactory` in `src/main/java/com/arthas/gateway/orchestration/K8sClientFactory.java`（kubeconfig → fabric8 `KubernetesClient`；enabling plumbing，由下游 IT 覆盖）
- [X] T020 [P] [US1] Write failing `K8sListToolsContractIT` in `src/test/java/com/arthas/gateway/orchestration/K8sListToolsContractIT.java`（断言 K-LP-1、K-LS-1：真实集群 pod/service 清单、namespace 过滤、`hasJvm`/`hasShell` 标记）
- [X] T021 [US1] Implement `K8sPodExplorer` in `src/main/java/com/arthas/gateway/orchestration/K8sPodExplorer.java`（fabric8 list pods/services、探测 hasJvm/hasShell）（依赖 T019）
- [X] T022 [US1] Implement `K8sToolHandlers`（listPods/listServices 完整 + ensureArthasMcp 占位待接 Provisioner）+ `K8sToolRegistry`（3 个 `ExposedTool`，`routingMode=GATEWAY_LOCAL`，handler 自带闭包不经路由器）+ merge into `GatewayMcpServerConfig#mcpToolSpecifications`（35+3=**38** specs）in `src/main/java/com/arthas/gateway/orchestration/K8sToolHandlers.java`、`src/main/java/com/arthas/gateway/orchestration/K8sToolRegistry.java`、`src/main/java/com/arthas/gateway/config/GatewayMcpServerConfig.java`（green for list tools K-LP-1/K-LS-1；`listChanged=false` 不变）（依赖 T021）
- [X] T023 [P] [US1] Write failing `ArthasProvisionerIT` in `src/test/java/com/arthas/gateway/orchestration/ArthasProvisionerIT.java`（断言 K-ENS-1/2/4..9、K-ATOMIC-1：对真实 demo pod ensure→ready、重复→reused、无 JVM/无 shell/绑 loopback 等真实故障→结构化错误且未注册、命名派生 `{server}-{pod}`）
- [X] T024 [US1] Implement `NodePortExposer` in `src/main/java/com/arthas/gateway/orchestration/NodePortExposer.java`（label pod `arthas-mcp-gateway/target=<logicalName>` + create NodePort Service selector 命中 → 可达 `mcpUrl=http://<nodeIP>:<nodePort>`，根 URL 无 `/mcp`；research.md R5）（依赖 T019）
- [X] T025 [US1] Implement `ArthasProvisioner` in `src/main/java/com/arthas/gateway/orchestration/ArthasProvisioner.java`（ensure 核心：fabric8 exec 定位 JVM PID → 上传/获取 `arthas-boot.jar` → exec 启动 `--attach-only --http-port --target-ip 0.0.0.0 --use-version 4.3.0` → NodePort 暴露 → 内部健康检查（轮询 initialize/listTools）→ `DynamicBackendStore.register` + composer swap；任一子步失败→failed 不注册；`OrchestrationRecord` 全程记录；green for T023）（依赖 T024、Phase 2）
- [X] T026 [US1] Wire `K8sToolHandlers.ensureArthasMcp` → `ArthasProvisioner`（完成 ensure handler：派生 `{server}-{pod}`、查注册表幂等复用、调用 Provisioner、按契约映射 `reason`/`stage` 结构化错误）in `src/main/java/com/arthas/gateway/orchestration/K8sToolHandlers.java`（依赖 T025）
- [X] T027 [P] [US1] Write failing `K8sEnsureContractIT` in `src/test/java/com/arthas/gateway/orchestration/K8sEnsureContractIT.java`（断言 K-ENS-3、K-COEXIST-1/2 + SC-003：经网关 MCP 端点 ensure→用返回 target 调 watch/trace 捕获该 pod JVM 真实诊断、热重载不误删动态 target、pod 删除→30 秒内隔离且明确错误不影响其他 target；官方 SDK client 驱动）

**Checkpoint (波次 B)**: 真实 k3s 上 `mvn -pl . verify -Dit.test='K8sListToolsContractIT,ArthasProvisionerIT,K8sEnsureContractIT'` 全绿（含 K-ENS-7 回归守护：`0.0.0.0` 经 NodePort 可达 / loopback 不可达，research.md R4）

### 波次 C：端到端（真实 Claude Code 编排）

- [X] T028 [US1] Wave C 端到端验证：真实 Claude Code（`claude -p --mcp-config .mcp.json`）编排 `k8s.list-pods`→选含 JVM 的 demo pod→`k8s.ensure-arthas-mcp`→用返回 target 调 `watch`，确认结果来自该 pod JVM 且全程 5 分钟内（SC-001；可用性冒烟走真实 CC，仅验"能调通"）

**Checkpoint (波次 C)**: SC-001 闭环跑通——"启动 + 暴露 + 纳管 + 使用"经真实 Claude Code 编排完成，结果归属正确

---

## Phase 4: Polish & Cross-Cutting Concerns

**Purpose**: 跨切面加固、回归守护与文档收尾（不破 001/002）

- [X] T029 [P] Regression guard：确认 `tools/list` = **38**（35 既有 + 3 编排）、`listChanged=false`；既有 35 工具行为 + 双侧契约（`InitializeAndToolsListContractTest`/`GatewayToolsContractTest`/`ToolsCallRoutingContractIT`）+ `HotReloadIT` + 异步任务全绿（`mvn -pl . verify`，quickstart §5）
- [X] T030 [P] Add optional ArchUnit boundary test in `src/test/java/com/arthas/gateway/architecture/PackageBoundaryTest.java`（断言 `com.arthas.gateway.backend..`/`handler..`/`config..` 不依赖 `com.arthas.gateway.orchestration..`——gateway-core 零 K8S 感知，research.md R1/R6；test-scope 轻量依赖）
- [X] T031 [P] Verify gateway-core 零 K8S 感知：确认 `ToolsCallRouter`/`GatewayToolHandlers` 无 `k8s.*` 分支、编排工具 handler 闭包不经路由器（research.md R6 内聚性纪律）
- [X] T032 Run `quickstart.md` validation：场景 A（波次 A CI）+ B（真实 k3s）+ C（端到端）+ SC-003（pod 删除故障韧性）+ 回归（§6 Done Definition 逐项核对）
- [X] T033 [P] Update docs：README/quickstart 增 38 工具说明、`arthas-gateway.k8s.*` 配置、`test-env/k8s/` 测试床用法（宪法"每项新能力必须有文档"）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，可立即开始；T008/T009 依赖 T005/T006/T007
- **Foundational (Phase 2 = 波次 A)**: 依赖 Setup 的 fabric8 依赖（T001 仅供 Phase 3，本层实际不依赖 K8S）；**阻塞 US1**——`ensure` 注册子行为依赖此层
- **User Story 1 (Phase 3 = 波次 B + C)**: 依赖 Foundational 全绿 + Setup 测试床（T010）就绪
- **Polish (Phase 4)**: 依赖 US1 完成

### Within Foundational (波次 A)

1. T011/T013 可并行（独立）→ T012/T014 各自 green
2. T015（DynamicBackendStoreTest）依赖 T012（source）→ T016 green
3. T017（RegistryComposerTest）依赖 T016 → T018 green（既有 reloader 测试须回归通过）

### Within US1 (波次 B + C)

1. T019（K8sClientFactory）为所有 fabric8 操作的前置
2. 列举链：T020（IT 红）→ T021（PodExplorer）→ T022（Handlers/Registry/Config 合并 38）→ 列举工具 green
3. 供给链：T023（IT 红）→ T024（NodePortExposer）→ T025（ArthasProvisioner）→ T026（接 ensure handler）→ 供给 green
4. 端到端契约：T027（K8sEnsureContractIT 红）在 T026 后 green
5. 波次 C：T028 端到端验证在全部 green 后执行

### Parallel Opportunities

- Setup：T001–T007、T010 彼此独立（不同文件）可并行；T008/T009 串行
- Foundational：T011 ∥ T013（独立测试/实体）；T012 ∥ T014 可并行实现
- US1：T020 ∥ T023 ∥ T027 三个 IT 可并行先写（红）；T024 ∥ T021 在 T019 后可并行
- Polish：T029–T033 彼此独立可并行

---

## Parallel Example: US1 波次 B 测试先行

```bash
# 并行先写三个失败契约 IT（红）：
Task T020: "K8sListToolsContractIT in src/test/.../orchestration/K8sListToolsContractIT.java"
Task T023: "ArthasProvisionerIT in src/test/.../orchestration/ArthasProvisionerIT.java"
Task T027: "K8sEnsureContractIT in src/test/.../orchestration/K8sEnsureContractIT.java"

# 再按依赖链实现至 green（T019→T021→T022 / T024→T025→T026）
```

## Parallel Example: Foundational 波次 A

```bash
# 并行实现两组独立实体（green）：
Task T012: "Source + BackendConfig.source + BackendConfigLoader"
Task T014: "OrchestrationRecord + OrchestrationRecordStore"
```

---

## Implementation Strategy

### MVP First（仅 US1）

1. Phase 1 Setup（依赖 + 配置 + 测试床）
2. Phase 2 Foundational（波次 A 动态注册层——**CRITICAL，阻塞 US1**）
3. Phase 3 US1（波次 B 编排层 + 3 工具 → 波次 C 端到端）
4. **STOP and VALIDATE**：US1 独立测试（SC-001 闭环 + 回归 001/002）
5. Phase 4 Polish（回归守护 + 边界 + 文档）

### Incremental Delivery（波次递进）

1. Setup + Foundational → 动态注册地基 ready（波次 A CI 全绿）
2. US1 波次 B → 真实 k3s 供给契约全绿（K-ENS-* / K-COEXIST-*）
3. US1 波次 C → 端到端 SC-001 闭环跑通
4. Polish → 38 工具 + 回归不破 + 文档收尾
5. 每个波次增价值且不破坏前一波次（回归对照 = 001/002）

---

## Notes

- **TDD 硬约束**：所有功能代码测试先于实现（红→绿→重构），测试任务一律成对先出现
- **真实性硬约束（零桩）**：成功路径真实 arthas/K8S；故障用真实故障条件（停 pod/无 shell/无 JVM/绑 loopback/无 exec 权限）；CI 对波次 B/C `Assume` 跳过、本地手跑
- **gateway-core 零 K8S 感知**：编排工具 handler 自带闭包、不经 `ToolsCallRouter`（research.md R6）；ArchUnit 守护（T030）
- **P2/P3/P4 不在本清单**：离线构造脚本/portal/Windows agent 后置，参照 001「集群整体后置」先例
- 每个任务或逻辑组完成后提交；任一 checkpoint 可停下独立验证；同一问题连续失败 3 次暂停重评（CLAUDE.md）
```


---

## `specs/003-k8s-arthas-mcp-launch/冒烟测试报告.md`

```markdown
# K8S 目标 arthas MCP 启动与纳管 · 冒烟测试报告（003 特性）

| 项 | 值 |
|---|---|
| 特性 | `003-k8s-arthas-mcp-launch`（P1 / 用户故事 1 / MVP） |
| 网关版本 | 0.1.0-SNAPSHOT（fat jar `target/arthas-mcp-gateway-0.1.0-SNAPSHOT.jar`，**38 工具**） |
| 测试时间 | 2026-06-24（本地 Windows，集群在 debian 192.168.31.92） |
| 测试方式 | **真实环境零桩**：真实 k3s 集群 + 真实业务 pod（demo-business）；驱动分层——真实 Claude Code（`claude -p`）验「工具可用性」+ 官方 MCP Java SDK client（failsafe `*IT`）验「结果一致性与双侧协议契约」 |
| 回归对照 | 上一特性基线 `specs/002-code-review-remediation/`（更早 `specs/001-arthas-mcp-gateway/`） |

> **结论先行**：SC-001 端到端闭环全部通过。真实 Claude Code 经 MCP 编排 `k8s.list-pods` → `k8s.ensure-arthas-mcp` → `watch`，诊断结果（`OrderResult[orderId=46, price=1433, valid=true]`）明确来自所选 pod（`demo-business`）的 JVM。`./mvnw verify` **BUILD SUCCESS**：failsafe K8S 契约 IT `30/0/0/0` 全绿、surefire 含修复的回归守护（38 工具契约）与 gateway-core 零 K8S 边界。预存回归（`InitializeAndToolsListContractTest` 工具数 35→38）已修复。

---

## 1. 测试目标

验证 003 特性的 north-star 场景 **SC-001**：对指定 K8S 目标 pod，幂等启动 arthas MCP + NodePort 暴露 + 动态纳管 + 经网关诊断，且**诊断结果明确来自该 pod 的 JVM**（非桩、非串台）。配套验证：

- 3 个编排工具（`k8s.list-pods`/`k8s.list-services`/`k8s.ensure-arthas-mcp`）经真实 Claude Code 可调通；
- `ensure → watch` 经官方 SDK client 确定性断言（K-ENS-3）；
- 故障韧性 SC-003（后端不可达 → 熔断隔离，不影响其他 target）；
- 回归不破 001/002（38 工具 tools/list、双侧契约、热重载）；
- gateway-core 包零 K8S 依赖（ArchUnit 守护）。

## 2. 测试环境（真实，零桩）

| 角色 | 实体 | 说明 |
|---|---|---|
| K8S 集群 | 真实 **k3s** @ debian（`192.168.31.92`，node IP） | 一键幂等起：`bash test-env/k8s/setup.sh`（离线装 k3s + build demo 镜像 + import + apply + 导出 admin kubeconfig） |
| 业务 pod | `demo-business`（`test-env/k8s/Dockerfile.demo`，纯 app 镜像） | 含 shell+java+JVM；`OrderService.hotMethod` 由后台 hot-loop 自驱动触发；**arthas 不在镜像内**，ensure 时经 fabric8 上传 `tools/arthas-boot.jar` |
| kubeconfig | `test-env/k8s/kubeconfig/k3s-admin.yaml`（root-on-node 派生 admin，server `https://192.168.31.92:6443`） | **gitignored**（root-admin 集群凭证，仅本地受控内网） |
| 网关 | `arthas-mcp-gateway` fat jar（本机 Windows 运行，集群外） | `:8761`（`/mcp`、`/actuator/health`）；`arthas-gateway.k8s.kubeconfig` 指向上述 kubeconfig |

> 网关集群外运行 + 远程 kubeconfig/NodePort 正是 SC-001 真实拓扑（验证"非同机纳管"）。

## 3. 真实 Claude Code 实调（`claude -p`，工具可用性）

> 驱动分层（CLAUDE.md）：工具「可用性」走真实 Claude Code（`claude -p --mcp-config .mcp.json --model sonnet --strict-mcp-config`，prompt 经 stdin 传入以规避中文标点 shell 转义）。`.mcp.json` 指向 `http://127.0.0.1:8761/mcp`。

### 3.1 `k8s.list-pods`（枚举候选）

```
default 命名空间 pod 列表：
| Pod 名称 | hasJvm |
| demo-business | true |
```

✅ 真实 Claude Code 经 MCP 调通编排工具，返回**真实 k3s 集群**的 pod 清单（`demo-business` 含可诊断 JVM）。

### 3.2 `k8s.ensure-arthas-mcp` + `watch`（SC-001 完整闭环）

Claude 依次编排两步，结果如下（**原样来自工具，真实数据**）：

**步骤 1 — `k8s.ensure-arthas-mcp`（供给 + 纳管）**

| 字段 | 值 |
|---|---|
| target | `debian-demo-business` |
| status | `ready`（新建并就绪：注入 arthas + 启动 MCP + NodePort 暴露 + 健康检查 + 动态纳管完整流程） |
| mcpUrl | `http://192.168.31.92:32017` |
| namespace | `default` |

**步骤 2 — `watch`（异步任务 `t-293819`，真实诊断捕获）**

```
status=completed  resultCount=1  timedOut=false  stage=final  isError=false
className   = com.arthas.gateway.testfixtures.OrderService
methodName  = hotMethod
accessPoint = AtExit                      ← 方法正常返回后捕获
cost        = 0.032019 ms
ts          = 2026-06-24 15:57:43.481
value（默认 {params, target, returnObj}）：
  params    = @Object[][isEmpty=false; size=1]                         ← 1 个入参
  target    = @OrderService[…@3eebd408]                                ← 被观测实例
  returnObj = @OrderResult[OrderResult[orderId=46, price=1433, valid=true]]   ← 真实业务返回值
```

✅ **铁证**：诊断数据由 `demo-business` 在观测窗口内的**真实调用**产生（返回具体业务对象 `OrderResult{orderId=46, price=1433, valid=true}`），非桩。整条链路「K8S 启动纳管 → 网关路由 → arthas watch → 真实调用捕获」端到端打通，全程 < 5 分钟（SC-001）。

## 4. 官方 MCP Java SDK client · 契约验证（failsafe `*IT`）

`./mvnw verify` **BUILD SUCCESS**（Total 01:49 min）。failsafe 阶段 K8S 契约 IT 全绿：

```
[INFO] Tests run: 30, Failures: 0, Errors: 0, Skipped: 0
  ArthasProvisionerIT        : 5/0/0/0  (15.4s)   ← K-ENS-1/2/4..9 + K-ATOMIC-1 + K-ENS-7 回归守护
  K8sEnsureContractIT        : 2/0/0/0  (33.1s)   ← K-ENS-3 + SC-003（真实熔断）
  K8sListToolsContractIT     : 3/0/0/0  (1.8s)    ← K-LP-1 + K-LS-1
```

`K8sEnsureContractIT` 真实链路日志（节选）：

```
NodePort 已暴露：default/demo-business-2 → http://192.168.31.92:31544
ensure 供给完成：target=debian-demo-business-2 mcpUrl=http://192.168.31.92:31544
异步任务已接受 tool=watch target=debian-demo-business taskId=t-b81993
Registered tools: 38
ToolCapabilities[listChanged=false]
```

✅ SDK client 确定性断言：`ensure → watch` 经网关捕获**该 pod JVM** 的真实诊断（K-ENS-3）；`listChanged=false` 不变（38 工具静态，不广播变更）。

## 5. 回归守护

### 5.1 预存回归修复：`InitializeAndToolsListContractTest`（35 → 38）

T022 合并 3 个 K8S 编排工具（35→38）时，**遗漏同步更新** 001/002 基线契约测试，导致 T029 `./mvnw verify` 暴露 2 失败 + 1 错误（`s_tl_1` 期望 35 实得 38；`s_tl_2` K8S 工具误判"缺 target"；`s_tl_3` K8S 工具不在 baseline 触发 NPE）。证据驱动定位（`K8sToolRegistry.java:24-26` 常量确认 `k8s.` 前缀；`arthas-tools.json` baseline = 31；`quickstart.md:74` 官方基线 38）后修复：

- `s_tl_1_toolsCountIs35` → `s_tl_1_toolsCountIs38`（总数 35→38）；
- `arthasTools()` 辅助法增加 `k8s.*` 前缀过滤（K8S 工具无 `target`，本就不该进逐字比对）。

修复后单跑：`Tests run: 8, Failures: 0, Errors: 0, Skipped: 0` ✅。

### 5.2 gateway-core 零 K8S 依赖（`PackageBoundaryTest`，ArchUnit）

```
Tests run: 2, Failures: 0, Errors: 0
  diagnosticCoreDoesNotDependOnOrchestration   ← backend/handler/config/tool/task/auth/obs 不依赖 orchestration
  diagnosticCoreDoesNotDependOnK8sClientApi    ← 不依赖 io.fabric8 / io.kubernetes
```

✅ gateway-core 包零 K8S 感知（编排工具自带闭包、不经 `ToolsCallRouter`，research.md R1/R6）。

### 5.3 故障韧性 SC-003（真实熔断，非桩）

`K8sEnsureContractIT` 以**真实故障条件**验证（`demo-business-2` 后端不可达）：

```
基础设施故障（计入熔断）tool=jvm target=debian-demo-business-2 耗时=5004ms  BackendUnreachableException
基础设施故障（计入熔断）tool=jvm target=debian-demo-business-2 耗时=5001ms
基础设施故障（计入熔断）tool=jvm target=debian-demo-business-2 耗时=5014ms
熔断拒绝（OPEN）target=debian-demo-business-2 retryAfterMs=993
工具调用完成 tool=jvm target=debian-demo-business isError=false 耗时=104ms   ← 其他 target 不受影响
```

✅ 连续 3 次基础设施故障 → 熔断 OPEN → 退避 probe 恢复；`debian-demo-business`（健康 target）诊断不受影响。

## 6. 通过项汇总

| 能力 | 验证手段 | 结果 |
|---|---|---|
| SC-001 端到端（启动+暴露+纳管+诊断，结果归属正确 pod JVM） | 真实 Claude Code `claude -p`（list-pods→ensure→watch） | ✅ `OrderResult[orderId=46,price=1433,valid=true]` 来自 demo-business JVM |
| 编排工具经真实 CC 可调通 | `claude -p`（list-pods / ensure / watch） | ✅ 全部调通，返回真实集群/pod 数据 |
| ensure 幂等供给 + NodePort 暴露 + 动态纳管 | SDK client（`K8sEnsureContractIT`） | ✅ status=ready，mcpUrl 可达，target 进注册表 |
| 结果一致性 + 双侧契约 | SDK client（failsafe `*IT`） | ✅ K-ENS-3 真实诊断确定性断言 |
| 故障韧性 SC-003（熔断隔离、不影响他者） | SDK client（真实不可达后端） | ✅ 熔断 OPEN → probe 恢复，健康 target 不受影响 |
| 回归不破 001/002 | `./mvnw verify` | ✅ BUILD SUCCESS，38 工具 tools/list、`listChanged=false` |
| 38 工具契约（含 K8S 工具无 target） | `InitializeAndToolsListContractTest`（修复后） | ✅ 8/8 |
| gateway-core 零 K8S 依赖 | `PackageBoundaryTest`（ArchUnit） | ✅ 2/2 |

**总体结论**：003 特性（K8S 目标 arthas MCP 启动与纳管，P1/US1/MVP）在真实 k3s 集群上**SC-001 闭环全部通过**，真实 Claude Code 端到端编排验证可用，回归不破 001/002，gateway-core 零 K8S 边界由 ArchUnit 守护。

---

## 附录 · 复现与清理

```bash
# 1) 备好真实 k3s 测试床（一次性，幂等）
bash reference/k3s/fetch.sh          # 离线预置 k3s airgap 资源
bash test-env/k8s/setup.sh           # 装集群 + build demo 镜像 + apply pod + 导出 kubeconfig

# 2) 起网关常驻（指向 kubeconfig，38 工具）
bash smoke/gateway-start.sh

# 3) 真实 Claude Code 端到端（SC-001）
echo "用 k8s.list-pods 列出 default 命名空间 pod" | claude -p --mcp-config .mcp.json --model sonnet --strict-mcp-config
echo "调 k8s.ensure-arthas-mcp(server=debian,pod=demo-business)，再用返回 target 调 watch(OrderService.hotMethod)" \
  | claude -p --mcp-config .mcp.json --model sonnet --strict-mcp-config

# 4) 回归 + 契约 IT（需真实 k3s）
./mvnw verify

# 5) 清理常驻进程
bash smoke/gateway-stop.sh
```
```


---

## `specs/004-portal-backend-management/contracts/admin-api-contract.md`

```markdown
# 管理 API 契约：portal 后端管理平台（004）

> 网关侧 `/admin` HTTP 管理 API 的端点契约（方法/路径/请求/响应/状态码/错误）。实体见 [data-model.md](../data-model.md)；不变量见 [admin-invariants.md](./admin-invariants.md)。鉴权 MVP = Noop（受控内网，research.md R8）。能力按需开关（`@ConditionalOnProperty`，关闭则端点 404，R9）。

## 1. 后端配置 CRUD（`/admin/backends`）

### GET `/admin/backends` — 列表 + 汇总

- **响应 200**：`{ "backends": [BackendDto], "summary": { "total": N, "healthy": H, "unhealthy": U } }`
- **断言 A-LIST-1**：返回全部后端（静态 + 动态），每条含 `name/source/state/healthy/breaker/url/protocol/auth.mode/timeouts/maxConcurrentTasks`；`summary` 计数与列表一致。

### GET `/admin/backends/{name}` — 详情

- **响应 200**：`BackendDto`
- **错误 404**：`{ "error": "未知后端", "name": "...", "available": [...] }`

### POST `/admin/backends` — 新增

- **请求**：`CreateBackendRequest`（`name`/`url`/`protocol`/`auth`/`connectTimeoutMs`/`callTimeoutMs`/`maxConcurrentTasks`）
- **响应 201**：`BackendDto`
- **行为**：静态后端 → 写回 `config/backends.yaml`（SnakeYAML dump，R2）+ 热重载 30s 内纳管；**动态后端 POST 拒绝**（R3）。
- **错误 400**：`name` 重复 / `url` 非法 / 缺必填 / 动态后端 POST → `{ "error": "...", "reason": "..." }`
- **断言 A-ADD-1**：静态后端新增后，网关 `list-targets` 30s 内可见（复用 SC-002）。
- **断言 A-ADD-2**：动态后端 POST → 400 + `reason: dynamic_backend_not_editable`。

### PUT `/admin/backends/{name}` — 修改

- **请求**：`UpdateBackendRequest`（`url`/`auth`/`connectTimeoutMs`/`callTimeoutMs`/`maxConcurrentTasks`，可空=不改）
- **响应 200**：`BackendDto`
- **行为**：静态后端 → 写回 YAML 热重载；**动态后端 PUT 拒绝**（R3）。
- **错误 400**：动态后端 PUT；404：未知 name。
- **断言 A-UPD-1**：静态后端改 url 后，热重载生效，后续诊断走新 url。

### DELETE `/admin/backends/{name}` — 删除

- **响应 204**：无体
- **行为**：静态后端 → 从 YAML 移除 + 热重载；动态后端 → `DynamicBackendStore.unregister` 即时移除。
- **错误 404**：未知 name。
- **断言 A-DEL-1**：删除后 `list-targets` 不再含该 target（静态经热重载 / 动态即时）。
- **断言 A-DEL-2**：删除 in-flight 后端经 002 `retirementGrace` 宽限切断（不破坏韧性）。

## 2. 异步任务（`/admin/tasks`）

### GET `/admin/tasks` — 列表查询（增量，FR-015）

- **查询参数（全可选）**：
  - `status`（enum: `WORKING`/`COMPLETED`/`FAILED`/`CANCELLED`）：状态过滤，复用 `TaskStore.list(TaskState)`；缺省=全部
  - `tool`（string）：工具名**精确**匹配（如 `watch`）
  - `target`（string）：target 名**精确**匹配（如 `debian-demo-business`）
  - `page`（int ≥ 0，默认 `0`）：页码（0-based）
  - `size`（int 1..100，默认 `20`）：每页条数；`>100` clamp 100、`<1` 取 1
- **响应 200**：`{ "items": [TaskSummaryDto], "total": N, "page": P, "size": S }`
  - `items`：当前页摘要，按 `createdAt` **倒序**（最新在前）
  - `total`：**过滤后、分页前**的总数（分页元数据独立）
  - `TaskSummaryDto`：`taskId`/`tool`/`target`/`status`/`createdAt`/`completedAt`/`isError`（**无 frames**，INV-LIST-1；`isError` 仅 `COMPLETED` 时据 `result.isError()`，其余态 `false`）
- **行为**：空结果返 200 + `items=[]`/`total=0`（**非 404**）；`page`/`size` 越界 clamp（不报 400）。
- **断言 A-LIST-TASKS-1**：`items` 按 `createdAt` 倒序；`total` = 过滤后总数（与分页独立，INV-LIST-2/3）。
- **断言 A-LIST-TASKS-2**：`status`/`tool`/`target` 组合过滤后，`items` 仅含匹配项，`total` 同步反映。
- **能力开关**：`arthas-gateway.admin.export.enabled`（与 export 共用，关则 404，INV-LIST-4）。

### GET `/admin/tasks/{taskId}/export?format=json` — 导出

- **响应 200**：`application/json` + `Content-Disposition: attachment; filename=<taskId>.json`；体 = `TaskExportDto`（`taskId/tool/target/status/createdAt/completedAt` + `frames[]`）。
- **行为**：`frames[]` **原样来自 `TaskStore.get(taskId)` 的 `GatewayTask` 结果**，不篡改/摘要/截断（宪法原则二）。
- **错误 404**：任务不存在；**409**：任务未 `completed`（`working`/`cancelled`/`failed`）→ `{ "error": "...", "status": "...", "reason": "task_not_completed" }`。
- **断言 A-EXP-1**：导出 `completed` 任务的 `frames` 与 `task-get` 结果一致（无篡改）。
- **断言 A-EXP-2**：导出未完成/不存在任务 → 409/404，不返空体。

## 3. 错误体格式（统一）

```json
{ "error": "<简述>", "reason": "<machine_code>", "name"?: "...", "available"?: [...], "status"?: "..." }
```

错误显式传播、不静默成功（宪法原则五 / FR-010）。

## 4. 能力开关（`@ConditionalOnProperty`）

| 开关 | 默认 | 关闭时 |
|---|---|---|
| `arthas-gateway.admin.crud.enabled` | `true` | `/admin/backends/*` 全部 404 |
| `arthas-gateway.admin.export.enabled` | `true` | `/admin/tasks/*/export` 404 |

两能力独立、互不影响（R9 / FR-012）。

## 5. 鉴权

MVP Noop（受控内网，复用 001 `GatewayAuthenticator` 语义）。Bearer token 鉴权为演进项（research.md R8）。
```


---

## `specs/004-portal-backend-management/contracts/admin-invariants.md`

```markdown
# 管理面不变量：portal 后端管理平台（004）

> 管理面（`/admin`）必须始终满足的不变量。契约端点见 [admin-api-contract.md](./admin-api-contract.md)。每条配断言 ID，供 TDD 测试锚定（宪法原则四/七）。

## I-1 管理面 / 诊断面隔离

`/admin` REST 端点的存在与调用**不影响**诊断面 `/mcp`：38 工具静态不变、双侧契约（`InitializeAndToolsListContractTest` 等）继续通过。
- **断言 INV-ISOL-1**：全量 CRUD + 导出操作期间，`/mcp` tools/list = 38、`listChanged=false`、watch/jvm 诊断正常。

## I-2 任务导出原样（宪法原则二）

`/admin/tasks/{id}/export` 的 `frames[]` 与 `arthas-gateway.task-get`（同 taskId）的结果**逐字一致**——不篡改、不摘要、不截断、不重排序。
- **断言 INV-EXP-1**：导出 JSON 的 `frames` 与 task-get 响应的对应字段深度相等。

## I-3 动态后端不可手动增改

`source=DYNAMIC` 的后端由 003 `k8s.ensure-arthas-mcp` 产生（身份 `{server}-{pod}` 派生）。`POST`（增动态）/ `PUT`（改动态）**一律拒绝**（400）；仅 `DELETE`（= `DynamicBackendStore.unregister`）允许。
- **断言 INV-DYN-1**：POST/PUT 动态后端 → 400 + `reason: dynamic_backend_not_editable`，且 `DynamicBackendStore` 状态不变。

## I-4 能力开关独立隔离

后端 CRUD 与任务导出经各自 `@ConditionalOnProperty` 独立装配。关闭其一**不影响**另一个，也不影响诊断面。
- **断言 INV-SWITCH-1**：`admin.crud.enabled=false` → `/admin/backends/*` 全 404，但 `/admin/tasks/*/export`（若开）正常、`/mcp` 正常。
- **断言 INV-SWITCH-2**：`admin.export.enabled=false` → `/admin/tasks/*/export` 404，但 `/admin/backends/*`（若开）正常。

## I-5 静态 CRUD 经文件热重载（文件 = source of truth）

静态后端 CRUD **必须**经"写回 `config/backends.yaml` → 既有 `BackendConfigWatcher`/`BackendRegistryReloader` 热重载"路径生效；portal/admin **不直接调** `BackendRegistry.rebuild`（避免绕过文件导致重启后状态不一致）。
- **断言 INV-FILE-1**：POST/PUT/DELETE 静态后端后，`backends.yaml` 文件内容相应变化；网关 `list-targets` 经热重载（≤30s）反映变化，重启后仍一致。

## I-6 错误显式传播（宪法原则五）

所有管理操作错误以**结构化 HTTP 错误体**返回（状态码 + `error`/`reason`），不得静默成功或吞为 200 空体。
- **断言 INV-ERR-1**：未知后端/任务、校验失败、动态不可改、未完成任务导出 → 对应 4xx + 错误体（无静默 200）。

## I-7 凭据脱敏

`BackendDto.auth` 仅暴露 `mode`，**不回显** `token`/`username`/`password`（机密字段脱敏，避免经管理面泄露）。
- **断言 INV-SECRET-1**：GET 列表/详情响应的 `auth` 不含 `token`/`username`/`password` 字段（或显式标记脱敏）。

## I-8 前端同源、展示层定位（v2）

前端 SPA 必须**内嵌网关 JAR、同源服务**（`vite build` → `src/main/resources/static/`，浏览器访问网关根加载），`fetch('/admin/...')` 同源（**无 CORS**）。前端仅展示（fetch + render + download），**不承载核心管理逻辑**（核心在 Java `/admin`，宪法原则六 / research.md R13）。
- **断言 INV-WEB-1**：`./mvnw verify` 产出含前端 static 的单 JAR；浏览器访问网关根加载 SPA、同源 fetch `/admin` 成功（无 CORS 预检）。
- **断言 INV-WEB-2**：核心校验/导出/热重载逻辑在后端 Java（ArchUnit 守护 `admin` 包承载），前端无独立业务规则（仅 fetch + render + download）。

## I-9 异步任务列表（增量，FR-015）

`GET /admin/tasks` 列表查询必须满足：摘要纯（无 frames）、分页元数据一致、排序确定、与导出端点同开关。

- **断言 INV-LIST-1**：`TaskSummaryDto` **禁含 `frames`**（摘要纯；frames 仅由 `/admin/tasks/{id}/export` 提供，INV-EXP-1）。
- **断言 INV-LIST-2**：`total` = 过滤后、分页前的总数（与 `items` 分页独立；`items.length ≤ size`，但 `total` 可大于 `items.length`）。
- **断言 INV-LIST-3**：`items` 按 `createdAt` **倒序**（最新在前）；同 createdAt 顺序不依赖（taskId 去重由 TaskStore 保证）。
- **断言 INV-LIST-4**：`arthas-gateway.admin.export.enabled=false` → `GET /admin/tasks` 与 `GET /admin/tasks/{id}/export` **同 404**（与 export 共用开关，yaml 驱动）。
```


---

## `specs/004-portal-backend-management/data-model.md`

```markdown
# Data Model: portal 后端管理平台（004 增量）

> 本文定义 004 相对 [001 data-model](../001-arthas-mcp-gateway/data-model.md) 与 [003 data-model](../003-k8s-arthas-mcp-launch/data-model.md) 的**增量**。001 既有 `BackendConfig`/`BackendEntry`/`BackendRegistry`/`RegistryHolder`/`BackendRegistryReloader`/`GatewayTask`/`TaskStore`/`CircuitBreaker` 与 003 `DynamicBackendStore`/`Source`/`OrchestrationRecord` 语义**不变**，本文仅记 004 新增 DTO、请求载体与受影响处。决策见 [research.md](./research.md)。

---

## 1. 新增 DTO（`admin` 包，只读投影 / 请求载体，不持久化）

### 1.1 `BackendDto`（后端配置 CRUD 响应，GET 列表/详情）

只读投影，数据源自 `BackendRegistry` + `BackendEntry`（001）。

| 字段 | 类型 | 来源 | 说明 |
|---|---|---|---|
| `name` | String | BackendConfig.name | 逻辑名（target） |
| `source` | enum `STATIC`\|`DYNAMIC` | BackendConfig.source（003） | STATIC=YAML 种子；DYNAMIC=003 ensure 注册 |
| `state` | enum `ACTIVE`\|`RETIRED` | BackendEntry.state（001） | 运行态 |
| `healthy` | boolean | BackendEntry 健康检查 | 最近一次健康探测 |
| `breaker` | enum `CLOSED`\|`OPEN` | CircuitBreaker（001） | 熔断状态 |
| `url` | String | BackendConfig.url | arthas MCP 根 URL |
| `protocol` | enum `STREAMABLE`\|`STATELESS` | BackendConfig.protocol | |
| `auth` | `{mode, ...}` | BackendConfig.auth | mode 仅暴露（凭据脱敏，不回显 token） |
| `connectTimeoutMs` / `callTimeoutMs` / `maxConcurrentTasks` | int | BackendConfig | |

**列表响应**额外含 `summary: {total, healthy, unhealthy}`。

### 1.2 `TaskExportDto`（任务导出响应）

| 字段 | 类型 | 来源 |
|---|---|---|
| `taskId` / `tool` / `target` / `status` | — | GatewayTask（001） |
| `createdAt` / `completedAt` | Instant | GatewayTask |
| `frames[]` | 原样结果帧 | GatewayTask 结果 content（**原样透传，宪法原则二**） |

### 1.3 请求载体（CRUD 入参）

- `CreateBackendRequest`：`name`/`url`/`protocol`/`auth`/`connectTimeoutMs`/`callTimeoutMs`/`maxConcurrentTasks`（缺省值同 `BackendConfigLoader` 默认）。
- `UpdateBackendRequest`：`url`/`auth`/`connectTimeoutMs`/`callTimeoutMs`/`maxConcurrentTasks`（可空=不改）。

---

## 2. 复用既有（零改动）

| 既有实体 | 复用点 |
|---|---|
| `BackendConfig`（001） | 静态/动态后端配置载体 |
| `BackendEntry`（001） | 注册表条目（config+client+breaker+taskSlots） |
| `BackendRegistry`（001） | 查询/健康视图数据源 |
| `DynamicBackendStore`（003） | 动态后端 DELETE → `unregister` |
| `TaskStore` / `GatewayTask`（001） | 导出数据源 |
| `BackendRegistryReloader` + WatchService（001） | 静态后端写回 YAML 触发热重载（SC-002） |

---

## 3. 状态机（复用 001/003，不新增）

- **后端 state**：`ACTIVE → RETIRED`（001）。CRUD `DELETE` 静态后端经热重载移除；DELETE 动态后端经 `unregister` 即时移除。
- **任务 status**：`WORKING → COMPLETED | CANCELLED | FAILED`（001）。导出**仅 `COMPLETED`** 可导出（`WORKING`/`CANCELLED`/`FAILED` → 409/404）。

---

## 4. 校验规则（CRUD）

- **name 唯一**：静态 + 动态全局唯一（复用 003 冲突检测 I-3）；重复 → 400。
- **url 非空 + 合法**：`http(s)://...`，非法 → 400。
- **动态后端 POST/PUT 拒绝**：动态后端由 003 ensure 产生，不可手动增改（research.md R3）→ 400。
- **DELETE in-flight 后端**：复用 002 `retirementGrace` 宽限切断 in-flight（不破坏既有韧性）。

---

## 5. 配置增量（`application.yml`）

```yaml
arthas-gateway:
  admin:
    crud:
      enabled: true   # 后端配置 CRUD（@ConditionalOnProperty，默认开；关闭则 /admin/backends 不暴露）
    export:
      enabled: true   # 异步任务结果导出（默认开；关闭则 /admin/tasks/*/export 不暴露）
```

`@ConditionalOnProperty(name="arthas-gateway.admin.crud.enabled", havingValue="true", matchIfMissing=true)`；两能力独立开关、互不影响（research.md R9 / spec FR-012）。
```


---

## `specs/004-portal-backend-management/plan.md`

```markdown
# Implementation Plan: portal 后端管理平台（v2 Web 前端版）

**Branch**: `004-portal-backend-management` | **Date**: 2026-07-06（v2） | **Spec**: [spec.md](./spec.md)

**Input**: `/specs/004-portal-backend-management/spec.md`；设计决策见 [docs/superpowers/specs/2026-06-25-portal-backend-management-design.md](../../docs/superpowers/specs/2026-06-25-portal-backend-management-design.md)（v2）。

> **v2 变更**：v1 CLI/API（picocli）→ **Web 前端**（Vue 3 SPA）。后端 `/admin` HTTP API 保留；CLI/picocli 废弃（零代码未实施）；新增 `web/` 前端项目 + Maven 构建集成。

## Summary

落地 003 P3 portal 的**配置管理子集**：后端 Java `/admin` HTTP API（CRUD + 任务导出，`@ConditionalOnProperty` 按需开关）+ 前端 Vue 3 SPA（`web/`，经 `frontend-maven-plugin` 集成 Maven、`vite build` 内嵌 `static/`、Spring Boot 同源服务）。单 JAR 单产物。复用 001/003 既有（`BackendRegistry`/`DynamicBackendStore`/`TaskStore`/热重载），零侵入诊断面 `/mcp`。

## Technical Context

**Language/Version**: Java 21（LTS，后端，沿用 001/003）+ **TypeScript**（前端 SPA）。

**Primary Dependencies**:
- 后端（沿用）：Spring Boot 4.1.0 + Spring AI 2.0.0 + MCP Java SDK 2.0.0。
- 前端（新增）：**Vue 3 + Vite + TypeScript + Vue Router**（`web/`，经 npm）。
- 构建集成（新增）：**`com.github.eirslett:frontend-maven-plugin`**（Maven 内跑 npm install + build，自动下载 node）。

**Storage**: 文件 `config/backends.yaml`（静态后端，复用 001 热重载）+ in-memory（动态后端 `DynamicBackendStore` / 任务 `TaskStore`，复用）。

**Testing**: 后端 JUnit 5 + AssertJ + surefire（单元/契约）+ failsafe（真实 `*IT`）；前端 **Vitest + Vue Test Utils**（组件）；TDD + 双侧契约 + 真实零桩。

**Target Platform**: 受控内网 JVM 服务（Linux 为主、开发期 Windows）；浏览器访问网关根加载 SPA。

**Project Type**: web-service（`/mcp` 诊断 + `/admin` 管理）+ **web-ui**（Vue SPA 内嵌），单 JAR。

**Performance Goals**: 管理面低频运维操作；诊断面沿用 001。

**Constraints**: 受控内网、Noop 鉴权；单 Maven 模块（003 R1）+ 新增 `web/` 前端目录；**单 JAR 内嵌前端**（同源、无 CORS）；gateway-core 零 K8S 依赖不变；`/admin` + SPA 与 `/mcp` 隔离；任务导出原样透传（原则二）；动态后端 MVP 不持久化（B 后置）。

**Scale/Scope**: 少量后端（沿用 001）；管理面单用户（YAML 写回串行化）。

## Constitution Check

*GATE: 基于 `.specify/memory/constitution.md` v1.2.0。原则六经 v2 论证（前端=展示层）。node/vite 工具链新增见 Complexity Tracking。*

| 原则/约束 | 核查 | 结论 |
|---|---|---|
| 一 MCP 规范符合性 | `/admin` + SPA 是 REST/Web，不影响 `/mcp` MCP 契约；38 工具静态不变 | ✓ PASS |
| 二 透明无损聚合 | 任务导出原样来自 `TaskStore`（FR-006） | ✓ PASS |
| 三 连接生命周期 | 后端 CRUD 复用 `BackendRegistry`/`BackendEntry` + 热重载 + 动态注册 | ✓ PASS |
| 四 双侧契约 | 后端管理 API 契约测试先于实现（FR-013） | ✓ PASS |
| 五 可观测性 | `/admin` + SPA 暴露后端/健康/任务，错误显式传播（FR-008） | ✓ PASS |
| **六 Java 主力** | **前端（Vue/TS）= 展示层**，仅消费 `/admin`、渲染 UI、触发下载；核心逻辑（CRUD/导出/校验/热重载）全在 Java 后端。前端引入 node/vite 见 Complexity Tracking 论证 | ✓ PASS（附论证） |
| 七 TDD | 后端 + 前端测试先于实现（FR-013） | ✓ PASS |
| 八 先决研究 | Phase 0 `research.md` R1–R13（含 Vue/Vite 选型、构建集成、内嵌静态、原则六对齐） | ✓ PASS |
| 技术约束 | Java LTS / Maven 可复现（`./mvnw verify` 出含前端 JAR，FR-011）/ HTTP / 后端注册表 / 官方 SDK | ✓ PASS |

## Project Structure

### Documentation (this feature)

```text
specs/004-portal-backend-management/
├── plan.md / spec.md / research.md（R1–R13）/ data-model.md
├── contracts/（admin-api-contract + admin-invariants）
├── quickstart.md
└── tasks.md（/speckit-tasks）
```

### Source Code（单 Maven 模块 + 新增 `web/` 前端目录）

```text
src/main/java/com/arthas/gateway/
├── GatewayApplication.java        # 既有，无改（Spring Boot 默认服务 static/）
├── admin/                         # 004 后端管理 REST（@ConditionalOnProperty 按需开关）
│   ├── backend/                        # 后端配置 CRUD（admin.crud.enabled）
│   │   ├── BackendAdminController.java
│   │   ├── BackendAdminService.java
│   │   ├── BackendsYamlWriter.java
│   │   ├── BackendCrudAutoConfig.java
│   │   └── dto/BackendDto + 请求载体
│   └── task/                           # 任务导出（admin.export.enabled）
│       ├── TaskExportController.java
│       ├── TaskExportService.java
│       ├── TaskExportAutoConfig.java
│       └── dto/TaskExportDto
├── backend/ handler/ config/ task/ tool/ auth/ obs/ orchestration/  # 既有，复用（零改动）
src/main/resources/static/        # vite build 产物落点（Spring Boot 同源服务 SPA）
web/                              # 004 新增前端项目（Vue 3 + Vite + TS）
├── package.json / vite.config.ts / tsconfig.json
└── src/
    ├── App.vue / main.ts / router.ts
    ├── views/（BackendListView、TaskExportView）
    ├── components/（BackendTable、BackendForm、HealthBadge、DownloadButton）
    ├── api/（adminClient.ts：fetch /admin 封装）
    └── __tests__/（Vitest 组件测试）
src/test/java/com/arthas/gateway/
├── admin/                         # 后端契约/单元（surefire）
└── integration/                   # *IT（failsafe：真实 CRUD/导出 + 浏览器端到端）
```

**Structure Decision**: 单 Maven 模块（003 R1）。
- 后端 `admin` 包：`/admin` REST，同 JVM 调 `BackendRegistry`/`DynamicBackendStore`/`TaskStore`；`backend`/`task` 子包各自 `@ConditionalOnProperty`。
- 前端 `web/`：Vue 3 SPA，`vite build` → `src/main/resources/static/`，Spring Boot 同源服务；`frontend-maven-plugin` 集成 Maven。
- **无 `portal/` CLI 包**（v2 废弃）。
- gateway-core 零 K8S 依赖不变（`PackageBoundaryTest` 继续通过）。

## Complexity Tracking

> 宪法原则六/CLAUDE.md"不私自新增工具链"——前端引入 node/vite 工具链，正当理由论证：

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| 引入 node + Vite + Vue 3 前端工具链（非 Java） | 用户裁决 portal 改 Web 前端（可视化控制台）；前端展示层必需 TS/构建链才能做 SPA | 原生 HTML/JS 无构建：MVP 管理面（表格+表单+下载）交互复杂时难维护、无组件化；CLI（v1）被用户否决（要可视化） |
| 前端 = 非核心展示层（原则六对齐） | 核心管理逻辑（CRUD/导出/校验/热重载）全在 Java `/admin`；前端仅 fetch + render + download，不承载业务规则 | 把逻辑放前端违背原则六（核心逻辑必须 Java）；故校验/导出/注册均在后端，前端只展示 |

**论证结论**：前端工具链引入有正当理由（用户要 Web 前端、展示层必需），核心逻辑不依赖前端（Java 后端自洽），`frontend-maven-plugin` 保证 CI 可复现（FR-011）。符合原则六"非 Java 仅作辅助"。
```


---

## `specs/004-portal-backend-management/quickstart.md`

```markdown
# Quickstart：portal 后端管理平台（004 v2 Web 前端 端到端验证）

**Feature**: `004-portal-backend-management` | **Date**: 2026-07-06（v2）

> 004 v2 能力的**端到端验证指南**（后端 `/admin` API + 前端 Vue SPA）。实现细节归 `tasks.md`。契约见 [contracts/](./contracts/)；实体见 [data-model.md](./data-model.md)；决策见 [research.md](./research.md)（R1–R13）。

---

## 0. 前置环境

| 项 | 要求 |
|---|---|
| 构建 | `./mvnw clean verify`——`frontend-maven-plugin` 自动跑 `npm install + vite build`，产物内嵌 `static/`，产出含前端的 **单 JAR**（FR-011；CI 可复现） |
| 网关 | `java -jar arthas-mcp-gateway.jar`（或 `./mvnw spring-boot:run`）起于 `:8761` |
| 真实后端 | 001 双后端夹具（`smoke/gateway-start.sh`）或 003 ensure 纳管的动态 target |
| 真实任务 | 一次 `completed` 的 `watch` 任务（导出验证） |
| 浏览器 | 访问 `http://localhost:8761/` 加载 portal SPA |

> MVP 受控内网、Noop 鉴权（research.md R8）。前端开发期可 `cd web && npm run dev`（Vite HMR + proxy `/admin` → `:8761`）。

---

## 1. 场景 A：后端配置 CRUD（US1，Web UI）

1. 浏览器访问 `http://localhost:8761/` → portal SPA 加载 → 进入「后端管理」页。
2. **列表**（A-LIST-1）：表格显示全部后端（静态种子 + 003 动态），每行 `name/source/state/healthy/breaker` + 健康徽标；`healthy/breaker` 与 `/actuator/health` details 一致（SC-003）；`auth` 仅显 `mode`（脱敏 INV-SECRET-1）。
3. **新增静态后端**（A-ADD-1 / INV-FILE-1）：点「新增」→ 表单填 name/url/protocol/auth → 提交 → 写回 `backends.yaml` → 网关热重载（≤30s）→ 列表刷新可见（`list-targets` 亦可见，复用 SC-002）。
4. **改 / 删**（A-UPD-1 / A-DEL-1）：行内「编辑」改 url（热重载后诊断走新 url）；「删除」静态从 YAML 移除、动态即时 unregister。
5. **动态后端语义**（INV-DYN-1）：动态后端「编辑/新增」禁用或提交返 400（`reason: dynamic_backend_not_editable`）；「删除」允许（unregister）。

---

## 2. 场景 B：异步任务结果导出（US2，Web UI）

1. portal → 「任务导出」页 → 任务列表（taskId/tool/target/status/createdAt）。
2. 选一个 `completed` 任务 → 点「下载」（A-EXP-1 / INV-EXP-1）：浏览器下载 `<taskId>.json`，含 `taskId/tool/target/status/createdAt/completedAt` + `frames[]`；`frames` 与 `task-get`（同 taskId）逐字一致（原样透传，原则二）。
3. **错误**（A-EXP-2）：`working`/`cancelled`/不存在任务点下载 → 友好错误提示（409/404），不静默返空。

---

## 3. 场景 C：能力按需开关（FR-014 / R9）

```yaml
# application.yml
arthas-gateway:
  admin:
    crud:   { enabled: false }   # 关闭后端 CRUD
    export: { enabled: true }    # 保留导出
```
重启网关 → 后端管理页 `/admin/backends/*` 返 404、SPA 显示「CRUD 已禁用」降级；任务导出页正常（INV-SWITCH-1）。反向同理（INV-SWITCH-2）。`/mcp` 诊断面不受影响。

---

## 4. 场景 D：构建一体化（FR-011 / SC-005）

```bash
./mvnw clean verify
# frontend-maven-plugin 跑 npm install + vite build → src/main/resources/static/
# 产出含前端 SPA 的单 JAR；浏览器访问网关根加载 portal
```

---

## 5. 场景 E：异步任务列表查询（增量，FR-015 / SC-006）

1. 触发几个异步任务（经 `watch`/`trace` 等）→ portal → 「任务导出」页 → 上方「最近任务」列表区**自动加载首页**（`onMounted`）。
2. **列表**（A-LIST-TASKS-1）：表格显示任务摘要（`taskId/tool/target/status/createdAt/completedAt/isError`，**无 frames** INV-LIST-1），按 `createdAt` **倒序**；`total` = 过滤后总数（INV-LIST-2）。
3. **过滤**（A-LIST-TASKS-2）：status 下拉切 `WORKING`/`COMPLETED`/`FAILED`/`CANCELLED` → 重查第一页；可叠加 `?tool=`/`?target=`（URL 直查，精确匹配）。
4. **分页**：`size` 默认 20（上限 100，超 clamp），翻页递增 `page`；末页按钮自动 disabled。
5. **点列表项衔接导出**：点行 → 自动填 taskId + 触发查询 → 显示 frames 数 + 「下载 JSON」（复用场景 B 导出流）。
6. **空态/错误态**（自验证反馈）：无任务显「暂无任务」；端点故障显错误提示（不白屏）。
7. **开关**（INV-LIST-4）：`arthas-gateway.admin.export.enabled=false` → `/admin/tasks` 列表与 `/{id}/export` **同 404**。

---

## 6. 回归对照（不得破 001/002/003）

```bash
./mvnw verify
```

**预期**（INV-ISOL-1 / SC-004）：既有 38 工具 `/mcp` 契约（`InitializeAndToolsListContractTest` 等）、双侧契约、热重载（`HotReloadIT`）、异步任务、K8S 编排（003 `*IT`）全绿；`/admin` + SPA 不影响 `/mcp`。

---

## 7. 验证清单（Done Definition）

- [ ] 场景 A 后端 CRUD（Web UI）：静态写 YAML 热重载（A-ADD-1）、动态不可改可删（INV-DYN-1）、列表健康一致（SC-003）、脱敏（INV-SECRET-1）。
- [ ] 场景 B 任务导出（Web UI）：completed 原样下载（A-EXP-1/INV-EXP-1）、未完成/不存在→409/404（A-EXP-2）。
- [ ] 场景 C 能力开关：crud/export 独立，关闭=404+前端降级（INV-SWITCH-1/2）。
- [ ] 场景 D 构建一体化：`./mvnw verify` 出含前端单 JAR（SC-005）。
- [ ] 场景 E 任务列表查询：摘要无 frames（INV-LIST-1）、createdAt 倒序（INV-LIST-3）、`total`=过滤后（INV-LIST-2）、status/tool/target 过滤 + 分页（A-LIST-TASKS-1/2）、点列表项衔接导出、空态/错误态可见（自验证）、`export.enabled=false` 同 404（INV-LIST-4）。
- [ ] 回归：001/002/003 + 38 工具契约不破（INV-ISOL-1/SC-004）。
- [ ] gateway-core 零 K8S 依赖不变（003 `PackageBoundaryTest`）。
- [ ] 前端=展示层（核心逻辑 Java 后端，原则六对齐，R13）。
```


---

## `specs/004-portal-backend-management/research.md`

```markdown
# Research: portal 后端管理平台（004 特性 v2 Web 前端版）

> Phase 0 先决研究（宪法原则八）。v2：CLI → Web 前端重设计。每条决策援引既有代码证据，列备选与权衡。

---

## R1. 入口：浏览器访问网关根加载 SPA（v2 改）

**决策**：portal 入口 = **浏览器访问网关根 URL**（`http://<gateway>/`）→ Spring Boot 服务 `src/main/resources/static/` 内的 SPA（Vue 3）→ SPA 同源 fetch `/admin`。**无 picocli CLI**（v2 废弃，零代码未实施）。

**理由（证据）**：`GatewayApplication` 现状 = `SpringApplication.run`（`GatewayApplication.java:20-22`）。Spring Boot 默认服务 `static/` 资源——前端 `vite build` 产物落入即自动服务，零额外入口代码。v1 的 picocli 双入口路由废弃（用户裁决要 Web 前端）。

**备选（否决）**：
- v1 picocli CLI（废弃，用户要可视化控制台）。
- 前端 + CLI 并存（双套入口工作量，MVP 聚焦前端）。

---

## R2. backends.yaml 写回：SnakeYAML dump 重写（不保留注释）

**决策**：MVP 静态后端 CRUD 用 **SnakeYAML `dump` 重写整个 `config/backends.yaml`**，**不保留原文注释**；注释保留后置。

**理由（证据）**：`BackendConfigLoader` 用 SnakeYAML（`BackendConfigLoader.java:3,59`）`load` 只读；SnakeYAML `dump` 不保留注释（技术事实）；热重载读结构不依赖注释；保留注释需 snakeyaml-engine 或手写模板，复杂、脆弱、YAGNI。

**spec 假设对齐**：spec 假设已记"MVP dump 重写不保留注释"。

**备选（否决）**：snakeyaml-engine（换库不一致）；模板化写回（脆弱）。

---

## R3. 动态后端 CRUD 语义（source=DYNAMIC）

**决策**：动态后端 POST/PUT **拒绝**（400）；DELETE = `DynamicBackendStore.unregister`（003 D-UNREG-*）。

**理由（证据）**：`DynamicBackendStore`（003）提供 `register/unregister`、强制 DYNAMIC、冲突检测 I-3。动态后端身份由 `ArthasProvisioner.ensure` 派生（`{server}-{pod}`），手动增改破坏一致性。

**备选（否决）**：允许 POST 动态（与 ensure 重叠，YAGNI）。

---

## R4. /admin 与 /mcp 隔离

**决策**：`/admin` 用 Spring Web MVC `@RestController @RequestMapping("/admin")`；`/mcp` 由 spring-ai MCP servlet 承载。路径路由隔离，互不影响。

**理由（证据）**：诊断面 `/mcp` 由 spring-ai 装配（`GatewayMcpServerConfig`）；`/admin` 不进 MCP 工具集（38 工具静态，`InitializeAndToolsListContractTest` 守护），不污染 MCP 协议（原则一）。

**备选（否决）**：管理面做成 MCP 工具（违背"工具集静态 + 诊断聚合"，原则二）。

---

## R5. 任务导出序列化（原样透传）

**决策**：`GET /admin/tasks/{taskId}/export` → `TaskStore.get` → `GatewayTask` → `TaskExportDto`（Jackson JSON，`Content-Disposition: attachment`）；frames **原样**，不篡改/截断（原则二）；仅 `completed`（其他 409/404）。

**理由（证据）**：`TaskStore`（`task/TaskStore.java:85`）`get` 返 `Optional<GatewayTask>`；Jackson 由 Spring Boot 传递带入。

**备选（否决）**：直接返 GatewayTask JSON（无 DTO，内部 record 演化会泄漏导出格式）。

---

## R6. 前端 HTTP（同源 fetch，v2 改，原 v1 CLI HttpClient 废弃）

**决策**：前端 SPA 经浏览器 **`fetch('/admin/...')`** 同源调后端（内嵌静态部署，无 CORS）；错误经 HTTP 状态码 → 前端友好提示（toast/inline）。

**理由**：单 JAR 内嵌（R12）保证前端与 `/admin` 同源，避免 CORS 配置。v1 的 `GatewayAdminClient`（java.net.http.HttpClient，portal CLI 用）废弃。

**备选（否决）**：v1 CLI HttpClient（CLI 废弃）；独立 SPA 部署需 CORS（违背单 JAR 简洁）。

---

## R7. 静态后端 CRUD × 热重载协作

**决策**：静态 CRUD → `BackendsYamlWriter` 写回 `config/backends.yaml` → 既有 `BackendConfigWatcher`（WatchService）→ `BackendRegistryReloader` 热重载（复用 001 SC-002，30s）。前端/admin **不直接调** `BackendRegistry`（文件 = source of truth）。

**理由（证据）**：001 已实现完整热重载链；portal 写 YAML 即触发，零改动复用。

**并发**：MVP 单用户，写回串行化。

**备选（否决）**：直接调 `BackendRegistry.rebuild`（破坏文件 source of truth，重启不一致）。

---

## R8. 鉴权（Noop，受控内网）

**决策**：MVP `/admin` 无鉴权（与 001 `/mcp` 一致，受控内网）。Bearer token 后置（与 001 演进首要项统一）。

**备选（否决）**：单独 Bearer token filter（MVP 引入 token 管理，后置统一）。

---

## R9. 能力开关（@ConditionalOnProperty）

**决策**：后端 CRUD 与任务导出各独立子包（`admin/backend`/`admin/task`），各自 `@ConditionalOnProperty`（`arthas-gateway.admin.crud.enabled`/`admin.export.enabled`，默认 `true`）。关闭则后端端点 404、前端页面降级提示。

**理由**：用户要求"能力独立模块、按需组合"；Spring 条件装配零运行时开销、配置驱动、无需 Maven 多模块。

**备选（否决）**：Maven 多模块（构建复杂，003 R1 后置）；不可关（违背按需组合）。

---

## R10. 前端技术栈：Vue 3 + Vite + TypeScript（v2 新增）

**决策**：前端用 **Vue 3 + Vite + TypeScript**（`web/` 目录）。Vue Router 路由、Vite 构建、TS 类型安全。

**理由**：Vue 3 单文件组件适合管理面 MVP（表格 + 表单 + 下载）；Vite 构建快、dev HMR；TS 类型安全；生态成熟。

**备选（否决）**：
- React（更主流，但 Vue 管理面 MVP 更轻）。
- 原生 HTML/JS（无构建链，但交互复杂难维护、无组件化）。

**宪法对齐**：前端 = 展示层（Vue/TS），核心逻辑 Java 后端（R13）。

---

## R11. 前端构建集成 Maven（frontend-maven-plugin，v2 新增）

**决策**：`web/` 前端经 **`frontend-maven-plugin`**（`com.github.eirslett:frontend-maven-plugin`）在 Maven 构建内跑 `npm install + npm run build`，挂 `generate-resources` 阶段；产物落 `src/main/resources/static/`。`./mvnw verify` 一条命令产出含前端的 JAR。

**理由**：CI 可复现（宪法"CI 复现本地构建"）；frontend-maven-plugin 自动下载指定版本 node（无需 CI 预装 node）；与 Maven 生命周期统一。

**备选（否决）**：
- 手动 vite build（CI 需预装 node，违背"可复现"）。
- exec-maven-plugin 调 npm（需 CI 预装 node）。

**约束**：构建需联网下载 node（首次）+ npm 依赖；离线场景需预置（演进）。

---

## R12. 前端内嵌静态（单 JAR，v2 新增）

**决策**：`vite build` 产物落 `src/main/resources/static/`，Spring Boot 默认服务（同源）。dev 期 `vite dev` + proxy `/admin` → `localhost:8761`（HMR 开发体验）。prod 单 JAR 部署、浏览器访问网关根加载 SPA、同源 fetch `/admin`（无 CORS）。

**理由**：保持 003/004 单 JAR 单产物理念；同源避免 CORS；Spring Boot 默认服务 `static/` 零配置。

**备选（否决）**：独立 SPA 部署（CORS + 两产物）；仅开发期独立（生产未定）。

---

## R13. 宪法原则六对齐：前端 = 展示层（v2 新增）

**决策**：前端（Vue/TS）严格定位为**展示层**——仅消费 `/admin` API、渲染 UI、触发下载；**不承载核心管理逻辑**（CRUD 校验/导出序列化/热重载/动态注册全部在 Java `/admin` 后端）。

**理由（宪法原则六）**："非 Java 代码仅作为辅助，不得承载核心功能逻辑。"前端 = 辅助展示层，核心逻辑 Java 后端——符合。前端引入 node/vite 工具链在 plan **Complexity Tracking 显式论证**（前端展示层必需、核心逻辑不依赖、CI 可复现）。

**校验**：ArchUnit 守护"核心逻辑在 Java"（admin 包承载 CRUD/导出逻辑）；前端无独立业务规则（仅 fetch + render）。

---

## 待实测 / 演进项（透明记录）

- **YAML 注释保留**：MVP dump 丢注释；演进 snakeyaml-engine / 模板化。
- **占位符还原**：MVP 写回机密字段当前值；演进保留 `${ENV}`。
- **动态后端持久化（B）**：MVP 重启丢失；演进落盘 + 恢复。
- **操作审计（D）**：演进 ensure 历史 + CRUD 审计。
- **任务导出多格式/可视化**：CSV/HTML/frames 表格图表后置。
- **健康监控 dashboard**：后置。
- **管理面鉴权**：Bearer token，与 001 演进项统一。
- **前端离线构建**：node/npm 离线预置（演进）。
```


---

## `specs/004-portal-backend-management/spec.md`

```markdown
# Feature Specification: portal 后端管理平台（v2 Web 前端版）

**Feature Branch**: `004-portal-backend-management`

**Created**: 2026-07-06（v2：CLI → Web 前端重设计）

**Status**: Draft

**Input**: 用户描述："准备开始实现 后端管理平台……你自己明确下之前有哪些是妥协导致没有完成的管理能力，加上异步任务的结果导出能力，为 portal 平台的管理能力范围。" → brainstorming v2 裁决：**Web 前端**（非 CLI），落地 003 P3 portal 的配置管理子集。

> **设计来源**：[2026-06-25-portal-backend-management-design.md](../../docs/superpowers/specs/2026-06-25-portal-backend-management-design.md)（v2）。后端 `/admin` HTTP API + 前端 Vue 3 SPA（内嵌单 JAR）。CLI 废弃。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 后端配置 CRUD 管理（Priority: P1）

运维人员经 portal 的 **Web UI**（浏览器访问网关根，SPA），对网关后端注册表进行**可视化查询/增/删/改**（含静态种子与 003 动态纳管的后端），并查看每后端的健康/熔断状态，无需 SSH 手编 `config/backends.yaml`。

**Why this priority**: 001 以来后端管理靠手编 YAML（妥协项，001 spec 假设§110），是管理面最基础、最高频的能力，宪法原则三（后端为受管理一等对象）与原则五（暴露后端/健康状况）的直接落地。

**Independent Test**: 浏览器打开 portal → 后端管理页见既有种子 + 动态后端 → 表单新增静态后端 → 网关 `list-targets` 30s 内感知 → 删除 → 网关感知移除，全程不手编 YAML。

**Acceptance Scenarios**:

1. **Given** 网关运行（静态种子 + 003 动态后端），**When** 运维浏览器访问 portal 后端管理页，**Then** 列表显示全部后端，每条含 `name/source(STATIC|DYNAMIC)/state/healthy/breaker` 等 + 汇总（total/healthy/unhealthy）。
2. **Given** portal，**When** 表单新增静态后端（name+url+认证）提交，**Then** 写回 `config/backends.yaml`，网关热重载 30s 内纳管，列表刷新可见。
3. **Given** portal，**When** 删除静态后端，**Then** YAML 移除并热重载生效；删除动态后端则 `DynamicBackendStore.unregister` 即时移除。
4. **Given** portal，**When** 改静态后端 url/auth/timeouts，**Then** 写回 YAML 热重载；改动态后端 → 拒绝并明确提示（动态须先删再 ensure）。

---

### User Story 2 - 异步任务结果导出（Priority: P2）

运维或开发者经 portal **Web UI**，把网关异步任务（`watch`/`trace`/`stack`/`tt`/`monitor`）的完整结果**导出下载为 JSON 文件**，供离线分析、归档与分享——补齐 001"仅 task-get 同步返 JSON、无文件导出"的缺口。

**Why this priority**: 用户明确要求的新能力；依赖任务系统（`TaskStore`），后端管理（US1）更基础，故 P2。

**Independent Test**: portal 任务导出页 → 选一个 `completed` 的真实 watch 任务 → 点下载 → 得 JSON 文件，含任务元信息 + 真实诊断帧（hotMethod 的 accessPoint/className/value 等）。

**Acceptance Scenarios**:

1. **Given** 网关有 `completed` 异步任务，**When** 经 portal 任务导出页点下载，**Then** 浏览器下载 JSON 文件（`Content-Disposition: attachment`），含 `taskId/tool/target/status/createdAt/completedAt` + `frames[]`（原样来自 TaskStore，不篡改/截断）。
2. **Given** 任务 `working`/`cancelled`/不存在，**When** 下载，**Then** 明确错误提示（409/404），不静默返空。

---

### Edge Cases

- **未知后端名**：GET/PUT/DELETE → 404 + `available[]`（前端友好提示）。
- **未知/未完成任务导出**：404（未知）/ 409（working/cancelled，仅 completed 可导出）。
- **CRUD 校验失败**：重复 name、非法 URL、缺必填、改动态后端 → 400 + 详情（前端表单 inline 校验 + 后端复核）。
- **`backends.yaml` 写入失败**（IO/锁/磁盘满）→ 500 + 详情，透明记录（原则五）。
- **静态与动态 name 冲突**：增静态与既有动态同名 → 拒绝（复用 003 冲突检测 I-3）。
- **并发改 YAML**：MVP 管理面单用户，写回串行化（无锁）。
- **删除 in-flight 后端**：复用 002 `retirementGrace` 宽限切断。
- **前端构建失败/资源缺失**：网关仍可服务 `/admin` + `/mcp`（前端缺失不阻塞后端）；SPA 加载失败显示降级提示。

## Requirements *(mandatory)*

### Functional Requirements

**后端管理 API（US1/US2 共享，前端 SPA 消费）**

- **FR-001**: 系统必须提供**独立于诊断面 `/mcp` 的管理 HTTP API**（`/admin/backends`、`/admin/tasks/{id}/export`），与诊断面隔离、互不影响。
- **FR-002**: `GET /admin/backends` 必须返回全部后端清单（`name/source/state/healthy/breaker/url/protocol/auth.mode/timeouts/maxConcurrent`）+ `summary`——运维无需读源码（原则五）。
- **FR-003**: `POST /admin/backends` 增静态后端必须写回 `config/backends.yaml` + 001 热重载 30s 内纳管（SC-002）；动态后端必须 `DynamicBackendStore.register` 即时生效。
- **FR-004**: `DELETE /admin/backends/{name}` 删静态从 YAML 移除并热重载；删动态调 `unregister` 即时移除。
- **FR-005**: `PUT /admin/backends/{name}` 改静态写 YAML 热重载；动态后端不可改（明确错误）。
- **FR-006**: `GET /admin/tasks/{taskId}/export?format=json` 必须把 `completed` 任务的完整结果（元信息 + frames）作为可下载 JSON 返回；frames **原样来自 TaskStore，不得篡改/摘要/截断**（原则二）。
- **FR-007**: 导出 `working`/`cancelled`/不存在任务必须明确错误（409/404），不静默返空。
- **FR-008**: 所有管理操作错误必须**结构化、显式传播**（HTTP 状态码 + 错误体），不静默成功（原则五）。

**前端 Web UI（Vue 3 SPA）**

- **FR-009**: 系统必须提供 **Web UI**（Vue 3 SPA），经浏览器访问网关根加载，同源 fetch `/admin` API（无 CORS），覆盖后端 CRUD 管理（列表/增删改表单/健康状态）与任务导出下载。
- **FR-010**: 前端 SPA 构建产物必须**内嵌网关 JAR**（`vite build` → `src/main/resources/static/`，Spring Boot 同源服务），保持**单 JAR 单产物**部署（dev 期 vite dev server + proxy）。
- **FR-011**: 前端构建必须集成进 Maven 构建（`frontend-maven-plugin` 跑 `npm install + build`），`./mvnw verify` 一条命令产出含前端的 JAR（CI 可复现，宪法"CI 复现本地构建"）。
- **FR-012**: 前端 = **展示层**（Vue/TypeScript），核心管理逻辑（CRUD/导出/校验/热重载）仍在 Java `/admin` 后端——符合宪法原则六"非 Java 仅作辅助"。前端引入 node/vite 工具链在 plan Complexity Tracking 论证。

**贯穿（测试 + 能力开关）**

- **FR-013**: 后端管理面与前端必须以 **TDD** 开发（测试先于实现），契约测试 + 真实零桩测试并存（宪法原则四/七）。
- **FR-014**: 后端 CRUD 与任务导出必须各自**独立、可按需启用/关闭**（`@ConditionalOnProperty`：`arthas-gateway.admin.crud.enabled`/`admin.export.enabled`，默认 `true`）；关闭某能力时后端端点 404、前端对应页面降级提示，互不影响。
- **FR-015**（增量）：`GET /admin/tasks` 必须返回异步任务**摘要列表**（`taskId/tool/target/status/createdAt/completedAt/isError`，**不含 frames**），支持 `status`/`tool`/`target` 可选过滤 + `page`/`size` 标准分页（响应含 `total`），按 `createdAt` 倒序——运维可浏览/定位任务而非仅按 taskId 导出。开关复用 `arthas-gateway.admin.export.enabled`（关则与 export 同 404，INV-LIST-4）。

### Key Entities

- **BackendDto**（后端响应，只读投影）：`name`/`source`/`state`/`healthy`/`breaker`/`url`/`protocol`/`auth.mode`/`timeouts`/`maxConcurrent`。源 `BackendRegistry`+`BackendEntry`（001）。
- **TaskExportDto**（导出响应）：`taskId`/`tool`/`target`/`status`/`createdAt`/`completedAt` + `frames[]`。源 `TaskStore`/`GatewayTask`（001）。
- **前端组件**（Vue 3）：`BackendListView`/`BackendForm`/`TaskExportView` 等（展示层，不持久化，仅消费 `/admin`）。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 经 portal Web UI 增/删/改静态后端，网关 `list-targets` 30s 内反映变化（复用 001 SC-002）。
- **SC-002**: 经 portal Web UI 下载一个 `completed` 真实 watch 任务，文件含完整、原样的诊断帧（accessPoint/className/methodName/cost/ts/value），与 `task-get` 一致（无篡改）。
- **SC-003**: portal 后端列表的健康/熔断状态与 `/actuator/health` details 一致（原则五）。
- **SC-004**: 管理面（`/admin` + SPA）全量操作期间，诊断面 `/mcp` 既有 38 工具与双侧契约不受影响（回归不破 001/002/003）。
- **SC-005**: `./mvnw verify` 一条命令产出含前端 SPA 的单 JAR，浏览器访问网关根加载 portal（CI 可复现）。
- **SC-006**（增量）：portal `/tasks` 页列表区可浏览异步任务（自动查首页 + status 过滤 + 分页 + 点列表项填 taskId 衔接导出）；列表/过滤/分页/排序经真实 ContractIT + Playwright 端到端自验证。

## Assumptions

- 部署在**受控内网**，MVP 管理面**无鉴权**（Noop）；Bearer token 为演进项。
- 复用既有：001 `BackendRegistry`/`BackendEntry`/`BackendRegistryReloader`/`TaskStore`、003 `DynamicBackendStore`；本特性仅以管理面封装暴露，不重建。
- **单 Maven 模块**（003 R1 包级边界）+ **新增 `web/` 前端项目目录**（Vue 3 + Vite + TS）+ 新增 `admin` 包（后端 REST，下分 `backend`/`task` 子包，各自 `@ConditionalOnProperty`）；**无 portal CLI 包**（v2 废弃）；gateway-core 零 K8S 依赖不变。
- **单 JAR 内嵌前端**：`vite build` → `src/main/resources/static/`，Spring Boot 同源服务 SPA + `/admin`（无 CORS）；dev 期 `vite dev` + proxy `/admin` → `:8761`。
- **node/vite 工具链新增**：经 `frontend-maven-plugin` 统一在 Maven 构建内跑（CI 需 node；宪法"不私自新增工具链"在 plan 论证——前端展示层必需）。
- **动态后端 MVP 不持久化**（B 后置）：portal 增删动态后端即时生效，网关重启丢失（须重 ensure）；静态后端写回 YAML 持久。
- `backends.yaml` 写回用 SnakeYAML `dump` 重写（**不保留原文注释**，注释保留后置；见 research.md R2）；机密字段写回当前解析值（占位符还原后置）。MVP 单用户（写回串行化）。
- 任务导出 MVP 仅 JSON、仅 `completed`、全量不分页（CSV/HTML/可视化后置）。
```


---

## `specs/004-portal-backend-management/tasks.md`

```markdown
# Tasks: portal 后端管理平台（v2 Web 前端版）

**Input**: Design documents from `/specs/004-portal-backend-management/`（v2）

**Prerequisites**: plan.md（v2，含 Complexity Tracking 前端论证）、spec.md（v2）、research.md（R1–R13）、data-model.md、contracts/、quickstart.md、`.specify/memory/constitution.md`（v1.2.0）。

**实施范围声明**：本计划实施 004 v2 全部 = US1（后端 CRUD，P1）+ US2（任务导出，P2），后端 Java `/admin` API + 前端 Vue 3 SPA（内嵌单 JAR）。**v1 的 CLI/picocli 废弃**（零代码未实施，本清单无 CLI 任务）。非目标（可视化/dashboard/B/D/K8S/鉴权）后置。

**TDD 硬约束（不可妥协，宪法原则七 + CLAUDE.md）**：后端 + 前端所有功能代码必须 TDD——先写失败测试（红）、再实现至通过（绿）、再重构。本清单中**测试任务一律先于其实现任务**。**真实性硬约束（零桩）**：后端契约 IT 由 HTTP client 驱动真实网关 + 真实后端/任务；前端组件测试用 Vitest，端到端真实浏览器。

**波次映射**：Phase 2 = 共享基建（前端 SPA 骨架 + admin 条件装配）；Phase 3 = US1（后端 CRUD + 前端管理页）；Phase 4 = US2（后端导出 + 前端导出页）；Phase 5 = Polish。

## Format: `[ID] [P?] [Story?] Description`

- **[P]**: 可并行（不同文件、无未完成任务依赖）
- **[Story]**: 仅 Phase 3/4 任务带 `[US1]`/`[US2]`；Setup/Foundational/Polish 不带
- 每条任务含精确文件路径

## Path Conventions

- 单 Maven 模块，后端 Java 源 `src/main/java/com/arthas/gateway/...`，后端测试 `src/test/java/com/arthas/gateway/...`
- 前端项目根 `web/`（Vue 3 + Vite + TS），前端测试 `web/src/__tests__/`（Vitest）
- 前端构建产物 → `src/main/resources/static/`（Spring Boot 同源服务）
- 配置 `src/main/resources/application.yml`（`arthas-gateway.admin.*`）

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 前端项目初始化 + Maven 构建集成 + 能力开关配置外化

- [X] T001 [P] Initialize `web/` 前端项目（Vue 3 + Vite + TypeScript + Vue Router）in `web/`（`package.json`/`vite.config.ts`/`tsconfig.json`/`src/main.ts`/`src/App.vue`/`src/router.ts` 骨架；vite build outDir → `../src/main/resources/static/`；research.md R10/R12）
- [X] T002 [P] Add `com.github.eirslett:frontend-maven-plugin` to `pom.xml`（挂 `generate-resources` 跑 `npm install + npm run build`，自动下载 node；产物落 `src/main/resources/static/`；FR-011/R11）
- [X] T003 [P] Add `arthas-gateway.admin.*` config section to `src/main/resources/application.yml`（`crud.enabled: true` / `export.enabled: true`，默认开；R9）
- [X] T004 [P] Add `Admin` 子段（`crud.enabled`/`export.enabled` 绑定）to `src/main/java/com/arthas/gateway/config/GatewayProperties.java`

**Checkpoint**: 前端项目就位、Maven 构建集成、能力开关配置外化

---

## Phase 2: Foundational (Blocking Prerequisites — 共享基建)

**Purpose**: 前端 SPA 骨架（路由 + adminClient）+ 后端条件装配骨架。**⚠️ CRITICAL**：US1/US2 均依赖此层。

> **TDD（宪法原则七）**：测试先写并确认失败（红），再实现至通过（绿）。

- [X] T005 [P] Write failing `AdminCapabilitySwitchTest` in `src/test/java/com/arthas/gateway/admin/AdminCapabilitySwitchTest.java`（断言 INV-SWITCH-1/2：`admin.crud.enabled=false` → `/admin/backends/*` 404；`admin.export.enabled=false` → `/admin/tasks/*/export` 404；两者独立、默认开）
- [X] T006 Implement 条件装配骨架 in `src/main/java/com/arthas/gateway/admin/backend/BackendCrudAutoConfig.java`、`src/main/java/com/arthas/gateway/admin/task/TaskExportAutoConfig.java`（`@ConditionalOnProperty(name=..., havingValue="true", matchIfMissing=true)`；green for T005）
- [X] T007 [P] Write failing 前端 SPA 骨架测试 in `web/src/__tests__/App.test.ts`、`web/src/__tests__/api/adminClient.test.ts`（Vitest：根路由加载 App、`adminClient` fetch `/admin` 封装 + 错误传播；research.md R6）
- [X] T008 Implement 前端 SPA 骨架 in `web/src/App.vue`、`web/src/router.ts`、`web/src/api/adminClient.ts`（Vue Router 路由 `/`、`fetch('/admin/...')` 同源封装；green for T007）

**Checkpoint**: 共享基建全绿——US1/US2 可在此之上构建

---

## Phase 3: User Story 1 - 后端配置 CRUD（Priority: P1）🎯 MVP

**Goal**: 后端 `/admin/backends` CRUD API + 前端「后端管理」页（列表/增删改表单/健康徽标/凭据脱敏），静态写 `backends.yaml` 热重载、动态 `DynamicBackendStore`。

**Independent Test**: 浏览器 → portal 后端管理页 → 表单新增静态后端 → 网关 30s 内纳管 → 删除 → 感知移除，不手编 YAML。

> **TDD + 真实零桩**：后端契约 IT 由 HTTP client 驱动真实网关 + 真实后端；前端组件 Vitest。断言见 `contracts/`。

### 后端

- [X] T009 [P] [US1] Write failing `BackendDtoTest` in `src/test/java/com/arthas/gateway/admin/backend/dto/BackendDtoTest.java`（字段全集 + INV-SECRET-1 凭据脱敏：`auth` 仅 `mode`，不回显 token/username/password）
- [X] T010 [US1] Implement `BackendDto` + `CreateBackendRequest` + `UpdateBackendRequest` in `src/main/java/com/arthas/gateway/admin/backend/dto/`（green for T009）
- [X] T011 [P] [US1] Write failing `BackendsYamlWriterTest` in `src/test/java/com/arthas/gateway/admin/backend/BackendsYamlWriterTest.java`（INV-FILE-1：写回 `version`+`backends`、热重载可解析、不保留注释为已知行为；R2）
- [X] T012 [US1] Implement `BackendsYamlWriter` in `src/main/java/com/arthas/gateway/admin/backend/BackendsYamlWriter.java`（SnakeYAML `dump` 重写 `config/backends.yaml`；green for T011）
- [X] T013 [P] [US1] Write failing `BackendAdminServiceTest` in `src/test/java/com/arthas/gateway/admin/backend/BackendAdminServiceTest.java`（静态 POST/PUT/DELETE 经 YamlWriter；动态 POST/PUT 拒绝 INV-DYN-1、DELETE=`DynamicBackendStore.unregister`；name 冲突 400；R3/R7）
- [X] T014 [US1] Implement `BackendAdminService` in `src/main/java/com/arthas/gateway/admin/backend/BackendAdminService.java`（编排 `BackendRegistry`/`DynamicBackendStore`/`BackendsYamlWriter`；green for T013）
- [X] T015 [US1] Implement `BackendAdminController` in `src/main/java/com/arthas/gateway/admin/backend/BackendAdminController.java`（`@RestController @RequestMapping("/admin/backends")` GET 列表/详情、POST、PUT、DELETE + 错误体；装配进 `BackendCrudAutoConfig`）
- [X] T016 [P] [US1] Write failing `BackendAdminContractIT` in `src/test/java/com/arthas/gateway/admin/backend/BackendAdminContractIT.java`（failsafe *IT，真实网关 + 真实后端：A-LIST-1 健康一致 SC-003、A-ADD-1 静态写 YAML 热重载 30s、A-UPD-1/A-DEL-1、INV-DYN-1、INV-ERR-1、INV-SECRET-1）

### 前端

- [X] T017 [P] [US1] Write failing 前端组件测试 in `web/src/__tests__/views/BackendListView.test.ts`、`web/src/__tests__/components/BackendForm.test.ts`（Vitest：列表渲染 + 健康徽标、表单提交触发 adminClient.create、动态后端编辑禁用、错误 inline 提示）
- [X] T018 [US1] Implement 前端后端管理页 in `web/src/views/BackendListView.vue`、`web/src/components/`（`BackendTable.vue`/`BackendForm.vue`/`HealthBadge.vue`）+ 扩 `api/adminClient.ts`（backends CRUD）；green for T017

**Checkpoint (US1)**: 浏览器 → 后端管理页全链路通——静态 CRUD 写 YAML 热重载、动态不可改可删、健康视图、脱敏

---

## Phase 4: User Story 2 - 异步任务结果导出（Priority: P2）

**Goal**: 后端 `/admin/tasks/{id}/export` API + 前端「任务导出」页（任务列表 + 下载），`completed` 任务原样 JSON 下载。

**Independent Test**: 触发真实 watch → completed → portal 任务导出页点下载 → JSON 文件 frames 与 `task-get` 一致（无篡改）。

> **TDD + 真实零桩**：契约 IT 用真实 watch；前端组件 Vitest。

### 后端

- [X] T019 [P] [US2] Write failing `TaskExportDtoTest` in `src/test/java/com/arthas/gateway/admin/task/dto/TaskExportDtoTest.java`（INV-EXP-1：`frames[]` 原样来自 `GatewayTask`，与 task-get 逐字一致）
- [X] T020 [US2] Implement `TaskExportDto` in `src/main/java/com/arthas/gateway/admin/task/dto/TaskExportDto.java`（green for T019）
- [X] T021 [P] [US2] Write failing `TaskExportServiceTest` in `src/test/java/com/arthas/gateway/admin/task/TaskExportServiceTest.java`（`TaskStore.get` → DTO；仅 `completed` 可导出，其他 → 409/404；A-EXP-2）
- [X] T022 [US2] Implement `TaskExportService` in `src/main/java/com/arthas/gateway/admin/task/TaskExportService.java`（原样 frames，宪法原则二；green for T021）
- [X] T023 [US2] Implement `TaskExportController` in `src/main/java/com/arthas/gateway/admin/task/TaskExportController.java`（`GET /admin/tasks/{taskId}/export?format=json` + `Content-Disposition: attachment`；装配进 `TaskExportAutoConfig`）
- [X] T024 [P] [US2] Write failing `TaskExportContractIT` in `src/test/java/com/arthas/gateway/admin/task/TaskExportContractIT.java`（failsafe *IT，真实 watch→completed→export：A-EXP-1 frames 与 task-get 一致 INV-EXP-1、A-EXP-2 →409/404、Content-Disposition attachment）

### 前端

- [X] T025 [P] [US2] Write failing 前端组件测试 in `web/src/__tests__/views/TaskExportView.test.ts`、`web/src/__tests__/components/DownloadButton.test.ts`（Vitest：任务列表渲染、下载触发 `GET /admin/tasks/{id}/export`、错误提示）
- [X] T026 [US2] Implement 前端任务导出页 in `web/src/views/TaskExportView.vue`、`web/src/components/DownloadButton.vue` + 扩 `api/adminClient.ts`（export 下载）；green for T025

**Checkpoint (US2)**: 浏览器 → 任务导出页闭环——completed 原样 JSON 下载、未完成/不存在明确错误

---

## Phase 5: Polish & Cross-Cutting Concerns

**Purpose**: 回归守护、包边界、能力开关端到端、浏览器 E2E、文档（不破 001/002/003）

- [X] T027 [P] Regression guard：`./mvnw verify` 全绿——`frontend-maven-plugin` 跑通前端构建（SC-005 含前端 JAR）+ `/mcp` 38 工具契约（`InitializeAndToolsListContractTest` 等）+ 双侧契约 + 热重载（`HotReloadIT`）+ 003 K8S `*IT`；INV-ISOL-1 / SC-004 管理面 + SPA 不影响诊断面
- [X] T028 [P] Add ArchUnit boundary assertions to `src/test/java/com/arthas/gateway/architecture/PackageBoundaryTest.java`（`admin` 不破 gateway-core 边界；gateway-core 零 K8S 依赖不变；核心逻辑在 Java 后端、前端无业务规则——原则六 R13）
- [X] T029 [P] 能力开关端到端 IT：`admin.crud.enabled=false` / `admin.export.enabled=false` 各自 `/admin/*` 404 + 前端降级提示、互不影响、`/mcp` 不受影响（INV-SWITCH-1/2）
- [X] T030 浏览器端到端（真实零桩）：浏览器 → SPA → `/admin` CRUD + 导出全链路（Playwright E2E 或手测脚本，真实网关 + 真实后端/任务）
- [X] T031 Run `quickstart.md` validation：场景 A（CRUD）+ B（导出）+ C（开关）+ D（构建）+ 回归（§6 Done Definition 逐项核对）
- [X] T032 [P] Update docs：README 增 portal Web UI 说明 + `arthas-gateway.admin.*` 配置 + 前端构建（`./mvnw verify` 出含前端单 JAR）+ 能力按需开关（宪法"每项新能力必须有文档"）

---

## Phase 6: 增量 — 异步任务列表查询（FR-015 / SC-006）

**Goal**: 后端 `GET /admin/tasks` 列表查询（摘要 + 三维度过滤 + 标准分页 + createdAt 倒序）+ 前端 `/tasks` 页列表区（自验证：空态/错误态/加载态可见反馈，点列表项填 taskId 衔接现有导出流）。

**Independent Test**: 触发几个真实任务 → portal `/tasks` 页列表区自动展示 → status 过滤 / 翻页 → 点列表项填 taskId → 导出 JSON。

> **TDD + 真实零桩**：Service 单测 mock TaskStore 边界（存储已验证）；ContractIT 真实 Spring + 真实 TaskStore Bean + JDK HttpClient 赸实 HTTP；前端 vitest。

### 后端（测试先于实现）

- [X] T033 [P] Write failing `TaskSummaryDtoTest` in `src/test/java/com/arthas/gateway/admin/task/dto/TaskSummaryDtoTest.java`（7 字段、**无 frames** INV-LIST-1、isError 映射：COMPLETED 据 `result.isError()`、其余态 false）
- [X] T034 Implement `TaskSummaryDto` in `src/main/java/com/arthas/gateway/admin/task/dto/TaskSummaryDto.java`（record，green for T033）
- [X] T035 [P] Write failing `TaskListServiceTest` in `src/test/java/com/arthas/gateway/admin/task/TaskListServiceTest.java`（status/tool/target 过滤组合、createdAt 倒序 INV-LIST-3、page/size 分页、`total`=过滤后 INV-LIST-2、size `>100` clamp 100 / `<1` 取 1）
- [X] T036 Implement `TaskListService` in `src/main/java/com/arthas/gateway/admin/task/TaskListService.java`（green for T035）
- [X] T037 Extend `TaskExportController` 加 `GET /admin/tasks` list 端点 in `src/main/java/com/arthas/gateway/admin/task/TaskExportController.java`（query 参数 → `service.list` → `{items,total,page,size}` 响应；同 controller 同开关 INV-LIST-4）
- [X] T038 [P] Write failing `TaskListContractIT` in `src/test/java/com/arthas/gateway/admin/task/TaskListContractIT.java`（failsafe `*IT`，真实 Spring + 真实 TaskStore + JDK HttpClient：put 真实 GatewayTask → A-LIST-TASKS-1 倒序/total、A-LIST-TASKS-2 过滤组合、分页元数据一致、空结果 200）
- [X] T039 Extend `AdminCapabilitySwitchIT` in `src/test/java/com/arthas/gateway/admin/AdminCapabilitySwitchIT.java`（`export.enabled=false` → `GET /admin/tasks` 也 404 INV-LIST-4，开关层自验证）

### 前端（测试先于实现）

- [X] T040 [P] Write failing 前端测试 in `web/src/__tests__/views/TaskExportView.test.ts`（扩：列表区渲染 + 自动查首页 + status 过滤切换 + 分页交互 + 点项填 taskId + **空态/错误态可见反馈**）
- [X] T041 Implement 前端列表区 in `web/src/views/TaskExportView.vue` + 扩 `web/src/api/adminClient.ts`（`listTasks(params)` + `TaskSummaryDto`/`TaskSummaryPage` 类型；空态/错误态/加载态自验证反馈；green for T040）

### 自验证

- [X] T042 Playwright 端到端自验证（真实零桩）：启 arthas（`k8s.ensure-arthas-mcp`）→ 触发真实 `watch`/`jvm` 任务 → portal `/tasks` 页列表区展示 → status 过滤 + 翻页 → 点列表项填 taskId 导出（SC-006）
- [X] T043 [P] Update docs：`quickstart.md` 加场景 E（浏览任务列表 → 过滤 → 点项导出）；README 补列表查询能力（宪法"每项新能力必须有文档"）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖；T001/T002/T003/T004 独立（T002 构建集成依赖 T001 前端项目存在，可同 phase）
- **Foundational (Phase 2)**: 依赖 Setup；**阻塞 US1/US2**
- **US1 (Phase 3)**: 依赖 Foundational（T006 条件装配 / T008 SPA 骨架）；后端先行（T009–T016），前端跟后（T017–T018）
- **US2 (Phase 4)**: 依赖 Foundational；与 US1 独立（可并行，MVP 先 US1）
- **Polish (Phase 5)**: 依赖 US1+US2 完成

### Within US1（后端先行 → 前端跟进）

1. 后端：T009（DTO 测试）→ T010；T011（YamlWriter 测试）→ T012；T013（Service 测试）→ T014 → T015（Controller）；T016（契约 IT）可并行先写
2. 前端：T017（组件测试）→ T018（后端 API 就绪后）

### Within US2

1. 后端：T019 → T020；T021 → T022 → T023；T024（契约 IT）
2. 前端：T025 → T026

### Parallel Opportunities

- Setup：T001 ∥ T003 ∥ T004（独立）；T002 依赖 T001
- Foundational：T005 ∥ T007（后端开关测试 ∥ 前端骨架测试）
- US1：T009 ∥ T011 ∥ T013（后端独立测试）；T016 ∥ T017（IT ∥ 前端测试）
- US2：T019 ∥ T021；T024 ∥ T025
- Polish：T027 ∥ T028 ∥ T029 ∥ T032（独立）

---

## Parallel Example: US1 后端测试先行

```bash
# 并行先写失败测试（红）：
Task T009: "BackendDtoTest in src/test/.../admin/backend/dto/BackendDtoTest.java"
Task T011: "BackendsYamlWriterTest in src/test/.../admin/backend/BackendsYamlWriterTest.java"
Task T013: "BackendAdminServiceTest in src/test/.../admin/backend/BackendAdminServiceTest.java"
Task T016: "BackendAdminContractIT（真实 IT）"

# 再按依赖链实现至 green（T010/T012 → T014 → T015 → 前端 T018）
```

---

## Implementation Strategy

### MVP First（仅 US1）

1. Phase 1 Setup（前端项目 + Maven 集成 + 配置）
2. Phase 2 Foundational（SPA 骨架 + 条件装配——**CRITICAL，阻塞 US1/US2**）
3. Phase 3 US1（后端 CRUD → 前端管理页）
4. **STOP and VALIDATE**：浏览器 → 后端管理页全链路（CRUD + 热重载 + 脱敏）
5. Phase 4 US2 → Phase 5 Polish

### Incremental Delivery

1. Setup + Foundational → 共享基建 ready（SPA 可加载、构建集成跑通）
2. US1 → 后端管理页可用（MVP！管理面基本盘）
3. US2 → 任务导出页可用
4. Polish → 回归 + 包边界 + E2E + 文档
5. 每个波次不破坏前一波次（回归对照 = 001/002/003）

---

## Notes

- **TDD 硬约束**：后端 + 前端所有功能代码测试先于实现（红→绿→重构，宪法原则七）
- **真实性硬约束（零桩）**：后端契约 IT 真实网关 + 真实后端 + 真实任务；前端组件 Vitest、端到端真实浏览器（CLAUDE.md）
- **管理面/诊断面隔离**：`/admin` + SPA 不影响 `/mcp` 38 工具契约（INV-ISOL-1）；`admin` 包是新增，gateway-core 零 K8S 依赖不变
- **能力按需开关**：`admin.crud.enabled`/`admin.export.enabled` 各自 `@ConditionalOnProperty`，关闭=后端 404 + 前端降级（FR-014/R9）
- **前端 = 展示层**（原则六 R13）：核心逻辑 Java 后端、前端仅 fetch + render + download；node/vite 工具链经 `frontend-maven-plugin` 集成（FR-011，CI 可复现）
- 每个任务或逻辑组完成后提交；任一 checkpoint 可停下独立验证；同一问题连续失败 3 次暂停重评（CLAUDE.md）
```


---


## `specs/005-k8s-orchestration-iteration/`（摘要索引）

> 005 = K8S 编排能力迭代（三点：① ensure 的 NodePort 暴露改为复用带 `arthas-mcp-gateway/target` label 的现有 Service（patch type+端口），找不到回退新建；② 后端配置 K8S 场景新增 K8S Host 配置实体 `arthas-gateway.k8s-hosts`（远端 Linux 入口），BackendConfig 加 `k8sHost`+`pod`（与 url 互斥），首次路由懒 resolve（`BackendResolver` 接口 gateway-core 定义 / `K8sBackendResolver` orchestration 实现）；③ JDK 适配 SPI（`ArthasLauncher` 策略接口 + `DefaultArthasLauncher` 默认 + 用户 `@Primary` 定制 + test fixture 真实实现 TDD）。零 gateway-core K8S 依赖不变（ArchUnit 守护），003 既有契约全部不破）。

### 文件清单与主题摘要

| 文件 | 主题摘要 |
|------|----------|
| `spec.md` | 三个用户故事（Service 复用 US1 / K8S Host 配置 US2 / JDK 适配 SPI US3）的 Given-When-Then 验收场景 + FR-001~015 功能需求 + SC-001~006 成功标准 + 边缘案例与假设（P1/P2 优先级划分）。 |
| `plan.md` | 实现计划——技术上下文（Java 21 / Spring Boot 4.1.0 / fabric8 7.6.1，无新增依赖）、单 Maven 模块包结构（`BackendResolver` 接口在 gateway-core / 实现在 orchestration）、4 项复杂度偏离追踪（含正当理由）、宪法八原则检查全 PASS。 |
| `research.md` | R1-R9 九项技术决策——R1 Service label 标记识别、R2 ClusterIP 自动 patch NodePort、R3 懒 resolve 时机（首次路由）、R4 K8sHost 配置位置（application.yml）、R5 多 host 独立 KubernetesClient、R6 BackendResolver 接口位置（gateway-core）、R7 ArthasLauncher SPI 形式、R8 @ConditionalOnMissingBean+@Primary 装配、R9 label key 复用 `arthas-mcp-gateway/target`。 |
| `data-model.md` | 新增/增量实体——K8sHost（配置态，name+kubeconfig+namespace）、BackendConfig 加 `k8sHost`+`pod`（与 url 互斥校验）、BackendResolver 接口（gateway-core，零 fabric8）、K8sBackendResolver（Map<host,provisioner> + 缓存）、ArthasLauncher SPI + LaunchContext record + LaunchException、DefaultArthasLauncher（003 现状外移）、BackendEntry.initializeOnce 懒 resolve hook。 |
| `contracts/orchestration-iteration-invariants.md` | 新增不变量 K-ENS-10/11/12（Service 复用 + ClusterIP 自动改 + patch 幂等）+ INV-K8SHOST-1~5（url/k8sHost 互斥 + 缓存幂等 + host 不存在/无 resolver 错误 + 静态旁路）+ INV-LAUNCHER-1~5（SPI 委托 + Default 兼容 + @Primary 覆盖 + LaunchException 映射 + test fixture 真实实现）+ INV-BOUNDARY-1/2（ArchUnit 包边界）+ 003 回归契约门禁（K-ATOMIC-1/K-ENS-2~9/SC-001）。 |
| `quickstart.md` | 端到端验证指南——场景 A（Service 复用，K-ENS-10/11/12）、场景 B（K8S Host 配置 + 懒 resolve，INV-K8SHOST-1~5）、场景 C（自定义 @Primary ArthasLauncher 独立 JDK）、场景 D（003 回归）+ SPI test fixture 验证 + Done Definition 验证清单。 |
| `tasks.md` | 31 个任务（T001-T031）6 个 Phase（Setup K8sHost 配置 / Foundational SPI+接口 / US1 Service 复用 / US2 K8S Host 懒 resolve / US3 JDK SPI / Polish 回归+ArchUnit+文档），TDD 测试先于实现、标注并行机会与依赖执行序。 |
| `checklists/requirements.md` | 规格质量校验清单——内容质量（聚焦 WHAT/WHY）、需求完整性（FR↔SC↔验收场景三向可溯）、特性就绪度三项全 pass，确认 spec 忠实反映 brainstorming 设计并可进入 `/speckit-plan`。 |

> 各文件完整内容见仓库 `specs/005-k8s-orchestration-iteration/` 目录；设计决策见 `docs/superpowers/specs/2026-07-10-k8s-orchestration-iteration-design.md`。

