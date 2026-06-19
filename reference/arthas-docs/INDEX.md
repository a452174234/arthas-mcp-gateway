# INDEX · 快查索引

> 给大模型 / code agent 的**功能 → 源码文件**速查表。所有路径相对于 arthas 源码根目录 `reference/arthas/`。
> 命名规则：用户命令 `xxx` → 命令类 `XxxCommand`（`core/command/<组>/`）；监听器 `XxxAdviceListener`；数据模型 `XxxModel`（`core/command/model/`）；MCP 工具 `XxxTool`（`core/mcp/tool/function/<组>/`）。

---

## 一、命令速查（按用户命令名）

### 监控诊断（`monitor200`，走字节码增强）

| 命令 | 命令类 | 监听器 / 依赖 | Model / View |
|---|---|---|---|
| `watch` | `command/monitor200/WatchCommand` | `WatchAdviceListener` | `WatchModel` / `command/view/WatchView` |
| `trace` | `command/monitor200/TraceCommand` | `TraceAdviceListener`/`PathTraceAdviceListener` | `TraceModel`/`TraceTree` |
| `stack` | `command/monitor200/StackCommand` | `StackAdviceListener` | `StackModel` |
| `monitor` | `command/monitor200/MonitorCommand` | `MonitorAdviceListener` | `MonitorModel` |
| `tt` | `command/monitor200/TimeTunnelCommand` | `TimeTunnelAdviceListener` | `TimeTunnelModel`/`TimeFragmentVO` |
| `line` | `command/monitor200/LineCommand` | `LineCommandAdviceListener` | `LineListModel` |
| `dashboard` | `command/monitor200/DashboardCommand` | `ThreadSampler`/MXBean | `DashboardModel` |
| `thread` | `command/monitor200/ThreadCommand` | `ThreadUtil`/`ThreadSampler` | `ThreadModel`/`ThreadVO`/`BusyThreadInfo` |
| `jvm` | `command/monitor200/JvmCommand` | 各类 MXBean | `JvmModel`/`JvmItemVO` |
| `memory` | `command/monitor200/MemoryCommand` | `MemoryPoolMXBean` | `MemoryModel`/`MemoryEntryVO` |
| `heapdump` | `command/monitor200/HeapDumpCommand` | `HotSpotDiagnosticMXBean` | `HeapDumpModel` |
| `profiler` | `command/monitor200/ProfilerCommand` | `one/profiler/AsyncProfiler` | `ProfilerModel` |
| `mbean` | `command/monitor200/MBeanCommand` | — | — |
| `perfcounter` | `command/monitor200/PerfCounterCommand` | — | — |
| `vmtool` | `command/monitor200/VmToolCommand` | JNI `VmTool` | — |

> 这些命令的统一基类：`command/monitor200/EnhancerCommand`（`enhance()` 入口）。
> 字节码增强底层见 [字节码增强](./02-核心运行时/core/core-字节码增强.md)。

### 类与字节码（`klass100`，直接用 Instrumentation）

| 命令 | 命令类 | 关键依赖 | Model |
|---|---|---|---|
| `sc` | `command/klass100/SearchClassCommand` | `SearchUtils.searchClass()` | `SearchClassModel`/`ClassDetailVO` |
| `sm` | `command/klass100/SearchMethodCommand` | `getDeclaredMethods()` | `SearchMethodModel`/`MethodVO` |
| `jad` | `command/klass100/JadCommand` | `ClassDumpTransformer` + CFR | `JadModel` |
| `dump` | `command/klass100/DumpClassCommand` | `ClassDumpTransformer` + `retransformClasses` | `DumpClassModel` |
| `getstatic` | `command/klass100/GetStaticCommand` | 反射 + `ExpressFactory` | `GetStaticModel` |
| `ognl` | `command/klass100/OgnlCommand` | `ExpressFactory.unpooledExpress()` | `OgnlModel` |
| `mc` | `command/klass100/MemoryCompilerCommand` | [`memorycompiler/DynamicCompiler`](./02-核心运行时/memorycompiler.md) | `MemoryCompilerModel` |
| `classloader` | `command/klass100/ClassLoaderCommand` | `getAllLoadedClasses()` | `ClassLoaderModel`/`ClassLoaderVO` |
| `redefine` | `command/klass100/RedefineCommand` | `redefineClasses(ClassDefinition)` | `RedefineModel` |
| `retransform` | `command/klass100/RetransformCommand` | `RetransformClassFileTransformer` | `RetransformModel` |

