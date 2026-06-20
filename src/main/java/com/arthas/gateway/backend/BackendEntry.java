package com.arthas.gateway.backend;

import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;

/**
 * 单后端运行对象（data-model.md §3）+ <b>统一拦截层</b>（002 整改 · data-model.md §2）。
 *
 * <p>聚合：{@code config}（不可变声明）+ {@code client}（MCP 客户端契约）+ {@code breaker}（熔断器）+
 * {@code taskSlots}（Semaphore，per-target 限流）+ {@code state}（ACTIVE/RETIRED）+ {@code initialized}
 *（initialize 原子守卫，P1-4）。
 *
 * <p><b>不变量</b>：一次 {@code tools/call} 全程持有<b>固定的</b> BackendEntry 引用（调用方持 {@code final} 引用），
 * registry 原子替换不影响 in-flight 调用（data-model.md §3「热重载并发不串台」边缘情况）。
 *
 * <h3>统一拦截层（002 整改）</h3>
 * <p>原散在 {@code ToolsCallRouter} 两路径的「熔断守卫 + 取/还槽 + initialize + 故障分类」收口到此，
 * 使同步/异步共用同一分类规则（data-model.md §2.2）：
 * <ul>
 *   <li>{@link #execute}：<b>同步入口</b>。{@code admitCore}（熔断读 + 取槽）→ {@code invoke} → {@code finally releaseSlot}
 *       （RAII，同步路径槽结构性不漏，P0-2）。<b>不经 STATELESS 校验</b>（T013：STATELESS 同步仍可用）。</li>
 *   <li>{@link #admit}：<b>异步前置准入</b>（{@code submitAsync} 在 {@code asyncExecutor.submit} 前调用）。
 *       US1 = 熔断读 + 取槽；US3 在首部加 STATELESS 校验（仅异步）。释放唯一由 {@code AsyncTaskExecutor.onTerminal}
 *       负责（路由器闭包不再手动 releaseSlot，P0-2）。</li>
 *   <li>{@link #invoke}：真正调后端 + 故障分类（同步/异步共用，P1-3）。
 *       {@code initializeOnce}(CAS/DCL) → {@code callTool}；成功/业务错误（{@code McpError}/{@code isError=true}）
 *       → {@code recordSuccess}（不计熔断，C-CB-2）；基础设施故障（非 McpError 的 {@code RuntimeException}）
 *       → {@code recordFailure}（C-CB-1）并抛 {@link BackendUnreachableException}。<b>取消中断不计熔断</b>
 *       （检测 {@code Thread.interrupted()} 跳过 recordFailure，P1-3/cancel 方案）。</li>
 *   <li>{@link #isHealthy}：健康单一事实源（US5）：{@code state==ACTIVE && breaker.state()!=OPEN}。</li>
 * </ul>
 *
 * <p><b>错误边界</b>：{@code admitCore}/{@code invoke} 抛<b>域异常</b>（携带 {@code retryAfterMs}/
 * {@code maxConcurrentTasks}/{@code cause}），<b>不</b>依赖 {@code McpError}/注册表；结构化 {@code McpError}
 * （含 {@code data.available}）由 {@code ToolsCallRouter} 翻译（{@code data.available} 需 {@code RegistryHolder}）。
 *
 * @param config  后端声明（不可变）
 * @param client  MCP 客户端契约（真实实装 HttpBackendClient）
 * @param breaker 熔断器（注入时钟，便于无真实时间的单测）
 */
public final class BackendEntry {

    private final BackendConfig config;
    private final BackendClient client;
    private final CircuitBreaker breaker;
    private final Semaphore taskSlots;
    private final Object initLock = new Object();
    private volatile BackendState state = BackendState.ACTIVE;
    private volatile boolean initialized = false;

    public BackendEntry(BackendConfig config, BackendClient client, CircuitBreaker breaker) {
        this.config = Objects.requireNonNull(config, "config 不可为空");
        this.client = Objects.requireNonNull(client, "client 不可为空");
        this.breaker = Objects.requireNonNull(breaker, "breaker 不可为空");
        this.taskSlots = new Semaphore(config.maxConcurrentTasks());
    }

    public BackendConfig config() {
        return config;
    }

    public BackendClient client() {
        return client;
    }

    public CircuitBreaker breaker() {
        return breaker;
    }

    /** 当前运行态（ACTIVE=在册可用 / RETIRED=热重载移除中，in-flight 可完成）。 */
    public BackendState state() {
        return state;
    }

    /** US2 热重载移除时标 RETIRED：in-flight 持旧 Entry 可完成，新调用不再路由到此。 */
    public void markRetired() {
        this.state = BackendState.RETIRED;
    }

    /**
     * 同步执行一次完整调用（统一拦截层 · 同步入口，US1 T007）：
     * {@code admitCore}（熔断读 + 取槽）→ {@code invoke}（initialize + callTool + 分类）→ {@code finally releaseSlot}。
     *
     * <p>槽在 {@code admitCore} 取、{@code finally} 还，任意路径（成功/失败/异常）必配对 → 同步路径槽结构性不漏（P0-2）。
     *
     * @throws CircuitOpenException          熔断 OPEN 未满退避（admitCore）
     * @throws ConcurrencyLimitException     并发越界（admitCore）
     * @throws BackendUnreachableException   基础设施故障（invoke）；后端业务错误 {@code McpError} 原样向上抛
     */
    public CallToolResult execute(String toolName, Map<String, Object> backendArgs) {
        admitCore();
        try {
            return invoke(toolName, backendArgs);
        } finally {
            releaseSlot();
        }
    }

