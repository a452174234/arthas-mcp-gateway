# core · 字节码增强（最核心的魔法）

> 覆盖源码包：`core/advisor/**`
> 解决：**`watch`/`trace`/`stack` 等命令如何在目标 JVM 运行时修改字节码、运行时如何把数据回传给 arthas。**

这是 arthas 区别于普通 JMX 工具的根本所在。建议配合 [`spy.md`](../../01-启动与attach/spy.md) 一起读——本篇是 spy 的"使用方"。

---

## 一、核心概念与数据流

```
                  编译期（增强时）                        运行期（方法被调用时）
┌──────────────────────────────────────┐   ┌─────────────────────────────────────┐
│ Enhancer.enhance()                   │   │ 目标业务方法执行                       │
│  → 搜匹配类、过滤                      │   │   ↓ 方法入口织入的代码                  │
│  → ASM 在方法前后织入对                │   │ SpyAPI.atEnter/atExit/atExceptionExit │
│    SpyAPI 的静态调用 ──────────────────┼──→│   ↓                                  │
│  → retransformClasses 触发转换         │   │ SpyImpl.atEnter(...)  （本篇）        │
│  → 注册 AdviceListener 到 Manager     │   │   ↓ 查询                              │
└──────────────────────────────────────┘   │ AdviceListenerManager                 │
                                           │   ↓ 分发                              │
                                           │ WatchAdviceListener.before/after...   │
                                           │   ↓ OGNL 求值 → 输出                   │
                                           └─────────────────────────────────────┘
```

四个关键角色：**`Enhancer`**（织入）、**`SpyImpl`**（运行时回调入口）、**`AdviceListenerManager`**（类/方法→监听器映射）、**`AdviceListener`**（各命令的处理逻辑）。

---

## 二、关键类

### `Enhancer`
`core/.../advisor/Enhancer.java` — **字节码增强入口**，实现 `ClassFileTransformer`，连接 Instrumentation 与 arthas 监控逻辑。

- `Enhancer(AdviceListener, boolean isTracing, boolean skipJDKTrace, Matcher...)` — 配置监听器、是否 trace、是否跳过 JDK 方法、类/方法匹配器。
- **`transform(loader, name, clazz, protectionDomain, bytes)`** — `ClassFileTransformer` 实现：把目标类字节码转成增强后的字节码。
- **`enhance(Instrumentation, int maxNumOfMatchedClass)`** — 执行增强：搜匹配类 → 过滤（Lambda/接口/数组/Bootstrap 类等）→ 注册到 `TransformerManager` → `retransformClasses`。
- `reset(Instrumentation, classNameMatcher)` — 撤销增强（`reset` 命令底层）。

> 织入用的是 **Alibaba ByteKit** 的拦截器注解（见 `SpyInterceptors`）。trace 模式还会在方法内部调用前后织入 `atBeforeInvoke`/`atAfterInvoke`。

### `AdviceWeaver`
`core/.../advisor/AdviceWeaver.java` — **监听器生命周期管理中心**（注意：名字像"编织者"，实际是注册表）。

- `reg(AdviceListener)` — 注册（触发 `listener.create()`）。
- `unReg(AdviceListener)` — 注销（触发 `listener.destroy()`）。
- `suspend(adviceId)` / `resume(listener)` — 暂停/恢复。
- `listener(id)` — 按 ID 查询。
- 内部 `Map<Long, AdviceListener> advices`。

### `AdviceListener`
`core/.../advisor/AdviceListener.java` — **增强事件监听器接口**，定义方法各阶段回调。

- `id()`、`create()`、`destroy()`。
- `before(clazz, methodName, methodDesc, target, args)`。
- `afterReturning(..., returnObject)`。
- `afterThrowing(..., throwable)`。
- `atLine(..., lineNumber, argNames, localVars, localVarNames)`。

### `AdviceListenerAdapter`
`core/.../advisor/AdviceListenerAdapter.java` — **适配器抽象类**，简化具体监听器实现。

