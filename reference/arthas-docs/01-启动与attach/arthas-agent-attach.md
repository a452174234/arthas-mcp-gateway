# arthas-agent-attach 模块 · 编程式 Attach

> 路径：`arthas-agent-attach/src/main/java/com/taobao/arthas/agent/attach/`
> 在整体中的位置：**第二条 attach 入口**——不需要命令行，直接在应用代码里把 arthas 挂到当前 JVM。

## 职责

当用户希望在自己的应用代码中**主动**启动 arthas（例如 Spring Boot 启动时自动 attach，或集成测试里用），就依赖这个模块。它对外暴露一个极简 API：`ArthasAgent.attach()`。

其内部做的事与 [`agent`](./agent.md) 的运行时 attach 本质相同，只是入口从"外部进程 attach"变成了"同 JVM 内通过 ByteBuddyAgent 拿 Instrumentation"。

> 这也是 `arthas-spring-boot-starter`（不在本文档范围）底层依赖的 attach 实现。

## 关键类

### `ArthasAgent`
`arthas-agent-attach/src/main/java/com/taobao/arthas/agent/attach/ArthasAgent.java` — **编程式 attach 入口**。

- `attach()` — 无参：用默认配置 attach。
- `attach(Map<String,String> configMap)` — 带配置（ip/port/tunnel/认证等）attach。
- `attach(String arthasHome)` — 指定 arthas 目录 attach。
- `init()` — **核心初始化**：
  1. 检查 `SpyAPI.isInited()`（已运行则跳过）；
  2. `ByteBuddyAgent.install()` 获取 `Instrumentation`；
  3. 查找/解压 `arthas-bin.zip`；
  4. 创建 `AttachArthasClassloader`；
  5. 反射调用 `ArthasBootstrap.getInstance(inst, configMap)`。

### `AttachArthasClassloader`
`arthas-agent-attach/src/main/java/com/taobao/arthas/agent/attach/AttachArthasClassloader.java` — **编程式 attach 专用类加载器**，逻辑与 `agent/ArthasClassloader` 一致。

- `AttachArthasClassloader(URL[] urls)` — 隔离加载。
- `appendURL(URL)` / `loadClass(String, boolean)` — 同 `ArthasClassloader` 的双亲委派策略。

> 与 `agent` 模块的 `ArthasClassloader` 几乎是镜像，只是用在"编程式 attach"这条链路上、类加载器来源（解压 zip vs 已有 jar）略有差异。

## 调用链（编程式 attach）

```
应用代码: ArthasAgent.attach(configMap)
  → ArthasAgent.init()
     · if (SpyAPI.isInited()) return;
     · instrumentation = ByteBuddyAgent.install();
     · 解压 arthas-bin.zip → 构造 AttachArthasClassloader
     · 反射 ArthasBootstrap.getInstance(instrumentation, configMap)
        └─ 后续与命令行启动完全相同（见 core 入口文档）
```

## 与其它模块的关系

- 反射进入 [`core/.../server/ArthasBootstrap`](../02-核心运行时/core/core-入口与全局选项.md)。
- 依赖 [`spy`](./spy.md) 的 `SpyAPI.isInited()`。
- 与 [`agent`](./agent.md) 互为替代：命令行走 agent，编程式走本模块。

## 定位提示

> "我想在应用启动时自动 attach arthas，代码怎么写？" → `ArthasAgent.attach(configMap)`。
> "它和命令行 `arthas-boot.jar` 的区别？" → 入口不同，最终都到 `ArthasBootstrap`；本模块在同 JVM 内用 ByteBuddyAgent 拿 Instrumentation，无需外部进程。
