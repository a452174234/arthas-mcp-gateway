package com.arthas.gateway.backend;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BackendEntry 单测（T025 容器 + T047 per-target 限流，data-model.md §3 / §11 规则 6）。
 *
 * <p>纯逻辑单测（surefire，无真实后端）。覆盖 {@code taskSlots}（Semaphore）非阻塞限流：
 * 许可数 = {@code config.maxConcurrentTasks}；{@code tryAcquireSlot} 拿 1 个许可，
 * 并发 &gt; 上限（耗尽许可）即时返 {@code false}（前置限流→调用方返 INVALID_PARAMS，C-LIMIT-1 的网关侧逻辑，
 * 避免越界打后端）；{@code releaseSlot} 回收许可（try-finally 配对）。
 *
 * <p><b>零桩约束的边界</b>：{@code NOOP_CLIENT} 是<b>测试缝</b>（仅满足 {@code client} 字段占位，
 * 限流单测从不调用其 {@code callTool}——{@code callTool} 抛 {@link UnsupportedOperationException}
 * 以明示「非 arthas 响应替身」）。真实 6 并发越界→INVALID_PARAMS 的端到端证明（C-LIMIT-1）由
 * {@code FaultIsolationContractTest}（T043，真实并发 task）承担，非本单测职责。熔断计入/不计入裁决
 * （C-CB-1/2）在 {@link CircuitBreakerTest}，401 处理在 T046。
 */
class BackendEntryTest {

    /** 测试缝：占位 client，限流单测从不调用其响应路径（非 arthas 替身）。 */
    private static final BackendClient NOOP_CLIENT = new BackendClient() {
        @Override
        public void initialize() {
            // 占位：限流单测不握手
        }

        @Override
        public McpSchema.CallToolResult callTool(String name, Map<String, Object> arguments) {
            throw new UnsupportedOperationException("限流单测不调用 client（测试缝，非 arthas 响应替身）");
        }

        @Override
        public boolean isInitialized() {
            return false;
        }

        @Override
        public void close() {
            // 占位
        }
    };

    private static BackendConfig config(int maxConcurrentTasks) {
        return new BackendConfig(
                "order-service",
                "http://host:8563/mcp",
                Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000,
                30000,
                maxConcurrentTasks);
    }

    private static BackendEntry entry(int maxConcurrentTasks) {
        return new BackendEntry(config(maxConcurrentTasks), NOOP_CLIENT, CircuitBreaker.create(() -> 0L));
    }

    @Test
    void initiallyActiveHoldsWiringAndFullSlots() {
        BackendEntry e = entry(2);
        assertThat(e.state()).as("新建 Entry 初始 ACTIVE").isEqualTo(BackendState.ACTIVE);
        assertThat(e.config().name()).isEqualTo("order-service");
        assertThat(e.client()).isSameAs(NOOP_CLIENT);
        assertThat(e.breaker().state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(e.availableSlots()).as("许可数 == maxConcurrentTasks").isEqualTo(2);
    }

    @Test
    void acquireUpToMaxConcurrentSucceeds() {
        BackendEntry e = entry(2);
        assertThat(e.tryAcquireSlot()).isTrue();
        assertThat(e.tryAcquireSlot()).isTrue();
        assertThat(e.availableSlots()).as("两次获取后许可耗尽").isZero();
    }

    @Test
    void acquireBeyondMaxRejectedImmediately() {
        BackendEntry e = entry(2);
        e.tryAcquireSlot();
        e.tryAcquireSlot();
        // 并发 > maxConcurrentTasks（许可耗尽）→ 前置限流，非阻塞即时返 false（不等）
        assertThat(e.tryAcquireSlot()).as("并发>上限即时限流返 false").isFalse();
    }

    @Test
    void releaseReenablesSubsequentAcquire() {
        BackendEntry e = entry(2);
        e.tryAcquireSlot();
        e.tryAcquireSlot();
        assertThat(e.tryAcquireSlot()).isFalse();
        e.releaseSlot();
        assertThat(e.availableSlots()).as("释放回收 1 个许可").isEqualTo(1);
        assertThat(e.tryAcquireSlot()).as("释放后可再次获取").isTrue();
    }

    @Test
    void availableSlotsTracksAcquireAndRelease() {
        BackendEntry e = entry(5);
        assertThat(e.availableSlots()).isEqualTo(5);
        e.tryAcquireSlot();
        assertThat(e.availableSlots()).isEqualTo(4);
        e.tryAcquireSlot();
        assertThat(e.availableSlots()).isEqualTo(3);
        e.releaseSlot();
        assertThat(e.availableSlots()).isEqualTo(4);
    }

    @Test
    void defaultsToFiveSlotsWhenConfigured() {
        // 对齐后端硬上限 5（data-model.md §2）；网关 Semaphore 守护避免越界 INVALID_PARAMS
        BackendEntry e = entry(5);
        for (int i = 0; i < 5; i++) {
            assertThat(e.tryAcquireSlot()).as("第 %d 次获取应成功", i + 1).isTrue();
        }
        assertThat(e.tryAcquireSlot()).as("第 6 次获取越界，前置限流").isFalse();
    }

    @Test
    void markRetiredTransitionsStateForGracefulShutdown() {
        // US2 热重载移除时标 RETIRED（in-flight 持旧 Entry 可完成，新调用不再路由到此）
        BackendEntry e = entry(2);
        assertThat(e.state()).isEqualTo(BackendState.ACTIVE);
        e.markRetired();
        assertThat(e.state()).isEqualTo(BackendState.RETIRED);
        // RETIRED 不影响限流语义（in-flight 调用仍需配对释放槽位）
        assertThat(e.tryAcquireSlot()).isTrue();
        e.releaseSlot();
    }
}
