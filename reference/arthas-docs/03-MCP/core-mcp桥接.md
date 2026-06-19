# core/mcp · 命令到 MCP 工具的桥接

> 路径：`core/src/main/java/com/taobao/arthas/core/mcp/`（下文 `core/.../core/mcp/`）
> 在整体中的位置：**桥接层**。把 arthas 命令封装成 MCP 工具，并启动/对接 [`arthas-mcp-server`](./arthas-mcp-server.md)。

---

## 包总览

| 包 | 职责 |
|---|---|
| `mcp/`（根级） | `ArthasMcpBootstrap`/`ArthasMcpServer`：启动 MCP 服务、扫描分类工具 |
| `mcp/tool/function/` | `AbstractArthasTool` 工具基类 + 命令组工具 |
| `mcp/tool/function/basic1000` | 基础命令工具 |
| `mcp/tool/function/jvm300` | JVM 诊断工具 |
| `mcp/tool/function/klass100` | 类操作工具 |
| `mcp/tool/function/monitor200` | 监控分析工具 |
| `mcp/tool/util` | `McpToolUtils` 工具规范转换 |
| `mcp/util` | 认证提取、VO 过滤 |

---

## 一、启动与对接

### `ArthasMcpBootstrap`
`core/.../mcp/ArthasMcpBootstrap.java` — MCP 启动引导（单例）。

- `start()` / `shutdown()` / `getInstance()` / `getCommandExecutor()`。
- 由 [`ArthasBootstrap.bind()`](../02-核心运行时/core/core-入口与全局选项.md) 在配置了 `mcpEndpoint` 时调用。

### `ArthasMcpServer`
`core/.../mcp/ArthasMcpServer.java` — arthas MCP 服务器实现。

- `start()` — 启动。
- **`scanAndClassifyTools()`** — 扫描 `@Tool` 方法并按 `taskSupport` 分类（`ToolClassification`：`normalTools`/`optionalTaskTools`/`requiredTaskTools`）。
- `startStreamableServer()` / `startStatelessServer()` — 按配置启动对应模式。
- `configureTaskSupport()` / `buildServerCapabilities()`。
- 内部 `McpTaskThreadFactory`（守护线程）。

> 它组装 `arthas-mcp-server` 的 `McpServer.netty(...)` 构建器，把扫描到的工具注册进去。

---

## 二、工具基类

### `AbstractArthasTool`
`core/.../mcp/tool/function/AbstractArthasTool.java` — **所有 arthas 工具的抽象基类**。

- **`executeSync(toolContext, commandStr)`** — 同步执行 arthas 命令。
- **`executeStreamable(toolContext, commandStr, ...)`** — 流式执行（异步 + 轮询结果 + 进度通知 + 取消检查）。
- `buildCommand(base)` / `addParameter` / `addFlag` / `addQuotedParameter` — 拼装 arthas 命令行。
- `executeAsyncWithRetry()` — 处理"Another job is running"重试。
- 内部 `ToolExecutionContext`：`commandContext`/`mcpTransportContext`/`authSubject`/`userId`/`exchange`。

> 工具方法的执行最终落到 `session/ArthasCommandContext`（在 mcp-server 模块）→ core 的 `CommandExecutorImpl`。

---

## 三、命令组工具（`@Tool` 方法）

每个工具类用 `@Tool` 标注方法、`@ToolParam` 标注参数，对应一个 arthas 命令。

### `basic1000` · 基础命令
- `VersionTool`(`version`)、`OptionsTool`(`options`)、`StopTool`(`stop`)、`ViewFileTool`(`cat`/`more`)。

### `jvm300` · JVM 诊断
- `DashboardTool`(`dashboard`，**streamable**，参数 `intervalMs`/`numberOfExecutions`)、`JvmTool`(`jvm`)、`ThreadTool`(`thread`)、`MemoryTool`(`memory`)、`SysPropTool`(`sysprop`)、`SysEnvTool`(`sysenv`)、`VMOptionTool`(`vmoption`)、`VMToolTool`(`vmtool`)、`OgnlTool`(`ognl`)、`GetStaticTool`(`getstatic`)、`MBeanTool`(`mbean`)、`HeapdumpTool`(`heapdump`)。

