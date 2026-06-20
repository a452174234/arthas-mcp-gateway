package com.arthas.gateway.backend;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T003 {@link CircuitBreaker} 线程安全单测（002 整改 P1-1/FR-003）。
 *
 * <p>验证 synchronized 修复后:并发 {@code recordFailure} 不丢失更新（达阈值精确 OPEN）、
 * HALF_OPEN 仅放 1 探测（无状态撕裂）。用<b>真实线程池 + CountDownLatch 屏障</b>触发真实并发
 * （非 arthas 桩；熔断器是纯逻辑领域对象）。
 */
class CircuitBreakerConcurrencyTest {

    @Test
    void concurrentRecordFailuresOpensAtThreshold_noLostUpdate() throws Exception {
        AtomicLong clock = new AtomicLong(0L);
        CircuitBreaker breaker = CircuitBreaker.create(clock::get);

        int threads = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch fire = new CountDownLatch(1);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        fire.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    breaker.recordFailure();
                    return null;
                });
            }
            ready.await();
            fire.countDown(); // 同时开闸，最大化并发竞争
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(breaker.state())
                .as("100 次并发失败后必 OPEN（阈值3，无丢失更新导致该断不断）")
                .isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void halfOpenAllowsExactlyOneProbe_underConcurrentAllowRequest() throws Exception {
        AtomicLong clock = new AtomicLong(0L);
        CircuitBreaker breaker = CircuitBreaker.create(clock::get);
        // 推到 OPEN（阈值 3）
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure();
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        clock.set(2_000_000_000L); // 推进超过 base 退避(1s)，下次 allowRequest 应转 HALF_OPEN 放探测

        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch fire = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger(0);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        fire.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    if (breaker.allowRequest()) {
                        allowed.incrementAndGet();
                    }
                    return null;
                });
            }
            ready.await();
            fire.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(allowed.get())
                .as("HALF_OPEN 仅放 1 个探测（synchronized 保证首个转 HALF_OPEN 后其余被拒，无半开放行多探测）")
                .isEqualTo(1);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    }
}
