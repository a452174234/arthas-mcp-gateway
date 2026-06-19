# core · 命令系统

> 覆盖源码包：`core/command/**`（`basic1000`、`klass100`、`monitor200`、`logger`、`express`、`hidden`、`model`、`view` 及根级骨架类）
> 解决：**用户敲的每一条命令（`watch`/`jad`/`thread`…）源码在哪、怎么注册、怎么执行。**

> 📌 想直接查"某命令 → 源码文件"，看 [INDEX.md](../../INDEX.md) 的速查表。

---

## 一、命令骨架（根级）

### `BuiltinCommandPack`
`core/.../command/BuiltinCommandPack.java` — **内置命令注册中心**，实现 `CommandResolver`。

- 在 `initCommands(disabledCommands)` 中**硬编码**所有内置命令类（`HelpCommand.class`、`WatchCommand.class`… 共 40+），通过 `Command.create()` 实例化，并按 `@Name` 注解过滤被禁用的命令。
- `commands()` — 返回所有可用命令，供 Shell 调度。

### `CommandExecutorImpl`
`core/.../command/CommandExecutorImpl.java` — **命令执行引擎**（实现 MCP 的 `CommandExecutor` 接口），提供同步/异步执行、会话管理、结果分发。

- `executeSync(commandLine, timeout, sessionId, authSubject, userId)` — 同步执行（等完成或超时）。
- `executeAsync(commandLine, sessionId)` — 异步执行，返回 jobId。
- `pullResults(sessionId, consumerId)` — 拉取异步命令输出（MCP/HTTP API 用）。
- `interruptJob(sessionId)` — 中断前台任务。
- `createSession(quiet)` / `closeSession(sessionId)` / `setSessionAuth(...)` / `setSessionUserId(...)` — 会话生命周期。

> 这是 MCP / HTTP API 执行 arthas 命令的入口（见 [`core-mcp桥接`](../../03-MCP/core-mcp桥接.md)）。

### `ScriptSupportCommand`
`core/.../command/ScriptSupportCommand.java` — 脚本类命令（如 `tt`、Groovy）的扩展接口。定义 `ScriptListener`（`create`/`before`/`afterReturning`/`afterThrowing`）与 `Output`（`print`/`println`/`finish`）。

### `Constants`
`core/.../command/Constants.java` — 公共文案常量。`EXPRESS_DESCRIPTION`（说明 `target`/`params`/`returnObj`/`throwExp` 上下文变量）、`EXPRESS_EXAMPLES`、`CONDITION_EXPRESS`。

---

## 二、`basic1000` · 基础命令

`core/.../command/basic1000/` — 不涉及字节码增强的工具/系统命令。

| 命令类 | 用户命令 | 作用 |
|---|---|---|
| `HelpCommand` | `help` | 命令列表与单命令帮助 |
| `AuthCommand` | `auth` | 会话内登录认证 |
| `OptionsCommand` | `options` | 查看/设置全局选项（→ [`GlobalOptions`](./core-入口与全局选项.md)） |
| `ResetCommand` | `reset` | 重置已增强的类 |
| `StopCommand` | `stop` | 关闭 arthas |
| `SessionCommand` | `session` | 当前会话信息 |
| `HistoryCommand` | `history` | 命令历史 |
| `VersionCommand` | `version` | 版本 |
| `CatCommand` | `cat` | 查看文件 |
| `GrepCommand` | `grep` | 文本过滤（管道） |
| `TeeCommand` | `tee` | 输出分流 |
| `ClsCommand` | `cls` | 清屏 |
| `PwdCommand` | `pwd` | 当前目录 |
| `EchoCommand` | `echo` | 回显 |
| `Base64Command` | `base64` | Base64 编解码 |
| `KeymapCommand` | `keymap` | 快捷键 |
| `SystemPropertyCommand` | `sysprop` | 系统属性 |
| `SystemEnvCommand` | `sysenv` | 环境变量 |
| `VMOptionCommand` | `vmoption` | 查看/改 JVM 选项 |
| `JFRCommand` | `jfr` | Java Flight Recording（JDK11+，动态加载） |

> 入口统一为 `process(CommandProcess)`；输出走对应 `model/` 下的 VO + `command/view` 渲染。

---

## 三、`klass100` · 类与字节码命令

`core/.../command/klass100/` — 直接操作 `Instrumentation` / 反射 / 编译的命令。

