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

## 第 85 章 BackendResolver / K8sBackendResolver（005 懒 resolve）

**文件**：`src/main/java/com/arthas/gateway/backend/BackendResolver.java:18`、`src/main/java/com/arthas/gateway/orchestration/K8sBackendResolver.java:38`

005 特性 US2 的核心：把「K8S 模式 backend 首次路由时 ensure 出 mcpUrl」抽为 gateway-core 接口 + orchestration 实现，**gateway-core 零 fabric8 依赖**（ArchUnit INV-BOUNDARY-1/2 守护）。

```java
// === BackendResolver.java:18（gateway-core 定义，零 fabric8）===
public interface BackendResolver {
    // K8S 模式 config（k8sHost 非空）→ 懒 resolve 出 mcpUrl（调 ensure + 缓存）；
    // 静态模式（url 非空）→ empty（用 config.url）
    Optional<String> resolveMcpUrl(BackendConfig config);
}

// === K8sBackendResolver.java:38（orchestration 实装，implements + AutoCloseable）===
public class K8sBackendResolver implements BackendResolver, AutoCloseable {

    private final Map<String, ArthasProvisioner> provisioners;  // hostName → provisioner
    private final Map<String, GatewayProperties.K8sHost> hosts;
    private final Clock clock;
    /** logicalName → mcpUrl 缓存（幂等命中，INV-K8SHOST-2）。 */
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
    private List<KubernetesClient> ownedClients = List.of();    // 本 resolver 拥有的 host clients

    @Override
    public Optional<String> resolveMcpUrl(BackendConfig config) {
        if (!config.isK8sMode()) {
            return Optional.empty();                            // 静态模式旁路（INV-K8SHOST-5）
        }
        String host = config.k8sHost();
        ArthasProvisioner provisioner = provisioners.get(host);
        if (provisioner == null) {
            throw new K8sResolveException("unknown_k8s_host",  // host 未在 arthas-gateway.k8s-hosts 配置（INV-K8SHOST-3）
                    "K8S host 未在 arthas-gateway.k8s-hosts 配置：" + host);
        }
        GatewayProperties.K8sHost meta = hosts.get(host);
        String server = (meta 有 name) ? meta.getName() : host;
        String namespace = (meta 有 namespace) ? meta.getNamespace() : "default";
        String logicalName = ArthasProvisioner.deriveLogicalName(server, config.pod());

        // 缓存命中直接返；未缓存才 ensure（避免重复 arthas attach / NodePort 往返）
        String mcpUrl = cache.computeIfAbsent(logicalName,
                k -> doEnsure(provisioner, server, host, config.pod(), namespace));
        return Optional.of(mcpUrl);
    }

    private String doEnsure(ArthasProvisioner provisioner, ...) {
        OrchestrationRecord rec = provisioner.ensure(server, pod, namespace, clock.instant());
        if (rec.status() != READY && rec.status() != REUSED) {
            throw new K8sResolveException("ensure_failed",     // failed/ensuring 终态（INV-K8SHOST-2）
                    host + "/" + pod + " ensure 终态=" + rec.status() + ...);
        }
        return rec.mcpUrl();
    }

    @Override
    public void close() {
        for (KubernetesClient c : ownedClients) { try { c.close(); } catch (Exception ignored) {} }
    }

    // K8sResolveException（携带 reason：unknown_k8s_host / ensure_failed，供上层观测/分类）
}
```

