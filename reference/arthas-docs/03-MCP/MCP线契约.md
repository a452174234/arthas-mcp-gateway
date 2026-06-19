# MCP 线契约（网关服务端 ↔ Claude Code）

> 本文件定义网关作为 **MCP 服务端**对调用方（Claude Code）暴露的**线级（wire-level）契约**——即"网关必须如何在 MCP 协议层表现"。
> 全部内容摘自 arthas-mcp-server 源码（带 `file:line`），网关复刻服务端时须与之一致。
> 路径相对 `reference/arthas/`。配套：[后端接入契约](./后端接入契约.md)（网关↔arthas 后端客户端侧）、[MCP能力清单](./MCP能力清单.md)（暴露哪些工具）。

> 重要：arthas MCP 服务端有 **Streamable（有状态/SSE，支持 task）** 与 **Stateless（无状态/HTTP，无 task）** 两套实现。网关对 Claude Code 侧若要支持长任务/任务(task)语义，须复刻 **Streamable 路径**的路由与 task 协议。下文凡有差异处均标注。

---

## 1. 协议版本

| 常量 | 值 | 行号 |
|---|---|---|
| `MCP_2024_11_05` | `2024-11-05` | `arthas-mcp-server/.../protocol/spec/ProtocolVersions.java:9` |
| `MCP_2025_03_26` | `2025-03-26` | `:15` |
| `MCP_2025_06_18` | `2025-06-18` | `:21` |
| `MCP_2025_11_25` | `2025-11-25` | `:27` |
| `LATEST_PROTOCOL_VERSION` | `2025-11-25` | `McpSchema.java:34` |

**版本协商规则**（`McpStatelessNettyServer.java:117-128`，Streamable 同 `McpNettyServer.java:177-188`）：
- 客户端请求的版本在接受列表中 → 原样回显；
- 否则 → 回显**列表最后一个**（最高版本）作为建议版本。

**建议网关**：对 Claude Code 侧声明接受 `[2024-11-05, 2025-03-26, 2025-06-18, 2025-11-25]`，实现"不在列表则回最高版本"逻辑。

---

## 2. JSON-RPC 消息骨架

`JSONRPC_VERSION = "2.0"`（`McpSchema.java:36`）。消息判别**靠字段存在性**（非 sealed，`McpSchema.java:192-209`）：
- 含 `method` + `id` → Request；
- 含 `method` 无 `id` → Notification；
- 含 `result` 或 `error` → Response。

| 类型 | 字段 | 行号 |
|---|---|---|
| `JSONRPCRequest` | `jsonrpc` / `method` / `id`(Object) / `params`(Object) | `McpSchema.java:221-254` |
| `JSONRPCNotification` | `jsonrpc` / `method` / `params`（无 id） | `:258-284` |
| `JSONRPCResponse` | `jsonrpc` / `id` / `result` / `error` | `:288-350` |
| `JSONRPCError` | `code`(int) / `message` / `data` | `:324-349` |

合法请求最小字段集：
```json
{ "jsonrpc": "2.0", "id": "<string|number>", "method": "<method>", "params": {} }
```

> 网关解析时须按"存在性"分流 request/notification/response（与 arthas 反序列化一致）。

---

## 3. initialize 握手

### 3.1 请求 / 响应

`InitializeRequest`（`McpSchema.java:357-382`）：`protocolVersion` / `capabilities`(ClientCapabilities) / `clientInfo`(Implementation)。

`InitializeResult`（`McpSchema.java:386-418`）：`protocolVersion` / `capabilities`(ServerCapabilities) / `serverInfo`(Implementation) / `instructions`。

`Implementation = { name: String, version: String }`（`McpSchema.java:800-818`）。

### 3.2 ClientCapabilities（`McpSchema.java:430-538`）
`experimental` / `roots{listChanged}` / `sampling` / `elicitation`。

### 3.3 ServerCapabilities（`McpSchema.java:540-796`）
| 字段 | 子结构 | 关键子字段 | 行号 |
|---|---|---|---|
| `logging` | LoggingCapabilities（空） | — | `:552` |
| `prompts` | PromptCapabilities | `listChanged` | `:553, 627-637` |
| `resources` | ResourceCapabilities | `subscribe`、`listChanged` | `:554, 639-658` |
| `tools` | ToolCapabilities | **仅 `listChanged`** | `:555, 660-671` |
| `tasks` | TaskCapabilities（arthas 自定义扩展） | `list`/`cancel`/`requests.tools.call` | `:556, 673-771` |
| `experimental` | Map | — | `:551` |

> **关键**：`ToolCapabilities` 只有 `listChanged`，**没有** `taskSupport` 顶层字段。任务支持信息在**每个 Tool** 的 `execution.taskSupport`（`McpSchema.java:1535/1633`）。分页字段 `nextCursor` 直接挂在 `ListToolsResult` 上，且 arthas 恒传 null（不支持分页）。

