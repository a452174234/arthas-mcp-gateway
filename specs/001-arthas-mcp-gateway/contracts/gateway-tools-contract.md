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
