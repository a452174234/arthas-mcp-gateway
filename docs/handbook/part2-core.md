# Part 2 · 001 诊断聚合核心实现

> 本部分深入「MCP 服务端装配 → 工具暴露 → 路由 → 统一拦截层 → 后端注册表与热重载 → 异步任务 → 自有工具 → 认证 → 可观测」的逐类逐方法实现。每节配代码片段（实读摘录，带 `file:line` 锚点）。

---

## 第 6 章 MCP 服务端装配（config 包）

### 6.1 GatewayProperties — 顶层配置属性

**文件**：`src/main/java/com/arthas/gateway/config/GatewayProperties.java:22`

**职责**：`@ConfigurationProperties(prefix="arthas-gateway")`，只持「文件位置 + 默认值」，**不绑定后端列表**（避免与 `@ConfigurationProperties` 一次性加载冲突 WatchService 热重载，注释 `:10-18`）。

```java
@ConfigurationProperties(prefix = "arthas-gateway")
public class GatewayProperties {
    private String backendsFile = "config/backends.yaml";   // 后端映射表
    private Task task = new Task();                          // 异步任务子段
    private K8s k8s = new K8s();                             // 003 K8S 编排子段
    private Admin admin = new Admin();                       // 004 portal 子段
    // ...
}
```

**嵌套子段**：

| 子段 | 字段 | 默认 | 说明 |
|------|------|------|------|
| `Task`（`:70-105`） | `backendTimeout` | `Duration.ofMinutes(11)`（>后端 10min 上限） | 异步后台兜底超时 |
| | `resultTtl` | `Duration.ofHours(1)` | 终态任务可查询保留时长 |
| | `globalMaxInflight` | `null`（未设→动态默认 `max(1, 后端数×5)`） | 全局背压上限 |
| `K8s`（`:115-231`，003） | `kubeconfig` | `test-env/k8s/kubeconfig/k3s-admin.yaml` | kubeconfig 文件路径 |
| | `namespace` | `default` | 默认命名空间 |
| | `nodePortRange` | `30000-32767` | NodePort 分配范围 |
| | `ensureTimeout` | `5min` | ensure 全流程超时 |
| | `targetIp` | `0.0.0.0` | arthas 绑定地址（NodePort 可达，R4） |
| | `arthasBootJar` | `tools/arthas-boot.jar` | 上传用静态工具 |
| | `mcpPort` | `8563` | pod 内 arthas MCP 端口 |
| | `arthasVersion` | `4.3.0` | arthas 版本 |
| | `arthasPassword` | `arthas-mcp-gateway`（缺省，生产应覆盖） | arthas 绑 0.0.0.0 强制鉴权 |
| `Admin`（`:239-288`，004） | `crud.enabled` | `true` | 后端 CRUD 开关 |
| | `export.enabled` | `true` | 任务导出/列表开关 |

### 6.2 GatewayMcpServerConfig — MCP 服务端 + 38 工具 + capabilities

**文件**：`src/main/java/com/arthas/gateway/config/GatewayMcpServerConfig.java:44`

**职责**：装配 MCP 服务端三件套——静态工具注册表、38 个 `SyncToolSpecification`、capabilities 锁定。

**`staticToolRegistry()` bean（`:56-58`）**：
```java
@Bean
StaticToolRegistry staticToolRegistry() {
    return StaticToolRegistry.fromClasspath(TOOLS_RESOURCE);  // "arthas-tools.json"
}
```

**`mcpToolSpecifications`（`:77-114`）—— 38 工具注册**：

35 静态工具经 `ToolsCallRouter.route`，3 K8S 自带闭包经 `K8sToolHandlers.handle`（kubeconfig 缺失时抛 `INVALID_PARAMS` 明确错误，而非启动期崩）：

```java
@Bean
List<SyncToolSpecification> mcpToolSpecifications(
        StaticToolRegistry registry, ToolsCallRouter router,
        ObjectProvider<K8sToolHandlers> k8sHandlersProvider) {
    List<SyncToolSpecification> specs = new ArrayList<>();
    // 35 静态工具（31 arthas + 4 自有）—— handler 委托 router
    for (ExposedTool exposed : registry.tools()) {
        Tool tool = McpSchema.Tool.builder()
            .name(exposed.name())
            .description(exposed.description())
            .inputSchema(exposed.inputSchema()).build();
        BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler =
            (exchange, request) -> router.route(exposed, request);
        specs.add(new SyncToolSpecification(tool, handler));
    }
    // 3 K8S 编排工具——自带闭包，绕过 router（gateway-core 零 K8S 感知，R6）
    for (ExposedTool exposed : K8sToolRegistry.tools()) {
        Tool tool = McpSchema.Tool.builder()...build();
        BiFunction<McpSyncServerExchange, CallToolRequest, CallToolResult> handler =
            (exchange, request) -> {
                K8sToolHandlers handlers = k8sHandlersProvider.getIfAvailable();
                if (handlers == null) {
                    throw McpError.builder(McpErrorCodes.INVALID_PARAMS)
                        .message("K8S 编排未启用：未配置可读的 arthas-gateway.k8s.kubeconfig ...")
                        .build();
                }
                return handlers.handle(exposed, request);
            };
        specs.add(new SyncToolSpecification(tool, handler));
    }
    return specs;
}
```

**`gatewayCapabilitiesCustomizer()`（`:133-144`）—— capabilities 锁定**：

`@Primary` + `McpSyncServerCustomizer`，覆盖 Spring AI starter 默认（starter 默认广播 prompts/resources/logging/completions 且 tools.listChanged 硬编码 true）：

```java
@Bean
@Primary
McpSyncServerCustomizer gatewayCapabilitiesCustomizer() {
    return spec -> {
        spec.immediateExecution(true);  // 保留 starter servlet customizer 职责
        spec.capabilities(McpSchema.ServerCapabilities.builder()
                .tools(false)  // listChanged=false（工具集静态）
                .build());
    };
}
```

