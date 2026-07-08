# Part 10 · 关键类源码摘录（逐段解读）

> 本部分摘录网关最核心的 12 个类的源码（实读当前实现），逐段中文解读「这段做什么、为什么这样写、关键不变量」。这是手册的「代码级字典」—— 查任一类的实现细节。

> **约定**：源码按 `file:line` 锚点摘录，省略号 `// ...` 表示为简洁省略的非关键部分（import/日志/相似方法）。完整源码请读原文件。

---

## 第 73 章 ToolsCallRouter（tools/call 唯一入口）

**文件**：`src/main/java/com/arthas/gateway/handler/ToolsCallRouter.java:61`

```java
@Component
public class ToolsCallRouter {

    private final GatewayToolHandlers gatewayHandlers;
    private final RegistryHolder registry;
    private final AsyncTaskExecutor asyncExecutor;

    // 构造注入（Spring 自动装配）
    public ToolsCallRouter(GatewayToolHandlers gatewayHandlers,
                           RegistryHolder registry,
                           AsyncTaskExecutor asyncExecutor) { ... }

    // === 核心入口：每个静态工具的 handler 都调此方法 ===
    public CallToolResult route(ExposedTool tool, CallToolRequest request) {
        // 1. 网关自有工具：无 target，解析前分流（避免 target 缺失误报）
        if (tool.routingMode() == RoutingMode.GATEWAY_LOCAL) {
            return gatewayHandlers.handle(tool, request);
        }
        // 2. 解析 target + 校验（缺失/非 String/空白 → INVALID_PARAMS）
        DiagnosticRequest dr = DiagnosticRequest.parse(tool, request);
        // 3. 按 routingMode 分派
        return switch (dr.routingMode()) {
            case SYNC_DIRECT, STREAM_AGGREGATE -> forwardSync(tool, dr);
            case ASYNC_TASK -> submitAsync(tool, dr);
            default -> throw new IllegalStateException("不可达：GATEWAY_LOCAL 已在 parse 前分流");
        };
    }

    // === 同步路径 ===
    private CallToolResult forwardSync(ExposedTool tool, DiagnosticRequest dr) {
        BackendEntry entry = resolveTarget(dr);  // 不在册 → INVALID_PARAMS + available
        try {
            return entry.execute(tool.name(), dr.backendArgs());  // 统一拦截层
        } catch (CircuitOpenException e) {
            throw backendUnreachableError(dr, e.retryAfterMs(), "目标熔断中：" + dr.target());
        } catch (ConcurrencyLimitException e) {
            throw concurrencyLimitError(entry, dr, e.maxConcurrentTasks());
        } catch (BackendUnreachableException e) {
            throw backendUnreachableError(dr, entry.breaker().retryAfterMillis(), "目标不可达：" + dr.target());
        }
        // 注：McpError（后端业务错误）不经此 catch，原样向上抛
    }

    // === 异步路径 ===
    private CallToolResult submitAsync(ExposedTool tool, DiagnosticRequest dr) {
        BackendEntry entry = resolveTarget(dr);
        entry.admit(tool.name());  // STATELESS 校验 + 熔断 + 取槽
        GatewayTask task;
        try {
            task = asyncExecutor.submit(
                tool.name(), dr.target(),
                () -> entry.invoke(tool.name(), dr.backendArgs()),  // 后台闭包
                entry::releaseSlot);                                  // onTerminal=释放槽
        } catch (GlobalConcurrencyLimitException e) {
            entry.releaseSlot();  // 全局背压拒绝 → 显式释放 admit 已取的槽（P0-2）
            throw globalConcurrencyLimitError(dr, e.globalMaxInflight());
        }
        return asyncAcceptedResponse(task);  // 立即返 working
    }

    // === target 解析 ===
    private BackendEntry resolveTarget(DiagnosticRequest dr) {
        return registry.current().get(dr.target())
            .orElseThrow(() -> McpError.builder(McpErrorCodes.INVALID_PARAMS)
                .message("未知 target：" + dr.target())
                .data(Map.of("target", dr.target(),
                             "reason", "unknown_target",
                             "available", List.copyOf(registry.current().names())))
                .build());
    }

    // === 异步接受响应（固定 working） ===
    private CallToolResult asyncAcceptedResponse(GatewayTask task) {
        return McpJson.json(Map.of(
            "taskId", task.taskId(),
            "status", "working",  // 固定（不重读 task 状态，避免与后台瞬时失败竞态）
            "_meta", Map.of("toolName", task.toolName(), "target", task.target())));
    }

    // === 错误翻译（域异常 → 结构化 McpError） ===
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
    // concurrencyLimitError / statelessAsyncError / globalConcurrencyLimitError 类似（不同 reason + 字段）
}
```

