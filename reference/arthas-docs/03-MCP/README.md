# 03 · MCP（Model Context Protocol）

本组回答：**arthas 如何把自己暴露给大模型 / Agent，让 AI 能调用 arthas 的诊断能力？**

arthas 的 MCP 实现是**双栈**结构——一个独立协议服务器模块 + core 内的命令桥接层。

### 源码导航（正查：理解 arthas MCP 如何实现）

| 文档 | 路径 | 角色 |
|---|---|---|
| [arthas-mcp-server](./arthas-mcp-server.md) | `arthas-mcp-server/` | 独立 MCP 协议服务器：JSON-RPC over HTTP/SSE，含会话、任务机制 |
| [core-mcp桥接](./core-mcp桥接.md) | `core/.../core/mcp/**` | 把 arthas 命令包装成 MCP 工具（`@Tool`），并启动/对接 mcp-server |

### 速查定位（为 **arthas MCP 网关**开发服务：摘抄/对接/定位）

> 下列 5 篇专为网关开发而建，全部以源码 `file:line` 为据，可直接作为网关实现的事实依据。

| 文档 | 用途 | 一句话 |
|---|---|---|
| [问题定位反向索引](./问题定位反向索引.md) | **遇问题快定位** | "我想做 X / 报错 Y" → 精确 `文件:方法:行号` |
| [MCP能力清单](./MCP能力清单.md) | **摘抄拷贝单一事实源** | 31 个工具全量定义（JSON Schema + streamable/taskSupport）；resource/prompt=0 |
| [MCP线契约](./MCP线契约.md) | **网关↔Claude Code 服务端契约** | 协议版本、initialize、tools、task、error 线格式 |
| [后端接入契约](./后端接入契约.md) | **网关↔arthas 后端客户端契约** | /mcp 端点、认证、会话 25min、task 并发 5、TTL 30min |
| [工具传输分类表](./工具传输分类表.md) | **逐工具路由速查** | 每个工具的 streamable/taskSupport/转发模式/结果形态 |

> **网关开发首选入口**：先看 [问题定位反向索引](./问题定位反向索引.md)（定位）+ [MCP能力清单](./MCP能力清单.md)（摘抄）；需要协议细节时查 [MCP线契约](./MCP线契约.md)/[后端接入契约](./后端接入契约.md)；写路由层时查 [工具传输分类表](./工具传输分类表.md)。

---

## 为什么要分两层

- **`arthas-mcp-server`** 是一个**通用的 MCP 协议实现**（参考 Spring AI MCP），负责 JSON-RPC 消息收发、会话管理、任务（task）机制、传输层（Netty HTTP/SSE）。它本身**不懂 arthas 命令**，只懂"工具（Tool）"抽象。
- **`core/mcp`** 负责**桥接**：把 arthas 的命令（`watch`/`jad`/`thread`…）逐个封装成带 `@Tool`/`@ToolParam` 注解的工具函数，并实现 `CommandExecutor` 接口，让 mcp-server 能调用 core 执行命令、回收结果。

二者通过接口 `CommandExecutor`（定义在 mcp-server，实现在 core 的 `CommandExecutorImpl`）解耦。

---

## MCP 工具调用全链路

以大模型调用 `watch` 工具（Streamable 模式）为例：

```
大模型/Agent  HTTP POST /mcp  (JSON-RPC tools/call)
  ↓
arthas-mcp-server/.../handler/McpHttpRequestHandler
  → McpStreamableHttpRequestHandler   （建立 SSE）
    → DefaultMcpStreamableServerSessionFactory（建会话、装 McpRequestHandler）
      → McpRequestHandler<CallToolResult> (tools/call)
        → task/ServerTaskToolHandler.handleToolCall()
          ├─ 请求带 task 参数 → handleTaskToolCreateTask()
          └─ 否则            → handleAutomaticTaskPolling()
        → tool/DefaultToolCallback.call(toolInput, toolContext)
          → 反射调 @Tool 方法（如 core 的 WatchTool.watch()）
            → core/.../mcp/tool/function/AbstractArthasTool.executeStreamable()
              → 构建 arthas 命令行 "watch ..."
              → session/ArthasCommandContext.executeAsync()
                → core 的 CommandExecutorImpl.executeAsync()  ← 进入 core 主链路
              → 轮询 pullResults() → 经 SSE 发中间进度 → 任务转 COMPLETED
```

> Stateless 模式更简单：单次 HTTP 请求内同步执行 `executeSync()` 后直接返回。

---

## 阅读顺序

**理解 arthas MCP 实现（正查）**：
1. [`arthas-mcp-server.md`](./arthas-mcp-server.md) —— 先理解 MCP 协议服务器（spec / server / handler / transport / task / tool）
2. [`core-mcp桥接.md`](./core-mcp桥接.md) —— 再看 arthas 命令如何变成工具、如何启动对接

**开发 arthas MCP 网关（速查）**：
3. [`问题定位反向索引.md`](./问题定位反向索引.md) —— 遇问题先查这里，跳源码
4. [`MCP能力清单.md`](./MCP能力清单.md) —— 网关 `tools/list` 的静态填充依据
5. [`MCP线契约.md`](./MCP线契约.md) + [`后端接入契约.md`](./后端接入契约.md) —— 两端协议契约
6. [`工具传输分类表.md`](./工具传输分类表.md) —— 写网关路由层时查

> 前置：建议先了解 [`core/CommandExecutorImpl`](../02-核心运行时/core/core-命令系统.md)（命令执行引擎）和 [`core/shell HTTP API`](../02-核心运行时/core/core-shell交互系统.md)。
>
> ⚠️ 包路径纠偏：core 侧 MCP 实际包是 `com.taobao.arthas.core.mcp`（含 `.core`），部分旧文档误写为 `com.taobao.arthas.mcp`。完整树见 [问题定位反向索引 附录](./问题定位反向索引.md#附核心包路径纠偏)。
