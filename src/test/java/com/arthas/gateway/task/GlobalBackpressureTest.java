package com.arthas.gateway.task;

import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.BackendEntry;
import com.arthas.gateway.backend.BackendRegistry;
import com.arthas.gateway.backend.CircuitBreaker;
import com.arthas.gateway.backend.Protocol;
import com.arthas.gateway.backend.RegistryHolder;
import com.arthas.gateway.handler.GatewayToolHandlers;
import com.arthas.gateway.handler.McpErrorCodes;
import com.arthas.gateway.handler.ToolsCallRouter;
import com.arthas.gateway.testfixtures.FakeBackendClient;
import com.arthas.gateway.tool.ExposedTool;
import com.arthas.gateway.tool.RoutingMode;
import com.arthas.gateway.tool.TaskSupport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T016 全局背压单测(002 整改 · P2-4/FR-010,US4)。
 *
 * <p>验证跨 target 累计在途异步任务受全局上限闸门:{@code AsyncTaskExecutor} 持 {@code globalInflight} 计数,
 * {@code submit} 前 acquire、终态 release。动态默认=后端数×5(本测试注入小 cap=2 便于触发)。
 *
 * <p><b>真实性</b>:2 个真实 {@link BackendEntry}(STREAMABLE)+ 受控 {@link FakeBackendClient}
 * (callTool 阻塞在 latch 上保持 inflight,模拟真实在途异步任务)。第 3 次跨 target 提交触发真实
 * {@link GlobalConcurrencyLimitException}(非桩)→ 路由器翻译为 {@code global_concurrency_limit}。
 */
class GlobalBackpressureTest {

    private static final String TARGET_A = "svc-a";
    private static final String TARGET_B = "svc-b";

    private final java.util.concurrent.atomic.AtomicInteger idSeq = new java.util.concurrent.atomic.AtomicInteger();

    private TaskStore store;
    private AsyncTaskExecutor executor;
    private ToolsCallRouter router;
    private CountDownLatch block;
    private FakeBackendClient clientA;
    private FakeBackendClient clientB;

    @BeforeEach
    void setUp() {
        store = new TaskStore(Duration.ofHours(1), java.time.Instant::now);
        // 全局 cap=2(注入小值便于触发越界;生产动态默认=后端数×5)
        executor = new AsyncTaskExecutor(store, Duration.ofMinutes(11), java.time.Instant::now,
                () -> "t-gb-" + idSeq.incrementAndGet(),
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(), () -> 2);

        block = new CountDownLatch(1);
        clientA = blockingClient();
        clientB = blockingClient();
        BackendEntry entryA = new BackendEntry(cfg(TARGET_A), clientA, CircuitBreaker.create(System::nanoTime));
        BackendEntry entryB = new BackendEntry(cfg(TARGET_B), clientB, CircuitBreaker.create(System::nanoTime));
        BackendRegistry registry = new BackendRegistry(1L, Map.of(TARGET_A, entryA, TARGET_B, entryB));

        router = new ToolsCallRouter(new RegistryHolder(registry), executor,
                new GatewayToolHandlers(new RegistryHolder(registry), executor));
    }

    @AfterEach
    void tearDown() {
        if (block.getCount() > 0) {
            block.countDown(); // 释放在途任务
        }
        executor.close();
        store.close();
    }

    /** 跨 target 累计 inflight 超 cap → 第 3 次提交返 INVALID_PARAMS(reason=global_concurrency_limit)。 */
    @Test
    void crossTargetCumulativeInflightExceedsGlobalCap() {
        // 提交 2 个跨 target 异步(各阻塞在 latch 上,保持 inflight)= cap
        router.route(asyncTool(), new CallToolRequest("watch", Map.of("target", TARGET_A)));
        router.route(asyncTool(), new CallToolRequest("watch", Map.of("target", TARGET_B)));
        assertThat(executor.currentGlobalInflight())
                .as("2 个在途任务占满全局 cap=2").isEqualTo(2);

        // 第 3 次跨 target 提交 → 全局越界 → McpError
        assertThatThrownBy(() -> router.route(asyncTool(), new CallToolRequest("watch", Map.of("target", TARGET_A))))
                .isInstanceOf(McpError.class)
                .satisfies(t -> {
                    McpError err = (McpError) t;
                    assertThat(err.getJsonRpcError().code())
                            .as("全局越界 → INVALID_PARAMS").isEqualTo(McpErrorCodes.INVALID_PARAMS);
                    Map<?, ?> data = (Map<?, ?>) err.getJsonRpcError().data();
                    assertThat(data.get("reason")).isEqualTo("global_concurrency_limit");
                    assertThat(data.get("globalMaxInflight")).isEqualTo(2);
                    assertThat(data.get("target")).isEqualTo(TARGET_A);
                });
        // 全局计数未被第 3 次占用(已回滚),仍为 2
        assertThat(executor.currentGlobalInflight()).isEqualTo(2);
        // 第 3 次未入 store(无僵尸)
        assertThat(store.list(TaskState.WORKING)).hasSize(2);
    }

    /** 未越界:cap 内的异步提交正常接受(store 出现 WORKING 任务、全局计数=1)。 */
    @Test
    void underCapAsyncSubmitsSucceed() {
        router.route(asyncTool(), new CallToolRequest("watch", Map.of("target", TARGET_A)));
        assertThat(executor.currentGlobalInflight()).as("1 个在途,未越界").isEqualTo(1);
        assertThat(store.list(TaskState.WORKING)).hasSize(1);
    }

    private static BackendConfig cfg(String name) {
        return new BackendConfig(name, "http://localhost:9001", Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null), 5000, 60000, 5);
    }

    private FakeBackendClient blockingClient() {
        FakeBackendClient c = new FakeBackendClient();
        c.setCallToolBlock(block); // 同一 latch:两个 client 的 callTool 都阻塞,保持 inflight
        return c;
    }

    private static ExposedTool asyncTool() {
        return new ExposedTool(
                "watch",
                "异步观测",
                Map.of(
                        "type", "object",
                        "properties", new LinkedHashMap<>(),
                        "required", List.of(),
                        "additionalProperties", false),
                TaskSupport.OPTIONAL,
                RoutingMode.ASYNC_TASK);
    }
}
