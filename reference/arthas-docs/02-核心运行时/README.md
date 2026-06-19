# 02 · 核心运行时

本组是 arthas 的主体。包含：

| 模块 | 路径 | 角色 |
|---|---|---|
| [common](./common.md) | `common/` | 全项目共享工具库 |
| [memorycompiler](./memorycompiler.md) | `memorycompiler/` | 运行时内存 Java 编译器（`mc`/`redefine` 依赖） |
| [client](./client.md) | `client/` | 本地 Telnet 客户端 |
| [core](./core/README.md) | `core/` | **核心**：命令系统 + 字节码增强 + Shell 交互 + 分发 + 视图 + MCP 桥接 |

---

## core 子目录导航（重点）

`core` 是最大的模块，按功能拆成 5 篇文档，进入 [`core/README.md`](./core/README.md) 查看总览。速览：

| 文档 | 覆盖源码包 | 看它解决什么 |
|---|---|---|
| [core-入口与全局选项](./core/core-入口与全局选项.md) | `core/Arthas.java`、`core/.../server/`、`core/.../security/`、根级 `GlobalOptions`/`Option` | arthas 启动后如何完成核心初始化、全局开关、安全认证 |
| [core-命令系统](./core/core-命令系统.md) | `core/.../command/**` | 某个命令（`watch`/`jad`/`thread`…）的注册与实现 |
| [core-字节码增强](./core/core-字节码增强.md) | `core/.../advisor/**` | `watch`/`trace` 如何在运行时改字节码、运行时如何回调 |
| [core-shell交互系统](./core/core-shell交互系统.md) | `core/.../shell/**` | 终端输入如何路由到命令、telnet/http/webconsole 接入 |
| [core-支撑层](./core/core-支撑层.md) | `core/.../config`、`distribution`、`env`、`util`、`view`、`one/profiler` | 配置、结果分发、环境、工具、视图渲染、profiler 集成 |

> core 内还有 `core/.../mcp/**`（命令→MCP 工具桥接），归入 [`../03-MCP/`](../03-MCP/README.md) 一并讲解。

---

## 阅读顺序建议

1. [`common.md`](./common.md) —— 先认识被到处复用的基础工具
2. [`memorycompiler.md`](./memorycompiler.md) —— 理解 `mc`/`redefine` 的编译底座
3. [`client.md`](./client.md) —— 本地 telnet 客户端（轻量，快速过）
4. [`core/README.md`](./core/README.md) → [`core-入口与全局选项.md`](./core/core-入口与全局选项.md) —— 进入 core 主干
5. 按 [总 README 的学习路径](../README.md#五渐进式学习路径) 继续