**逐段解读**：
- **route（:82-93）**：GATEWAY_LOCAL 在 parse 前分流——因自有工具无 target，若先 parse 会因 target 缺失误报 INVALID_PARAMS。这是「解析顺序」的关键设计。
- **forwardSync（:99-121）**：catch 三类域异常翻译为 McpError；**McpError 不经 catch**（业务错误原样向上抛，透传给客户端）。
- **submitAsync（:124-156）**：`admit` 取槽，`submit` 提交后台；GlobalConcurrencyLimit 拒绝时**显式 releaseSlot**（admit 已取的槽不漏，P0-2）。
- **asyncAcceptedResponse（:243-249）**：固定 `status:"working"`，不重读 task 状态——避免「后台刚 submit 就失败」的竞态导致返 completed 却说 working。
- **错误翻译**：`available` 永远是当前注册表快照，帮调用方发现可用目标。

---

## 第 74 章 BackendEntry（统一拦截层）

**文件**：`src/main/java/com/arthas/gateway/backend/BackendEntry.java:45`

```java
public final class BackendEntry {

    private final BackendConfig config;
    private final BackendClient client;
    private final CircuitBreaker breaker;
    private final Semaphore taskSlots;             // = maxConcurrentTasks（默认 5）
    private final Object initLock = new Object();
    private volatile BackendState state = BackendState.ACTIVE;
    private volatile boolean initialized = false;

    public BackendEntry(BackendConfig config, BackendClient client, CircuitBreaker breaker) {
        this.config = config;
        this.client = client;
        this.breaker = breaker;
        this.taskSlots = new Semaphore(config.maxConcurrentTasks());
    }

    // === 同步入口（RAII 释放槽） ===
    public CallToolResult execute(String toolName, Map<String, Object> backendArgs) {
        admitCore();                    // 熔断守卫 + 取槽
        try {
            return invoke(toolName, backendArgs);
        } finally {
            releaseSlot();              // 任意路径必释放（P0-2）
        }
    }

    // === 异步前置准入（仅异步路径校验 STATELESS） ===
    public void admit(String toolName) {
        if (config.protocol() == Protocol.STATELESS) {
            throw new StatelessAsyncException();  // 仅异步拒绝（P1-2）
        }
        admitCore();
    }

    // === 熔断守卫 + 取槽（同步/异步共用） ===
    private void admitCore() {
        if (!breaker.allowRequest()) {
            throw new CircuitOpenException(breaker.retryAfterMillis());
        }
        if (!taskSlots.tryAcquire()) {
            throw new ConcurrencyLimitException(config.maxConcurrentTasks());
        }
    }

    public void releaseSlot() {
        taskSlots.release();
    }

    // === 故障分类核心 ===
    public CallToolResult invoke(String toolName, Map<String, Object> backendArgs) {
        try {
            initializeOnce();                                  // DCL 幂等握手
            CallToolResult result = client.callTool(toolName, backendArgs);
            breaker.recordSuccess();                           // 正常（含 isError=true）不计熔断
            return result;
        } catch (McpError e) {                                 // 后端业务错误
            breaker.recordSuccess();                           // 不计熔断（C-CB-2）
            throw e;                                           // 原样抛
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {      // cancel 中断不计熔断（P1-3）
                throw new BackendUnreachableException(e);
            }
            breaker.recordFailure();                           // 基础设施故障计入（C-CB-1）
            throw new BackendUnreachableException(e);
        }
    }

    // === 双检锁握手（P1-4 原子） ===
    private void initializeOnce() {
        if (initialized) return;                    // 一读（volatile，无锁快路径）
        synchronized (initLock) {
            if (!initialized) {                     // 二读（同步块内再确认）
                client.initialize();
                initialized = true;
            }
        }
    }

    // === 健康单一事实源（P3-2） ===
    public boolean isHealthy() {
        return state == BackendState.ACTIVE && breaker.state() != CircuitBreaker.State.OPEN;
    }

    public void markRetired() {
        state = BackendState.RETIRED;
    }

    // getter: config / client / breaker / state
}
```

