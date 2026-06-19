# arthas-mcp-server 模块 · MCP 协议服务器

> 路径：`arthas-mcp-server/src/main/java/com/taobao/arthas/mcp/server/`
> 在整体中的位置：**通用 MCP 协议实现**（参考 Spring AI MCP）。负责 JSON-RPC 收发、会话、任务、传输层，不直接懂 arthas 命令。

---

## 包总览

| 包 | 职责 |
|---|---|
| `protocol/spec` | MCP 协议类型定义（JSON-RPC 消息、Tool/Task、会话/传输接口） |
| `protocol/server` | 服务器核心（有状态 Streamable / 无状态 Stateless） |
| `protocol/server/handler` | HTTP 接入处理器 |
| `protocol/server/transport` | Netty 传输层（HTTP/SSE） |
| `protocol/server/store` | 事件存储 |
| `protocol/config` | 配置属性 |
| `session` | arthas 命令会话桥接 |
| `tool` | 工具抽象（`@Tool`、`ToolCallback`、JSON Schema） |
| `task` | 任务机制（长任务异步执行/轮询/取消） |
| `util` | 工具类（JSON/断言/保活/认证提取） |
| 根级 `CommandExecutor` | 命令执行接口（core 实现它） |

---

## 一、`protocol/spec` · 协议定义 ⭐

- **`McpSchema`** — 核心类型：`JSONRPCRequest/Notification/Response`、`InitializeRequest/Result`（握手）、`CallToolRequest/Result`、`Tool`（name/description/inputSchema）、`Task`/`TaskStatus`（WORKING/INPUT_REQUIRED/COMPLETED/FAILED/CANCELLED）/`TaskSupportMode`（FORBIDDEN/OPTIONAL/REQUIRED）、`ServerCapabilities`、常量 `LATEST_PROTOCOL_VERSION = "2025-11-25"`。
- **`McpSession`** — 会话接口：`sendRequest(method, params, typeRef)`/`sendNotification(...)`/`closeGracefully()`。
- **`McpServerTransport` / `McpServerTransportProvider`** — 传输抽象：`notifyClients(...)`/`setSessionFactory(...)`。
- `McpError`、**`ProtocolVersions`**（`MCP_2024_11_05` … `MCP_2025_11_25`）。
- 其余：`McpStreamableServerSession`/`McpStreamableServerTransport(Provider)`/`McpStatelessServerTransport`/`McpSession`/`EventStore`/`MissingMcpTransportSession`/`HttpHeaders`。

## 二、`protocol/server` · 服务器核心

- **`McpServer`** — 服务器入口与构建器：`netty(transportProvider)`（Streamable）/`netty(transport)`（Stateless）；`StreamableServerNettySpecification`（`commandExecutor()`/`sessionManager()`/`taskTool()`/`taskStore()`/`taskMessageQueue()`）、`StatelessServerNettySpecification`。
- **`McpNettyServer`** — Streamable 实现：`addTool/removeTool/notifyToolsListChanged`、`toolsListRequestHandler()`/`toolsCallRequestHandler()`、集成 `ServerTaskToolHandler`。
- **`McpStatelessNettyServer`** — Stateless 实现（无会话/任务，单请求完成）。
- **`McpRequestHandler<T>`** — 请求处理器接口：`handle(exchange, commandContext, params)` → `CompletableFuture<T>`。
- **`McpInitRequestHandler`** / **`McpNotificationHandler`** — 初始化/通知处理器接口。
- **`McpServerFeatures`** — 功能规范：`ToolSpecification`/`ResourceSpecification`/`PromptSpecification`/`McpServerConfig`（按功能自动构造 `ServerCapabilities`）。
- **`DefaultMcpStatelessServerHandler`** / **`McpStatelessServerHandler`** — 无状态处理。
- **`McpNettyServerExchange`** — 服务端交换对象（会话/传输上下文/客户端能力）。
- **`McpTransportContext`** / **`DefaultMcpTransportContext`** / **`McpTransportContextExtractor`** — 传输上下文（传认证等到工具层）。

## 三、`protocol/server/handler` · HTTP 接入

- **`McpHttpRequestHandler`** — HTTP 统一入口：按 `protocol` 配置路由到 Streamable 或 Stateless；`sendError()` 统一错误。
- **`McpStreamableHttpRequestHandler`** — 流式：SSE 长连接 + 消息流。
- **`McpStatelessHttpRequestHandler`** — 无状态：单次请求-响应。

## 四、`protocol/server/transport` · 传输层

- **`NettyStreamableServerTransportProvider`** — 流式传输：`protocolVersions()`（支持到 `2025-11-25`）、`setSessionFactory()`、`notifyClients(...)`（广播到所有 SSE）、`getMcpRequestHandler()`。
- **`NettyStatelessServerTransport`** — 无状态传输。

## 五、`protocol/server/store`

- **`InMemoryEventStore`** — 内存事件存储（SSE 重连补发等）。

## 六、`protocol/config`

- **`McpServerProperties`** — `name/version/instructions`、变更通知开关、`mcpEndpoint`（默认 `/mcp`）、`requestTimeout`（默认 10s）、`protocol`（`STREAMABLE`/`STATELESS`）。

## 七、`session` · arthas 命令会话桥接

- **`ArthasCommandContext`** — 命令执行上下文：`executeSync(...)`/`executeAsync(...)`/`pullResults()`/`interruptJob()`/`setSessionAuth(...)`/`setSessionUserId(...)`。内部 `CommandSessionBinding`（`mcpSessionId` ↔ `arthasSessionId` + `consumerId`）。
- **`ArthasCommandSessionManager`** — `createCommandSession(mcpSessionId)`/`getCommandSession(mcpSessionId, authSubject)`/`isSessionValid(...)`（25 分钟过期）/`closeCommandSession(...)`/`createIsolatedTaskSession(taskId)`/`isAtConcurrencyLimit()`。

