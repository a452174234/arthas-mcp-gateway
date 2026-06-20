package com.arthas.gateway.backend;

import com.arthas.gateway.handler.McpErrorCodes;
import com.arthas.gateway.testfixtures.FakeBackendClient;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T010（前置到 US1）{@link BackendEntry} 统一拦截层单测（002 整改 · P1-3/P1-4，US1）。
 *
 * <p>验证 {@code invoke} 故障分类（P1-3）、{@code execute} 槽 RAII、{@code admit} 准入守卫、
 * {@code initializeOnce} 握手恰好一次（P1-4）、{@code isHealthy} 单一事实源。
 *
 * <p><b>真实性</b>：用受控 {@link FakeBackendClient}（触发真实失败条件：抛异常/McpError/返回 isError）+
 * 真实 {@link CircuitBreaker} + 真实线程池（CAS 并发）。FakeBackendClient 非 arthas 成功桩（见其类注释）。
 */
class BackendEntryInterceptionLayerTest {

    private static BackendConfig cfg(int maxConcurrent) {
        return new BackendConfig("order", "http://localhost:8563", Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 60000, maxConcurrent);
    }

    private static BackendEntry entry(CircuitBreaker breaker, FakeBackendClient client) {
        return new BackendEntry(cfg(5), client, breaker);
    }

    private static CircuitBreaker closedBreaker() {
        return CircuitBreaker.create(new AtomicLong(0)::get);
    }

    // ===== invoke 故障分类（P1-3：异步/同步共用，业务错误不计熔断，基础设施故障计入）=====