→ 锁定「仅 tools、listChanged=false、不声明 prompts/resources/logging/completions」，与契约 S-INIT-2 一致。

### 6.3 TaskInfrastructureConfig — 异步任务装配

**文件**：`src/main/java/com/arthas/gateway/config/TaskInfrastructureConfig.java:26`

装配 `TaskStore` + `AsyncTaskExecutor`，从 `GatewayProperties.Task` 取默认值。

**全局背压动态默认（`:34-44`）**：
```java
@Bean
AsyncTaskExecutor asyncTaskExecutor(TaskStore store, GatewayProperties props, RegistryHolder holder) {
    return new AsyncTaskExecutor(store, props.getTask().getBackendTimeout(),
            () -> {
                Integer configured = props.getTask().getGlobalMaxInflight();
                if (configured != null) return configured;
                return Math.max(1, holder.current().size() * 5);  // 动态：后端数 × 5
            });
}
```

### 6.4 BackendRegistryBootstrap — 启动期装配注册表

**文件**：`src/main/java/com/arthas/gateway/config/BackendRegistryBootstrap.java:40`

**启动期一次性装配**：`backends.yaml → BackendConfigLoader → BackendEntryFactory.create → BackendRegistry → RegistryHolder`。

- 资源解析**先文件系统后 classpath**（`:75-81`），皆无则空注册表（不阻断启动）。
- **构造不连**（HttpBackendClient 不发 initialize）——首次路由才握手（懒连接，避免启动慢 + 不可达后端阻断启动）。

### 6.5 BackendConfigWatcherConfig — 热重载装配

**文件**：`src/main/java/com/arthas/gateway/config/BackendConfigWatcherConfig.java:32`

装配 `BackendConfigWatcher`（`destroyMethod="close"`），退役宽限 = `backendTimeout`（11min，保证 in-flight 异步任务能在 client 关闭前完成）。

### 6.6 DynamicRegistrationConfig — 动态注册装配（003）

**文件**：`src/main/java/com/arthas/gateway/config/DynamicRegistrationConfig.java:21`

装配 `RegistryComposer` + `DynamicBackendStore`。

**规避 watcher↔store 循环依赖**：
- watcher 经 `ObjectProvider<DynamicBackendStore>` 懒解析 store。
- store bean 以 `watcher::staticSnapshotNames`（I-3 冲突检测静态种子来源）+ `watcher::recomposeForDynamicChange`（onChange 回调）为构造入参（`:36-39`）。

---

## 第 7 章 工具注册与暴露（tool 包）

### 7.1 RoutingMode — 路由模式枚举

**文件**：`src/main/java/com/arthas/gateway/tool/RoutingMode.java:14`

| 值 | 用于 | 行为 |
|----|------|------|
| `SYNC_DIRECT` | 25 即时 arthas 工具（jvm/thread/sc/...） | 同步转发、原样透传 |
| `STREAM_AGGREGATE` | dashboard（1） | 同步（SDK 聚合 SSE 多帧） |
| `ASYNC_TASK` | 5 长任务（watch/trace/stack/tt/monitor） | 立即返 taskId，后台执行 |
| `GATEWAY_LOCAL` | 4 自有（list-targets/task-get/list/cancel） | 网关本地处理，不转发后端 |

### 7.2 TaskSupport — 任务支持枚举

**文件**：`src/main/java/com/arthas/gateway/tool/TaskSupport.java:17`

`FORBIDDEN`(26) / `OPTIONAL`(5) / `REQUIRED`(0)；`fromWire` 解析 JSON 小写线值（来自 `arthas-tools.json` 的 `taskSupport` 字段）。

### 7.3 ExposedTool — 工具不可变 record

**文件**：`src/main/java/com/arthas/gateway/tool/ExposedTool.java:19`

字段：`name/description/inputSchema/routingMode/taskSupport`。紧凑构造器对 inputSchema **深冻结**（`deepImmutable`，`:36-44`）防止外部修改泄漏到 `tools/list`。

### 7.4 StaticToolRegistry — 启动期构建 35 工具

**文件**：`src/main/java/com/arthas/gateway/tool/StaticToolRegistry.java:24`

**`fromClasspath("arthas-tools.json")`（`:63-80`）**：读 31 arthas schema → 注入 target → 追加 4 自有。

**`buildArthasTool`（`:84-112`）—— target 注入核心**：

```java
private static ExposedTool buildArthasTool(Map<String, Object> schema) {
    Map<String, Object> properties = (Map) schema.get("properties");
    properties.put("target", new LinkedHashMap<>(TARGET_SCHEMA));  // 注入 target
    schema.put("properties", properties);
    List<String> required = new ArrayList<>((List<String>) schema.getOrDefault("required", List.of()));
    required.add(0, "target");  // 进 required 首位
    schema.put("required", required);
    // ... 构建 ExposedTool（routingMode/taskSupport 据字段判定）
}
```

`TARGET_SCHEMA`（`:30-32`）：
```java
{ "type": "string", "description": "目标 JVM 逻辑名（见 list-targets），决定路由到哪个 arthas 后端" }
```

→ 每个 arthas 工具的 inputSchema 都被注入 `target`（required 首位），客户端调用时必须提供。

**`gatewayTools()`（`:115-147`）—— 4 自有工具**：

| 工具 | 参数 | routingMode |
|------|------|-------------|
| `arthas-gateway.list-targets` | 无 | GATEWAY_LOCAL |
| `arthas-gateway.task-get` | `taskId`（必填） | GATEWAY_LOCAL |
| `arthas-gateway.task-list` | `status`（可选过滤） | GATEWAY_LOCAL |
| `arthas-gateway.task-cancel` | `taskId`（必填） | GATEWAY_LOCAL |