### `klass100` · 类操作
- `JadTool`(`jad`，参数含 `classPattern`/`classLoaderHash`/`sourceOnly`/`noLineNumber`/`useRegex`/`dumpDirectory`)、`SearchClassTool`(`sc`)、`SearchMethodTool`(`sm`)、`ClassLoaderTool`(`classloader`)、`DumpClassTool`(`dump`)、`MemoryCompilerTool`(`mc`)、`RedefineTool`(`redefine`)、`RetransformTool`(`retransform`)。

### `monitor200` · 监控分析
- **`WatchTool`**(`watch`，**streamable + taskSupport=OPTIONAL**，参数 `classPattern`/`methodPattern`/`express`/`condition`/`beforeMethod`/`exceptionOnly`/`successOnly`/`numberOfExecutions`/`regex`/`maxMatchCount`/`expandLevel`/`sizeLimit`/`timeout`)、`TraceTool`(`trace`)、`StackTool`(`stack`)、`MonitorTool`(`monitor`)、`TimeTunnelTool`(`tt`)、**`ProfilerTool`**(`profiler`，参数 `action`/`event`/`file`/`format`/`duration` 等 40+，底层 [`one/profiler`](../02-核心运行时/core/core-支撑层.md))。

> 命令组命名规则：`basic1000`/`klass100`/`monitor200`/`jvm300` 与 [`core/command`](../02-核心运行时/core/core-命令系统.md) 下的子包对应（jvm300 是 MCP 侧对 JVM 类命令的归类）。

---

## 四、`tool/util` · 工具规范转换

- **`McpToolUtils`** — `toStreamableToolSpecifications(tools)`/`toStatelessToolSpecifications(tools)`/`toToolSpecification(toolCallback)`/`createSuccessResult(content)`/`createErrorResult(msg)`。把 arthas 的 `ToolCallback` 转成 mcp-server 的 `ToolSpecification`。

## 五、`mcp/util`

- **`McpAuthExtractor`** — `extractAuthSubjectFromContext(ctx)`/`extractUserIdFromRequest(request)`（`X-User-Id` 头）、常量 `SUBJECT_ATTRIBUTE_KEY`/`USER_ID_HEADER`。
- **`McpObjectVOFilter`** — `register()` 注册自定义过滤器（过滤敏感对象的 VO 序列化）。

---

## 桥接全链路（连接 mcp-server 与 core）

```
（mcp-server 侧）DefaultToolCallback.call(toolInput, toolContext)
  ↓ 反射 @Tool 方法（如 WatchTool.watch()）          ← core/mcp
AbstractArthasTool.executeStreamable(ctx, "watch ...")
  ↓ 拼装命令行 + 设会话认证
ArthasCommandContext.executeAsync()                   ← mcp-server/session
  ↓
CommandExecutor.executeAsync(sessionId)               ← 接口（mcp-server 定义）
  ↓ 实现
core/.../command/CommandExecutorImpl.executeAsync()   ← core
  ↓ 进入 core 命令主链路（shell/command/advisor/...）
  ↓
AbstractArthasTool 轮询 commandContext.pullResults()
  ↓ 经 SSE 发进度 / 任务转 COMPLETED → 返回 CallToolResult
```

## 定位提示

> "我想给 arthas 加一个新的 MCP 工具。" → 在 `mcp/tool/function/<组>/` 加一个带 `@Tool`/`@ToolParam` 的类，继承 `AbstractArthasTool`，在 `executeSync/executeStreamable` 里拼命令。
> "MCP 服务在哪里被启动的？" → `mcp/ArthasMcpBootstrap.start()` → `ArthasMcpServer.start()`（由 `ArthasBootstrap.bind()` 触发）。
> "`watch` 工具的参数怎么定义的？" → `mcp/tool/function/monitor200/WatchTool`。
> "MCP 调用最终怎么变成 arthas 命令执行的？" → `AbstractArthasTool.executeStreamable()` → `ArthasCommandContext` → `CommandExecutorImpl`。