**逐段解读**：
- **execute（:94-101）**：RAII 模式——`admitCore` 取槽，`finally releaseSlot` 释放，**槽结构性不漏**（无论 invoke 成功/抛异常都释放，P0-2）。
- **admit（:120-125）**：异步路径多一道 STATELESS 校验（同步 execute 不校验，因 STATELESS 同步仍可用，P1-2）。
- **invoke（:149-166）**：**故障分类核心**——区分「业务错误（McpError/isError）」与「基础设施故障（RuntimeException）」。业务错误不计熔断（后端正常响应了，只是业务逻辑错）；基础设施故障计熔断（连接/超时等）。cancel 中断（`Thread.interrupted()`）跳过 recordFailure（避免 cancel 误开熔断，P1-3）。
- **initializeOnce（:175-185）**：双检锁（DCL）——不用裸 CAS 是因 CAS 的 loser 会立即 callTool，而 winner 的 initialize 可能未完成 → loser 抢跑在未初始化会话上。DCL 保证「恰好一次 + 其余等待」（P1-4）。
- **isHealthy（:188-190）**：单一事实源——list-targets / HealthIndicator / admitCore（经 breaker.allowRequest）三处共用，避免重复判定（P3-2）。

---

## 第 75 章 CircuitBreaker（per-target 熔断状态机）

**文件**：`src/main/java/com/arthas/gateway/backend/CircuitBreaker.java:32`

```java
public final class CircuitBreaker {

    public static final int DEFAULT_FAILURE_THRESHOLD = 3;
    public static final Duration DEFAULT_BASE_BACKOFF = Duration.ofSeconds(1);
    public static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(30);

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long baseBackoffNanos;
    private final long maxBackoffNanos;
    private final LongSupplier nanoClock;            // 可注入（测试驱动退避）

    private State state = State.CLOSED;
    private int consecutiveFailures = 0;
    private int consecutiveOpens = 0;
    private long currentBackoffNanos;
    private long openedAtNanos;

    public static CircuitBreaker create(LongSupplier nanoClock) {
        return new CircuitBreaker(DEFAULT_FAILURE_THRESHOLD, DEFAULT_BASE_BACKOFF, DEFAULT_MAX_BACKOFF, nanoClock);
    }

    public synchronized State state() { return state; }

    // === 准入判定（OPEN 满 retireAfterMs 转 HALF_OPEN 放 1 探测） ===
    public synchronized boolean allowRequest() {
        return switch (state) {
            case CLOSED -> true;
            case OPEN -> {
                if (nanoClock.getAsLong() - openedAtNanos >= currentBackoffNanos) {
                    state = State.HALF_OPEN;
                    yield true;   // 退避满 → 放 1 探测
                }
                yield false;
            }
            case HALF_OPEN -> false;   // 探测在途，第二个被拒（仅放 1 个）
        };
    }

    // === OPEN 时剩余退避（毫秒） ===
    public synchronized long retryAfterMillis() {
        if (state != State.OPEN) return 0L;
        long remainingNanos = currentBackoffNanos - (nanoClock.getAsLong() - openedAtNanos);
        return Math.max(0L, Duration.ofNanos(remainingNanos).toMillis());
    }

    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        if (state == State.HALF_OPEN) {
            state = State.CLOSED;        // 探测成功 → CLOSED（退避重置）
            consecutiveOpens = 0;
        }
    }

    // === 失败计入（CLOSED 累计达阈值 OPEN；HALF_OPEN 探测失败 → OPEN 升级退避） ===
    public synchronized void recordFailure() {
        switch (state) {
            case CLOSED -> {
                consecutiveFailures++;
                if (consecutiveFailures >= failureThreshold) {
                    open();
                }
            }
            case HALF_OPEN -> open();   // 探测失败 → 重新 OPEN（退避升级）
            case OPEN -> { /* 已 OPEN，不刷新 openedAt，避免人为延长阻断 */ }
        }
    }

    private void open() {
        consecutiveOpens++;
        currentBackoffNanos = escalatedBackoff();
        openedAtNanos = nanoClock.getAsLong();
        state = State.OPEN;
    }

    // === 指数退避：min(base × 2^(opens-1), max) ===
    private long escalatedBackoff() {
        int shift = consecutiveOpens - 1;
        if (shift >= 31) return maxBackoffNanos;       // 避免位移溢出
        return Math.min(baseBackoffNanos * (1L << shift), maxBackoffNanos);
    }
}
```

**逐段解读**：
- **全部可变方法 `synchronized`**（P1-1 线程安全）——默认并发下多线程同时 recordFailure/allowRequest，不加锁会丢失更新（该断不断）。
- **allowRequest HALF_OPEN 放 1 探测**——`state=HALF_OPEN; yield true` 后，后续请求 HALF_OPEN 分支返 false，保证只放 1 个探测请求。
- **recordFailure OPEN 不刷新 openedAt**——避免人为延长阻断（每次失败都重置 openedAt 会让退避永远不满）。
- **escalatedBackoff**（1s→2s→4s→...→30s cap）——指数退避，避免故障后端被频繁探测压垮。
- **nanoClock 可注入**——测试传可变时钟，确定性断言退避时序（不真实 sleep）。

---

