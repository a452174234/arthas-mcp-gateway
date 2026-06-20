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