**逐段解读**：
- **接口定义在 gateway-core**（`backend` 包，零 fabric8 import）——`backend` 包是诊断核心，不可耦合 K8S；实现在 `orchestration` 包。装配为 `Optional<BackendResolver>`（无 K8S 配置时不装配），`BackendEntry` 经 `Supplier` 懒取。
- **resolveMcpUrl 三分支**——①静态模式旁路（empty，INV-K8SHOST-5）；②host 不在 provisioners 映射 → `unknown_k8s_host`（INV-K8SHOST-3，配置错）；③命中 provisioner → 缓存 computeIfAbsent。
- **缓存粒度 logicalName**（`{hostName}-{pod}`）——同一 K8S 后端首次路由 ensure 一次，后续命中缓存。ensure 本身原子幂等，但缓存避免重复 arthas attach / NodePort 往返开销。
- **doEnsure 终态判定**——仅 `READY`/`REUSED` 返 mcpUrl；`FAILED`/`ENSURING` 抛 `ensure_failed`（不返半成品 url）。
- **AutoCloseable**——`ownedClients`（每个 host 一个 KubernetesClient）容器关闭时一并释放，生命周期随 `backendResolver` bean。

---

## 第 86 章 ArthasLauncher / DefaultArthasLauncher（005 启动 SPI）

**文件**：`src/main/java/com/arthas/gateway/orchestration/ArthasLauncher.java:18`、`src/main/java/com/arthas/gateway/orchestration/DefaultArthasLauncher.java:14`

005 特性 US3：把 003 既有 `ArthasProvisioner` 硬编码的「定位 JVM + 启动 arthas」抽为策略点，用户可写 `@Primary @Component` 实现覆盖（适配容器独立 JDK 部署，FR-009~012）。

```java
// === ArthasLauncher.java:18（SPI 接口）===
public interface ArthasLauncher {
    long locatePid(LaunchContext ctx);                  // 默认：jps -q | head -1
    void startArthas(LaunchContext ctx, long pid);      // 默认：java -jar arthas-boot.jar <pid> ...

    // 启动上下文（record，封装 ensure 子步所需，不可变）
    record LaunchContext(String namespace, String pod, K8sExec exec, int mcpPort,
                         String targetIp, String arthasVersion, String arthasPassword,
                         String arthasBootJar, Duration attachTimeout, Duration locateTimeout) {}

    // 启动失败（携带 OrchestrationRecord.Error，供 ArthasProvisioner 映射 failed@locate_jvm/start_arthas）
    class LaunchException extends RuntimeException {
        private final OrchestrationRecord.Error error;  // reason@phase:message
        ...
    }
}

// === DefaultArthasLauncher.java:14（默认实现 = 003 现状逐字外移）===
public class DefaultArthasLauncher implements ArthasLauncher {
    private static final Pattern PID_LINE = Pattern.compile("\\s*(\\d+)\\s*");

    @Override
    public long locatePid(LaunchContext ctx) {
        K8sExec.ExecResult r;
        try {
            r = ctx.exec().exec(ctx.namespace(), ctx.pod(), ctx.locateTimeout(),
                    "sh", "-c", "jps -q 2>/dev/null | grep -E '^[0-9]+$' | head -1");
        } catch (K8sExecException e) {
            throw new LaunchException(new Error("locate_jvm", "k8s_unreachable", e.getMessage()));
        }
        if (r.exitCode() != 0) throw new LaunchException(new Error("locate_jvm", "no_shell", ...));
        String pidStr = r.stdout().trim();
        if (!PID_LINE.matcher(pidStr).matches())
            throw new LaunchException(new Error("locate_jvm", "no_jvm", "pod 内无运行中 JVM"));
        return Long.parseLong(pidStr.trim());
    }

    @Override
    public void startArthas(LaunchContext ctx, long pid) {
        K8sExec.ExecResult r;
        try {
            r = ctx.exec().exec(ctx.namespace(), ctx.pod(), ctx.attachTimeout(),
                    "java", "-jar", ctx.arthasBootJar(), String.valueOf(pid),
                    "--attach-only",
                    "--http-port", String.valueOf(ctx.mcpPort()),
                    "--target-ip", ctx.targetIp(),         // 0.0.0.0（NodePort 可达，R4）
                    "--telnet-port", "0",
                    "--use-version", ctx.arthasVersion(),
                    "--password", ctx.arthasPassword());
        } catch (K8sExecException e) {
            throw new LaunchException(new Error("start_arthas", "attach_failed", ...));
        }
        if (r.exitCode() != 0) throw new LaunchException(new Error("start_arthas", "attach_failed", ...));
    }
}
```