## 第 76 章 AsyncTaskExecutor（异步编排）

**文件**：`src/main/java/com/arthas/gateway/task/AsyncTaskExecutor.java:57`

```java
public final class AsyncTaskExecutor {

    private final TaskStore store;
    private final Duration callTimeout;                    // 兜底超时（11min）
    private final ExecutorService pool;                     // 虚拟线程池
    private final AtomicInteger globalInflight = new AtomicInteger(0);  // 全局背压
    private final IntSupplier globalInflightCap;            // cap 供应器
    private final ConcurrentMap<String, Future<?>> supervisorFutures = new ConcurrentHashMap<>();
    private final Supplier<String> taskIdGenerator;

    public AsyncTaskExecutor(TaskStore store, Duration callTimeout, IntSupplier globalInflightCap) {
        this.store = store;
        this.callTimeout = callTimeout;
        this.pool = Executors.newVirtualThreadPerTaskExecutor();  // 虚拟线程
        this.globalInflightCap = globalInflightCap;
        this.taskIdGenerator = defaultTaskIdGenerator();
    }

    // === 提交异步任务 ===
    public GatewayTask submit(String toolName, String target,
                              Supplier<CallToolResult> backendWork, Runnable onTerminal) {
        acquireGlobalInflight();                          // 全局背压（P2-4）
        String taskId = taskIdGenerator.get();
        GatewayTask task = new GatewayTask(taskId, toolName, target, clock.get(), clock);
        store.put(task);
        Future<?> supervisor;
        try {
            supervisor = pool.submit(() -> orchestrate(task, backendWork, onTerminal));
        } catch (RejectedExecutionException ree) {        // 外层拒绝 → 清理僵尸 + 释放
            store.remove(taskId);                         // P0-1
            onTerminal.run();                             // P0-2 释放槽
            releaseGlobalInflight();
            throw ree;
        }
        supervisorFutures.put(taskId, supervisor);
        return task;
    }

    // === 后台编排（嵌套 submit：外层 supervisor 等内层 worker） ===
    private void orchestrate(GatewayTask task, Supplier<CallToolResult> backendWork, Runnable onTerminal) {
        Future<CallToolResult> worker = null;
        try {
            worker = pool.submit(backendWork::get);                            // 内层 worker 调后端
            CallToolResult result = worker.get(callTimeout.toMillis(), TimeUnit.MILLISECONDS);
            task.markCompleted(result);                                        // isError=true 原样（G-TG-2）
        } catch (TimeoutException te) {
            worker.cancel(true);                                               // 中断后端 HTTP
            task.markFailed(new TaskError(TaskError.REASON_BACKEND_TIMEOUT,
                "后端 " + callTimeout + " 内未返回（兜底超时）"));
        } catch (ExecutionException ee) {
            worker.cancel(true);
            task.markFailed(toTaskError(ee.getCause()));
        } catch (InterruptedException ie) {                                    // cancel 触发
            Thread.currentThread().interrupt();
            if (worker != null) worker.cancel(true);
            task.markCancelled();
        } finally {
            supervisorFutures.remove(task.taskId());
            onTerminal.run();           // 任一终态释放 per-target 槽（P0-2）
            releaseGlobalInflight();    // 任一终态释放全局背压（P2-4）
        }
    }

    // === 全局背压 CAS ===
    private void acquireGlobalInflight() {
        int cap = globalInflightCap.getAsInt();
        if (cap <= 0) throw new GlobalConcurrencyLimitException(cap);
        if (globalInflight.incrementAndGet() > cap) {
            globalInflight.decrementAndGet();                           // CAS 回滚
            throw new GlobalConcurrencyLimitException(cap);
        }
    }

    private void releaseGlobalInflight() {
        globalInflight.decrementAndGet();
    }

    // === cancel ===
    public boolean cancel(GatewayTask task) {
        boolean cancelled = task.markCancelled();  // WORKING→CANCELLED（终态返 false）
        Future<?> supervisor = supervisorFutures.get(task.taskId());
        if (supervisor != null) supervisor.cancel(true);  // 中断 supervisor → orchestrate InterruptedException
        return cancelled;
    }

    // === taskId 生成（t- + 6 hex） ===
    private static Supplier<String> defaultTaskIdGenerator() {
        SecureRandom rng = new SecureRandom();
        byte[] buf = new byte[3];
        return () -> {
            rng.nextBytes(buf);
            return "t-" + HexFormat.of().formatHex(buf);
        };
    }
}
```