### 3.4 capabilities 自动推断
- Stateless（`McpStatelessServerFeatures.java:46-53`）：总开 logging；prompts/resources/tools 仅在非空时构造对应空 capability；**构造器签名无 tasks 参数，永不构造 tasks capability**。
- Streamable（`McpServerFeatures.java:59-72`）：同上，且当注册了 taskTools 才构造 `TaskCapabilities.builder().list().cancel().toolsCall().build()`（`:67-71`）。

### 3.5 `notifications/initialized`
常量 `McpSchema.java:45`。两套服务端都注册**空 handler**（`McpStatelessNettyServer.java:100`、`McpNettyServer.java:109-110`）——接受但不强制副作用。协议层要求客户端发；网关对下游应转发以保合规。

---

## 4. tools/list 与 tools/call

### 4.1 `Tool` record（`McpSchema.java:1531-1617`）
`name`(必填，空串报错) / `description`(可空) / `inputSchema`(JsonSchema，必填) / `execution`(ToolExecution)。
> **不存在** `annotations`/`outputSchema`/`title`/`enumerable` 字段（arthas 的 Tool 比 2025-11-25 草案精简）。

`ToolExecution`（`:1633-1651`）：唯一字段 `taskSupport: TaskSupportMode`。枚举（`:1619-1631`）：`FORBIDDEN`=`"forbidden"`、`OPTIONAL`=`"optional"`、`REQUIRED`=`"required"`。

### 4.2 `ListToolsResult`（`McpSchema.java:1451-1485`）
`tools`(List<Tool>) / `nextCursor`(String) / `_meta`。**arthas 的 `nextCursor` 恒为 null**（`McpStatelessNettyServer.java:249`、`McpNettyServer.java:365`）。

### 4.3 `CallToolRequest`（`McpSchema.java:1666-1773`）
`name` / `arguments`(Map<String,Object>) / `_meta` / `task`(TaskMetadata)。
`TaskMetadata`（`:2852-2869`）：仅 `ttl: Long`（JSON `"ttl"`），`ttlAsDuration()` 转毫秒。

### 4.4 `CallToolResult`（`McpSchema.java:1783-1870`）
`content`(List<Content>) / `isError`(Boolean) / `_meta`。**无 `structuredContent` 字段**。

### 4.5 Content 类型（`McpSchema.java:2530-2655`，多态 `@JsonTypeInfo(property="type")`）
| 实现 | `type` | 字段 |
|---|---|---|
| `TextContent` | `text` | `audience`、`priority`、`text` |
| `ImageContent` | `image` | `audience`、`priority`、`data`(base64)、`mimeType` |
| `EmbeddedResource` | `resource` | `audience`、`priority`、`resource`(ResourceContents) |

> **无 AudioContent**（标准草案有，arthas 未注册）。`ResourceContents` 多态（`:1148-1167`）：`TextResourceContents`(text) / `BlobResourceContents`(blob)。

### 4.6 method → handler 路由

**Streamable**（`McpNettyServer.prepareRequestHandlers`，`:127-165`）：

| method | 注册行 | handler |
|---|---|---|
| `initialize` | 注入 `:100-102` | `initializeRequestHandler` |
| `ping` | `:133` | 返回空 Map |
| `tools/list` | `:138` | `toolsListRequestHandler()`（`:357-367`，需 tools capability） |
| `tools/call` | `:139` | `toolsCallRequestHandler()`（`:369-414`） |
| `resources/list`、`resources/read`、`resources/templates/list` | `:144-146` | （arthas 恒空） |
| `prompts/list`、`prompts/get` | `:151-152` | （arthas 恒空） |
| `logging/setLevel` | `:157` | `setLoggerRequestHandler()` |
| `tasks/get`、`tasks/result` | `:162`（经 `ServerTaskToolHandler.getRequestHandlers`） | **总注册** |
| `tasks/list` | 同上 | 仅当 `taskCapabilities.getList()!=null` |
| `tasks/cancel` | 同上 | 仅当 `taskCapabilities.getCancel()!=null` |

**Stateless**（`McpStatelessNettyServer.java:71-104`）：仅 initialize/ping/tools\*/resources\*/prompts\*，**无 logging/setLevel，无任何 tasks/\***。

> **网关设计含义**：网关对 Claude Code 若暴露 task 能力，必须复刻 Streamable 的 task 路由，不能照抄 Stateless。

---

## 5. 错误

### 5.1 标准 error code（`McpSchema.java:119-146`，`ErrorCodes`）
| 常量 | 值 |
|---|---|
| `PARSE_ERROR` | -32700 |
| `INVALID_REQUEST` | -32600 |
| `METHOD_NOT_FOUND` | -32601 |
| `INVALID_PARAMS` | -32602 |
| `INTERNAL_ERROR` | -32603 |