**`snapshot()`（`:60-61`）**：返回不可变快照（`List.copyOf`），`tools/list` 用。

### 7.5 K8sToolRegistry — 3 K8S 工具规格（003）

**文件**：`src/main/java/com/arthas/gateway/orchestration/K8sToolRegistry.java`

| 工具 | 参数 | 说明 |
|------|------|------|
| `k8s.list-pods` | `namespace`? | 列 pod，含 hasJvm/hasShell 探测 |
| `k8s.list-services` | `namespace`? | 列 service |
| `k8s.ensure-arthas-mcp` | `server`/`pod`（必填）+ `namespace`? | 原子幂等供给 |

`routingMode=GATEWAY_LOCAL`（无 target 参数），但**实际不经 ToolsCallRouter**（handler 自带闭包，R6 决策）。

---

## 第 8 章 ToolsCallRouter — tools/call 唯一入口

**文件**：`src/main/java/com/arthas/gateway/handler/ToolsCallRouter.java:61`

`@Component`，35 静态工具的 `tools/call` 都经此路由（3 K8S 绕过）。

### 8.1 route — 分派入口（`:82-93`）

```java
public CallToolResult route(ExposedTool tool, CallToolRequest request) {
    if (tool.routingMode() == RoutingMode.GATEWAY_LOCAL) {
        return gatewayHandlers.handle(tool, request);  // 自有工具无 target，解析前分流
    }
    DiagnosticRequest dr = DiagnosticRequest.parse(tool, request);  // target 格式校验
    return switch (dr.routingMode()) {
        case SYNC_DIRECT, STREAM_AGGREGATE -> forwardSync(tool, dr);
        case ASYNC_TASK -> submitAsync(tool, dr);
        default -> throw new IllegalStateException("不可达：GATEWAY_LOCAL 已在 parse 前分流");
    };
}
```

### 8.2 forwardSync — 同步路径（`:99-121`）

```java
private CallToolResult forwardSync(ExposedTool tool, DiagnosticRequest dr) {
    BackendEntry entry = resolveTarget(dr);  // 不在册→INVALID_PARAMS+data.available
    try {
        return entry.execute(tool.name(), dr.backendArgs());  // 统一拦截层
    } catch (CircuitOpenException e) {
        throw backendUnreachableError(dr, e.retryAfterMs(), ...);
    } catch (ConcurrencyLimitException e) {
        throw concurrencyLimitError(entry, dr, e.maxConcurrentTasks());
    } catch (BackendUnreachableException e) {
        throw backendUnreachableError(dr, entry.breaker().retryAfterMillis(), ...);
    }
    // McpError（业务错误）不经此 catch，原样向上抛
}
```

### 8.3 submitAsync — 异步路径（`:124-156`）

```java
private CallToolResult submitAsync(ExposedTool tool, DiagnosticRequest dr) {
    BackendEntry entry = resolveTarget(dr);
    entry.admit(tool.name());  // STATELESS 校验 + 熔断 + 取槽
    GatewayTask task;
    try {
        task = asyncExecutor.submit(tool.name(), dr.target(),
            () -> entry.invoke(tool.name(), dr.backendArgs()),  // 后台闭包
            entry::releaseSlot);                                 // onTerminal=释放槽
    } catch (GlobalConcurrencyLimitException e) {
        entry.releaseSlot();  // 全局背压拒绝→显式释放 admit 已取的槽
        throw globalConcurrencyLimitError(dr, e.globalMaxInflight());
    }
    return asyncAcceptedResponse(task);  // 立即返 working
}
```

### 8.4 错误翻译（`:168-225`）—— 域异常 → 结构化 McpError

字段集与修复前**逐字一致**（FR-016）：

| 方法 | 触发 | data 字段 |
|------|------|-----------|
| `backendUnreachableError` | CircuitOpen/BackendUnreachable | `target/reason=backend_unreachable/available/retryAfterMs` |
| `statelessAsyncError` | StatelessAsync | `target/reason=stateless_unsupported_async/available` |
| `globalConcurrencyLimitError` | GlobalConcurrencyLimit | `target/reason=global_concurrency_limit/globalMaxInflight/available` |
| `concurrencyLimitError` | ConcurrencyLimit | `target/reason=concurrency_limit/maxConcurrentTasks` |

```java
private McpError backendUnreachableError(DiagnosticRequest dr, long retryAfterMs, String message) {
    return McpError.builder(McpErrorCodes.INVALID_PARAMS)
            .message(message)
            .data(Map.of(
                    "target", dr.target(),
                    "reason", "backend_unreachable",
                    "available", List.copyOf(registry.current().names()),
                    "retryAfterMs", retryAfterMs))
            .build();
}
```

**`available` 永远是当前注册表所有目标名快照**（`List.copyOf(registry.current().names())`），帮助调用方发现可用目标（S-ERR-2）。

### 8.5 asyncAcceptedResponse — 立即返 working（`:243-249`）

```java
private CallToolResult asyncAcceptedResponse(GatewayTask task) {
    return McpJson.json(Map.of(
            "taskId", task.taskId(),
            "status", "working",  // 固定（不重读 task 状态，避免与后台瞬时失败竞态）
            "_meta", Map.of("toolName", task.toolName(), "target", task.target())));
}
```

### 8.6 resolveTarget — 目标解析（`:228-237`）

```java
private BackendEntry resolveTarget(DiagnosticRequest dr) {
    return registry.current().get(dr.target())
        .orElseThrow(() -> McpError.builder(McpErrorCodes.INVALID_PARAMS)
            .message("未知 target：" + dr.target())
            .data(Map.of("target", dr.target(),
                         "reason", "unknown_target",
                         "available", List.copyOf(registry.current().names())))
            .build());
}
```

### 8.7 DiagnosticRequest — 瞬态值对象

**文件**：`src/main/java/com/arthas/gateway/handler/DiagnosticRequest.java:32`