- 把原始回调转成带 `ClassLoader`/`ArthasMethod` 的签名（子类只需实现 `before/afterReturning/afterThrowing` 的抽象版本）。
- 提供通用能力：`isConditionMet(conditionExpress, advice, cost)`（条件判断）、`isLimitExceeded(limit, times)`（`-n` 次数上限）、`abortProcess(...)`。
- 实现 `ProcessAware`，关联命令进程。

### `AdviceListenerManager`
`core/.../advisor/AdviceListenerManager.java` — **类/方法 → 监听器**的三层映射中心。

- 维护 `ClassLoader → (className+methodName+methodDesc) → List<AdviceListener>`。
- `registerAdviceListener(...)` — 普通方法增强。
- `registerTraceAdviceListener(...)` — trace 调用跟踪（多了 owner 维度）。
- `registerLineAdviceListener(..., lineNumber, ...)` — 行级增强。
- `queryAdviceListeners` / `queryTraceAdviceListeners` / `queryLineAdviceListeners` — 运行时查询。
- **弱引用 + 定时清理**：用 [`common` 的 `ConcurrentWeakKeyHashMap`](../common.md) 以 ClassLoader 为弱键，每 3 秒清理已终止命令的监听器，防内存泄漏。

### `Advice`
`core/.../advisor/Advice.java` — **方法执行上下文数据**，传给监听器。

- 字段：`loader`/`clazz`/`method(ArthasMethod)`/`target`/`params`/`returnObj`/`throwExp`/`lineNumber`/`argNames`/`localVars`/`localVarNames`/`localVarMap`，以及 `isBefore/isReturn/isThrow/isLine`。
- 静态工厂：`newForBefore` / `newForAfterReturning` / `newForAfterThrowing` / `newForLine`。

### `AccessPoint`
`core/.../advisor/AccessPoint.java` — 增强点枚举（位掩码）。
- `ACCESS_BEFORE(1,"AtEnter")` / `ACCESS_AFTER_RETUNING(2,"AtExit")` / `ACCESS_AFTER_THROWING(4,"AtExceptionExit")` / `ACCESS_LINE(8,"AtLine")`。

### `ArthasMethod`
`core/.../advisor/ArthasMethod.java` — 方法元数据（类 + 名 + 描述符），缓存反射 `Method`/`Constructor`。`invoke(target, args...)`（`tt` 重放用）、`setAccessible(...)`。

### `SpyImpl`
`core/.../advisor/SpyImpl.java` — **运行时回调入口**，实现 [`spy/SpyAPI.AbstractSpy`](../../01-启动与attach/spy.md)。

- `atEnter/atExit/atExceptionExit` — 方法级回调（解析 methodInfo → 查 `AdviceListenerManager` → 遍历调用 `before/afterReturning/afterThrowing`）。
- `atBeforeInvoke/atAfterInvoke/atInvokeException` — trace 调用跟踪回调。
- `atLine(...)` — 行级回调。
- 过滤已终止的监听器，避免无效调用。

### `SpyInterceptors`
`core/.../advisor/SpyInterceptors.java` — **ByteKit 拦截器集合**，供 `Enhancer` 织入用。每个是静态内部类，方法上标 `@AtEnter`/`@AtExit`/`@AtExceptionExit`/`@AtInvoke`/`@AtLine` + `@Binding.*`。

- `SpyInterceptor1/2/3` — 普通方法增强（进入/返回/异常）。
- `SpyLineInterceptor` — 行级增强。
- `SpyTraceInterceptor1/2/3` — 调用链跟踪（含 JDK）。
- `SpyTraceExcludeJDKInterceptor1/2/3` — 调用链跟踪（排除 JDK，`--skipJDKMethod`）。

### `TransformerManager`
`core/.../advisor/TransformerManager.java` — **转换器管理器**，统一注册/卸载 `ClassFileTransformer`。

- 维护四类列表：`watchTransformers`、`traceTransformers`、`reTransformers`（先于前两者）、`lazyTransformers`（类首次加载时增强）。
- `addTransformer(tf, isTracing)` / `addLazyTransformer(tf)` / `removeTransformer(tf)` / `destroy()`。
- 一个总 `classFileTransformer` 按序串联各 transform。