| 命令类 | 用户命令 | 作用 / 关键依赖 |
|---|---|---|
| `SearchClassCommand` | `sc` | 搜索已加载类；`SearchUtils.searchClass()`；输出 `SearchClassModel`/`ClassDetailVO` |
| `SearchMethodCommand` | `sm` | 搜索方法；`getDeclaredMethods()`；`MethodVO` |
| `JadCommand` | `jad` | 反编译；`ClassDumpTransformer` + CFR；`JadModel` |
| `DumpClassCommand` | `dump` | dump 字节码到文件；`ClassDumpTransformer` + `retransformClasses` |
| `GetStaticCommand` | `getstatic` | 查看静态字段；反射 `field.get(null)` + OGNL |
| `OgnlCommand` | `ognl` | 执行 OGNL；`ExpressFactory.unpooledExpress(loader).bind(obj).get(expr)` |
| `MemoryCompilerCommand` | `mc` | 内存编译；[`memorycompiler/DynamicCompiler`](../memorycompiler.md) |
| `ClassLoaderCommand` | `classloader` | 查看 ClassLoader 树/统计；`getAllLoadedClasses()` |
| `RedefineCommand` | `redefine` | 热更新；`inst.redefineClasses(ClassDefinition)` |
| `RetransformCommand` | `retransform` | 重转换；注册 `RetransformClassFileTransformer` + `retransformClasses`；支持 `-l`/`-d`/`--delete-all` |
| `ClassLoaderMetaspaceCommand` | classloader metaspace | Metaspace 管理（JDK11+，动态加载） |
| `ClassDumpTransformer` | （辅助） | retransform 时把字节码 dump 到文件 |

> 这些命令**不继承** `EnhancerCommand`，多数直接调 `Instrumentation`。

---

## 四、`monitor200` · 监控诊断命令（核心）

`core/.../command/monitor200/` — arthas 最核心的功能，监控类命令大多走字节码增强。

### 4.1 增强命令基类

- **`EnhancerCommand`** — **抽象基类**（不直接暴露为命令）。所有监控命令（watch/trace/stack/monitor/line/tt）继承它。
  - `process(process)` — 注册中断/q 退出处理器 → 调 `enhance(process)`。
  - **`enhance(process)`** — 核心：创建 `AdviceListener` → 构造 [`advisor/Enhancer`](./core-字节码增强.md) → `enhancer.enhance(inst, maxNumOfMatchedClass)` → 输出 `EnhancerModel`。

### 4.2 监控命令与各自的 AdviceListener

| 命令类 | 用户命令 | AdviceListener | 作用 |
|---|---|---|---|
| `WatchCommand` | `watch` | `WatchAdviceListener` | 方法调用数据观测；OGNL 求值 |
| `TraceCommand` | `trace` | `TraceAdviceListener` / `PathTraceAdviceListener`(`-p`) | 调用路径与耗时 |
| `StackCommand` | `stack` | `StackAdviceListener` | 方法调用栈 |
| `MonitorCommand` | `monitor` | `MonitorAdviceListener` | 调用次数/成功率/RT 统计（定时） |
| `LineCommand` | `line` | `LineCommandAdviceListener` | 源码行级观测（局部变量）；依赖 `LineEnhanceOptions` |
| `TimeTunnelCommand` | `tt` | `TimeTunnelAdviceListener` | 时光隧道：记录/重放调用现场 |

> `tt` 子功能多：`-t` 记录、`-l` 列表、`-i N` 查看、`-i N -w expr` OGNL、`-i N -p` 重放、`-s expr` 搜索、`--delete-all` 清空。

### 4.3 系统信息命令

| 命令类 | 用户命令 | 作用 |
|---|---|---|
| `DashboardCommand` | `dashboard` | 实时大盘（线程/内存/GC/Tomcat），定时刷新 |
| `ThreadCommand` | `thread` | 线程列表/栈/最忙N个(`-n`)/阻塞(`-b`)/按状态过滤 |
| `JvmCommand` | `jvm` | 各类 MXBean 汇总 |
| `MemoryCommand` | `memory` | 内存池信息 |
| `HeapDumpCommand` | `heapdump` | `HotSpotDiagnosticMXBean.dumpHeap` |
| `ProfilerCommand` | `profiler` | async-profiler 集成（火焰图/JFR）；底层 [`one/profiler/AsyncProfiler`](./core-支撑层.md) |
| `MBeanCommand` | `mbean` | MBean 管理 |
| `PerfCounterCommand` | `perfcounter` | 性能计数器 |
| `VmToolCommand` | `vmtool` | VM 工具（JNI，`gcl`/查对象等） |

### 4.4 监控命令统一执行流程（以 `watch` 为例）

1. `WatchCommand.process()` → `EnhancerCommand.enhance()`
2. 创建 `WatchAdviceListener` → 构造 `Enhancer(listener, classMatcher, methodMatcher)`
3. `Enhancer.enhance(inst, ...)`：搜匹配类 → 过滤 → 织入 [`spy/SpyAPI`](../../01-启动与attach/spy.md) 调用 → 注册监听器到 `AdviceListenerManager` → `retransformClasses`
4. 目标方法运行时回调 → `WatchAdviceListener.before/afterReturning/afterThrowing`
5. `ExpressFactory.threadLocalExpress(advice).get(expr)` 求值 → `output.println()` → 转 `WatchModel` → `WatchView` 渲染