`parse(tool, request)`（`:55-63`）：取 target → `requireTarget` → `stripTarget` → 构造。

- `requireTarget`（`:66-78`）：null/非 String/空白 → `McpError(INVALID_PARAMS)`。
- `stripTarget`（`:81-88`）：`LinkedHashMap` 拷贝后 `remove("target")`，**target 永不进 backendArgs**。
- 紧凑构造器（`:37-45`）：`backendArgs` 用 `Collections.unmodifiableMap(new LinkedHashMap<>(...))`——**容忍 null value**（MCP SDK 反序列化可选参数可能传 `{target:"x",timeout:null}`，`Map.copyOf` 抛 NPE 会误伤，002 整改 P2-2）。

---

## 第 9 章 BackendEntry — 统一拦截层（002 整改核心）

**文件**：`src/main/java/com/arthas/gateway/backend/BackendEntry.java:45`

**职责**：聚合 `config + client + breaker + taskSlots(Semaphore) + state + initialized`，是同步/异步共用的「熔断守卫 + 取/还槽 + initialize + 故障分类」入口。002 整改把这四原语从散在 `ToolsCallRouter` 两路径收口到此（统一拦截层）。

### 9.1 字段（`:47-53`）

```java
private final BackendConfig config;
private final BackendClient client;
private final CircuitBreaker breaker;
private final Semaphore taskSlots;             // = maxConcurrentTasks（默认 5）
private final Object initLock = new Object();
private volatile BackendState state = BackendState.ACTIVE;
private volatile boolean initialized = false;
```

### 9.2 execute — 同步入口（`:94-101`）

```java
public CallToolResult execute(String toolName, Map<String, Object> backendArgs) {
    admitCore();                    // 熔断守卫 + 取槽
    try {
        return invoke(toolName, backendArgs);
    } finally {
        releaseSlot();              // RAII，任意路径必释放（P0-2）
    }
}
```

**不经 STATELESS 校验**（STATELESS 同步仍可用）。

### 9.3 admit — 异步前置准入（`:120-125`）

```java
public void admit(String toolName) {
    if (config.protocol() == Protocol.STATELESS) {
        throw new StatelessAsyncException();  // 仅异步路径拒绝（P1-2）
    }
    admitCore();
}
```

### 9.4 admitCore — 熔断 + 取槽（`:128-135`）

```java
private void admitCore() {
    if (!breaker.allowRequest()) {
        throw new CircuitOpenException(breaker.retryAfterMillis());
    }
    if (!taskSlots.tryAcquire()) {
        throw new ConcurrencyLimitException(config.maxConcurrentTasks());
    }
}
```

### 9.5 invoke — 故障分类核心（`:149-166`）

```java
public CallToolResult invoke(String toolName, Map<String, Object> backendArgs) {
    try {
        initializeOnce();                                  // DCL 幂等 initialize
        CallToolResult result = client.callTool(toolName, backendArgs);
        breaker.recordSuccess();                           // 正常（含 isError=true）不计熔断
        return result;
    } catch (McpError e) {                                 // 后端业务错误
        breaker.recordSuccess();                           // 不计熔断（C-CB-2）
        throw e;                                           // 原样向上抛
    } catch (RuntimeException e) {
        if (Thread.currentThread().isInterrupted()) {      // cancel 中断不计熔断（P1-3）
            throw new BackendUnreachableException(e);
        }
        breaker.recordFailure();                           // 基础设施故障计入（C-CB-1）
        throw new BackendUnreachableException(e);
    }
}
```

**故障分类裁决**：
- 基础设施故障（连接拒绝/超时、initialize 失败、SSE 中断、读超时）→ `recordFailure`（→ 熔断）。
- 后端业务错误（`isError=true`/INVALID_PARAMS/McpError）→ `recordSuccess`（→ 不计熔断，原样抛）。
- cancel 中断（`Thread.interrupted()`）→ 不计熔断（避免 cancel 误开）。

### 9.6 initializeOnce — 双检锁握手（`:175-185`，P1-4）

```java
private void initializeOnce() {
    if (initialized) return;                    // 一读
    synchronized (initLock) {
        if (!initialized) {                     // 二读
            client.initialize();
            initialized = true;
        }
    }
}
```

**为何不用 CAS（裸 compareAndSet）**：CAS 会让 loser（竞态失败方）立即 `callTool`，而 winner 的 `initialize` 可能尚未完成 → loser 抢跑在未初始化会话上 callTool，状态紊乱。DCL 保证「恰好一次 + 其余等待」。

### 9.7 isHealthy — 健康单一事实源（`:188-190`）

```java
public boolean isHealthy() {
    return state == BackendState.ACTIVE && breaker.state() != CircuitBreaker.State.OPEN;
}
```

→ `list-targets` / `BackendRegistryHealthIndicator` / `admitCore`（经 breaker.allowRequest）三处共用，**单一事实源**（002 整改 P3-2，FR-012）。

### 9.8 markRetired — 热重载移除标记（`:80-82`）

热重载移除时标 `RETIRED`（in-flight 可完成、新调用不再路由到此）；延迟 `retirementGrace` 后 `close` client。

---

## 第 10 章 BackendClient / HttpBackendClient

### 10.1 BackendClient — 客户端契约接口

**文件**：`src/main/java/com/arthas/gateway/backend/BackendClient.java:44`

```java
public interface BackendClient {
    void initialize();
    CallToolResult callTool(String toolName, Map<String, Object> arguments);
    boolean isInitialized();
    void close();
}
```

**故障语义**（注释 `:30-40`）：基础设施故障抛 `RuntimeException`（→`recordFailure`）；业务错误返 `isError=true` 或抛 `McpError`（→`recordSuccess`，**不计熔断**）。

### 10.2 HttpBackendClient — 官方 SDK 封装

**文件**：`src/main/java/com/arthas/gateway/backend/HttpBackendClient.java:42`