**逐段解读**：
- **嵌套 submit**（外层 supervisor 等 `worker.get(timeout)`）——supervisor 是个壳，专等 worker；超时/cancel 时 `worker.cancel(true)` 中断后端 HTTP 调用（否则后端会一直阻塞）。
- **orchestrate finally**——`onTerminal.run()`（释放 per-target 槽）+ `releaseGlobalInflight()`（释放全局背压），保证任一终态都释放资源（P0-2/P2-4）。
- **acquireGlobalInflight CAS**——`incrementAndGet` 后判断 > cap 则 `decrementAndGet` 回滚 + 抛异常（回滚避免泄漏）。
- **cancel**——`markCancelled` 后中断 supervisor，orchestrate 捕获 InterruptedException → worker.cancel(true)。

---

## 第 77 章 ArthasProvisioner（ensure 核心，003）

**文件**：`src/main/java/com/arthas/gateway/orchestration/ArthasProvisioner.java`（关键方法摘录）

```java
public final class ArthasProvisioner {

    // 常量（见 Part 4 §26.2）

    public OrchestrationRecord ensure(String server, String pod, String namespace, Instant now) {
        String logicalName = deriveLogicalName(server, pod);  // {server}-{pod}（K-ENS-8）
        OrchestrationRecord rec = OrchestrationRecord.ensuring(logicalName, server, pod, namespace, now);
        recordStore.record(rec);

        // 幂等复用优先（K-ENS-2）
        Optional<BackendConfig> existing = dynamicStore.get(logicalName);
        if (existing.isPresent()) {
            String url = existing.get().url();
            if (probeHealthy(url, Duration.ofSeconds(HEALTH_PROBE_REQUEST_TIMEOUT.toSeconds() * 2))) {
                OrchestrationRecord reused = rec.reused(url, null, now);
                recordStore.record(reused);
                return reused;
            }
            // 命中但不健康 → 重新供给
        }
        return doProvision(rec, namespace, pod, logicalName, now);
    }

    private OrchestrationRecord doProvision(OrchestrationRecord rec, ...) {
        try {
            long pid = locateJvm(namespace, pod);              // 1. jps -q | head -1
            installArthas(namespace, pod);                     // 2. fabric8 .file().upload()
            startArthas(namespace, pod, pid);                  // 3. java -jar arthas-boot.jar <pid> --target-ip 0.0.0.0 ...
            NodePortExposer.ExposeResult exposed = exposer.expose(namespace, pod, logicalName, mcpPort);  // 4. NodePort
            rec = rec.withExposed(exposed.mcpUrl(), exposed.serviceRef());
            if (!probeHealthy(exposed.mcpUrl(), healthCheckTimeout)) {  // 5. MCP 握手轮询
                throw new ProvisionException(errorOf("health_check_timeout", "health_check", ...));
            }
            BackendConfig cfg = new BackendConfig(logicalName, exposed.mcpUrl(), Protocol.STREAMABLE,
                auth, CONNECT_TIMEOUT_MS, CALL_TIMEOUT_MS, MAX_CONCURRENT_TASKS, Source.DYNAMIC);
            dynamicStore.register(cfg);                        // 6. 动态注册
            return rec.ready(exposed.mcpUrl(), exposed.serviceRef(), now);
        } catch (ProvisionException e) {
            return rec.failed(e.error, now);  // 任一失败 → failed（不注册，K-ATOMIC-1）
        }
    }

    private void installArthas(String namespace, String pod) {
        boolean ok = client.pods().inNamespace(namespace).withName(pod)
            .file(REMOTE_ARTHAS_JAR).upload(arthasBootJar);  // fabric8 upload
        if (!ok) throw new ProvisionException(errorOf("attach_failed", "install_arthas", ...));
    }

    private void startArthas(String namespace, String pod, long pid) {
        K8sExec.ExecResult r = exec.exec(namespace, pod, ATTACH_TIMEOUT,
            "java", "-jar", REMOTE_ARTHAS_JAR, String.valueOf(pid),
            "--attach-only",
            "--http-port", String.valueOf(mcpPort),
            "--target-ip", targetIp,          // 0.0.0.0（R4）
            "--telnet-port", "0",
            "--use-version", arthasVersion,
            "--password", arthasPassword);
        if (r.exitCode() != 0) throw new ProvisionException(errorOf("attach_failed", "start_arthas", ...));
    }

    private boolean probeHealthy(String mcpUrl, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try (McpSyncClient c = McpClient.sync(
                    HttpClientStreamableHttpTransport.builder(mcpUrl)
                        .httpRequestCustomizer(new BackendAuthCustomizer(auth)).build())
                    .requestTimeout(HEALTH_PROBE_REQUEST_TIMEOUT).build()) {
                c.initialize();  // arthas 就绪则握手成功
                return true;
            } catch (RuntimeException e) {
                sleepQuiet(HEALTH_POLL_INTERVAL);
            }
        }
        return false;
    }
}
```