    /**
     * 异步前置准入（{@code submitAsync} 在提交后台前调用，US1 T007 + US3 T013）。
     *
     * <p>首检 <b>STATELESS 协议</b>（US3/P1-2）：无状态后端无法承载带任务语义、需轮询的异步诊断调用，
     * 嫡出 {@link StatelessAsyncException}（路由器翻译为 {@code INVALID_PARAMS} +
     * {@code reason=stateless_unsupported_async}）——前置拒绝，<b>不</b>提交后台、不耗兜底超时、不取槽。
     * 随后熔断读 + 取槽（与 {@code execute} 共用 {@link #admitCore}）。
     *
     * <p><b>仅异步路径</b>校验：同步 {@code execute} 直调 {@code admitCore}（不经 STATELESS 检查），
     * 故 STATELESS 后端同步调用仍正常（T013 不变量）。
     * 取得的槽由 {@code AsyncTaskExecutor.onTerminal}（→ {@link #releaseSlot()}）在任务终态/提交失败时释放。
     *
     * @param toolName 工具名（日志/未来可观测用；STATELESS 校验不依赖具体工具名——协议是后端级属性）
     * @throws StatelessAsyncException   后端协议为 STATELESS（仅异步）
     * @throws CircuitOpenException      熔断 OPEN 未满退避
     * @throws ConcurrencyLimitException 并发越界
     */
    public void admit(String toolName) {
        if (config.protocol() == Protocol.STATELESS) {
            throw new StatelessAsyncException();
        }
        admitCore();
    }

    /** 准入核心：熔断守卫读 + 取槽（同步 execute 与异步 admit 共用）。 */
    private void admitCore() {
        if (!breaker.allowRequest()) {
            throw new CircuitOpenException(breaker.retryAfterMillis());
        }
        if (!taskSlots.tryAcquire()) {
            throw new ConcurrencyLimitException(config.maxConcurrentTasks());
        }
    }

    /**
     * 调用后端 + initialize + 故障分类 + 熔断驱动（统一拦截层核心，US1 T007 + US2 T011）。
     *
     * <p>同步/异步共用（P1-3）：返回后端<b>原始</b> {@code CallToolResult}（含 {@code isError=true}，原样透传，原则二）。
     * <ul>
     *   <li>正常返回（含 {@code isError=true} 业务错误）→ {@code recordSuccess}（不计熔断，C-CB-2）。</li>
     *   <li>{@code McpError}（后端 JSON-RPC error）→ {@code recordSuccess} 后<b>原样抛出</b>（不计熔断，C-CB-2）。</li>
     *   <li>非 McpError 的 {@code RuntimeException}（基础设施故障，含 initialize 失败）→
     *       若当前线程<b>中断态</b>（取消导致）<b>跳过</b> recordFailure（避免 cancel 误熔断，P1-3/cancel），
     *       否则 {@code recordFailure}（C-CB-1）后抛 {@link BackendUnreachableException}。</li>
     * </ul>
     */
    public CallToolResult invoke(String toolName, Map<String, Object> backendArgs) {
        try {
            initializeOnce();
            CallToolResult result = client.callTool(toolName, backendArgs);
            breaker.recordSuccess();
            return result;
        } catch (McpError e) {
            breaker.recordSuccess();
            throw e;
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                // 取消导致的中断：不计熔断（避免 cancel 误开熔断拖累同步路径）；仍抛供上层标 cancelled/failed
                throw new BackendUnreachableException(e);
            }
            breaker.recordFailure();
            throw new BackendUnreachableException(e);
        }
    }

    /**
     * 幂等 initialize 守卫（P1-4/FR-006，自 HttpBackendClient 上移至拦截层，便于注入测试）。
     *
     * <p>双检锁（volatile + synchronized）：仅首个持锁者真正 {@code client.initialize()}，其余并发调用方<b>等待</b>
     * 其完成后跳过（裸 CAS 会让 loser 抢跑 callTool 导致会话未就绪，故用 DCL 保证「恰好一次 + 其余等待」）。
     * 握手失败时 {@code initialized} 保持 false → 下次调用重试（不毒化 entry）。
     */
    private void initializeOnce() {
        if (initialized) {
            return;
        }
        synchronized (initLock) {
            if (!initialized) {
                client.initialize();
                initialized = true;
            }
        }
    }

    /** 健康单一事实源（US5/FR-012）：{@code ACTIVE} 且熔断非 OPEN。list-targets/HealthIndicator/守卫共用。 */
    public boolean isHealthy() {
        return state == BackendState.ACTIVE && breaker.state() != CircuitBreaker.State.OPEN;
    }

    /**
     * 非阻塞获取一个并发槽（既有，保留供可观测/测试；生产路由已改走 {@link #admit}）。
     *
     * @return true=获槽；false=越界
     */
    public boolean tryAcquireSlot() {
        return taskSlots.tryAcquire();
    }

    /** 释放一个并发槽（RAII finally / 异步 onTerminal 共用）。 */
    public void releaseSlot() {
        taskSlots.release();
    }

    /** 当前可用并发许可数（list-targets / 可观测 / 测试用）。 */
    public int availableSlots() {
        return taskSlots.availablePermits();
    }
}
