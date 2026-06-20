package com.arthas.gateway.integration;

import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.BackendEntry;
import com.arthas.gateway.backend.BackendEntryFactory;
import com.arthas.gateway.backend.BackendRegistry;
import com.arthas.gateway.backend.Protocol;
import com.arthas.gateway.backend.RegistryHolder;
import com.arthas.gateway.testfixtures.ArthasMcpBackend;
import com.arthas.gateway.testfixtures.McpClientHarness;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T030 异步后台超时契约测试（failsafe，G-ASYNC-2，真实 arthas + 真实业务服务 + 真实网关，零桩）。
 *
 * <p><b>真实慢后端条件</b>：后台 hot-loop 每 ~50ms 调一次 {@code OrderService.hotMethod}（arthas watch 事件源）。
 * 提交 watch（{@code numberOfExecutions=100}）→ arthas 需采集 ~100 次执行才返回（hot-loop ~20/s → ~5s），
 * <b>确定性</b>超过本测试注入的<b>短兜底</b> {@code arthas-gateway.task.backend-timeout=3s}
 * （覆盖默认 11min；G-ASYNC-2 用「短兜底」让真实超时在秒级可测，而非等 11min）。
 * 后台 worker {@code get(3s)} 超时 → {@code worker.cancel(true)} 中断后端 HTTP 调用 → task 标
 * {@code failed} + {@code error.reason="backend_timeout"}（{@code TaskError.REASON_BACKEND_TIMEOUT}）。
 *
 * <p><b>为何不用 per-request {@code slowMs} / {@code numberOfExecutions=1}</b>：hot-loop 连续触发快调用，
 * watch({@code numberOfExecutions=1}) 会立刻命中某个快调用而秒完成，无法形成稳定的「超时」条件；
 * 高 {@code numberOfExecutions} 让 arthas 真实采集中被兜底打断，是<b>确定性</b>的真实慢后端。
 *
 * <p>无任何桩——失败原因是真实的 {@link java.util.concurrent.TimeoutException}（后台兜底），
 * 非模拟。SDK client 自身 30s 请求超时不触发（3s 兜底先于它）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "arthas-gateway.task.backend-timeout=3s")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AsyncTaskTimeoutIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TARGET_CLASS = "com.arthas.gateway.testfixtures.OrderService";
    private static final String TARGET_METHOD = "hotMethod";

    @LocalServerPort
    private int port;

    @Autowired
    private RegistryHolder holder;

    @Autowired
    private BackendEntryFactory factory;

    private ArthasMcpBackend backend;

    private String gatewayUrl() {
        return "http://localhost:" + port + "/mcp";
    }

    @BeforeAll
    void startRealArthasAndRegister() throws Exception {
        backend = ArthasMcpBackend.start("order");
        BackendEntry entry = factory.create(noneAuth("order", backend.baseUrl()));
        holder.getAndSet(new BackendRegistry(1L, Map.of("order", entry)));
    }

    @AfterAll
    void stopBackend() {
        if (backend != null) {
            backend.close();
        }
    }

    // ===== G-ASYNC-2：后台超短兜底 → task FAILED（backend_timeout） =====

    @Test
    void backend_timeout_marks_task_failed() throws Exception {
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();

            // 立即接受（G-ASYNC-1 形状复用）
            CallToolResult accepted = h.callTool("watch", Map.of(
                    "target", "order",
                    "classPattern", TARGET_CLASS,
                    "methodPattern", TARGET_METHOD,
                    "numberOfExecutions", 100, // ~5s 采满，> 3s 兜底（hot-loop ~20/s）
                    "timeout", 30));
            JsonNode acc = JSON.readTree(text(accepted));
            String taskId = acc.path("taskId").asText();
            assertThat(taskId).matches("t-[0-9a-f]{6,}");
            assertThat(acc.path("status").asText()).isEqualTo("working");

            // 轮询至终态：预期 3s 兜底触发 → failed + backend_timeout
            JsonNode terminal = pollUntilTerminal(h, taskId, Duration.ofSeconds(20));
            assertThat(terminal.path("status").asText())
                    .as("G-ASYNC-2：后台超短兜底 → failed").isEqualTo("failed");
            assertThat(terminal.path("error").path("reason").asText())
                    .as("失败原因：backend_timeout（真实 TimeoutException，非桩）")
                    .isEqualTo("backend_timeout");
            assertThat(terminal.path("error").path("message").asText())
                    .as("错误详情非空").isNotBlank();
        }
    }

    // ===== 辅助 =====

    /** 轮询 task-get 直到非 working 终态；返回该终态的 task-get JSON 节点。 */
    private static JsonNode pollUntilTerminal(McpClientHarness h, String taskId, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            CallToolResult r = h.callTool("arthas-gateway.task-get", Map.of("taskId", taskId));
            JsonNode node = JSON.readTree(text(r));
            if (!"working".equals(node.path("status").asText())) {
                return node;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("任务在 " + timeout + " 内未达终态（仍 working），taskId=" + taskId);
    }

    private static String text(CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        for (var c : result.content()) {
            if (c instanceof TextContent tc && tc.text() != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(tc.text());
            }
        }
        return sb.toString();
    }

    private static BackendConfig noneAuth(String name, String url) {
        return new BackendConfig(
                name, url, Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5);
    }
}