**逐段解读**：
- **SPI 接口两方法**——`locatePid`（定位 JVM）+ `startArthas`（启动 attach）；`LaunchContext` record 封装所有子步入参（namespace/pod/exec/mcpPort/targetIp/arthas 参数/超时/jar 路径），不可变。
- **LaunchException 携带 Error**——reason@phase 结构（如 `no_jvm@locate_jvm`、`attach_failed@start_arthas`），`ArthasProvisioner` 直接映射为 ensure failed 记录（K-ENS-4/5 不破，INV-LAUNCHER-4）。
- **DefaultArthasLauncher = 003 现状**——PATH 的 `jps` + `java -jar`，逻辑逐字外移；装配为 `@ConditionalOnMissingBean(ArthasLauncher.class)`（用户未提供自定义实现时生效，INV-LAUNCHER-2 兼容）。
- **用户覆盖路径**——写 `@Primary @Component implements ArthasLauncher`，定制 javaPath（容器独立 JDK）+ 完整命令模板（INV-LAUNCHER-3）。SPI 测试用 `TestArthasLauncher` 真实实现（非 mock，INV-LAUNCHER-5）。

---

## 第 87 章 BackendConfig（005 K8S 模式增量）

**文件**：`src/main/java/com/arthas/gateway/backend/BackendConfig.java:27`

005 US2：record 加 `k8sHost`/`pod` 两字段，`url`/`k8sHost` 互斥（INV-K8SHOST-1），加 `withResolvedUrl`（懒 resolve 后构造静态等价 config）。

```java
public record BackendConfig(
        String name, String url, Protocol protocol, Auth auth,
        int connectTimeoutMs, int callTimeoutMs, int maxConcurrentTasks,
        String k8sHost, String pod, Source source) {        // 005 加 k8sHost/pod（末两位，向后兼容）

    public BackendConfig {
        if (name == null || name.isBlank()) throw new BackendConfigException("后端 name 不可为空");
        Objects.requireNonNull(protocol, "protocol 不可为空");
        Objects.requireNonNull(auth, "auth 不可为空");
        validateModeRouting(name, url, k8sHost, pod);        // url/k8sHost 互斥（INV-K8SHOST-1）
        if (connectTimeoutMs <= 0) throw new BackendConfigException(...);
        if (maxConcurrentTasks < 1 || maxConcurrentTasks > 5) throw new BackendConfigException(...);
        if (source == null) source = Source.STATIC;          // 缺省 STATIC（向后兼容）
    }

    /** 寻址模式校验（005 US2，INV-K8SHOST-1）。 */
    private static void validateModeRouting(String name, String url, String k8sHost, String pod) {
        boolean hasUrl = url != null && !url.isBlank();
        boolean hasK8s = k8sHost != null && !k8sHost.isBlank();
        if (hasUrl && hasK8s) throw new BackendConfigException(name + ": url 与 k8sHost 互斥（不可同时配置）");
        if (!hasUrl && !hasK8s) throw new BackendConfigException(name + ": url 与 k8sHost 须二选一");
        if (hasUrl) requireHttpUrl(name, url);               // 静态模式：url 须合法 http(s)
        else if (pod == null || pod.isBlank()) throw new BackendConfigException(name + ": K8S 模式须配 pod");
    }

    public boolean isK8sMode() { return k8sHost != null && !k8sHost.isBlank(); }

    /** 005 懒 resolve 后构造静态等价 config（K8S 模式 → 静态 url），k8sHost/pod 清空。 */
    public BackendConfig withResolvedUrl(String mcpUrl) {
        return new BackendConfig(name, mcpUrl, protocol, auth,
                connectTimeoutMs, callTimeoutMs, maxConcurrentTasks, null, null, source);
    }

    // 向后兼容构造器：7 参（无 source/k8sHost/pod）/ 8 参（无 k8sHost/pod）→ 委托 10 参，k8sHost/pod=null
    public BackendConfig(String name, String url, Protocol protocol, Auth auth,
                         int connectTimeoutMs, int callTimeoutMs, int maxConcurrentTasks) {
        this(name, url, protocol, auth, connectTimeoutMs, callTimeoutMs, maxConcurrentTasks, null, null, Source.STATIC);
    }

    @Override
    public boolean equals(Object o) {
        ... // 排除 source（可观测标记），纳入 k8sHost/pod（寻址变化=不同后端）
        return ... && Objects.equals(k8sHost, that.k8sHost) && Objects.equals(pod, that.pod);
    }
    @Override
    public int hashCode() {
        return Objects.hash(name, url, protocol, auth, connectTimeoutMs, callTimeoutMs, maxConcurrentTasks, k8sHost, pod);
    }
}
```

