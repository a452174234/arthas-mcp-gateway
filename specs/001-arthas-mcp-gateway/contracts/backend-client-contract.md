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