**无 -32000 段自定义 code**。业务错误**复用**标准 code（实例见 §6.4）。

### 5.2 `McpError`（`arthas-mcp-server/.../protocol/spec/McpError.java`）
- 推荐 `McpError.builder(code).message(...).data(...).build()`（`:43-74`），内部 `new JSONRPCError(code,message,data)`（`:71`）。
- `@Deprecated McpError(Object)`（`:24-27`）无 code——Stateless 缺失 handler 兜底用它（`DefaultMcpStatelessServerHandler.java:67-69`）。**网关须自行补 METHOD_NOT_FOUND**。

### 5.3 业务错误映射实例（来自源码）
| 场景 | code | 行号 |
|---|---|---|
| 普通工具被以 task 模式调用 | METHOD_NOT_FOUND(-32601) | `McpNettyServer.java:382-386` |
| 未知工具名 | INVALID_PARAMS(-32602) | `McpNettyServer.java:398-411` |
| 并发 task 上限 | INVALID_PARAMS(-32602) | `ToolCallbackCreateTaskHandler.java:54-57` |
| REQUIRED 工具但请求无 task 元数据 | INVALID_PARAMS(-32602) | `ServerTaskToolHandler.java:212-216` |
| 请求带 task 但无 taskStore | INVALID_REQUEST(-32600) | `ServerTaskToolHandler.java:201-205` |
| task 创建失败（非 McpError 异常） | INTERNAL_ERROR(-32603) | `ServerTaskToolHandler.java:264-270` |
| Stateless handler 异常兜底 | INTERNAL_ERROR(-32603) | `DefaultMcpStatelessServerHandler.java:81-82` |

---

## 6. Task 协议（仅 Streamable 路径）

### 6.1 method 常量（`McpSchema.java:90-96`）
| 常量 | 值 | 性质 |
|---|---|---|
| `METHOD_TASKS_LIST` | `tasks/list` | 请求 |
| `METHOD_TASKS_GET` | `tasks/get` | 请求（非阻塞查状态） |
| `METHOD_TASKS_RESULT` | `tasks/result` | 请求（**阻塞**取结果，注释 `:93` "Blocking result retrieval"） |
| `METHOD_TASKS_CANCEL` | `tasks/cancel` | 请求 |
| `METHOD_NOTIFICATION_TASKS_STATUS` | `notifications/tasks/status` | 通知 |
| `METHOD_NOTIFICATION_TASKS_LIST_CHANGED` | `notifications/tasks/list_changed` | 通知（定义但源码未发送） |

关联 meta key：`RELATED_TASK_META_KEY = "io.modelcontextprotocol/related-task"`（`McpSchema.java:109`），`tasks/result` 响应注入此键（`ServerTaskToolHandler.java:508-515`）。

### 6.2 相关 record
- `Task`（`McpSchema.java:2728-2850`）：`taskId`/`status`/`statusMessage`/`createdAt`/`lastUpdatedAt`/`ttl`/`pollInterval`。
- `CreateTaskResult`（`:2887-2910`）：`task`/`meta`（tools/call 任务化返回）。
- `GetTaskResult`（`:2998-3087`）、`GetTaskPayloadResult`（`:3118-3141`，对应 tasks/result）、`CancelTaskResult`（`:3168-3246`）、`ListTasksResult`（`:2914-2948`，含 nextCursor）。
- `TaskStatusNotification`（`:3248-3388`）。

### 6.3 TaskStatus 枚举（`McpSchema.java:2702-2717`）
`WORKING`=`working` / `INPUT_REQUIRED`=`input_required` / `COMPLETED`=`completed` / `FAILED`=`failed` / `CANCELLED`=`cancelled`（**英式拼写**）。
`isTerminal()` = COMPLETED || FAILED || CANCELLED（`:2714-2716`）。无 `paused`/`running`。

### 6.4 生命周期状态机（`ServerTaskToolHandler.doHandleTaskToolCall`，`:189-232`）
客户端发 `tools/call`，工具 task-aware（`execution.taskSupport != FORBIDDEN`）：

1. **请求带 `task` 字段**（`request.getTask()!=null`，`:198`）：
   - 无 taskStore → INVALID_REQUEST（`:199-206`）；
   - 否则创建 task（后台异步执行，主流程**立即返回 task**），状态 `WORKING`（`ToolCallbackCreateTaskHandler.java:72-91`）。
