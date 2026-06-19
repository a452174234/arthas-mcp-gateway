# client 模块 · Telnet 客户端

> 路径：`client/src/main/java/`
> 在整体中的位置：**本地 Telnet 客户端**。用户用它连上 arthas agent 暴露的 telnet 服务（默认 `127.0.0.1:3658`），进入交互式诊断。

## 职责

提供 arthas 的命令行客户端 `arthas-client.jar`。支持两种工作模式：

1. **交互模式**：连上后进入 REPL，手动输入命令；
2. **批量模式**：通过 `-c 'cmd1;cmd2'` 或 `-f batch.as` 一次性下发一批命令（CI/自动化场景常用）。

## 关键类（arthas 自有）

### `TelnetConsole`
`client/src/main/java/com/taobao/arthas/client/TelnetConsole.java` — **客户端主类**。基于 Apache Commons Net `TelnetClient` + JLine `ConsoleReader`。

- **`process(String[] args, ActionListener eotEventCallback)`** — 核心入口：解析参数 → 连服务器 → 启动双向 IO。
- `batchModeRun(TelnetClient, List<String> commands, int executionTimeout)` — 批量模式：逐条发命令，等待 `[arthas@...]` 提示符，支持超时。
- `setTargetIp/setPort/setCommand/setBatchFile/setExecutionTimeout` — CLI 参数（middleware-cli 注入）。
- `setHelp/setWidth/setHeight/setQuiet` — 显示相关参数。

### `IOUtil`
`client/src/main/java/com/taobao/arthas/client/IOUtil.java` — **双向 IO 中继**。

- `readWrite(remoteInput, remoteOutput, localInput, localOutput)` — 启动两个线程：reader（本地输入 → 远程）、writer（远程输入 → 本地输出，优先级 +1，reader 为 daemon）。

## 第三方代码（vendored）

### `org.apache.commons.net.*`
`client/src/main/java/org/apache/commons/net/**` — **内嵌的 Apache Commons Net Telnet 实现**（`TelnetClient`、`TelnetCommand`、`WindowSizeOptionHandler`、`SocketClient` 等）。

- **为什么内嵌**：让 `arthas-client.jar` 不依赖外部 jar 即可独立运行，简化用户使用。
- **处理建议**：阅读源码时无需逐类深究，它是标准的 telnet 协议实现（Apache License 2.0，头部保留 ASF 版权）。只有需要调试 telnet 协议细节（窗口大小协商、选项处理）时才需要进入。

## 与其它模块的关系

- 被 [`boot`](../01-启动与attach/boot.md) 的 `ProcessUtils.startArthasClient()` 调用。
- 依赖 [`common`](./common.md) 工具。

## 定位提示

> "arthas 客户端怎么连服务端、怎么跑批量命令？" → `client/.../TelnetConsole.process()` / `batchModeRun()`。
> "`org.apache.commons.net` 下是什么？" → 内嵌的 telnet 协议实现，第三方代码，通常不用看。
