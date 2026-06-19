# 01 · 启动与 Attach

本组模块回答一个核心问题：**arthas 是怎么"挂"到目标 Java 进程上去的？**

涉及 4 个模块，它们共同构成启动/attach 链路：

| 模块 | 路径 | 角色 |
|---|---|---|
| [boot](./boot.md) | `boot/` | 命令行入口（`java -jar arthas-boot.jar`） |
| [agent](./agent.md) | `agent/` | Java Agent 引导：`premain`/`agentmain`，隔离类加载 |
| [arthas-agent-attach](./arthas-agent-attach.md) | `arthas-agent-attach/` | 编程式 attach（在应用代码里 `ArthasAgent.attach()`） |
| [spy](./spy.md) | `spy/` | `SpyAPI` 钩子接口，注入目标 JVM，供字节码增强回调 |

---

## 启动 / Attach 总链路

arthas 有两种典型的挂载方式，最终都汇聚到 `core` 的 `ArthasBootstrap`。

### 方式一：命令行启动（`java -jar arthas-boot.jar`）

```
用户执行 java -jar arthas-boot.jar [pid]
  │
  ▼  boot/Bootstrap.main()
  │   解析参数 → boot/ProcessUtils.select() 选目标进程
  │           → 查找/下载 arthas 目录（boot/DownloadUtils）
  ▼  boot/ProcessUtils.startArthasCore(targetPid, args)
  │   拉起一个 arthas-core.jar 进程，通过 attach API 注入目标 JVM
  │
  ╔════════════════ 新进程（在目标 JVM 内） ════════════════╗
  ║ agent/.../agent334/AgentBootstrap.agentmain(args, inst)  ║
  ║   → AgentBootstrap.main()                                 ║
  ║     · 检查 spy/SpyAPI.isInited()（避免重复启动）           ║
  ║     · 创建隔离类加载器 agent/.../ArthasClassloader         ║
  ║   → AgentBootstrap.bind(inst, agentLoader, args)          ║
  ║     · 反射调用 core/.../server/ArthasBootstrap             ║
  ║        .getInstance(inst, configureString)                ║
  ║       └─ ArthasBootstrap 构造：                           ║
  ║            · initSpy()：把 arthas-spy.jar 追加到           ║
  ║              BootstrapClassLoader（spy 全局可见）          ║
  ║            · enhanceClassLoader()（按需增强 ClassLoader）  ║
  ║            · bind(configure)：启动 Shell(Telnet/HTTP)/MCP ║
  ║            · SpyAPI.init()                                ║
  ╚══════════════════════════════════════════════════════════╝
  │
  ▼  回到 boot：boot/ProcessUtils.startArthasClient()
      启动 client/TelnetConsole 连接 127.0.0.1:3658
```

### 方式二：编程式 attach（应用代码内）

```
应用代码调用 arthas-agent-attach/.../ArthasAgent.attach(configMap)
  → ArthasAgent.init()
     · 检查 SpyAPI.isInited()
     · ByteBuddyAgent.install() 拿到 Instrumentation
     · 解压 arthas-bin.zip
     · 创建 AttachArthasClassloader
     · 反射调用 ArthasBootstrap.getInstance(inst, configMap)
        └─ 后续与方式一相同
```

---

## 关键设计：为什么 spy 要单独成包并放在 `java.arthas`

这是理解 arthas 字节码增强的**第一块基石**，详见 [`spy.md`](./spy.md) 的"设计说明"小节。简述：

- `spy` 模块只有一个类 `SpyAPI`，且它的包名故意是 `java.arthas`。
- 启动时通过 `Instrumentation.appendToBootstrapClassLoaderSearch()` 把 `arthas-spy.jar` 加到 **BootstrapClassLoader**，使 `SpyAPI` 对目标 JVM 内**所有 ClassLoader** 都可见、且全局唯一。
- 字节码增强（`core/advisor/Enhancer`）在被观测方法里插入的是对 `SpyAPI` 静态方法的调用；运行时 `SpyAPI` 把回调委托给由 ArthasClassloader 加载的 `core/advisor/SpyImpl`。
- 这样目标业务代码的 ClassLoader 不需要能加载到 arthas 的实现类，**只依赖一个轻量的 `SpyAPI`**，从而绕过各种自定义 ClassLoader 的隔离问题（见 arthas issue #1596）。

---

## 阅读顺序

1. [`boot.md`](./boot.md) —— 最外层入口，先建立全局观感
2. [`agent.md`](./agent.md) —— 进入目标 JVM 的引导逻辑
3. [`spy.md`](./spy.md) —— 钩子接口与注入机制（承上启下，连接到 core 的字节码增强）
4. [`arthas-agent-attach.md`](./arthas-agent-attach.md) —— 编程式 attach（与 agent 平行的另一条入口）

> 继续深入请前往 [`../02-核心运行时/core/core-入口与全局选项.md`](../02-核心运行时/core/core-入口与全局选项.md)，看 `ArthasBootstrap` 如何完成核心初始化。