### 其它
- **`InvokeTraceable`** — trace 监听器实现的接口：`invokeBeforeTracing/invokeAfterTracing/invokeThrowTracing`。
- **`LineEnhanceOptions`** — 行级增强配置（`Set<Integer> lines`、`methodDesc`、`LineMode`、`LineDuplicatePolicy`）。

---

## 三、增强全链路（watch 为例）

### 阶段 1：命令触发增强
`WatchCommand.process()` → `EnhancerCommand.enhance()` → 创建 `WatchAdviceListener` + `Enhancer` → `Enhancer.enhance(inst, maxNum)`。

### 阶段 2：搜类 + 过滤
`SearchUtils.searchClass()` 找匹配类 → `filter()` 剔除 Lambda/接口/数组/`Integer`/`Class`/arthas 自身类/Bootstrap 类（除非 `unsafe`）。

### 阶段 3：注册 + retransform
`TransformerManager.addTransformer(this, isTracing)` → `inst.retransformClasses(matchingClasses)` → JVM 回调 `Enhancer.transform()`。

### 阶段 4：ASM 织入（transform 内）
- 检查 ClassLoader 能否加载 `SpyAPI`（不能则跳过）。
- 用 ByteKit 为匹配方法挂上拦截器：
  - 方法入口插入 `SpyAPI.atEnter(clazz, methodInfo, this, args)`
  - 返回处插入 `SpyAPI.atExit(clazz, methodInfo, this, args, returnObj)`
  - 异常处插入 `SpyAPI.atExceptionExit(clazz, methodInfo, this, args, throwable)`
  - trace 模式额外在内部调用前后插 `atBeforeInvoke/atAfterInvoke/atInvokeException`
  - 行级模式在指定行插 `atLine(...)`
- `AdviceListenerManager.registerAdviceListener(...)` 建立映射。

### 阶段 5：运行时回调
业务调用被增强方法 → 触发织入的 `SpyAPI.atEnter` →（[`spy`](../../01-启动与attach/spy.md) 转发）→ `SpyImpl.atEnter` → `AdviceListenerManager.queryAdviceListeners(...)` → `WatchAdviceListener.before` → 构造 `Advice.newForBefore()` → 条件判断 → OGNL 求值 → 输出。

### 阶段 6：返回 / 异常
`SpyAPI.atExit`/`atExceptionExit` → `SpyImpl` → `afterReturning`/`afterThrowing` → 同上。

### 阶段 7：撤销（reset 命令）
`Enhancer.reset(inst, "*")` → `retransformClasses` → `transform` 因 `matchingClasses` 不含该类返回 null → 类还原 → 清缓存与监听器映射。

---

## 四、关键设计亮点

1. **弱引用防泄漏**：`AdviceListenerManager` 以 ClassLoader 弱键 + 定时清理。
2. **多监听器共享增强**：同一方法可被多次 watch，共享一份增强代码，靠 listener 列表分发。
3. **懒加载增强**：`lazyTransformers` 可增强"未来才加载的类"。
4. **行级增强**：依赖 debug 信息的 `LocalVariableTable` 拿局部变量名/值。
5. **安全默认**：默认不增强 Bootstrap 类（需 `unsafe`），避免 JVM 崩溃。
6. **ClassLoader 增强**：`ClassLoader_Instrument` 兜底保证 spy 可见（见 [入口文档](./core-入口与全局选项.md)）。

## 定位提示

> "watch 在方法里插的代码长啥样？" → `advisor/SpyInterceptors`（ByteKit 拦截器）。
> "运行时这些回调最先进入哪？" → `advisor/SpyImpl.atEnter/atExit/atExceptionExit`。
> "同一个方法怎么找到该回调哪个监听器？" → `advisor/AdviceListenerManager`。
> "trace 怎么记录方法内部的每次调用？" → `SpyTraceInterceptor*` + `InvokeTraceable`。
> "reset 怎么撤销增强？" → `Enhancer.reset()` + `TransformerManager.removeTransformer()`。