### 基础命令（`basic1000`）

| 命令 | 命令类 | 说明 |
|---|---|---|
| `help` | `command/basic1000/HelpCommand` | 命令列表/帮助 |
| `options` | `command/basic1000/OptionsCommand` | → [`GlobalOptions`](./02-核心运行时/core/core-入口与全局选项.md) |
| `reset` | `command/basic1000/ResetCommand` | 撤销增强 → `Enhancer.reset()` |
| `stop` | `command/basic1000/StopCommand` | 关闭 arthas |
| `auth` | `command/basic1000/AuthCommand` | 登录 → [`security`](./02-核心运行时/core/core-入口与全局选项.md) |
| `session`/`history`/`version`/`keymap`/`cls`/`pwd`/`echo` | `command/basic1000/*Command` | 会话/历史/版本/快捷键/清屏/目录/回显 |
| `cat`/`grep`/`tee`/`base64` | `command/basic1000/*Command` | 文件/过滤/分流/编码（grep 管道另见 `shell/command/internal/GrepHandler`） |
| `sysprop`/`sysenv`/`vmoption` | `command/basic1000/System*Command`/`VMOptionCommand` | 系统属性/环境变量/JVM 选项 |
| `jfr` | `command/basic1000/JFRCommand` | JDK11+，动态加载 |

### 日志（`logger`）

| 命令 | 命令类 | Helper |
|---|---|---|
| `logger` | `command/logger/LoggerCommand` | `LogbackHelper`/`Log4jHelper`/`Log4j2Helper`/`LoggerHelper`/`AsmRenameUtil` |

### 隐藏（`hidden`）

`july`→`JulyCommand`、`thanks`→`ThanksCommand`。

---

## 二、MCP 工具速查（`core/mcp/tool/function/`）

| 工具类 | 对应命令 | 组 |
|---|---|---|
| `WatchTool` | `watch`（streamable, taskSupport=OPTIONAL） | `monitor200` |
| `TraceTool`/`StackTool`/`MonitorTool`/`TimeTunnelTool`/`ProfilerTool` | `trace`/`stack`/`monitor`/`tt`/`profiler` | `monitor200` |
| `JadTool`/`SearchClassTool`/`SearchMethodTool`/`ClassLoaderTool`/`DumpClassTool`/`MemoryCompilerTool`/`RedefineTool`/`RetransformTool` | `jad`/`sc`/`sm`/`classloader`/`dump`/`mc`/`redefine`/`retransform` | `klass100` |
| `DashboardTool`/`JvmTool`/`ThreadTool`/`MemoryTool`/`SysPropTool`/`SysEnvTool`/`VMOptionTool`/`VMToolTool`/`OgnlTool`/`GetStaticTool`/`MBeanTool`/`HeapdumpTool`/`PerfCounterTool` | `dashboard`/`jvm`/`thread`/`memory`/`sysprop`/`sysenv`/`vmoption`/`vmtool`/`ognl`/`getstatic`/`mbean`/`heapdump`/`perfcounter` | `jvm300` |
| `VersionTool`/`OptionsTool`/`StopTool`/`ViewFileTool` | `version`/`options`/`stop`/`cat` | `basic1000` |

> 工具基类：`core/.../core/mcp/tool/function/AbstractArthasTool`。详见 [core-mcp桥接](./03-MCP/core-mcp桥接.md)。
>
> ⚠️ 包路径纠偏：core 侧 MCP 实际包是 `com.taobao.arthas.core.mcp`（含 `.core`），本文档部分处简写为 `core/.../mcp/`，实际应为 `core/.../core/mcp/`。

