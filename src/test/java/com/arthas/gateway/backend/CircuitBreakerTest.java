package com.arthas.gateway.backend;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CircuitBreaker 状态机测试（T045，data-model.md §8）。
 *
 * <p>纯逻辑单测（surefire，无需真实后端）。用可推进的假时钟（纳秒）驱动，不依赖真实时间。
 * 覆盖：CLOSED→OPEN（连续 3 次基础设施失败）→HALF_OPEN（退避后）→CLOSED（探测成功）/ OPEN（探测失败，退避升级）；
 * 退避 base 1s×2、cap 30s；HALF_OPEN 仅放 1 个探测。
 *
 * <p><b>失败计入与否的裁决不在本类</b>——业务错误（isError=true/INVALID_PARAMS）不计入熔断，
 * 由 BackendClient（T024/T046，Wave C）决定是否调用 {@code recordFailure}。本类仅提供 success/failure 记录与状态。
 */
class CircuitBreakerTest {

    /** 可推进的假时钟（纳秒），避免依赖真实时间。 */
    private static final class FakeClock implements LongSupplier {
        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long getAsLong() {
            return nanos.get();
        }

        void advance(Duration d) {
            nanos.addAndGet(d.toNanos());
        }
    }

    private static CircuitBreaker breaker(FakeClock clock) {
        return CircuitBreaker.create(clock);
    }

    private static void failN(CircuitBreaker b, int n) {
        for (int i = 0; i < n; i++) {
            b.recordFailure();
        }
    }

    @Test
    void initiallyClosedAndAllowsRequests() {
        CircuitBreaker b = breaker(new FakeClock());
        assertThat(b.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(b.allowRequest()).isTrue();
    }

    @Test
    void staysClosedBelowFailureThreshold() {
        CircuitBreaker b = breaker(new FakeClock());
        failN(b, 2); // < 阈值 3
        assertThat(b.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(b.allowRequest()).isTrue();
    }

    @Test
    void opensAfterConsecutiveFailureThreshold() {
        CircuitBreaker b = breaker(new FakeClock());
        failN(b, 3);
        assertThat(b.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(b.allowRequest()).as("OPEN 立即阻断，不等 30s").isFalse();
    }

    @Test
    void failureStreakResetsOnSuccess() {
        CircuitBreaker b = breaker(new FakeClock());
        failN(b, 2);
        b.recordSuccess(); // 成功打断连续失败计数
        failN(b, 2);
        assertThat(b.state()).as("成功打断连续计数，未达阈值").isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(b.allowRequest()).isTrue();
    }

    @Test
    void openBlocksUntilBaseBackoffElapsesThenGrantsProbe() {
        FakeClock clock = new FakeClock();
        CircuitBreaker b = breaker(clock);
        failN(b, 3);
        assertThat(b.state()).isEqualTo(CircuitBreaker.State.OPEN);

        clock.advance(Duration.ofMillis(999)); // base 退避 1s，差 1ms
        assertThat(b.allowRequest()).as("未满 1s 退避，阻断").isFalse();
        assertThat(b.state()).isEqualTo(CircuitBreaker.State.OPEN);

        clock.advance(Duration.ofMillis(1)); // 满 1s
        assertThat(b.allowRequest()).as("满退避，放行 1 个探测").isTrue();
        assertThat(b.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    }

    @Test
    void halfOpenProbeSuccessClosesCircuit() {
        FakeClock clock = new FakeClock();
        CircuitBreaker b = breaker(clock);
        failN(b, 3);
        clock.advance(Duration.ofSeconds(1));
        assertThat(b.allowRequest()).isTrue(); // HALF_OPEN
        b.recordSuccess();
        assertThat(b.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(b.allowRequest()).isTrue();
    }

    @Test
    void halfOpenProbeFailureReopensWithEscalatedBackoff() {
        FakeClock clock = new FakeClock();
        CircuitBreaker b = breaker(clock);
        failN(b, 3);                  // 退避 1s
        clock.advance(Duration.ofSeconds(1));
        b.allowRequest();             // HALF_OPEN
        b.recordFailure();            // 探测失败 → OPEN，退避升级 2s
        assertThat(b.state()).isEqualTo(CircuitBreaker.State.OPEN);
        clock.advance(Duration.ofMillis(1999));
        assertThat(b.allowRequest()).as("升级后 2s 退避未满，阻断").isFalse();
        clock.advance(Duration.ofMillis(1));
        assertThat(b.allowRequest()).as("满 2s 退避，放行").isTrue();
    }

    @Test
    void halfOpenBlocksSecondRequestDuringProbe() {
        FakeClock clock = new FakeClock();
        CircuitBreaker b = breaker(clock);
        failN(b, 3);
        clock.advance(Duration.ofSeconds(1));
        assertThat(b.allowRequest()).as("首个探测放行").isTrue();
        assertThat(b.allowRequest()).as("探测未决前，第二个请求阻断（HALF_OPEN 仅放 1 个）").isFalse();
    }

    @Test
    void backoffEscalatesAndCapsAt30s() {
        FakeClock clock = new FakeClock();
        CircuitBreaker b = breaker(clock);
        // 退避序列：1,2,4,8,16,32→cap 30,30
        long[] expectedMs = {1000, 2000, 4000, 8000, 16000, 30000, 30000};
        for (long ms : expectedMs) {
            if (b.state() == CircuitBreaker.State.CLOSED) {
                failN(b, 3); // 首轮靠失败开路；后续轮探测失败已置 OPEN
            }
            clock.advance(Duration.ofMillis(ms - 1));
            assertThat(b.allowRequest()).as("退避 %dms 未满应阻断", ms).isFalse();
            clock.advance(Duration.ofMillis(1));
            assertThat(b.allowRequest()).as("退避 %dms 满应放探测", ms).isTrue();
            b.recordFailure(); // 升级
        }
    }

    @Test
    void closingResetsBackoffToBase() {
        FakeClock clock = new FakeClock();
        CircuitBreaker b = breaker(clock);
        failN(b, 3);                  // 退避 1s
        clock.advance(Duration.ofSeconds(1));
        b.allowRequest();             // HALF_OPEN
        b.recordFailure();            // 升级 2s
        clock.advance(Duration.ofSeconds(2));
        b.allowRequest();             // HALF_OPEN
        b.recordSuccess();            // CLOSED，退避计数重置

        failN(b, 3);                  // 再次开路，应为基础 1s（非升级值）
        clock.advance(Duration.ofMillis(999));
        assertThat(b.allowRequest()).as("恢复后重置为基础 1s 退避").isFalse();
        clock.advance(Duration.ofMillis(1));
        assertThat(b.allowRequest()).isTrue();
    }
}
