# core · 支撑层

> 覆盖源码包：`core/config`、`core/distribution`(+impl)、`core/env`(+convert)、`core/util/*`、`core/view`、`one/profiler`
> 解决：**配置加载、命令结果分发、环境/类型转换、各类工具、视图渲染引擎、async-profiler 集成。**

---

## 一、`config` · 启动配置

`core/src/main/java/com/taobao/arthas/core/config/`

- **`Configure`** — arthas 核心配置类（`ArthasBootstrap.bind()` 的入参）。字段：`ip`/`telnetPort`/`httpPort`/`tunnelServer`/`agentId`/`username`/`password`/`outputPath`/`enhanceLoaders`/`appName`/`statUrl`/`sessionTimeout`/`disabledCommands`/`commandLocations`/`mcpEndpoint`/`mcpProtocol`。`toString()`/`toConfigure(str)` 用 `FeatureCodec` 序列化。
- **`FeatureCodec`** — 线程安全的特征编解码器：`toString(Map)`/`toMap(str)`/`escapeEncode`/`escapeDecode`/`escapeSplit`。
- **`BinderUtils`** — 配置注入（类 Spring Boot）：`inject(env, instance)`/`inject(env, prefix, instance)`。
- **`Config`** / **`NestedConfig`** — 配置注解（`prefix`、嵌套）。

---

## 二、`distribution`(+impl) · 结果分发 ⭐

`core/src/main/java/com/taobao/arthas/core/distribution/`

让一条命令的结果能同时发到**多个消费者**（本地终端、tunnel、HTTP 拉取者）。

接口：
- **`ResultDistributor`** — `appendResult(ResultModel)`/`close()`。
- **`ResultConsumer`** — `appendResult(...)`/`pollResults()`/`getLastAccessTime()`/`isHealthy()`/`isPolling()`/`getConsumerId()`。
- **`CompositeResultDistributor`** — `addDistributor/removeDistributor`（组合分发）。
- **`SharingResultDistributor`** — `addConsumer/removeConsumer/getConsumers/getConsumer(id)`（共享分发，多消费者）。
- **`PackingResultDistributor`** — `getResults()`（打包，用于同步执行）。
- **`DistributorOptions`** — `resultQueueSize`（默认 50）。
- **`ResultConsumerHelper`** — `getItemCount(model)`（估算 item 数，用于切片）。

impl：
- **`CompositeResultDistributorImpl`** — 同时分发给所有子分发器。
- **`SharingResultDistributorImpl`** — 守护线程异步分发 + 消费者健康检查（全不健康则中断当前命令）。
- **`ResultConsumerImpl`** — 队列满丢旧；`pollResults()` 长轮询；`isHealthy()` 综合判断；`shouldFlush(...)`。
- **`PackingResultDistributorImpl`** — 存队列，`getResults()` 一次性取空（同步执行用）。
- **`TermResultDistributorImpl`** — 直接渲染到终端（用 `ResultViewResolver`）。

> 这是 `CommandExecutorImpl.executeAsync` + HTTP API `pullResults` 能工作的底座。

---

## 三、`env`(+convert) · 环境/属性/类型转换

`core/src/main/java/com/taobao/arthas/core/env/`

- **`Environment`** / **`ArthasEnvironment`** — 环境接口与实现（自动注册系统属性 + 环境变量；`addFirst`/`addLast` 控制优先级）。
- **`PropertyResolver`** / **`PropertySourcesPropertyResolver`** — `containsProperty`/`getProperty(key)`/`getProperty(key, type)`/`resolvePlaceholders(${...})`。
- **`PropertySource`** / **`SystemEnvironmentPropertySource`** / **`PropertiesPropertySource`** — 多种属性源（系统环境变量支持点号/横线/大小写变体）。
- **`ConversionService`** — `canConvert`/`convert`。

`env/convert`：**`Converter<S,T>`**、`ConvertiblePair`、**`DefaultConversionService`**（注册了一堆：`StringToInteger/Long/Boolean/InetAddress/Enum/Array`、`ObjectToString`）。

---

## 四、`util/*` · 工具集

`core/src/main/java/com/taobao/arthas/core/util/`

### `util/affect` · 影响统计
- **`Affect`** — 基类，`cost()`（耗时）。
- **`EnhancerAffect`** — 增强影响：`cCnt`(类数)/`mCnt`(方法数)/`addClassDumpFile(...)`/`addMethodAndCount(...)`/`getTransformer()`/`getListenerId()`。`toString()` 生成"影响 N 个类 M 个方法"报告。
- **`RowAffect`** — 行影响：`rCnt`。