**装配（`:53-63`）**：

```java
McpClientTransport transport = HttpClientStreamableHttpTransport.builder(config.url())
    .connectTimeout(Duration.ofMillis(config.connectTimeoutMs()))
    .httpRequestCustomizer(BackendAuthCustomizer.forBackend(config))  // 出站认证
    .build();
this.client = McpClient.sync(transport)
    .clientInfo(new Implementation("arthas-mcp-gateway", "0.1.0"))
    .requestTimeout(Duration.ofMillis(config.callTimeoutMs()))
    .build();
```

**callTool（`:74-76`）**：
```java
public CallToolResult callTool(String toolName, Map<String, Object> arguments) {
    return client.callTool(new CallToolRequest(toolName, arguments));  // content/isError/_meta 原样
}
```

**端点为根 URL**（注释 `:25-26`）：arthas 4.3.0 MCP 端点无 `/mcp` 后缀（T009 实测裁决），故 `config.url()` 直接用 `http://host:8563`（非 `/mcp`）。

**initialize（`:65-72`）**：`client.initialize()` 经 SDK 完成 MCP 握手（`initialize` → `initialized`）。

---

## 第 11 章 后端注册表与热重载（backend 包）

### 11.1 BackendConfig — 不可变配置 record

**文件**：`src/main/java/com/arthas/gateway/backend/BackendConfig.java:21`

record（`:21-29`）：`name/url/protocol/auth/connectTimeoutMs/callTimeoutMs/maxConcurrentTasks/source`。

**紧凑构造器（`:31-52`）**：
- `requireHttpUrl` 校验 http(s)+host。
- `maxConcurrentTasks ∈ [1,5]`（后端硬上限，`BackendConfig.java:44-47`）。
- source 缺省 `STATIC`。

**equals/hashCode 显式排除 source（`:71-94`）**：仅 7 字段参与复用判定——同核心字段、异 source 仍 equal，保连接池复用语义稳定（热重载时 `BackendRegistryReloader` 据此复用旧 Entry）。

**内嵌 Auth（`:126-165`）**：
- 按 mode 校验（BEARER→token / BASIC→user+pass / NONE→无）。
- `toString` **脱敏**——`**** + 末 2 位`（≤2 位仅 `****`），防凭据泄漏（002 整改 P3-1）。

### 11.2 BackendConfigLoader — YAML 解析（纯逻辑）

**文件**：`src/main/java/com/arthas/gateway/backend/BackendConfigLoader.java:33`

不依赖 Spring（用 SnakeYAML，Spring Boot 传递带入）。`load(InputStream) → LoadedBackends(version, backends)`。

**readVersion（`:80-92`）—— 严格整数**：
```java
// 仅 Integer/Long 通过，拒浮点
if (raw instanceof Number n) {
    if (n instanceof Integer || n instanceof Long) return n.longValue();
    throw new BackendConfigException("version 须整数，实得 " + raw + "（浮点/双精度拒绝）");
}
```
（002 整改 P3-4：修复前 `n.longValue()` 接受 `1.0` 静默截断）

**toBackendConfig（`:117-137`）**：默认值 `protocol=STREAMABLE`/`connect=5000`/`call=30000`/`maxConcurrent=5`/`source=STATIC`。

**resolvePlaceholder（`:163-175`）**：`${VAR}` / `${VAR:default}` 整值匹配，机密字段从环境变量取值（避免明文入库）。

**asInt（`:197-208`）**：严格——拒 `Double/Float`、拒超 int 范围的 Long，**报错保留原始值**。

**跨实例 name 唯一校验（`:103-114`，`seen.add`）**。

### 11.3 BackendRegistry — 不可变快照 record

**文件**：`src/main/java/com/arthas/gateway/backend/BackendRegistry.java:20`

```java
public record BackendRegistry(long version, Map<String, BackendEntry> byName) {
    public BackendRegistry {
        byName = Map.copyOf(byName);  // 防御性不可变拷贝
    }
    // get(name) / names() / size()
}
```

### 11.4 RegistryHolder — AtomicReference 持有者

**文件**：`src/main/java/com/arthas/gateway/backend/RegistryHolder.java:19`

```java
@Component
public class RegistryHolder {
    private final AtomicReference<BackendRegistry> ref = new AtomicReference<>(BackendRegistry.empty());
    public BackendRegistry current() { return ref.get(); }
    public BackendRegistry getAndSet(BackendRegistry next) { return ref.getAndSet(next); }
    public Optional<BackendEntry> get(String name) { return current().get(name); }
}
```

→ **一次 `tools/call` 全程持固定 BackendEntry 引用**（`final` 局部变量），registry 中途替换不影响 in-flight（"热重载/动态注册并发不串台"，I-1）。

### 11.5 BackendEntryFactory — 构造三件套

**文件**：`src/main/java/com/arthas/gateway/backend/BackendEntryFactory.java:22`

`@Component`，时钟可注入（`System::nanoTime` 默认，便于单测驱动熔断退避）：

```java
public BackendEntry create(BackendConfig config) {
    BackendClient client = new HttpBackendClient(config);          // 独立连接池+会话+SSE
    CircuitBreaker breaker = CircuitBreaker.create(clock);         // 独立熔断
    return new BackendEntry(config, client, breaker);              // 独立 Semaphore
}
```

### 11.6 BackendConfigWatcher — WatchService 热重载

**文件**：`src/main/java/com/arthas/gateway/backend/BackendConfigWatcher.java:40`

**常量（`:44-48`）**：`DEBOUNCE=500ms`、`POLL_INTERVAL=200ms`、`SHUTDOWN_AWAIT=2s`。

**线程模型**：
- 监听虚拟线程 `backend-config-watcher`（`:123`）。
- 退役调度器 `retireScheduler`（`:71-72`，单线程虚拟线程 `ScheduledExecutorService`，002 整改 P2-1：旧裸虚拟线程改 ScheduledExecutorService 才能延迟调度）。