**逐段解读**：
- **字段末两位新增**——`k8sHost`/`pod` 放 record 组件末尾，保留 001/003 既有的 7 参、8 参构造器调用点零改动（向后兼容构造器委托 10 参）。
- **validateModeRouting 互斥**（INV-K8SHOST-1）——`url` 与 `k8sHost` 不可同配（歧义）、不可皆空（无寻址）；静态模式校验 `url` 合法 http(s)，K8S 模式校验 `pod` 必填。任一失败抛 `BackendConfigException`（热重载保留旧表，§11 规则 7）。
- **withResolvedUrl**——懒 resolve 出 mcpUrl 后，把 K8S 模式 config 转静态等价（k8sHost/pod 清空），供 `HttpBackendClient` 按 mcpUrl 建连（`BackendEntry.resolveClientIfNeeded` 调用）。
- **equals/hashCode 纳入 k8sHost/pod、排除 source**——同核心字段异 source 仍视为同一可复用后端（保连接池复用）；K8S 寻址变化（host/pod 变）=不同后端。

---

## 第 88 章 NodePortExposer（005 US1 复用 label Service）

**文件**：`src/main/java/com/arthas/gateway/orchestration/NodePortExposer.java:63`

005 US1 改造：ensure 的 NodePort 暴露从「新建独立 Service」改为「优先复用带 `arthas-mcp-gateway/target` label 的现有 Service（patch type+端口，K-ENS-10/11/12），找不到回退新建（K-ENS-10 回退）」。