    @Test
    void invokeSuccessRecordsSuccess() {
        CircuitBreaker breaker = closedBreaker();
        BackendEntry e = entry(breaker, new FakeBackendClient());

        CallToolResult r = e.invoke("jvm", Map.of());

        assertThat(r).isNotNull();
        assertThat(breaker.state()).as("成功 → recordSuccess，CLOSED").isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void invokeBusinessIsErrorRecordsSuccessNotFailure() {
        CircuitBreaker breaker = closedBreaker();
        FakeBackendClient client = new FakeBackendClient();
        client.setCallToolResult(new CallToolResult(List.of(new TextContent("业务错误")), true, null, null));
        BackendEntry e = entry(breaker, client);

        CallToolResult r = e.invoke("ognl", Map.of());

        assertThat(r.isError()).as("isError=true 原样透传").isTrue();
        assertThat(breaker.state()).as("业务错误不计熔断，CLOSED").isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void invokeMcpErrorRecordsSuccessAndRethrows() {
        CircuitBreaker breaker = closedBreaker();
        FakeBackendClient client = new FakeBackendClient();
        client.setCallToolThrow(McpError.builder(McpErrorCodes.INVALID_PARAMS).message("后端 JSON-RPC error").build());
        BackendEntry e = entry(breaker, client);

        assertThatThrownBy(() -> e.invoke("watch", Map.of())).isInstanceOf(McpError.class);
        assertThat(breaker.state()).as("McpError（后端业务错误）不计熔断，CLOSED").isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void invokeInfraFailureRecordsFailureAndThrowsUnreachable() {
        CircuitBreaker breaker = closedBreaker();
        FakeBackendClient client = new FakeBackendClient();
        client.setCallToolThrow(new java.io.UncheckedIOException(new java.io.IOException("conn refused")));
        BackendEntry e = entry(breaker, client);

        assertThatThrownBy(() -> e.invoke("jvm", Map.of()))
                .isInstanceOf(BackendUnreachableException.class)
                .hasCauseInstanceOf(java.io.UncheckedIOException.class);
        assertThat(breaker.state()).as("基础设施故障 → recordFailure（阈值 3 未达仍 CLOSED）")
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void invokeThreeInfraFailuresOpenCircuit() {
        AtomicLong clock = new AtomicLong(0);
        CircuitBreaker breaker = CircuitBreaker.create(clock::get);
        FakeBackendClient client = new FakeBackendClient();
        client.setCallToolThrow(new java.io.UncheckedIOException(new java.io.IOException("down")));
        BackendEntry e = entry(breaker, client);

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> e.invoke("jvm", Map.of())).isInstanceOf(BackendUnreachableException.class);
        }
        assertThat(breaker.state()).as("纯 invoke 路径 3 次基础设施失败 → OPEN（P1-3 异步/同步统一驱动）")
                .isEqualTo(CircuitBreaker.State.OPEN);
    }

    /** P1-3/cancel 方案：worker 中断态下 invoke 抛异常 → 不 recordFailure（避免 cancel 误熔断）。 */
    @Test
    void invokeInterruptedDoesNotRecordFailure() {
        CircuitBreaker breaker = closedBreaker();
        FakeBackendClient client = new FakeBackendClient();
        client.setCallToolThrow(new RuntimeException("interrupted-call"));
        BackendEntry e = entry(breaker, client);

        try {
            Thread.currentThread().interrupt(); // 模拟 cancel(true) 中断 worker
            assertThatThrownBy(() -> e.invoke("watch", Map.of()))
                    .isInstanceOf(BackendUnreachableException.class);
        } finally {
            Thread.interrupted(); // 清理中断态，避免污染后续测试
        }
        assertThat(breaker.state()).as("取消中断不计熔断，CLOSED").isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // ===== execute 槽 RAII（同步路径）=====

    @Test
    void executeAcquiresAndReleasesSlotOnSuccess() {
        BackendEntry e = entry(closedBreaker(), new FakeBackendClient());
        assertThat(e.availableSlots()).isEqualTo(5);

        e.execute("jvm", Map.of());

        assertThat(e.availableSlots()).as("execute 成功后槽全数回收（RAII）").isEqualTo(5);
    }

    @Test
    void executeReleasesSlotOnFailure() {
        FakeBackendClient client = new FakeBackendClient();
        client.setCallToolThrow(new RuntimeException("boom"));
        BackendEntry e = entry(closedBreaker(), client);

        assertThatThrownBy(() -> e.execute("jvm", Map.of())).isInstanceOf(BackendUnreachableException.class);
        assertThat(e.availableSlots()).as("失败路径也释放槽（RAII，不泄漏）").isEqualTo(5);
    }

    // ===== admit 准入守卫 =====

    @Test
    void admitCircuitOpenThrowsBeforeAcquiringSlot() {
        AtomicLong clock = new AtomicLong(0);
        CircuitBreaker breaker = CircuitBreaker.create(clock::get);
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure();
        }
        BackendEntry e = entry(breaker, new FakeBackendClient());

        assertThatThrownBy(() -> e.admit("watch"))
                .isInstanceOf(CircuitOpenException.class)
                .satisfies(ex -> assertThat(((CircuitOpenException) ex).retryAfterMs()).isPositive());
        assertThat(e.availableSlots()).as("熔断拒绝时未取槽（不泄漏）").isEqualTo(5);
    }

    @Test
    void admitConcurrencyLimitThrowsWhenSlotsExhausted() {
        BackendEntry e = new BackendEntry(cfg(2), new FakeBackendClient(), closedBreaker());
        e.admit("watch"); // 占 1
        e.admit("watch"); // 占 2（满）

        assertThatThrownBy(() -> e.admit("watch"))
                .isInstanceOf(ConcurrencyLimitException.class)
                .satisfies(ex -> assertThat(((ConcurrencyLimitException) ex).maxConcurrentTasks()).isEqualTo(2));
    }

    // ===== initializeOnce CAS（P1-4）=====

    @Test
    void initializeOnceHandshakesExactlyOnceUnderConcurrency() throws Exception {
        FakeBackendClient client = new FakeBackendClient();
        BackendEntry e = entry(closedBreaker(), client);

        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch fire = new CountDownLatch(1);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        fire.await();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    e.invoke("jvm", Map.of());
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

        assertThat(client.initializeCount()).as("并发 invoke 仅握手一次（P1-4 原子）").isEqualTo(1);
    }

    @Test
    void initializeOnceRetriesAfterFailure() {
        FakeBackendClient client = new FakeBackendClient();
        client.setInitializeThrow(new IllegalStateException("handshake failed"));
        BackendEntry e = entry(closedBreaker(), client);

        assertThatThrownBy(() -> e.invoke("jvm", Map.of()))
                .isInstanceOf(BackendUnreachableException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(client.initializeCount()).as("首次握手失败已尝试").isEqualTo(1);

        // 握手失败应可重试（initialized 回退），恢复成功后不再握手
        client.setInitializeThrow(null);
        CallToolResult r = e.invoke("jvm", Map.of()); // 握手成功 + callTool 返回，不抛
        assertThat(r).isNotNull();
        assertThat(client.initializeCount()).as("第二次成功握手").isEqualTo(2);
        assertThat(client.isInitialized()).isTrue();
    }

    // ===== isHealthy 单一事实源（US5 前置）=====

    @Test
    void isHealthyReflectsCircuitOpen() {
        CircuitBreaker breaker = closedBreaker();
        BackendEntry e = entry(breaker, new FakeBackendClient());

        assertThat(e.isHealthy()).as("ACTIVE + CLOSED → healthy").isTrue();

        for (int i = 0; i < 3; i++) {
            breaker.recordFailure();
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(e.isHealthy()).as("熔断 OPEN → unhealthy").isFalse();
    }

    @Test
    void isHealthyFalseWhenRetiredEvenIfBreakerClosed() {
        BackendEntry e = entry(closedBreaker(), new FakeBackendClient());
        e.markRetired();
        assertThat(e.isHealthy()).as("RETIRED → unhealthy（即便熔断 CLOSED）").isFalse();
    }
}