**watchLoop（`:129-157`）**：poll 收集目标文件事件 → 防抖 500ms → `reloadOnce`。

**reloadOnce（`:163-184`）**：
```java
LoadedBackends loaded;
try (InputStream in = Files.newInputStream(configFile)) {
    loaded = loader.load(in);
}
ReloadResult result = reloader.reload(staticSnapshot, loaded);
if (!result.changed()) return;  // version 去重
staticSnapshot = result.registry();
applyCompose();  // 合并动态 → 原子替换
```

失败保留旧表（catch BackendConfigException/IOException，记 ERROR，不半替换，§11 规则 7）。

**applyCompose（`:212-221`）**：合并 staticSnapshot + 动态列表 → `holder.getAndSet` 原子替换 + `retireAll`（**保证热重载不误删动态 target**，I-2）。

**retireAll（`:229-235`）**：
```java
private void retireAll(List<BackendEntry> toRetire) {
    for (BackendEntry entry : toRetire) {
        entry.markRetired();  // 立即；新调用不再路由
    }
    if (!toRetire.isEmpty()) {
        retireScheduler.schedule(() -> {
            for (BackendEntry entry : toRetire) closeRetiredClient(entry);
        }, retirementGrace.toMillis(), TimeUnit.MILLISECONDS);  // 11min 后 close
    }
}
```

**recomposeForDynamicChange（`:190-196`）**：动态注册/注销触发，调 `applyCompose` 重算 effective。

**staticSnapshotNames（`:199-201`）**：供 `DynamicBackendStore` 做静态种子名冲突检测（I-3）。

**close（`:248-272`）**：关 WatchService + 中断监听线程 + retireScheduler `shutdown + awaitTermination(2s)`。

### 11.7 BackendRegistryReloader — 静态 diff 纯逻辑

**文件**：`src/main/java/com/arthas/gateway/backend/BackendRegistryReloader.java:36`

`reload(current, next) → ReloadResult`（`:51-76`）：
- `next.version == current.version` → `unchanged`（`:54-57`，version 去重）。
- 遍历新 backends：name 在旧表且 `BackendConfig.equals`（不含 source）→ **复用旧 Entry**（保连接池/session，`:61-65`）；否则 `factory.create` 新建。
- 旧表未被复用的 Entry → `toRetire`（`:69-74`）。

### 11.8 RegistryComposer — 静态∪动态合并

**文件**：`src/main/java/com/arthas/gateway/backend/RegistryComposer.java:29`

`compose(previous, staticReg, dynamic) → ComposeResult`（`:47-93`）：
- 静态优先复用 previous 同 config 旧实例，否则用 staticReg 已构造的 Entry。
- 动态同理复用 previous，否则 `factory.create`。
- 未复用旧 Entry → toRetire。
- 内嵌 `AtomicLong versionSeq`（`:33`），无变更不递增（`sameEffective` 名字集相同且每 target 同实例 identity，`:99-109`）。

### 11.9 DynamicBackendStore — 动态 target 入口（003）

**文件**：`src/main/java/com/arthas/gateway/backend/DynamicBackendStore.java:26`

`ConcurrentHashMap<name, BackendConfig>`，程序化写入动态 target 的**唯一入口**。

**register（`:47-69`）—— 三层冲突检测**：
```java
public void register(BackendConfig cfg) {
    if (cfg.source() != Source.DYNAMIC) throw new BackendConfigException("动态注册须 source=DYNAMIC...");
    String name = cfg.name();
    if (staticNames.get().contains(name))  // 1. 动态名 ∩ 静态种子名 → 拒绝（保护静态）
        throw new BackendConfigException("动态注册名与静态种子冲突，拒绝：" + name);
    BackendConfig existing = byName.get(name);
    if (existing != null) {
        if (!existing.url().equals(cfg.url()))  // 2. 同名异 URL → 拒绝
            throw new BackendConfigException("动态注册名与既有动态同名异 URL，拒绝：...");
        if (existing.equals(cfg)) return;       // 3. 同名同 URL 同字段 → 幂等（无回调）
    }
    byName.put(name, cfg);
    onChange.run();  // 触发 compose → holder.getAndSet
}
```

**unregister（`:77-84`）**：仅 DYNAMIC 可移（store 仅持动态）；不存在 → 幂等无操作。

**list（`:87-89`）**：返回动态配置列表（不可变）。

### 11.10 枚举与域异常

- `Protocol`（`Protocol.java:13`）：`STREAMABLE`（有状态、支持 task）/ `STATELESS`（不支持 task）。
- `BackendState`（`BackendState.java:12`）：`ACTIVE`/`RETIRED`。
- `Source`（`Source.java:17`）：`STATIC`（YAML 种子）/ `DYNAMIC`（程序注册）。
- `AuthMode`（`AuthMode.java:14`）：`NONE/BEARER/BASIC`。
- 域异常（不依赖 McpError/注册表）：`CircuitOpenException(retryAfterMs)` / `ConcurrencyLimitException(maxConcurrentTasks)` / `StatelessAsyncException` / `BackendUnreachableException(cause)` / `BackendConfigException` / `GlobalConcurrencyLimitException(globalMaxInflight)`。

---

## 第 12 章 异步任务系统（task 包）

### 12.1 GatewayTask — 应用层任务实体

**文件**：`src/main/java/com/arthas/gateway/task/GatewayTask.java:24`

字段（`:26-35`）：`taskId/toolName/target/createdAt`（不可变）+ `volatile status=WORKING / result / error / completedAt`。

**转换方法 `synchronized`**（原子终态检查 + 赋值）：
- `markCompleted(CallToolResult)`（`:55-63`）：原样保留（**含 isError=true**，G-TG-2）。
- `markFailed(TaskError)`（`:66-74`）/ `markCancelled()`（`:77-84`）。
- 终态返 false（不覆盖，兼容 cancel 与后台迟到的竞争——先到者赢）。

