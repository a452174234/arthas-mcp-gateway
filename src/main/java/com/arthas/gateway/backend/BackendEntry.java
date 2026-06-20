package com.arthas.gateway.backend;

import java.util.Objects;
import java.util.concurrent.Semaphore;

/**
 * 单后端运行对象（data-model.md §3）。
 *
 * <p>聚合：{@code config}（不可变声明）+ {@code client}（MCP 客户端契约，真实实装 T024）+
 * {@code breaker}（熔断器）+ {@code taskSlots}（Semaphore，per-target 限流）+ {@code state}（ACTIVE/RETIRED）。
 *
 * <p><b>不变量</b>：一次 {@code tools/call} 全程持有<b>固定的</b> BackendEntry 引用
 * （调用方持 {@code final} 引用），registry 原子替换不影响 in-flight 调用
 * （data-model.md §3「热重载并发不串台」边缘情况）。
 *
 * <p><b>per-target 限流（T047）</b>：{@code taskSlots = Semaphore(config.maxConcurrentTasks)}，
 * 默认/上限 5（后端硬约束）。调用前 {@link #tryAcquireSlot()} 非阻塞取许可；耗尽→返 false→
 * 调用方前置返 INVALID_PARAMS，避免越界触发后端 INVALID_PARAMS（C-LIMIT-1 的网关侧逻辑，
 * data-model.md §11 规则 6）。调用完成后 {@link #releaseSlot()} 回收（{@code try-finally} 配对）。
 *
 * @param config  后端声明（不可变）
 * @param client  MCP 客户端契约（真实实装 T024）
 * @param breaker 熔断器（注入时钟，便于无真实时间的单测）
 */
public final class BackendEntry {

    private final BackendConfig config;
    private final BackendClient client;
    private final CircuitBreaker breaker;
    private final Semaphore taskSlots;
    private volatile BackendState state = BackendState.ACTIVE;

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
     * 非阻塞获取一个并发槽（T047）。许可耗尽（并发 &gt; {@code maxConcurrentTasks}）即时返 false，
     * 调用方据此前置返 INVALID_PARAMS，避免越界打后端（C-LIMIT-1）。
     *
     * @return true=获槽可转发；false=越界应前置限流
     */
    public boolean tryAcquireSlot() {
        return taskSlots.tryAcquire();
    }

    /** 释放一个并发槽，与 {@link #tryAcquireSlot()} 在 {@code try-finally} 中配对。 */
    public void releaseSlot() {
        taskSlots.release();
    }

    /** 当前可用并发许可数（测试 / 可观测 / list-targets 用）。 */
    public int availableSlots() {
        return taskSlots.availablePermits();
    }
}
