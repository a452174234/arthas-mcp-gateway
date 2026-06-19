# core · Shell 交互系统

> 覆盖源码包：`core/shell/**`（最大的子系统，参考 vert.x shell 风格分层）
> 解决：**用户在终端敲一行命令，arthas 如何解析、调度、执行、回写；以及 telnet / http / websocket 多种接入怎么统一。**

---

## 一、分层总览

```
shell/             ← Shell / ShellServer 顶层接口
shell/system/      ← Job / Process / JobController 抽象（+impl 实现）
shell/impl/        ← ShellServerImpl / ShellImpl 服务端实现
shell/command/     ← Command / CommandProcess / 注册表（+impl）；internal 管道命令
shell/cli/         ← 命令行分词 CliToken、补全 Completion（+impl）
shell/session/     ← 会话 Session / SessionManager（+impl）
shell/history/     ← 命令历史 HistoryManager（+impl）
shell/handlers/    ← 各类事件 Handler（command/server/shell/term）
shell/term/        ← 终端 Term / TermServer（+impl/http/httptelnet）
shell/future/      ← 异步 Future
```

## 二、命令交互全链路

```
终端输入
  → term/impl/TermImpl.readline()
  → handlers/shell/ShellLineHandler.handle(line)
  → impl/ShellImpl.createJob()
  → system/impl/JobControllerImpl.createJob()     （处理管道/重定向）
  → system/impl/ProcessImpl（包装命令）
  → system/impl/JobImpl.run()
  → shell/command/Command.processHandler().handle(process)
  → command 下具体命令的 process(CommandProcess)
  → CommandProcess.write() / end()
  → ProcessImpl 经 stdoutHandlerChain → Term.write() 回终端
```

---

## 三、各子包详解

### `shell`（根级）· 顶层抽象
- **`Shell`** (`shell/Shell.java`) — 消费者与会话的交互抽象。`createJob(line)`、`jobController()`、`session()`、`close(reason)`。
- **`ShellServer`** (`shell/ShellServer.java`) — 服务器，管多个 TermServer。`registerCommandResolver(...)`、`registerTermServer(...)`、`createShell(term)`、`listen()`、`close()`。
- **`ShellServerOptions`** — 服务器配置。

### `shell/system`(+impl) · Job/Process 抽象
- **`Job`** / **`Process`** / **`JobController`** — 任务/进程/控制器接口。`Job`：`run()`/`interrupt()`/`suspend()`/`resume()`/`toBackground()`/`toForeground()`；`Process`：`run()`/`interrupt()`/`suspend()`/`resume()`/`setTty(...)`/`terminatedHandler(...)`；`JobController`：`createJob()`/`jobs()`/`getJob(id)`/`close()`。
- **`ExecStatus`** — 状态枚举：`READY/RUNNING/STOPPED/TERMINATED`。
- impl：**`JobImpl`**（`run(foreground)`/`suspend()`/`terminate()`/前后台切换）、**`ProcessImpl`**（`run(fg)`/`interrupt()`/`terminate(...)`/`updateStatus()`）、**`JobControllerImpl`**（`createJob()`/`createProcess()` 处理管道重定向/`checkPermission()`）、**`InternalCommandManager`**（`getCommand(name)`/`complete(...)`/`findLastPipe()`）、**`GlobalJobControllerImpl`**。

### `shell/impl` · 服务端实现
- **`ShellServerImpl`** — 主实现：`registerTermServer()`、**`handleTerm(term)`**（建 `ShellImpl`）、`listen()`、`evictSessions()`（清超时会话）。
- **`ShellImpl`** — 单会话：`createJob()`、`readline()`、`init()`（设中断/关闭处理器）、`statusLine()`、`setForegroundJob()`。
- **`BuiltinCommandResolver`** — 解析 `jobs`/`fg`/`bg`/`kill` 等 Shell 内置命令。

### `shell/command`(+impl/internal) · 命令抽象与管道
- **`Command`** — 命令基类。**`create(clazz)`**（从注解类创建）、`name()`、`processHandler()`、`complete(...)`。
- **`CommandProcess`** — 命令执行上下文接口。`args()`/`commandLine()`/`session()`/`write(data)`/`end(status)`。
- **`AnnotatedCommand`** — 注解命令标记接口。
- **`CommandRegistry`** — `registerCommand()`/`registerCommands()`/`unregisterCommand()`。
- **`CommandResolver`** — 命令查找接口（`BuiltinCommandPack` 实现它）。
- impl：`AnnotatedCommandImpl`、`CommandBuilderImpl`、`ShellInternalCommandResolver`。
- internal（管道/重定向内部命令）：**`GrepHandler`**（`apply(input)`/`inject(tokens)`）、**`RedirectHandler`**（`apply(data)`/`close()`）、**`TeeHandler`**、**`WordCountHandler`**、`StatisticsFunction`、`PlainTextHandler`、`StdoutHandler`、`CloseFunction`。

### `shell/cli`(+impl) · 命令行解析与补全
- **`CliToken`** / **`CliTokens`** — 命令行标记；`CliTokens.tokenize(line)` 分词。
- **`Completion`** — 补全接口：`session()`/`lineTokens()`/`complete(candidates)`。
- **`CompletionUtils`** — `completeClassName()`/`completeMethodName()`/`completeFilePath()`/`findLongestCommonPrefix()`。
- **`OptionCompleteHandler`** — 选项补全。
- impl：`CliTokenImpl`。