**逐段解读**：
- **ensure 幂等优先**——先查 `dynamicStore.get`，命中且健康 → reused（零副作用，K-ENS-2）；命中但不健康 → 重新供给。
- **doProvision 6 步原子**——任一失败 catch → `failed`（不调 register，注册表不含该 target，K-ATOMIC-1）。
- **installArthas**——fabric8 `.file().upload()` 上传 arthas-boot.jar（commons-compress 运行时依赖打 tar 流）。
- **startArthas**——`--target-ip 0.0.0.0`（NodePort 可达，R4）+ `--password`（绑 0.0.0.0 强制鉴权）。
- **probeHealthy**——真实 MCP 客户端 `initialize` 握手轮询（零桩，确认 arthas MCP 真就绪）。

---

## 第 78 章 DynamicBackendStore（动态注册，003）

**文件**：`src/main/java/com/arthas/gateway/backend/DynamicBackendStore.java:26`

```java
@Component
public class DynamicBackendStore {

    private final ConcurrentHashMap<String, BackendConfig> byName = new ConcurrentHashMap<>();
    private final Supplier<Set<String>> staticNames;   // 静态种子名供应器（I-3 冲突检测）
    private final Runnable onChange;                    // 触发 compose+swap

    public DynamicBackendStore(Supplier<Set<String>> staticNames, Runnable onChange) {
        this.staticNames = staticNames;
        this.onChange = onChange;
    }

    public void register(BackendConfig cfg) {
        if (cfg.source() != Source.DYNAMIC) {
            throw new BackendConfigException("动态注册须 source=DYNAMIC：" + cfg.name());
        }
        String name = cfg.name();
        // 1. 动态名 ∩ 静态种子名 → 拒绝（保护静态）
        if (staticNames.get().contains(name)) {
            throw new BackendConfigException("动态注册名与静态种子冲突，拒绝：" + name);
        }
        BackendConfig existing = byName.get(name);
        if (existing != null) {
            // 2. 同名异 URL → 拒绝
            if (!existing.url().equals(cfg.url())) {
                throw new BackendConfigException("动态注册名与既有动态同名异 URL，拒绝：...");
            }
            // 3. 同名同 URL 同字段 → 幂等（无回调）
            if (existing.equals(cfg)) return;
        }
        byName.put(name, cfg);
        onChange.run();  // 触发 compose → holder.getAndSet
    }

    public void unregister(String name) {
        BackendConfig removed = byName.remove(name);
        if (removed != null) onChange.run();  // 存在才触发；不存在幂等
    }

    public Optional<BackendConfig> get(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public List<BackendConfig> list() {
        return List.copyOf(byName.values());
    }
}
```

**逐段解读**：
- **三层冲突检测**——①动态∩静态种子（保护静态不被覆盖，I-3）；②同名异 URL（防止「同名指向不同后端」混乱）；③同名同 URL 同字段（幂等，无回调避免重复 compose）。
- **onChange 回调**——register/unregister 后触发 `BackendConfigWatcher.recomposeForDynamicChange` → `applyCompose` → `holder.getAndSet`（原子替换 effective 注册表）。
- **unregister 幂等**——不存在不抛错（返 null，不触发 onChange）。

---

## 第 79 章 BackendConfigWatcher（热重载）

**文件**：`src/main/java/com/arthas/gateway/backend/BackendConfigWatcher.java:40`（关键方法摘录）