> 详细的字节码织入与回调见 [`core-字节码增强.md`](./core-字节码增强.md)。

---

## 五、`logger` · 动态日志级别

`core/.../command/logger/`

- **`LoggerCommand`** (`logger`) — 查看/更新日志级别。无参列全部；`-n name -l level` 更新。
- **`LogbackHelper`** / **`Log4jHelper`** / **`Log4j2Helper`** — 各框架的具体操作 helper。
- **`LoggerHelper`** — 通用 helper 接口。
- **`AsmRenameUtil`** — 用 ASM 改 helper 类名，避免在目标 ClassLoader 里类冲突。

> 机制：动态生成 helper 类（持有目标 logger 引用），反射调用其 `getLoggers()`/`updateLevel()`，从而避免 arthas 直接依赖用户的日志框架版本。

---

## 六、`express` · OGNL 表达式引擎

`core/.../command/express/` — `watch`/`trace`/`tt`/`ognl` 等命令的条件与求值底座。

- **`Express`** — 引擎接口：`get(expr)`、`is(expr)`、`bind(obj)`、`reset()`。
- **`ExpressFactory`** — 工厂。
  - `threadLocalExpress(obj)` — ThreadLocal 复用实例（高频调用场景，避免重复创建；用 `WeakReference` 断开与 ArthasClassloader 的强引用）。
  - `unpooledExpress(loader)` — 非池化实例（`ognl` 命令用）。
- **`OgnlExpress`** — 基于 ognl 库的实现。
- **`ClassLoaderClassResolver`** / **`CustomClassResolver`** — ClassLoader 类解析。
- **`DefaultMemberAccess`** — 成员访问控制（决定能否访问 private）。
- **`ArthasObjectPropertyAccessor`** — 对象属性访问器。
- **`ExpressException`** — 异常。

> `strict` 模式（`GlobalOptions.strict`）控制是否允许调用任意方法/访问私有成员。

---

## 七、`hidden` · 隐藏命令

`core/.../command/hidden/` — 彩蛋：`JulyCommand`(`july`)、`ThanksCommand`(`thanks`)。

---

## 八、`model` · 数据模型 / VO

`core/.../command/model/` — 100+ 个结果模型类，与 `command/view` 配合做结构化输出。

典型（按命令）：
- `watch` → `WatchModel`
- `trace` → `TraceModel`/`TraceNode`/`TraceTree`
- `stack` → `StackModel`
- `monitor` → `MonitorModel`
- `thread` → `ThreadModel`/`ThreadVO`/`BusyThreadInfo`/`BlockingLockInfo`
- `dashboard` → `DashboardModel`
- `jvm` → `JvmModel`/`JvmItemVO`
- `tt` → `TimeTunnelModel`/`TimeFragmentVO`
- `jad` → `JadModel`；`sc` → `SearchClassModel`/`ClassVO`/`ClassDetailVO`；`sm` → `SearchMethodModel`/`MethodVO`
- 通用：`RowAffectModel`（影响统计）、`MessageModel`、`HelpModel`/`CommandVO`/`CommandOptionVO`

> 这些是纯数据类。查找规则：命令名 → `XxxModel`。

---

## 九、`view` · 命令视图渲染

`core/.../command/view/` — 70+ 个 View 类，把 Model 渲染成终端输出。

- **`ResultView`** / **`ResultViewResolver`** — 按 Model 类型自动选对应 View。
- **`ViewRenderUtil`** — 渲染工具。
- 各命令专用：`WatchView`/`TraceView`/`StackView`/`MonitorView`/`ThreadView`/`DashboardView`/`JvmView`/`JadView`/`HelpView`…

> 底层表格/树/ANSI 引擎在 [`core/view`](./core-支撑层.md)（`TableView`/`TreeView`/`Ansi`）。

---

## 十、命令注册与执行总览

```
注册：BuiltinCommandPack.initCommands() 硬编码命令类
       → Command.create(clazz) 实例化（按 @Name 过滤禁用项）
       → 通过 CommandResolver.commands() 暴露给 Shell

执行（交互）：Shell 解析命令行 → WatchCommand.process()
执行（API/MCP）：CommandExecutorImpl.executeSync/executeAsync()
                  → 同样进入命令 process()
```

## 定位提示

> "我想改 `watch` 命令的行为" → `monitor200/WatchCommand` + `monitor200/WatchAdviceListener`。
> "`jad` 反编译用的什么库？" → `klass100/JadCommand` → CFR（`ClassDumpTransformer` dump 后反编译）。
> "OGNL 表达式在哪求值？" → `express/ExpressFactory.threadLocalExpress(advice).get(expr)`。
> "命令结果怎么变成终端表格的？" → `command/model/*`（数据）+ `command/view/*`（渲染）+ [`core/view`](./core-支撑层.md)（引擎）。