### `util/matcher` · 匹配器（watch/trace 匹配基础）⭐
- **`Matcher<T>`** — `matching(target)`。
- **`WildcardMatcher`** — 通配符（`*`/`?`，支持转义）。
- **`RegexMatcher`** — 正则（用 `RegexCacheManager` 缓存编译结果）。
- **`EqualsMatcher<T>`** / **`TrueMatcher<T>`** / `FalseMatcher` — 精确/永真/永假。
- **`GroupMatcher<T>`** — 组合：内部 `And<T>`/`Or<T>`。

### `util/metrics` · 速率统计
- **`RateCounter`** — `update(value)`/`rate()`（随机保留历史采样）。
- **`SumRateCounter`** — 增量速率（计算与上次差值）。
- dashboard/thread 等命令用。

### `util/reflect` · 反射
- **`ArthasReflectUtils`** — `getClasses(loader, pkg)`/`getFields(clazz)`/`getField(clazz,name)`/`set(field,value,target)`/`getFieldValueByField(...)`/`valueOf(type,str)`/`defineClass(loader,name,bytes)`。
- `FieldUtils`。

### `util/collection` · 自定义集合
- **`GaStack<E>`** — 栈接口（`pop`/`push`/`peek`/`isEmpty`）。impl：`ThreadUnsafeGaStack`/`ThreadUnsafeFixGaStack`（trace 记录调用栈用）。

### `util/usage` · 用法渲染
- **`StyledUsageFormatter`** — `styledUsage(cli, width)`，生成 USAGE/SUMMARY/OPTIONS 文档。

---

## 五、`view` · 视图渲染引擎 ⭐

`core/src/main/java/com/taobao/arthas/core/view/`

与 `command/view`（各命令专用 View）配合：本包是**底层引擎**，`command/view` 是**上层适配**。

- **`View`** — 接口，`draw()`。
- **`TableView`** — 表格引擎（自动列宽、多行、对齐、边框）。`addRow(...)`/`hasBorder(...)`/`borders(...)`/`padding(...)`。内部 `ColumnDefine`。
- **`KVView`** — 键值对视图。`add(key,value)`。
- **`TreeView`** — 树形视图（trace 用）。`begin(data)`/`end()`/`end(mark)`，支持耗时统计 + 高亮最耗时节点。
- **`LadderView`** — 阶梯缩进视图。
- **`ObjectView`** — 对象结构渲染（基本类型/集合/Map/数组/Throwable/Date，支持深度与大小限制）。`toJsonString(obj)`。
- **`ClassInfoView`** / **`MethodInfoView`** — 类/方法信息视图（sc/sm/jad 用）。
- **`Ansi`** — ANSI 转义生成器（颜色/属性/光标）。`ansi()`/`fg(color)`/`bg(color)`/`a(attr)`/`reset()`。

---

## 六、`one/profiler` · async-profiler 集成

`core/src/main/java/one/profiler/`

`profiler` 命令（`monitor200/ProfilerCommand`）的底层，通过 JNI 调用 async-profiler 原生库。

- **`AsyncProfiler`** — Java API。`getInstance()`/`getInstance(libPath)`（自动加载 `libasyncProfiler.so`）、`start(event, interval)`/`resume(...)`/`stop()`/`getSamples()`/`getVersion()`/`execute(cmd)`/`dumpCollapsed(counter)`/`dumpTraces(max)`/`dumpFlat(maxMethods)`/`dumpOtlp()`/`addThread(...)`/`extractEmbeddedLib()`/`getPlatformTag()`。
- **`AsyncProfilerMXBean`** — JMX 接口（`OBJECT_NAME = "one.profiler:type=AsyncProfiler"`）。
- **`Events`** — 事件常量：`CPU`/`ALLOC`/`LOCK`/`WALL`/`CTIMER`/`ITIMER`。
- **`Counter`** — `SAMPLES`/`TOTAL`。

> 支持多平台（linux-x64/arm64/macos…），从 jar 内提取对应原生库。

---

## 定位提示

> "命令结果怎么同时发给终端和 tunnel？" → `distribution/impl/SharingResultDistributorImpl`。
> "`watch` 里类名/方法名的通配匹配在哪？" → `util/matcher/WildcardMatcher`/`RegexMatcher`。
> "终端那些漂亮表格怎么画的？" → `view/TableView` + `view/Ansi`。
> "`profiler` 命令底层怎么调 async-profiler？" → `one/profiler/AsyncProfiler.execute()`。
> "arthas 启动参数怎么解析成对象的？" → `config/Configure` + `config/FeatureCodec`。