```java
public final class BackendConfigWatcher implements AutoCloseable {

    private static final Duration DEBOUNCE = Duration.ofMillis(500);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(200);
    private static final Duration SHUTDOWN_AWAIT = Duration.ofSeconds(2);

    private final Path configFile;
    private final BackendConfigLoader loader;
    private final BackendRegistryReloader reloader;
    private final RegistryHolder holder;
    private final RegistryComposer composer;
    private final Duration retirementGrace;
    private final ScheduledExecutorService retireScheduler;  // 退役调度（虚拟线程）

    private volatile BackendRegistry staticSnapshot;
    private volatile boolean closed = false;

    // 监听虚拟线程
    void watchLoop() {
        try (WatchService ws = FileSystems.getDefault().newWatchService()) {
            configFile.getParent().register(ws, ENTRY_MODIFY, ENTRY_CREATE, ENTRY_DELETE);
            while (!closed) {
                WatchKey key = ws.poll(POLL_INTERVAL);
                if (key == null) continue;
                boolean matched = false;
                for (WatchEvent<?> e : key.pollEvents()) {
                    if (e.context() instanceof Path p && configFile.getParent().resolve(p).equals(configFile)) {
                        matched = true;
                    }
                }
                key.reset();
                if (matched) {
                    Thread.sleep(DEBOUNCE.toMillis());  // 防抖
                    reloadOnce();
                }
            }
        }
    }

    void reloadOnce() {
        LoadedBackends loaded;
        try (InputStream in = Files.newInputStream(configFile)) {
            loaded = loader.load(in);
        } catch (BackendConfigException | IOException e) {
            log.error("配置加载失败，保留旧注册表", e);  // 不半替换（§11 规则 7）
            return;
        }
        ReloadResult result = reloader.reload(staticSnapshot, loaded);
        if (!result.changed()) return;  // version 去重
        staticSnapshot = result.registry();
        applyCompose();
    }

    void applyCompose() {
        ComposeResult result = composer.compose(holder.current(), staticSnapshot, dynamicList());
        if (!result.changed()) return;  // sameEffective（名字集同 + 每 target identity 复用）
        holder.getAndSet(result.registry());  // 原子替换
        retireAll(result.toRetire());
    }

    private void retireAll(List<BackendEntry> toRetire) {
        for (BackendEntry entry : toRetire) {
            entry.markRetired();  // 立即；新调用不再路由
        }
        if (!toRetire.isEmpty()) {
            retireScheduler.schedule(() -> {
                for (BackendEntry entry : toRetire) {
                    try { entry.client().close(); } catch (Exception e) { log.warn("close 失败", e); }
                }
            }, retirementGrace.toMillis(), TimeUnit.MILLISECONDS);  // 11min 后 close
        }
    }

    // 动态注册/注销触发
    void recomposeForDynamicChange() {
        applyCompose();
    }

    // 供 DynamicBackendStore 做冲突检测
    Set<String> staticSnapshotNames() {
        return staticSnapshot.byName().keySet();
    }
}
```

**逐段解读**：
- **防抖 500ms**——编辑器保存可能触发多次事件，防抖避免重复 reload。
- **reloadOnce 失败保留旧表**——catch 异常不替换（§11 规则 7，不半替换）。
- **applyCompose 合并动态**——`composer.compose(previous, staticSnapshot, dynamicList)` 保证热重载不误删动态 target（I-2）。
- **retireAll 延迟 close**——立即 markRetired（新调用不再路由），延迟 11min close client（让 in-flight 完成，P2-1）。

---

## 第 80 章 BackendAdminService（portal CRUD，004）

**文件**：`src/main/java/com/arthas/gateway/admin/backend/BackendAdminService.java:38`（关键方法摘录）

```java
@Service
public class BackendAdminService {

    private final RegistryHolder registryHolder;
    private final DynamicBackendStore dynamicStore;
    private final BackendsYamlWriter yamlWriter;
    private final BackendConfigLoader loader;
    private final Path backendsFile;

    // Spring 构造（自建 loader + 文件路径）
    public BackendAdminService(RegistryHolder registryHolder, DynamicBackendStore dynamicStore,
            BackendsYamlWriter yamlWriter, GatewayProperties props) {
        this(registryHolder, dynamicStore, yamlWriter,
            new BackendConfigLoader(), Path.of(props.getBackendsFile()));
    }

    // 测试构造（可注入 loader + 路径，便于 @TempDir）
    BackendAdminService(RegistryHolder registryHolder, DynamicBackendStore dynamicStore,
            BackendsYamlWriter yamlWriter, BackendConfigLoader loader, Path backendsFile) { ... }

    public List<BackendDto> list() {
        return registryHolder.current().byName().values().stream()
            .map(e -> toDto(e.config(), e.state().name(), e.isHealthy(), e.breaker().state().name()))
            .toList();
    }

    public BackendDto create(CreateBackendRequest req) {
        String name = requireNonBlank(req.name(), "name");
        requireNonBlank(req.url(), "url");
        if (registryHolder.current().names().contains(name)) {
            throw new BackendConflictException("name 已存在：" + name, "duplicate_name");
        }
        BackendConfig cfg = toBackendConfig(req, name);
        LoadedBackends loaded = loadCurrent();
        List<BackendConfig> updated = new ArrayList<>(loaded.backends());
        updated.add(cfg);
        writeYaml(loaded.version() + 1, updated);  // 写回 → 001 热重载
        return toDto(cfg, "ACTIVE", false, "CLOSED");
    }

    public BackendDto update(String name, UpdateBackendRequest req) {
        BackendEntry entry = registryHolder.get(name)
            .orElseThrow(() -> new BackendNotFoundException(name, availableNames()));
        if (entry.config().source() == Source.DYNAMIC) {
            throw new BackendConflictException("动态后端不可编辑（须先删再 ensure）", "dynamic_backend_not_editable");
        }
        BackendConfig merged = mergeConfig(entry.config(), req);
        LoadedBackends loaded = loadCurrent();
        List<BackendConfig> updated = loaded.backends().stream()
            .map(b -> b.name().equals(name) ? merged : b).toList();
        writeYaml(loaded.version() + 1, updated);
        return toDto(merged, entry.state().name(), entry.isHealthy(), entry.breaker().state().name());
    }

    public void delete(String name) {
        BackendEntry entry = registryHolder.get(name)
            .orElseThrow(() -> new BackendNotFoundException(name, availableNames()));
        if (entry.config().source() == Source.DYNAMIC) {
            dynamicStore.unregister(name);  // 即时移除
            return;
        }
        LoadedBackends loaded = loadCurrent();
        List<BackendConfig> updated = loaded.backends().stream()
            .filter(b -> !b.name().equals(name)).toList();
        writeYaml(loaded.version() + 1, updated);
    }

    private void writeYaml(long version, List<BackendConfig> backends) {
        try {
            yamlWriter.write(backendsFile, version, backends);
        } catch (IOException e) {
            throw new BackendAdminException("写 backends.yaml 失败：" + e.getMessage(), e);
        }
    }
}
```

