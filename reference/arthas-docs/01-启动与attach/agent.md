# agent 模块 · Java Agent 引导

> 路径：`agent/src/main/java/`（包 `com.taobao.arthas.agent` 与 `com.taobao.arthas.agent334`）
> 在整体中的位置：**进入目标 JVM 后的第一站**，负责加载 `arthas-core` 并反射初始化 `ArthasBootstrap`。

## 职责

`arthas-agent.jar` 是被注入目标 JVM 的那个 agent（通过 `-javaagent` 或运行时 attach）。它代码极少，核心就两件事：

1. 提供 JVM Agent 入口：`premain`（启动时挂载）/ `agentmain`（运行时 attach）；
2. 用一个**隔离的类加载器**加载 `arthas-core.jar`，再反射调用 `ArthasBootstrap.getInstance()`，把控制权交给 core。

> 注意包名 `agent334`：这是为了在 JDK 9+（Java 9/10/11...）上使用兼容的 Agent API 而做的版本隔离命名（3.3.4 起的历史遗留命名），实际逻辑与启动方式一致。

## 关键类

### `AgentBootstrap`
`agent/src/main/java/com/taobao/arthas/agent334/AgentBootstrap.java` — **Agent 启动引导类**。

- `premain(String args, Instrumentation inst)` — JVM 启动时 `-javaagent` 入口。
- `agentmain(String args, Instrumentation inst)` — 运行时 attach 入口（`arthas-boot` 走的就是这条）。
- `main(String args, Instrumentation inst)` — **统一启动逻辑**：检查 `SpyAPI.isInited()`（避免重复绑定）→ 解析参数 → 在专用线程里 `bind()`。
- `bind(Instrumentation inst, ClassLoader agentLoader, String args)` — **核心**：反射调用 `ArthasBootstrap.getInstance(inst, args)` 并校验绑定结果。
- `resetArthasClassLoader()` — 重置类加载器，允许下次重新加载（用于热更新 arthas 自身）。

### `ArthasClassloader`
`agent/src/main/java/com/taobao/arthas/agent/ArthasClassloader.java` — **arthas 专用隔离类加载器**（继承 `URLClassLoader`）。

- `ArthasClassloader(URL[] urls)` — 以系统 ClassLoader 的父加载器为父，构造隔离加载器。
- `appendURL(URL)` — 动态追加 jar 到类路径。
- `loadClass(String name, boolean resolve)` — 自定义双亲委派：`java.*`/`sun.*` 等系统类走父加载器，其余**优先自身加载**，保证 arthas 的依赖与目标应用隔离。

## 启动调用链（运行时 attach 场景）

```
boot/ProcessUtils.startArthasCore()
  → 目标 JVM 收到 attach，加载 arthas-agent.jar
    → AgentBootstrap.agentmain(args, inst)
      → AgentBootstrap.main(args, inst)
        · if (SpyAPI.isInited()) return;   // 已在运行则跳过
        · 构造 ArthasClassloader（加载 arthas-core.jar）
        → AgentBootstrap.bind(inst, agentLoader, args)
          · 反射 ArthasBootstrap.getInstance(inst, configureString)
          · 校验返回的 bootstrap 非空
        · SpyAPI 在 core 初始化内被 init()
```

## 与其它模块的关系

- 反射进入 [`core/.../server/ArthasBootstrap`](../02-核心运行时/core/core-入口与全局选项.md) —— 此处是 agent 与 core 的边界。
- 依赖 [`spy`](./spy.md) 的 `SpyAPI.isInited()` 作为"是否已启动"的全局开关。
- 与 [`arthas-agent-attach`](./arthas-agent-attach.md) 是平行的两条 attach 入口（命令行 vs 编程式）。

## 定位提示

> "目标 JVM 被 attach 后，arthas 第一个执行的代码在哪？" → `agent/.../agent334/AgentBootstrap.agentmain()`。
> "arthas 怎么避免污染目标应用的依赖？" → `agent/.../ArthasClassloader.loadClass()`。
