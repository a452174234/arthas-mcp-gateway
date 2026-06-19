# boot 模块 · 命令行启动器

> 路径：`boot/src/main/java/com/taobao/arthas/boot/`
> 在整体中的位置：**最外层入口**。用户执行 `java -jar arthas-boot.jar` 时的 main 类就在这里。

## 职责

`boot` 是一个独立的可执行 jar（`arthas-boot.jar`），它**本身不诊断任何东西**，只负责：

1. 列出本机 Java 进程，让用户选一个目标；
2. 查找本地已下载的 arthas 目录，没有则从远程下载（按版本）；
3. 拉起一个 `arthas-core.jar` 进程，通过 attach API 把 arthas agent 注入目标 JVM；
4. 启动本地 Telnet 客户端连上 arthas 的 telnet 服务，进入交互界面。

它运行在**用户自己的 JVM**里，与目标 JVM 是两个独立进程。

## 关键类

### `Bootstrap`
`boot/src/main/java/com/taobao/arthas/boot/Bootstrap.java` — **命令行启动主类**（`main` 方法所在）。

- `main(String[] args)` — 入口：解析命令行参数 → 选 PID → 查找/下载 arthas → 启动 core 进程 → 启动 telnet 客户端。
- `setPid(long)` / `setTelnetPort(int)` / `setHttpPort(int)` / `setArthasHome(String)` / `setUseVersion(String)` — 通过 middleware-cli 注解注入的命令行参数（默认 telnet 3658 / http 8563）。

### `ProcessUtils`
`boot/src/main/java/com/taobao/arthas/boot/ProcessUtils.java` — **进程管理工具**。

- `select(boolean verbose, long telnetPortPid, String select)` — 交互式列出并选择目标 Java 进程。
- `startArthasCore(long targetPid, List<String> attachArgs)` — **核心**：启动 `arthas-core.jar` 进程并 attach 到目标 JVM。
- `startArthasClient(String arthasHomeDir, List<String> telnetArgs, OutputStream out)` — 启动 `arthas-client.jar` 连接 arthas 服务（即调用 [`client` 模块](../02-核心运行时/client.md)）。
- `findJavaHome()` — 定位 `JAVA_HOME`，处理 JDK8 的 `tools.jar`。
- `listProcessByJcmd()` / `listProcessByJps()` — 用 `jcmd`/`jps` 枚举 Java 进程（后者已废弃）。

### `DownloadUtils`
`boot/src/main/java/com/taobao/arthas/boot/DownloadUtils.java` — **arthas 下载工具**。

- `readLatestReleaseVersion()` — 读取远程最新版本号。
- `readRemoteVersions()` — 获取可用版本列表。
- `downArthasPackaging(repoMirror, version, savePath)` — 下载指定版本压缩包并解压。
- `saveUrl(filename, urlString, printProgress)` — HTTP 下载文件（带进度）。

## 与其它模块的关系

- 调用 [`client`](../02-核心运行时/client.md) 的 `TelnetConsole` 连接 telnet。
- 依赖 [`common`](../02-核心运行时/common.md) 的 `PidUtils`/`OSUtils`/`JavaVersionUtils` 等工具。
- 通过拉起 `arthas-core.jar` 间接进入 [`agent`](./agent.md) → [`core`](../02-核心运行时/core/README.md)。

## 定位提示

> "我想看 `arthas-boot.jar` 启动时怎么选进程、怎么把 arthas 挂上去" → `boot/.../Bootstrap.main()` + `ProcessUtils.startArthasCore()`。
