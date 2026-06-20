package com.arthas.gateway.backend;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * 单后端熔断器状态机（data-model.md §8，故障隔离）。
 *
 * <pre>
 *   CLOSED ──连续 N(=3) 次失败──► OPEN ──退避后──► HALF_OPEN ──探测成功──► CLOSED
 *                                   ▲                               │
 *                                   └───────────探测失败（退避升级）──┘
 * </pre>
 *
 * <ul>
 *   <li>CLOSED：正常转发。</li>
 *   <li>OPEN：{@link #allowRequest()} 立即返 false（不等 30s），调用方据此返明确错误。</li>
 *   <li>HALF_OPEN：退避期满后放 <b>1 个</b>探测；探测成功→CLOSED、失败→OPEN（退避升级）。</li>
 * </ul>
 *
 * <p>退避：base 1s、每轮 ×2、cap 30s；恢复（→CLOSED）后重置为基础值。
 *
 * <p><b>失败计入与否由调用方裁决</b>（data-model.md §8）：仅基础设施故障（连接拒绝/超时、initialize 失败、
 * 读超时、SSE 中断）调用 {@link #recordFailure()}；后端业务错误（isError=true/INVALID_PARAMS）是正常响应，
 * 调用 {@link #recordSuccess()}，<b>不计入</b>熔断（对齐宪法原则五：错误显式传播）。
 *
 * <p><b>线程安全（002 整改 P1-1/FR-003）</b>：全部可变状态访问方法均 {@code synchronized}。
 * 默认 {@code maxConcurrentTasks=5}（Semaphore 允许多线程并发操作熔断），"并发由槽串行化保证"的前提
 * 不成立（评审 P1-1）；synchronized 使连续失败计数累加、OPEN/HALF_OPEN 状态转换、HALF_OPEN 仅放 1 探测
 * 均原子一致——无丢失更新、无状态撕裂、无半开放行多探测。熔断非热路径，synchronized 开销可忽略。
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    public static final int DEFAULT_FAILURE_THRESHOLD = 3;
    public static final Duration DEFAULT_BASE_BACKOFF = Duration.ofSeconds(1);
    public static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(30);

    private final LongSupplier nanoClock;
    private final int failureThreshold;
    private final long baseBackoffNanos;
    private final long maxBackoffNanos;

    private State state = State.CLOSED;
    private int consecutiveFailures = 0;
    private int consecutiveOpens = 0;
    private long openedAtNanos = 0L;
    private long currentBackoffNanos = 0L;

    public CircuitBreaker(LongSupplier nanoClock, int failureThreshold, Duration baseBackoff, Duration maxBackoff) {
        this.nanoClock = nanoClock;
        this.failureThreshold = failureThreshold;
        this.baseBackoffNanos = baseBackoff.toNanos();
        this.maxBackoffNanos = maxBackoff.toNanos();
    }

    /** 默认参数（阈值 3、base 1s、cap 30s）+ 注入时钟。 */
    public static CircuitBreaker create(LongSupplier nanoClock) {
        return new CircuitBreaker(nanoClock, DEFAULT_FAILURE_THRESHOLD, DEFAULT_BASE_BACKOFF, DEFAULT_MAX_BACKOFF);
    }

    public synchronized State state() {
        return state;
    }

    /**
     * 熔断 OPEN 时建议的重试等待（毫秒，剩余退避）；非 OPEN 返 0。
     *
     * <p>用于失效 target 结构化错误（S-ERR-5）的 {@code retryAfterMs} 字段，告知调用方何时可再试。
     * OPEN 但已满退避时返 0（下次 {@link #allowRequest()} 会转 HALF_OPEN 放探测）。
     */
    public synchronized long retryAfterMillis() {
        if (state != State.OPEN) {
            return 0L;
        }
        long remainingNanos = currentBackoffNanos - (nanoClock.getAsLong() - openedAtNanos);
        return Math.max(0L, Duration.ofNanos(remainingNanos).toMillis());
    }

    /** 是否放行请求。CLOSED→true；OPEN→满退避则转 HALF_OPEN 放 1 探测，否则 false；HALF_OPEN→false（探测在途）。 */
    public synchronized boolean allowRequest() {
        return switch (state) {
            case CLOSED -> true;
            case OPEN -> {
                if (nanoClock.getAsLong() - openedAtNanos >= currentBackoffNanos) {
                    state = State.HALF_OPEN;
                    yield true;
                }
                yield false;
            }
            case HALF_OPEN -> false;
        };
    }

    /** 记录一次成功：清连续失败计数；HALF_OPEN 探测成功→CLOSED（退避重置）。 */
    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        if (state == State.HALF_OPEN) {
            state = State.CLOSED;
            consecutiveOpens = 0;
        }
    }

    /** 记录一次基础设施失败：CLOSED 累计达阈值→OPEN；HALF_OPEN 探测失败→OPEN（退避升级）；OPEN 忽略。 */
    public synchronized void recordFailure() {
        switch (state) {
            case CLOSED -> {
                consecutiveFailures++;
                if (consecutiveFailures >= failureThreshold) {
                    open();
                }
            }
            case HALF_OPEN -> open();
            case OPEN -> {
                // 已 OPEN，不刷新 openedAt（避免人为延长阻断）
            }
        }
    }

    /** 进入 OPEN：退避 = min(base × 2^(opens-1), max)，opens 累加。 */
    private void open() {
        consecutiveOpens++;
        currentBackoffNanos = escalatedBackoff();
        openedAtNanos = nanoClock.getAsLong();
        state = State.OPEN;
    }

    private long escalatedBackoff() {
        int shift = consecutiveOpens - 1;
        if (shift >= 31) {
            return maxBackoffNanos; // 必然超 cap，避免位移溢出
        }
        return Math.min(baseBackoffNanos * (1L << shift), maxBackoffNanos);
    }
}