### 12.2 TaskState / TaskError

**文件**：`src/main/java/com/arthas/gateway/task/TaskState.java:15` + `TaskError.java:14`

- `TaskState`：`WORKING/COMPLETED/FAILED/CANCELLED`（除 WORKING 均终态不可逆）；`isTerminal()` = `this != WORKING`。
- `TaskError`：常量 `REASON_BACKEND_TIMEOUT="backend_timeout"` / `REASON_BACKEND_UNREACHABLE="backend_unreachable"`。

### 12.3 AsyncTaskExecutor — 方案 C 异步编排

**文件**：`src/main/java/com/arthas/gateway/task/AsyncTaskExecutor.java:57`

**嵌套 submit 模式**（外层 supervisor 等内层 worker）+ 全局背压 AtomicInteger。

**线程池（`:98`）**：`Executors.newVirtualThreadPerTaskExecutor()`（虚拟线程，每任务一线程，互不阻塞）。

**submit（`:143-169`）核心**：
```java
public GatewayTask submit(String toolName, String target,
                          Supplier<CallToolResult> backendWork, Runnable onTerminal) {
    acquireGlobalInflight();                          // 全局背压（P2-4）
    String taskId = taskIdGenerator.get();
    GatewayTask task = new GatewayTask(taskId, toolName, target, clock.get(), clock);
    store.put(task);
    Future<?> supervisor;
    try {
        supervisor = pool.submit(() -> orchestrate(task, backendWork, onTerminal));
    } catch (RejectedExecutionException ree) {        // 外层拒绝→清理僵尸 + 释放槽/背压
        store.remove(taskId);                         // P0-1
        onTerminal.run();                             // P0-2 释放槽
        releaseGlobalInflight();
        throw ree;
    }
    supervisorFutures.put(taskId, supervisor);
    return task;
}
```

**orchestrate（`:172-201`）—— 后台编排**：
```java
private void orchestrate(GatewayTask task, Supplier<CallToolResult> backendWork, Runnable onTerminal) {
    Future<CallToolResult> worker = null;
    try {
        worker = pool.submit(backendWork::get);                            // 内层 worker 调后端
        CallToolResult result = worker.get(callTimeout.toMillis(), MILLISECONDS);
        task.markCompleted(result);                                        // isError=true 原样（G-TG-2）
    } catch (TimeoutException te) {
        worker.cancel(true);                                               // 中断后端 HTTP
        task.markFailed(new TaskError(REASON_BACKEND_TIMEOUT, "后端 " + callTimeout + " 内未返回"));
    } catch (ExecutionException ee) {
        worker.cancel(true);
        task.markFailed(toTaskError(ee.getCause()));
    } catch (InterruptedException ie) {                                    // cancel 触发
        Thread.currentThread().interrupt();
        worker.cancel(true);
        task.markCancelled();
    } finally {
        supervisorFutures.remove(task.taskId());
        onTerminal.run();           // 任一终态释放 per-target 槽（P0-2）
        releaseGlobalInflight();    // 任一终态释放全局背压（P2-4）
    }
}
```

**cancel（`:208-218`）**：`task.markCancelled()` 后中断 supervisor → orchestrate 捕获 InterruptedException → worker.cancel(true) 中断后端调用。

**acquireGlobalInflight（`:236-245`）**：
```java
private void acquireGlobalInflight() {
    int cap = globalInflightCap.get();
    if (cap <= 0) throw new GlobalConcurrencyLimitException(cap);  // 注册表空直接拒
    if (globalInflight.incrementAndGet() > cap) {
        globalInflight.decrementAndGet();                           // CAS 回滚
        throw new GlobalConcurrencyLimitException(cap);
    }
}
```

**defaultTaskIdGenerator（`:272-280`）**：`"t-" + 6 hex`（SecureRandom）。

### 12.4 TaskStore — ConcurrentHashMap + TTL 清理

**文件**：`src/main/java/com/arthas/gateway/task/TaskStore.java:38`

`ttl` + `clock` + 守护虚拟线程 `cleaner`（`:55-57`，周期 `max(60, ttl/4)` 秒）。

**三条清理路径**（002 整改 P2-3，避免逐条 task-get 的 O(N) 读放大）：

| 路径 | 方法 | 清理范围 |
|------|------|----------|
| 单条惰性 | `get(taskId)`（`:85-95`） | 仅判定目标单条——存在且未过期→返回；过期→定向 `remove` 返 empty；**不**触发全表扫描 |
| 全表惰性 | `list()`/`list(status)`（`:108-120`） | 访问时调 `cleanExpired()` 全表清理，过期任务对读不可见 |
| 主动 | `cleaner` 守护线程（`:55-57`） | 周期 `cleanExpired`，兜底长时间不被访问的终态任务 |

**cleanExpired（`:127-138`）**：`entrySet().removeIf` 原子移除。

**isExpired（`:141-144`）**：
```java
private boolean isExpired(GatewayTask task, Instant now) {
    Instant completedAt = task.completedAt();
    return completedAt != null && now.isAfter(completedAt.plus(ttl));  // 终态 + 超 TTL；WORKING 永不过期
}
```

**remove（`:72-74`）**：执行器外层拒绝时清理僵尸（P0-1）。

**containsRawForTest（`:103-105`）**：测试探针，验证 get 是否触发全表清理（不触发=正确）。

---

## 第 13 章 网关自有工具（GatewayToolHandlers）

**文件**：`src/main/java/com/arthas/gateway/handler/GatewayToolHandlers.java:44`

`@Component`，4 自有工具本地处理（不转发后端）。按 `tool.name()` switch 分派（`:55-63`）。

### 13.1 list-targets（`:67-79`）

