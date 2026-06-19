# spy 模块 · SpyAPI 钩子接口

> 路径：`spy/src/main/java/java/arthas/SpyAPI.java`
> 在整体中的位置：**字节码增强的回调契约**。整个 arthas 只此一个类，却是最关键的基础设施之一。

## 职责

`spy` 模块定义了 arthas 字节码增强注入到目标方法的**回调入口**。当 `watch`/`trace`/`stack` 等命令增强某个方法后，目标方法在"进入 / 返回 / 抛异常 / 内部调用 / 到达指定行"时，会调用 `SpyAPI` 的静态方法，arthas 据此收集数据。

它被设计得**尽可能轻量**：只有一个接口类 + 抽象实现 + 空实现。真正的逻辑在 [`core/advisor/SpyImpl`](../02-核心运行时/core/core-字节码增强.md) 里，运行时通过 `SpyAPI.setSpy()` 注入。

## 唯一的关键类

### `SpyAPI`
`spy/src/main/java/java/arthas/SpyAPI.java`

> ⚠️ 注意它的包名是 **`java.arthas`**（不是 `com.taobao.arthas.*`），这是刻意的，原因见下方"设计说明"。

**生命周期方法：**
- `init()` — 标记为已初始化。
- `isInited()` — 是否已初始化（被 [`agent`](./agent.md)/[`arthas-agent-attach`](./arthas-agent-attach.md) 用作"arthas 是否已在运行"的判断）。
- `setSpy(AbstractSpy spy)` — 注入真正的 Spy 实现（由 core 的 `SpyImpl` 设置）。
- `setNopSpy()` — 设为空操作（清理用）。
- `destroy()` — 销毁，重置初始化标志。

**方法级增强钩子（watch/trace 用）：**
- `atEnter(Class, String methodInfo, Object target, Object[] args)` — 方法进入。
- `atExit(Class, String methodInfo, Object target, Object[] args, Object returnObject)` — 方法正常返回。
- `atExceptionExit(Class, String methodInfo, Object target, Object[] args, Throwable throwable)` — 方法抛异常。

**调用链跟踪钩子（trace 用，针对方法内部的每次方法调用）：**
- `atBeforeInvoke(Class, String invokeInfo, Object target)`
- `atAfterInvoke(Class, String invokeInfo, Object target)`
- `atInvokeException(Class, String invokeInfo, Object target, Throwable)`

**行级增强钩子（watch 行号模式用）：**
- `atLine(Class, String methodInfo, int lineNumber, Object target, Object[] args, String[] argNames, Object[] localVars, String[] localVarNames)`

**内部类：**
- `AbstractSpy` — 抽象基类，声明上述所有钩子（`atEnter` 等在 `SpyAPI` 里转发到 `spyInstance.xxx()`）。
- `NopSpy` — 空实现，所有方法体为空（销毁/清理时使用，避免空指针）。

## 设计说明：为什么是 `java.arthas` 包 + 单独成包

这是 arthas 字节码增强能正常工作的**第一性原理**，务必理解：

1. **注入到 BootstrapClassLoader**：`core/.../server/ArthasBootstrap.initSpy()` 通过 `Instrumentation.appendToBootstrapClassLoaderSearch()` 把 `arthas-spy.jar` 加到 BootstrapClassLoader 的搜索路径。于是 `SpyAPI` 由**最顶层的 BootstrapClassLoader** 加载，对目标 JVM 内**所有 ClassLoader** 可见，且全局唯一。

2. **为什么不能放进 core**：字节码增强会在**目标业务类**的方法里插入 `SpyAPI.atEnter(...)` 调用。如果 `SpyAPI` 由 arthas 自己的 `ArthasClassloader` 加载，那么业务类的 ClassLoader（往往是 Web 容器、自定义加载器）很可能**加载不到** `SpyAPI`，导致 `NoClassDefFoundError`、甚至 JVM 崩溃（见 arthas issue #1596）。放进 BootstrapClassLoader 就彻底回避了这个问题。

3. **轻量契约 + 重型实现分离**：`SpyAPI` 只是个转发壳（`spyInstance` 字段指向真正的实现）。真正的逻辑 `core/advisor/SpyImpl` 由 `ArthasClassloader` 加载，在 `Enhancer` 的静态初始化块里通过 `SpyAPI.setSpy(new SpyImpl())` 注入。这样业务类只依赖一个极轻的 `SpyAPI`，而 arthas 的复杂逻辑被隔离在自己的类加载器里。

4. **配合 ClassLoader 增强**：对于连 BootstrapClassLoader 都加载不到的极端情况（部分被改写的 ClassLoader），`core/.../server/instrument/ClassLoader_Instrument` 会增强 `java.lang.ClassLoader.loadClass`，把 `java.arthas.*` 的加载重定向到 ExtensionClassLoader。

## 运行时回调链路（连接到 core）

```
目标业务方法被调用
  ↓ （Enhancer 之前在方法里织入了静态调用）
SpyAPI.atEnter(clazz, methodInfo, this, args)        ← 本模块
  ↓ 转发到注入的实例
core/.../advisor/SpyImpl.atEnter(...)                ← core
  ↓ 解析 methodInfo → methodName/methodDesc
core/.../advisor/AdviceListenerManager                ← core
  .queryAdviceListeners(classLoader, cls, m, desc)
  ↓
具体命令的 AdviceListener（如 WatchAdviceListener）  ← core/command/monitor200
  ↓ OGNL 求值 → 输出
```

## 与其它模块的关系

- 被 [`agent`](./agent.md) / [`arthas-agent-attach`](./arthas-agent-attach.md) 在启动时 `init()`/`isInited()`。
- 实现侧在 [`core/advisor/SpyImpl`](../02-核心运行时/core/core-字节码增强.md)，织入侧在 [`core/advisor/Enhancer`](../02-核心运行时/core/core-字节码增强.md)。

## 定位提示

> "增强后的方法到底调了什么？" → `spy/.../SpyAPI` 的 `atEnter`/`atExit`/`atExceptionExit` 等。
> "为什么 SpyAPI 在 `java.arthas` 包？" → 见本文"设计说明"。
> "真正的回调逻辑在哪？" → `core/.../advisor/SpyImpl.java`。