### `shell/session`(+impl) · 会话状态
- **`Session`** — `put/get`、`getSessionId()`、`getPid()`、`tryLock()`。
- **`SessionManager`** — 管理多会话。
- impl：**`SessionImpl`**（`ConcurrentHashMap` 存数据、`tryLock()`/`unLock()`、`getForegroundJob()`）、`SessionManagerImpl`。

### `shell/history`(+impl) · 命令历史
- **`HistoryManager`** — `addHistory(cmd)`/`getHistory()`/`saveHistory()`/`loadHistory()`。
- impl：`HistoryManagerImpl`。

### `shell/handlers/*` · 事件处理器（`Handler<T>` 接口的各类实现）
- 根级：**`Handler<T>`**（`handle(event)`）、`BindHandler`、`NoOpHandler`。
- `handlers/shell`：**`ShellLineHandler`**（处理命令行 + 内置 `jobs/fg/bg/kill/exit`）、`InterruptHandler`(Ctrl+C)、`SuspendHandler`(Ctrl+Z)、`CloseHandler`、`CommandManagerCompletionHandler`、`QExitHandler`、`FutureHandler`、`ShellForegroundUpdateHandler`。
- `handlers/server`：`TermServerListenHandler`、`TermServerTermHandler`（新终端连接）、`SessionClosedHandler`、`SessionsClosedHandler`。
- `handlers/command`：`CommandInterruptHandler`（命令执行中中断）。
- `handlers/term`：`EventHandler`、`RequestHandler`、`CloseHandlerWrapper`、`SizeHandlerWrapper`、`StdinHandlerWrapper`、`DefaultTermStdinHandler`。

### `shell/term`(+impl/http/httptelnet) · 终端与接入方式 ⭐
**这是多接入方式的核心，也是 Web Console / tunnel 对接的关键。**

- 抽象：**`Term`**（`readline()`/`echo()`/`interruptHandler()`/...）、**`TermServer`**（`createTelnetTermServer()`/`createHttpTermServer()`/`listen()`/`close()`）、`Tty`、`SignalHandler`。
- impl：**`TermImpl`**（基于 termd，`readline()`/`stdinHandler()`/`stdoutHandler()`/`handleIntr()`/`handleSusp()`）、**`TelnetTermServer`**、**`HttpTermServer`**、`CompletionAdaptor`、`CompletionHandler`、`FunctionInvocationHandler`、`Helper`（keymap）。
- **`term/impl/http`**（Web Console / WebSocket）：
  - **`NettyWebsocketTtyBootstrap`** — WebSocket 服务器启动（同时监听网络端口 + 本地 VM 内地址）。
  - `TtyServerInitializer` / `LocalTtyServerInitializer` — Netty pipeline。
  - `TtyWebSocketFrameHandler` — WebSocket 帧处理。
  - `ExtHttpTtyConnection` — 带 session 的 HTTP Tty 连接。
  - `HttpRequestHandler`、`DirectoryBrowser`（静态资源）、`BasicHttpAuthenticatorHandler`（HTTP Basic 认证）。
- **`term/impl/http/api`**（RESTful API，Web Console 后端）⭐：
  - **`HttpApiHandler`** — HTTP API 总入口：`processExecRequest()`（同步执行）、`processAsyncExecRequest()`（异步）、`processPullResultsRequest()`（拉结果）、`processInitSessionRequest()`/`processJoinSessionRequest()`（多客户端共享会话）。
  - `ApiRequest`/`ApiResponse`/`ApiAction`/`ApiState`/`ApiException`/`ObjectVOFilter`。
- **`term/impl/http/session`**：`HttpSession`、`HttpSessionManager`（`getHttpSessionFromContext()`）。
- **`term/impl/httptelnet`**：http-telnet 桥接。

### `shell/future` · 异步
- **`Future<T>`** — `complete(result)`/`fail(t)`/`setHandler(...)`/`isComplete()`/`succeeded()`/`failed()`。

---

## 四、设计要点

1. **统一抽象多接入**：Telnet/HTTP/WebSocket 都实现 `Term`/`TermServer`，上层无感知。
2. **Job/Process 模型**：支持前台/后台、挂起/恢复、管道、重定向（类 Unix shell 体验）。
3. **会话可共享**：HTTP API 的 `joinSession` 让多个客户端（如 Web Console + tunnel）共享同一 arthas 会话。
4. **权限集成**：`JobControllerImpl.checkPermission()` 接 [`security`](./core-入口与全局选项.md)。
5. **补全完善**：命令/类名/方法名/文件路径补全。

## 定位提示

> "命令行输入怎么变成命令调用的？" → `handlers/shell/ShellLineHandler` → `impl/ShellImpl.createJob()`。
> "Web Console / HTTP API 后端在哪？" → `term/impl/http/api/HttpApiHandler`。
> "`watch | grep xxx` 管道怎么实现的？" → `system/impl/JobControllerImpl.createProcess()` + `command/internal/GrepHandler`。
> "Ctrl+C 中断命令处理在哪？" → `handlers/command/CommandInterruptHandler`。
> "telnet/http 服务怎么启动的？" → `impl/ShellServerImpl.listen()` → `term/impl/TelnetTermServer`/`HttpTermServer`。