## 八、`tool` · 工具抽象 ⭐

- **`ToolCallback`** — `getToolDefinition()`/`call(toolInput)`/`call(toolInput, toolContext)`。
- **`ToolCallbackProvider`** / **`DefaultToolCallbackProvider`** — `getToolCallbacks()`；后者 `setToolBasePackage()` + `scanForToolCallbacks()`（扫描 `@Tool` 方法，支持目录与 jar）。
- **`DefaultToolCallback`** — 默认实现（反射调 `@Tool` 方法）。
- **`ToolContext`** / **`ToolContextKeys`** — 执行上下文（键：`EXCHANGE`/`COMMAND_CONTEXT`/`PROGRESS_TOKEN`/`MCP_TRANSPORT_CONTEXT`）。
- **`@Tool`** / **`@ToolParam`** — 工具与参数注解（name/description/streamable/taskSupport/required）。
- **`ToolDefinition`** / **`ToolDefinitions`** — 工具定义（含 `inputSchema` JSON Schema、`streamable`、`taskSupport()`）。
- `tool/execution`：`ToolCallResultConverter`/`DefaultToolCallResultConverter`、`ToolExecutionException`/`ToolExecutionExceptionProcessor`/`DefaultToolExecutionExceptionProcessor`。
- `tool/util`：**`JsonSchemaGenerator`**（从方法参数生成 JSON Schema）。

## 九、`task` · 任务机制 ⭐

支持长时间运行工具的异步执行、轮询、取消（MCP Task 协议）。

- **`TaskManager`** — 接口：`bind(host)`、`processInboundRequest()`（tasks/list/get/cancel）、`processOutboundNotification()`（notifications/tasks/status）、`taskStore()`/`messageQueue()`。impl：`DefaultTaskManager`/`NullTaskManager`/`TaskManagerHost`。
- **`ServerTaskToolHandler`** — `addTaskTool/removeTaskTool`、`handleToolCall()`（任务创建或自动轮询）、`handleTaskToolCreateTask()`、`handleAutomaticTaskPolling()`、`pollTaskUntilTerminal()`。
- **`TaskAwareToolSpecification`** — 任务感知工具规范（`tool()`/`callHandler()`/`createTaskHandler()`/`getTaskHandler()`/`getTaskResultHandler()`）。
- **`AbstractTaskAwareToolSpecificationBuilder`** — 构建器。
- 处理器接口：**`CreateTaskHandler`**（`createTask(args, ctx)` → `CreateTaskResult`）、**`GetTaskHandler`**、**`GetTaskResultHandler`**、`AbstractTaskHandler`。impl：`ToolCallbackCreateTaskHandler`。
- 存储：**`TaskStore<R>`**（`createTask`/`getTask`/`updateTaskStatus`/`storeTaskResult`/`getTaskResult`/`listTasks`/`requestCancellation`/`watchTaskUntilTerminal`）、**`InMemoryTaskStore`**（`ConcurrentSkipListMap`、TTL、分页、上限默认 1000、取消协作）。
- 消息队列：**`TaskMessageQueue`**/`InMemoryTaskMessageQueue`/`QueuedMessage`。
- `CreateTaskOptions`/`CreateTaskContext`/`DefaultCreateTaskContext`/`GetTaskFromStoreResult`/`TaskDefaults`（TTL 30min、poll 1s、并发上限 10）/`TaskManagerOptions`/`TaskMetadataUtils`/`TaskHelper`/`TaskHandlerRegistry`/`TriFunction`。

## 十、`util`

- **`JsonParser`**（共享 `ObjectMapper`）、**`Assert`**、**`Utils`**、**`KeepAliveScheduler`**（SSE 保活）、**`McpAuthExtractor`**（从 Netty ctx 提认证主体、从 `X-User-Id` 头提 userId）。

## 十一、根级 `CommandExecutor`

`arthas-mcp-server/.../CommandExecutor.java` — **桥接接口**，core 的 `CommandExecutorImpl` 实现它。

`executeSync(...)`/`executeAsync(...)`/`pullResults(...)`/`interruptJob(...)`/`createSession(quiet)`/`closeSession(...)`/`setSessionAuth(...)`/`setSessionUserId(...)`。

---

## 设计要点

1. **双模式**：Streamable（流式/会话/任务，适合 `watch`/`dashboard` 等长输出）与 Stateless（无状态，适合 `jad`/`sc` 等一次性查询）。
2. **工具分类**：按 `@Tool(taskSupport=…)` 分为普通工具（FORBIDDEN）、可选任务（OPTIONAL）、必须任务（REQUIRED）。
3. **认证透传**：HTTP 头/Netty ctx → `McpTransportContext` → 工具执行层 → arthas 会话（复用 core 的 [`security`](../02-核心运行时/core/core-入口与全局选项.md)）。
4. **`@Tool` 自动扫描**：`DefaultToolCallbackProvider` 扫包注册，新增工具只要加注解。

## 定位提示

> "MCP 的 JSON-RPC 消息类型定义在哪？" → `protocol/spec/McpSchema`。
> "HTTP `/mcp` 请求先进哪？" → `protocol/server/handler/McpHttpRequestHandler`。
> "长任务（watch）怎么异步执行/轮询？" → `task/ServerTaskToolHandler` + `task/InMemoryTaskStore`。
> "`@Tool` 注解处理、JSON Schema 生成？" → `tool/DefaultToolCallbackProvider` + `tool/util/JsonSchemaGenerator`。
> "mcp-server 怎么调到 arthas 命令的？" → 通过 `CommandExecutor` 接口，实现在 core（见 [core-mcp桥接](./core-mcp桥接.md)）。