```java
private CallToolResult listTargets(CallToolRequest request) {
    BackendRegistry reg = registry.current();
    List<Map<String, Object>> targets = reg.byName().values().stream()
        .map(e -> Map.of(
            "name", e.config().name(),
            "state", e.state().name(),
            "healthy", e.isHealthy(),                // 健康单一事实源
            "protocol", e.config().protocol().name()))
        .toList();
    return McpJson.json(Map.of("targets", targets, "version", reg.version()));
}
```

### 13.2 task-get（`:83-114`）

`store.get(taskId)` 缺失 → `INVALID_PARAMS`。按 status 渲染：
- `working` → `toolName/target/createdAt`。
- `completed` → `toolName/target/completedAt/result(renderResult)`。
- `failed` → `error{reason, message}`。
- `cancelled` → 仅 `taskId/status`。

### 13.3 task-list（`:118-135`）

可选 `status` 过滤，`store.list(status)` → tasks 概要列表。

### 13.4 task-cancel（`:139-147`）

`executor.cancel(task)`（WORKING→CANCELLED，终态幂等返当前状态）。

### 13.5 renderResult（`:179-193`）

```java
private static Map<String, Object> renderResult(CallToolResult result) {
    return Map.of(
        "isError", result.isError(),  // 原样
        "content", result.content().stream()  // sealed Content 多态渲染为 [{type, text}]
            .map(c -> c instanceof TextContent tc ? Map.of("type", "text", "text", tc.text()) : ...)
            .toList());
}
```

→ 避免 sealed 多态 Jackson 配置问题。

### 13.6 McpJson / McpErrorCodes

- `McpJson`（`handler/McpJson.java:23`，002 整改 P3-3 全局单例）：`public static final ObjectMapper MAPPER = new ObjectMapper()`；`json(Object)` 返 `CallToolResult(TextContent(MAPPER.writeValueAsString(node)), false, null, null)`。消除散在多 mapper 实例。
- `McpErrorCodes`（`handler/McpErrorCodes.java:19`）：集中定义 5 个 JSON-RPC 标准码（`PARSE_ERROR=-32700`/`INVALID_REQUEST=-32600`/`METHOD_NOT_FOUND=-32601`/`INVALID_PARAMS=-32602`/`INTERNAL_ERROR=-32603`），避免裸字面量散落。

---

## 第 14 章 认证（auth 包）

### 14.1 BackendAuthCustomizer — 出站头注入

**文件**：`src/main/java/com/arthas/gateway/auth/BackendAuthCustomizer.java:28`

实现官方 SDK `McpSyncHttpClientRequestCustomizer`。构造时按 auth 预计算并缓存 `headerValue`（`:44-51`）：

```java
public static BackendAuthCustomizer forBackend(BackendConfig config) {
    BackendConfig.Auth auth = config.auth();
    String headerValue = switch (auth.mode()) {
        case NONE -> null;
        case BEARER -> "Bearer " + auth.token();
        case BASIC -> "Basic " + Base64.getEncoder().encodeToString(
            (auth.username() + ":" + auth.password()).getBytes(UTF_8));
    };
    return new BackendAuthCustomizer(headerValue);
}

public void customize(HttpRequest.Builder builder) {
    if (headerValue != null) builder.header("Authorization", headerValue);
}
```

→ 注入到 `HttpBackendClient` 的每个出站 HTTP 请求（连后端 arthas MCP）。

### 14.2 GatewayAuthenticator / NoopGatewayAuthenticator — 入站（MVP Noop）

**文件**：`src/main/java/com/arthas/gateway/auth/`

- `GatewayAuthenticator` 接口：入站 MCP 请求鉴权 seam。
- `NoopGatewayAuthenticator`：MVP 恒放行（受控内网假设）；为未来 Bearer token 演进预留 seam。

---

## 第 15 章 可观测性（obs 包）

### 15.1 BackendRegistryHealthIndicator

**文件**：`src/main/java/com/arthas/gateway/obs/BackendRegistryHealthIndicator.java:31`

`@Component`，实现 SB4 `org.springframework.boot.health.contributor.HealthIndicator`（新包，从 `actuate.health` 迁移，[memory sb4-health-package-moved]）。

**health()（`:40-67`）**：
```java
public Health health() {
    BackendRegistry reg = registry.current();
    Map<String, Object> backends = new LinkedHashMap<>();
    int healthy = 0;
    for (Map.Entry<String, BackendEntry> e : reg.byName().entrySet()) {
        BackendEntry entry = e.getValue();
        boolean h = entry.isHealthy();
        if (h) healthy++;
        backends.put(e.getKey(), Map.of(
            "state", entry.state().name(),
            "healthy", h,
            "protocol", entry.config().protocol().name(),
            "breaker", entry.breaker().state().name()));
    }
    return Health.up()  // 网关进程恒 UP（故障隔离，不因子后端挂而 DOWN）
        .withDetail("backends", backends)
        .withDetail("summary", Map.of("total", reg.size(), "healthy", healthy, "unhealthy", reg.size() - healthy))
        .build();
}
```

→ `/actuator/health` details 暴露全部后端 `state/healthy/protocol/breaker` + `summary`，运维无需读源码（宪法原则五）。

### 15.2 结构化日志

关键节点 INFO/WARN 日志（含 `tool`/`target`/`taskId`/`elapsedMs`）：
- `ToolsCallRouter`：工具调用完成、异步任务接受。
- `BackendEntry`：基础设施故障（计入熔断）、熔断拒绝。
- `AsyncTaskExecutor`：任务终态（completed/failed/cancelled）。
- `BackendConfigWatcher`：热重载生效（version 变化）。
- `ArthasProvisioner`（003）：ensure 各步进度。

---

> **下一步**：Part 3 深入 002 韧性设计（熔断/限流/超时/隔离/错误结构化 + 15 项整改 + 契约测试体系）。