---

## 三、"我想做 X" → 去哪看

| 我想… | 去这里 |
|---|---|
| 看某命令怎么实现 | 上方"命令速查" → 命令类的 `process()` |
| 理解 `watch`/`trace` 的字节码魔法 | [字节码增强](./02-核心运行时/core/core-字节码增强.md)（`advisor/Enhancer` + `SpyImpl`） |
| 改 OGNL 求值行为 | `command/express/ExpressFactory` / `OgnlExpress` |
| 改命令结果展示 | `command/model/*`（数据）+ `command/view/*`（渲染）+ `core/view`（引擎） |
| 理解启动/attach | [启动与attach](./01-启动与attach/README.md) |
| 理解命令调度/管道/补全 | [Shell 交互系统](./02-核心运行时/core/core-shell交互系统.md) |
| 改全局开关（`options`） | `core/GlobalOptions` |
| 改认证方式 | `core/security/SecurityAuthenticatorImpl` |
| 理解结果多路分发 | `core/distribution/impl/SharingResultDistributorImpl` |
| 看 Web Console / HTTP API 后端 | `core/shell/term/impl/http/api/HttpApiHandler` |
| 给 arthas 加 MCP 工具 | [core-mcp桥接](./03-MCP/core-mcp桥接.md) |
| 改 MCP 协议/任务机制 | [arthas-mcp-server](./03-MCP/arthas-mcp-server.md) |
| **开发 arthas MCP 网关（遇问题先查）** | **[问题定位反向索引](./03-MCP/问题定位反向索引.md)** |
| **摘抄 arthas MCP 工具定义（能力清单）** | **[MCP能力清单](./03-MCP/MCP能力清单.md)** |
| **网关↔Claude Code / ↔arthas 后端契约** | **[MCP线契约](./03-MCP/MCP线契约.md)**、**[后端接入契约](./03-MCP/后端接入契约.md)** |
| **逐工具决定转发模式（路由速查）** | **[工具传输分类表](./03-MCP/工具传输分类表.md)** |
| 改 `profiler` 底层（火焰图） | `one/profiler/AsyncProfiler` |
| 加内存编译能力 | `memorycompiler/DynamicCompiler` |
| 看 reset 怎么撤销增强 | `advisor/Enhancer.reset()` + `advisor/TransformerManager` |

---

## 四、关键类 → 文件路径（一键跳源码）

> 以下路径均相对 `reference/arthas/`。

### 启动 / Spy
- `arthas-boot` 入口 → `boot/src/main/java/com/taobao/arthas/boot/Bootstrap.java`
- 选进程/拉起 core → `boot/.../boot/ProcessUtils.java`
- Java Agent 引导 → `agent/src/main/java/com/taobao/arthas/agent334/AgentBootstrap.java`
- 隔离类加载器 → `agent/.../agent/ArthasClassloader.java`
- 编程式 attach → `arthas-agent-attach/.../attach/ArthasAgent.java`
- Spy 钩子接口 → `spy/src/main/java/java/arthas/SpyAPI.java`

### core 入口与配置
- 核心单例 → `core/.../server/ArthasBootstrap.java`
- attach 入口 → `core/.../Arthas.java`
- 全局开关 → `core/.../GlobalOptions.java`
- ClassLoader 增强 → `core/.../server/instrument/ClassLoader_Instrument.java`
- 认证 → `core/.../security/SecurityAuthenticatorImpl.java`
- 启动配置 → `core/.../config/Configure.java`

### 字节码增强（`core/advisor`）
- 增强入口 → `core/.../advisor/Enhancer.java`
- 监听器注册表 → `core/.../advisor/AdviceWeaver.java`
- 监听器接口 → `core/.../advisor/AdviceListener.java`
- 监听器适配基类 → `core/.../advisor/AdviceListenerAdapter.java`
- 类/方法→监听器映射 → `core/.../advisor/AdviceListenerManager.java`
- 调用上下文 → `core/.../advisor/Advice.java`
- 运行时回调入口 → `core/.../advisor/SpyImpl.java`
- ByteKit 拦截器 → `core/.../advisor/SpyInterceptors.java`
- 转换器管理 → `core/.../advisor/TransformerManager.java`