```java
public ExposeResult expose(String namespace, String pod, String logicalName, int mcpPort) {
    String labelValue = sanitizeLabelValue(logicalName);
    labelPod(namespace, pod, labelValue);                    // 1. label pod（幂等覆盖）

    // 2. 优先复用带 label 的现有 Service；找不到回退新建（003 现状）
    Service labeled = findLabeledService(namespace, labelValue);
    String serviceName; int nodePort;
    if (labeled != null) {
        serviceName = labeled.getMetadata().getName();
        nodePort = patchServiceAddNodePort(labeled, namespace, mcpPort);  // patch type+端口（K-ENS-10/11）
    } else {
        serviceName = sanitizeServiceName(SERVICE_PREFIX + labelValue);
        nodePort = ensureNodePortService(namespace, serviceName, labelValue, mcpPort);  // 回退新建（K-ENS-10 回退）
    }
    String nodeIp = resolveNodeIp();
    return new ExposeResult(serviceName, nodePort, "http://" + nodeIp + ":" + nodePort, serviceName + "/" + nodePort);
}

/** 查带 arthas-mcp-gateway/target=<labelValue> label 的现有 Service（005 US1，K-ENS-10）。 */
Service findLabeledService(String namespace, String labelValue) {
    List<Service> svcs = client.services().inNamespace(namespace)
            .withLabel(TARGET_LABEL_KEY, labelValue).list().getItems();  // labelSelector 查询
    return (svcs == null || svcs.isEmpty()) ? null : svcs.get(0);       // 无 → null（触发回退新建）
}

/** 在现有 Service 上 patch 出 NodePort（005 US1，K-ENS-11/12）。 */
int patchServiceAddNodePort(Service svc, String namespace, int mcpPort) {
    Integer existing = nodePortForTargetPort(svc, mcpPort);
    if (existing != null) return existing;                   // K-ENS-12 幂等：已有同 targetPort 的 NodePort → 复用
    // 拷贝既有端口（为无名端口补 name），缺 mcpPort 端口才加
    List<ServicePort> ports = ...;
    if (!hasMcpPort) ports.add(new ServicePortBuilder().withName("arthas-mcp-" + mcpPort)
            .withPort(mcpPort).withNewTargetPort(mcpPort).build());
    Service toPatch = new ServiceBuilder(svc).editSpec()
            .withType("NodePort").withPorts(ports).endSpec().build();   // K-ENS-11：type ClusterIP→NodePort
    Service patched = client.services().inNamespace(namespace).resource(toPatch).update();
    return nodePortForTargetPort(patched, mcpPort);          // K8S 分配的 nodePort
}

/** 取 Service 中 targetPort=指定值端口的 nodePort（幂等复用判定，K-ENS-12）。 */
private static Integer nodePortForTargetPort(Service svc, int targetPort) {
    for (var p : svc.getSpec().getPorts()) {
        Integer tp = (p.getTargetPort() != null) ? p.getTargetPort().getIntVal() : null;
        if (tp != null && tp == targetPort && p.getNodePort() != null) return p.getNodePort();
    }
    return null;
}
```

**逐段解读**：
- **expose 二分支**（005 改造核心）——先 `findLabeledService` 查运维预打 label 的业务 Service：命中则 patch（复用既有 Service，K-ENS-10/11），否则回退 003 的 `ensureNodePortService` 新建独立 Service（K-ENS-10 回退，向后兼容）。
- **findLabeledService**——运维在业务 Service 上预打 `arthas-mcp-gateway/target=<sanitize(logical)>` label 即声明「由网关复用暴露 NodePort」；`labelSelector` 查询命中首个（多端口业务 Service 场景）。
- **patchServiceAddNodePort 幂等优先**（K-ENS-12）——已有同 targetPort 的 NodePort 端口 → 直接复用 nodePort（不重复 patch）；否则 `withType("NodePort")` 把 ClusterIP 改 NodePort（K-ENS-11）+ 补 mcpPort 端口，K8S 在 30000-32767 自动分配 nodePort。多端口 Service 每个端口须有 name，故为无名端口补 `port-N`。
- **回退路径不破**——无 label Service 时走 003 既有 `ensureNodePortService`（create-or-get 独立 Service），003 既有契约不破。

---

## 第 89 章 ArthasProvisioner（005 委托 launcher）

**文件**：`src/main/java/com/arthas/gateway/orchestration/ArthasProvisioner.java:120`

005 US3 改造：`locateJvm`/`startArthas` 委托 `ArthasLauncher`（003 既有 jps/java -jar 逻辑外移至 `DefaultArthasLauncher`），加 11 参构造（注入 launcher）+ `buildContext`（构造 LaunchContext）。