2. **不带 task + `taskSupport==REQUIRED`**（`:210`）→ INVALID_PARAMS（`:211-217`）。
3. **不带 task + `taskSupport==OPTIONAL` + 有 taskStore**（`:219`）→ `handleAutomaticTaskPolling`（`:277-312`）：内部建 task → 阻塞轮询至终态 → **单次 HTTP 同步返回** `CallToolResult`（对客户端透明；自动轮询超时 10 分钟，`TaskDefaults.java:50`；若终态 INPUT_REQUIRED → INTERNAL_ERROR，`:349-361`）。
4. **不带 task + 无 taskStore + 有 callHandler**（`:223-225`）→ 当普通工具直接调用。

后台执行状态迁移（`ToolCallbackCreateTaskHandler.java:102-180`）：成功→COMPLETED；isError/异常→FAILED；被取消→CANCELLED。

状态流：
```
创建 → WORKING
WORKING ──成功──► COMPLETED（终态）
WORKING ──失败──► FAILED（终态）
WORKING ──需输入──► INPUT_REQUIRED ──(继续交互)──► WORKING/终态
WORKING / INPUT_REQUIRED ──取消──► CANCELLED（终态）
```

客户端取结果：`tasks/get`（查状态）→ `tasks/result`（阻塞取最终 payload）→ `tasks/cancel`（取消）；服务端可主动推 `notifications/tasks/status`（需 GET SSE 长连接）。

---

## 7. @Tool → ToolDefinition → inputSchema 暴露流程

精确链路（file:line）：

1. **类路径扫描**：`DefaultToolCallbackProvider.scanForToolCallbacks`（`DefaultToolCallbackProvider.java:60-70`）→ `scanPackageForToolMethods`（`:72-102`，file/jar 两种协议）→ `processClass`（`:138-152`，过滤 interface/enum/annotation，对 `@Tool` 方法调 `registerToolMethod`）。
2. **`@Tool` Method → ToolDefinition**：`ToolDefinitions.from(method)`（`tool/definition/ToolDefinitions.java:22-24`）→ `builder(method)`（`:12-20`）：
   - `name` ← `getToolName`（`:26-33`，取 `@Tool.name()`，空则 `method.getName()`）；
   - `description` ← `getToolDescription`（`:35-42`）；
   - `inputSchema` ← **`JsonSchemaGenerator.generateForMethodInput(method)`**（`:17`）；
   - `streamable` ← `isStreamable`（`:44-51`）；
   - `taskSupport` ← `getTaskSupport`（`:53-60`，取 `@Tool.taskSupport()`，默认 FORBIDDEN）。
3. **inputSchema 生成**：`JsonSchemaGenerator.generateForMethodInput`（`tool/util/JsonSchemaGenerator.java:37`）。规则见 [MCP能力清单 §0](./MCP能力清单.md#0-摘抄规则先读这一节)。固定 `type=object`、`additionalProperties=false`、只处理 `@ToolParam` 参数、不生成 enum/default。
4. **注册进服务端**：`ToolDefinition` → `McpSchema.Tool`（经 `McpToolUtils.toToolSpecification`，`core/.../core/mcp/tool/util/McpToolUtils.java:41`，把 `taskSupport` 放进 `ToolExecution`）→ Streamable 走 `McpServer.StreamableServerNettySpecification.tools()/taskTool()`（`McpServer.java:108-127/223-246`）→ `McpNettyServer` 构造器（`McpNettyServer.java:71-75`）→ `toolsListRequestHandler`（`:357-367`）合并普通工具与 task 工具进 `ListToolsResult`。

> **关键**：inputSchema 在**启动期一次性静态生成**（`JsonSchemaGenerator.java:37`），运行时不变。网关 `tools/list` 应直接返回静态 schema（与 [MCP能力清单](./MCP能力清单.md) 一致）。

---

## 8. 网关复刻契约的关键差异点（TL;DR）

1. **消息判别靠字段存在性**（`McpSchema.java:200-208`），非 sealed。
2. **版本协商**：接受列表 ≠ LATEST；Stateless 默认不收 2024-11-05，Streamable 收全部 4 个。
3. **capabilities 自动推断**：Stateless 永不构造 tasks；Streamable 仅当注册了 taskTools 才构造。
4. **Tool schema 精简**：无 `annotations/outputSchema/title`，任务语义在 `execution.taskSupport`（非 capability 顶层）。
5. **Content 无 audio**（只有 text/image/resource）。
6. **error code 仅 5 个标准码**，无 -32xxx；业务错误复用标准码。
7. **nextCursor 恒 null**（所有 list 响应），分页未实现。
8. **Stateless 路径无 task**；task 完整语义只在 Streamable（`McpNettyServer.java:162`）。
9. **Stateless 缺失 handler 的错误无标准 code**（`DefaultMcpStatelessServerHandler.java:67-69`），网关须补 METHOD_NOT_FOUND。
10. **任务状态英式 `cancelled`**，状态机 5 态，无 paused。