### Shell（`core/shell`）
- 命令行处理 → `core/.../shell/handlers/shell/ShellLineHandler.java`
- Shell 服务端 → `core/.../shell/impl/ShellServerImpl.java`
- Job/Process → `core/.../shell/system/impl/JobControllerImpl.java`、`ProcessImpl.java`、`JobImpl.java`
- 命令基类 → `core/.../shell/command/Command.java`
- 命令上下文 → `core/.../shell/command/CommandProcess.java`
- 管道内部命令 → `core/.../shell/command/internal/GrepHandler.java`
- HTTP/WebSocket → `core/.../shell/term/impl/http/NettyWebsocketTtyBootstrap.java`
- HTTP API → `core/.../shell/term/impl/http/api/HttpApiHandler.java`

### 命令系统（`core/command`）
- 命令注册 → `core/.../command/BuiltinCommandPack.java`
- 命令执行引擎 → `core/.../command/CommandExecutorImpl.java`
- OGNL → `core/.../command/express/ExpressFactory.java`
- 监控命令基类 → `core/.../command/monitor200/EnhancerCommand.java`

### 支撑（`core`）
- 结果分发 → `core/.../distribution/impl/SharingResultDistributorImpl.java`
- 匹配器 → `core/.../util/matcher/WildcardMatcher.java`、`RegexMatcher.java`
- 视图引擎 → `core/.../view/TableView.java`、`Ansi.java`
- async-profiler → `core/src/main/java/one/profiler/AsyncProfiler.java`

### MCP
- mcp-server HTTP 入口 → `arthas-mcp-server/.../protocol/server/handler/McpHttpRequestHandler.java`
- 协议类型 → `arthas-mcp-server/.../protocol/spec/McpSchema.java`
- 任务机制 → `arthas-mcp-server/.../task/ServerTaskToolHandler.java`、`InMemoryTaskStore.java`
- 工具注解/扫描 → `arthas-mcp-server/.../tool/annotation/Tool.java`、`.../tool/DefaultToolCallbackProvider.java`
- 桥接接口 → `arthas-mcp-server/.../CommandExecutor.java`（core 的 `CommandExecutorImpl` 实现）
- arthas MCP 启动 → `core/.../core/mcp/ArthasMcpBootstrap.java`、`ArthasMcpServer.java`
- 工具基类 → `core/.../core/mcp/tool/function/AbstractArthasTool.java`

### 共享库
- 通用工具 → `common/src/main/java/com/taobao/arthas/common/`（`PidUtils`/`OSUtils`/`ReflectUtils`/`SocketUtils`/`UnsafeUtils`…）
- 内存编译器 → `memorycompiler/.../compiler/DynamicCompiler.java`
- Telnet 客户端 → `client/.../client/TelnetConsole.java`

---

## 五、四条核心链路（再回顾）

- **启动**：`boot/Bootstrap.main` → `ProcessUtils.startArthasCore` → `agent/.../AgentBootstrap.agentmain` → 反射 `core/.../ArthasBootstrap.getInstance` → `initSpy` → `bind`。
- **命令**：`shell` 解析 → `command/XxxCommand.process` → （监控类）`EnhancerCommand.enhance` → `advisor/Enhancer`。
- **增强回调**：业务方法 → `spy/SpyAPI` → `advisor/SpyImpl` → `AdviceListenerManager` → `XxxAdviceListener` → `distribution` → `view`。
- **MCP**：HTTP `/mcp` → `arthas-mcp-server/.../McpHttpRequestHandler` → `ServerTaskToolHandler` → `tool/DefaultToolCallback` → `core/.../core/mcp/.../XxxTool` → `CommandExecutorImpl`。

---

完整目录与学习路径见 [README.md](./README.md)。