```java
/** 005 US3：注入 ArthasLauncher（locatePid + startArthas 委托；用户 @Primary 实现覆盖 Default）。 */
public ArthasProvisioner(KubernetesClient client, NodePortExposer exposer,
                         DynamicBackendStore dynamicStore, OrchestrationRecordStore recordStore,
                         String targetIp, String arthasBootJar, int mcpPort, String arthasVersion,
                         String arthasPassword, Duration healthCheckTimeout, ArthasLauncher launcher) {
    ... // 注入全部依赖；launcher 非 null 校验；arthasBootJar 可读性校验
    this.launcher = Objects.requireNonNull(launcher, "launcher 不可为空");
}

/** 005 US3：构造 LaunchContext（namespace/pod + exec + arthas 启动参数 + 远程 jar path），传 ArthasLauncher。 */
private ArthasLauncher.LaunchContext buildContext(String namespace, String pod) {
    return new ArthasLauncher.LaunchContext(namespace, pod, exec, mcpPort, targetIp,
            arthasVersion, arthasPassword, REMOTE_ARTHAS_JAR, ATTACH_TIMEOUT, LOCATE_TIMEOUT);
}

/** 005 US3：委托 launcher.locatePid（003 既有 jps 逻辑外移至 DefaultArthasLauncher）。 */
private long locateJvm(String namespace, String pod) {
    try {
        return launcher.locatePid(buildContext(namespace, pod));
    } catch (ArthasLauncher.LaunchException e) {
        throw new ProvisionException(e.error());              // LaunchException.Error → ProvisionException（保留 reason@phase）
    }
}

/** 005 US3：委托 launcher.startArthas（003 既有 java -jar 逻辑外移至 DefaultArthasLauncher）。 */
private void startArthas(String namespace, String pod, long pid) {
    try {
        launcher.startArthas(buildContext(namespace, pod), pid);
    } catch (ArthasLauncher.LaunchException e) {
        throw new ProvisionException(e.error());
    }
}

// installArthas 保持 003 既有（fabric8 .file().upload()），package-private 供 spy 测试覆盖
void installArthas(String namespace, String pod) { ... }
```

**逐段解读**：
- **11 参构造（新）**——加 `ArthasLauncher launcher` 参数；既有 9 参（生产）、10 参（测试，含 healthCheckTimeout）构造器委托 11 参并传 `new DefaultArthasLauncher()`（003 现状逐字兼容）。
- **buildContext**——每次子步调用时构造不可变 `LaunchContext`（封装 exec 工具 + arthas 启动参数），传 launcher；用户自定义实现可用可不用 `exec` 字段（灵活适配独立 JDK 部署）。
- **locateJvm/startArthas 委托**——try 委托 launcher，catch `LaunchException` 转 `ProvisionException`（保留 `Error` 的 reason@phase 结构），由 `doProvision` 统一映射 ensure failed 记录（K-ENS-4/5 不破，INV-LAUNCHER-4）。
- **installArthas 不动**——fabric8 upload 逻辑保持 003 既有，`package-private` 供 spy 测试（T023 跳过真实 upload，聚焦 launcher 委托验证）。

> **配套懒 resolve 入口**（BackendEntry/Factory，005 US2）：`BackendEntryFactory.create`（:63）静态模式预建 `HttpBackendClient`（config.url），K8S 模式传 `null` + 经 `ObjectProvider<BackendResolver>` 注入懒解析 `Supplier`（打破 `resolver→provisioner→dynamicStore→watcher→factory` 构造期环）；`BackendEntry.initializeOnce`（:196）首次握手时调 `resolveClientIfNeeded`（:215）——client 为 null 则 `resolverSupplier.get()` 取 resolver（无 → `no_k8s_resolver`，INV-K8SHOST-4），`resolveMcpUrl(config)` 拿 mcpUrl 后 `config.withResolvedUrl(mcpUrl)` 建 `HttpBackendClient`。

---

> **手册完**。本手册覆盖：设计哲学（8 原则）+ 能力全景（38 工具）+ 技术栈 + 架构 + 5 特性逐类逐方法实现 + 韧性 15 项 + 契约测试 + K8S 编排（重点）+ portal + 配置 + 工具字典 + 测试清单 + 设计决策 + 关键源码（含 005 K8S 编排迭代：懒 resolve / 启动 SPI / 复用 label Service）。查任一细节按文件索引定位。
