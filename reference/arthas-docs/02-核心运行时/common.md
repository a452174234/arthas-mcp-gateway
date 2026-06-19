# common 模块 · 全项目共享工具库

> 路径：`common/src/main/java/com/taobao/arthas/common/`（含 `concurrent` 子包）
> 在整体中的位置：**最底层的依赖**。被 `core`、`memorycompiler`、`client`、`agent` 等几乎所有模块依赖。

## 职责

提供与 arthas 业务无关、跨平台的基础工具：彩色日志、进程 PID、反射、OS/JDK 检测、文件/IO、端口探测、Unsafe 黑科技等。理解这些类有助于在 core 里看到它们时知道"在干嘛"。

## 关键类

### 日志与渲染
- **`AnsiLog`** (`common/.../AnsiLog.java`) — 基于 `java.util.logging` 的 ANSI 彩色日志。
  - `trace/debug/info/warn/error(msg)`、`level(Level)`、`out(PrintStream)`、`enableColor()`、`red/yellow/green/blue(msg)`。
- **`UsageRender`** (`common/.../UsageRender.java`) — CLI 帮助文本彩色渲染。`render(usage)`。

### 进程与运行环境
- **`PidUtils`** (`common/.../PidUtils.java`) — 当前 JVM PID 与主类名。`currentPid()` / `currentLongPid()` / `mainClass()`。
- **`OSUtils`** (`common/.../OSUtils.java`) — OS 与 CPU 架构检测（含 ARM/LoongArch/musl libc）。`isWindows()/isLinux()/isMac()`、`isArm64()/isX86_64()`、`isMuslLibc()`。
- **`JavaVersionUtils`** (`common/.../JavaVersionUtils.java`) — JDK 版本判断。`javaVersion()`、`isJava8()`、`isLessThanJava9()` 等。
- **`PlatformEnum`** (`common/.../PlatformEnum.java`) — OS 类型枚举（`OSUtils` 内部用）。

### 反射与字节码
- **`ReflectUtils`** (`common/.../ReflectUtils.java`) — 源自 Spring 的反射工具集，**支持运行时 defineClass**。
  - `findConstructor/findMethod(desc, classLoader)`、`newInstance(...)`、`getBeanProperties(...)`。
  - **`defineClass(className, byte[], classLoader, protectionDomain, contextClass)`** — 核心入口：运行时定义类，适配 JDK9+ `Lookup.defineClass` 与传统 `ClassLoader.defineClass`（见源码 `SPRING PATCH` 区块）。
- **`ReflectException`** (`common/.../ReflectException.java`) — 反射异常包装（`RuntimeException`）。

### 文件与 IO
- **`FileUtils`** (`common/.../FileUtils.java`) — 参考 Apache Commons IO。`writeByteArrayToFile(...)`、`readFileToByteArray(...)`、`openOutputStream(...)`。
- **`IOUtils`** (`common/.../IOUtils.java`) — 流处理 + 解压。`toString(InputStream)`、`getBytes(...)`、`copy(...)`、`close(...)`、**`unzip(zipFile, extractFolder)`**（带 ZipSlip 防护）。

### 网络与命令执行
- **`SocketUtils`** (`common/.../SocketUtils.java`) — TCP 端口探测。
  - `findAvailableTcpPort(min, max)`、`isTcpPortAvailable(port)`、**`findTcpListenProcess(port)`**（Windows `netstat -ano` / Unix `lsof`，高频被 `boot`/`core` 使用）。
- **`ExecutingCommand`** (`common/.../ExecutingCommand.java`) — `Runtime.exec` 封装。`runNative(cmd)`、`getFirstAnswer(cmd)`、`getAnswerAt(cmd, idx)`。

### 黑科技 / 常量 / 杂项
- **`UnsafeUtils`** (`common/.../UnsafeUtils.java`) — 拿 `sun.misc.Unsafe` 和 `MethodHandles.Lookup.IMPL_LOOKUP`（JDK17+ 绕模块限制用）。字段 `UNSAFE`、方法 `implLookup()`。
- **`VmToolUtils`** (`common/.../VmToolUtils.java`) — JNI 动态库名探测。`detectLibName()`（如 `libArthasJniLibrary-x64.so`）。
- **`ArthasConstants`** (`common/.../ArthasConstants.java`) — 全局常量：`TELNET_PORT=3658`、`MAX_HTTP_CONTENT_LENGTH`、`ASESSION_KEY`、`NETTY_LOCAL_ADDRESS` 等。
- **`Pair<X,Y>`** (`common/.../Pair.java`) — 不可变二元组。`make(a,b)`、`getFirst()/getSecond()`。

### concurrent 子包
- **`ConcurrentWeakKeyHashMap<K,V>`** (`common/.../concurrent/ConcurrentWeakKeyHashMap.java`) — 源自 Netty 的弱键并发 Map（分段锁），被 core 的 `AdviceListenerManager` 用来按 ClassLoader 缓存监听器、避免类加载器泄漏。`purgeStaleEntries()`。
- **`ReusableIterator<E>`** (`common/.../concurrent/ReusableIterator.java`) — 可回退迭代器（`rewind()`），弱键 Map 的迭代器实现此接口。

## 与其它模块的关系

- 几乎被所有模块 import；是 arthas 的"地基"。
- `boot`/`core` 启动期重度依赖 `PidUtils`/`OSUtils`/`JavaVersionUtils`/`SocketUtils`。
- `core` 的字节码增强依赖 `ReflectUtils.defineClass` / `UnsafeUtils`。

## 定位提示

> "arthas 怎么拿当前进程 PID？" → `PidUtils.currentPid()`。
> "怎么找占用某端口的进程？" → `SocketUtils.findTcpListenProcess(port)`。
> "JDK17 下怎么绕模块化做反射/defineClass？" → `UnsafeUtils.implLookup()` / `ReflectUtils.defineClass()`。