**逐段解读**：
- **两构造器**——Spring 用（自建 loader + props 文件路径）+ 测试用（可注入，便于 @TempDir 隔离）。
- **create**——校验（缺必填/duplicate_name）→ 写 YAML（version+1）→ 001 热重载纳管（不直接调 BackendRegistry.rebuild，INV-FILE-1）。
- **update**——动态后端拒绝（INV-DYN-1）；静态 mergeConfig（null 字段保留旧值）→ 写 YAML。
- **delete**——动态→unregister（即时）；静态→过滤写回（热重载移除）。

---

## 第 81 章 TaskListService（任务列表，004 增量）

**文件**：`src/main/java/com/arthas/gateway/admin/task/TaskListService.java:29`（完整摘录见 Part 2 §38.2）

关键点：
- **clamp 不报 400**——`safeSize = min(100, max(1, size))`，越界自动归一化。
- **total = filtered.size()**——在 skip/limit 之前算（过滤后/分页前，INV-LIST-2）。
- **toSummary isError 映射**——仅 `result != null && result.isError()==TRUE` 时 true（WORKING/FAILED/CANCELLED 无 result → false）。

---

## 第 82 章 GatewayMcpServerConfig（MCP 服务端装配）

**文件**：`src/main/java/com/arthas/gateway/config/GatewayMcpServerConfig.java:44`（关键方法摘录见 Part 2 §6.2）

关键点：
- **35 静态工具 handler 委托 router**——`(exchange, request) -> router.route(exposed, request)`。
- **3 K8S 工具自带闭包**——经 `ObjectProvider<K8sToolHandlers>` 懒解析，缺失时返"未启用"错误（非启动崩）。
- **capabilities 锁定**——`@Primary McpSyncServerCustomizer` 覆盖 starter 默认为「仅 tools, listChanged=false」。

---

## 第 83 章 NodePortExposer（NodePort Service，003）

**文件**：`src/main/java/com/arthas/gateway/orchestration/NodePortExposer.java`（关键方法摘录见 Part 4 §27）

关键点：
- **label pod**——`arthas-mcp-gateway/target=<sanitize(logical)>`（幂等覆盖）。
- **ensureNodePortService create-or-get**——Service 名确定性派生 `arthas-mcp-<sanitize(logical)>`，已存在复用 NodePort，否则 create（K8S 自动分配 30000-32767）。
- **resolveNodeIp**——ExternalIP 优先，其次 InternalIP（k3s 单节点 = 192.168.31.92）。
- **sanitize**——label ≤63 字符、service name ≤253 字符（DNS-subdomain）。

---

## 第 84 章 TaskStore（存储 + TTL）

**文件**：`src/main/java/com/arthas/gateway/task/TaskStore.java:38`（完整摘录见 Part 2 §12.4）

关键点：
- **三路清理**——get 单条惰性（O(1)）/ list 全表惰性 / cleaner 守护线程。
- **isExpired**——终态（completedAt != null）+ 超 TTL；WORKING 永不过期。
- **cleaner 周期** = `max(60, ttl/4)` 秒。

---

> **手册完**。本手册覆盖：设计哲学（8 原则）+ 能力全景（38 工具）+ 技术栈 + 架构 + 4 特性逐类逐方法实现 + 韧性 15 项 + 契约测试 + K8S 编排（重点）+ portal + 配置 + 工具字典 + 测试清单 + 设计决策 + 关键源码。查任一细节按文件索引定位。
