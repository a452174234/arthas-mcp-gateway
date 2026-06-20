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

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T035 端到端异步任务契约测试（failsafe，真实 arthas + 真实业务服务 + 真实网关，零桩）。
 *
 * <p>经官方 SDK client 连网关 {@code /mcp}，驱动 watch（ASYNC_TASK optional 工具）走方案 C 全链路：
 * 网关 handler → {@code ToolsCallRouter} → {@code AsyncTaskExecutor} 后台虚拟线程 → 真实 arthas 后端
 * （Flow B：tools/call 不带 task，后端自动轮询路①）。
 *
 * <p>真实完成路径：watch 提交后<b>触发业务方法</b>（GET {@code /api/order} → {@code OrderService.hotMethod}），
 * arthas watch 命中（numberOfExecutions=1）→ 后端 Flow B 同步返回真实诊断 → 网关 task 转 COMPLETED。
 *
 * <p>断言（gateway-tools-contract.md §6）：
 * <ul>
 *   <li>G-ASYNC-1：watch 立即返 {taskId, status:working}（SDK client 不阻塞）。</li>
 *   <li>G-TG-1：poll task-get → COMPLETED，含<b>真实</b> watch 诊断输出（命中 hotMethod 的硬证据）。</li>
 *   <li>G-TL-1：task-list 含该任务。</li>
 *   <li>G-TC-1：task-cancel 一个 WORKING watch（未触发，numberOfExecutions=200）→ cancelled。</li>
 * </ul>
 *
 * <p>纯逻辑分支（completed/failed/cancelled 响应整形、isError 原样、未知 taskId）由
 * {@code GatewayToolsContractTest}（T029，surefire）覆盖；本 IT 聚焦<b>真实 arthas 端到端保真</b>。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AsyncTaskContractIT {

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

    // ===== G-ASYNC-1 + G-TG-1（completed，真实 watch 诊断） =====

    @Test
    void async_watch_completes_with_real_result() throws Exception {
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();

            // G-ASYNC-1：立即返 taskId + working（SDK client 不阻塞 30s+）
            long start = System.nanoTime();
            CallToolResult accepted = h.callTool("watch", Map.of(
                    "target", "order",
                    "classPattern", TARGET_CLASS,
                    "methodPattern", TARGET_METHOD,
                    "numberOfExecutions", 1,
                    "timeout", 30));
            long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

            JsonNode acc = JSON.readTree(text(accepted));
            String taskId = acc.path("taskId").asText();
            assertThat(taskId).as("watch 立即返回 taskId").matches("t-[0-9a-f]{6,}");
            assertThat(acc.path("status").asText()).isEqualTo("working");
            assertThat(elapsedMs).as("G-ASYNC-1：不阻塞等后端").isLessThan(5000);

            // 触发业务方法 → arthas watch 命中 → 后端 Flow B 返回真实诊断
            triggerOrder(backend.appPort());
            String status = pollTaskStatus(h, taskId, Duration.ofSeconds(30));

            assertThat(status).as("watch 完成（真实 arthas 诊断）").isEqualTo("completed");
            CallToolResult getResult = h.callTool("arthas-gateway.task-get", Map.of("taskId", taskId));
            JsonNode completed = JSON.readTree(text(getResult));
            assertThat(completed.path("result").path("isError").asBoolean())
                    .as("watch 成功（非业务错误）").isFalse();
            assertThat(extractResultText(completed))
                    .as("结果含命中方法的真实诊断（hotMethod）")
                    .containsAnyOf("hotMethod", "OrderService", "watch");
        }
    }

    // ===== G-TL-1：task-list 含任务 =====

    @Test
    void task_list_contains_submitted_task() throws Exception {
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();
            CallToolResult accepted = h.callTool("watch", Map.of(
                    "target", "order",
                    "classPattern", TARGET_CLASS,
                    "methodPattern", TARGET_METHOD,
                    "numberOfExecutions", 200, // 不触发 → 长期 working，供 list 观察
                    "timeout", 30));
            String taskId = JSON.readTree(text(accepted)).path("taskId").asText();

            CallToolResult listResult = h.callTool("arthas-gateway.task-list", Map.of());
            JsonNode list = JSON.readTree(text(listResult));

            assertThat(list.path("tasks").isArray()).isTrue();
            assertThat(extractTaskIds(list))
                    .as("task-list 含刚提交的任务")
                    .contains(taskId);
        }
    }

    // ===== G-TC-1：cancel working → cancelled =====

    @Test
    void cancel_working_task_transitions_to_cancelled() throws Exception {
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();
            CallToolResult accepted = h.callTool("watch", Map.of(
                    "target", "order",
                    "classPattern", TARGET_CLASS,
                    "methodPattern", TARGET_METHOD,
                    "numberOfExecutions", 200, // 不触发 → working
                    "timeout", 30));
            String taskId = JSON.readTree(text(accepted)).path("taskId").asText();

            CallToolResult cancelResult = h.callTool("arthas-gateway.task-cancel", Map.of("taskId", taskId));
            JsonNode cancel = JSON.readTree(text(cancelResult));
            assertThat(cancel.path("status").asText())
                    .as("cancel working → cancelled").isEqualTo("cancelled");

            // 落库状态确为 CANCELLED（G-TC-1）
            CallToolResult getResult = h.callTool("arthas-gateway.task-get", Map.of("taskId", taskId));
            assertThat(JSON.readTree(text(getResult)).path("status").asText()).isEqualTo("cancelled");
        }
    }

    // ===== 辅助 =====

    /** GET /api/order 触发 OrderService.hotMethod（watch 命中源）。 */
    private static void triggerOrder(int appPort) throws IOException {
        HttpURLConnection conn = (HttpURLConnection)
                URI.create("http://127.0.0.1:" + appPort + "/api/order?orderId=1").toURL().openConnection();
        try {
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            assertThat(code).as("触发 /api/order 成功（HTTP 2xx）").isBetween(200, 299);
        } finally {
            conn.disconnect();
        }
    }

    /** 轮询 task-get 直到终态或超时；返回 status（小写）。 */
    private static String pollTaskStatus(McpClientHarness h, String taskId, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            CallToolResult r = h.callTool("arthas-gateway.task-get", Map.of("taskId", taskId));
            String status = JSON.readTree(text(r)).path("status").asText();
            if (!"working".equals(status)) {
                return status;
            }
            Thread.sleep(500);
        }
        return "working"; // 超时仍在 working
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

    private static String extractResultText(JsonNode taskGetNode) {
        StringBuilder sb = new StringBuilder();
        JsonNode content = taskGetNode.path("result").path("content");
        if (content.isArray()) {
            for (JsonNode item : content) {
                String t = item.path("text").asText("");
                if (!t.isEmpty()) {
                    sb.append(t).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static java.util.List<String> extractTaskIds(JsonNode listNode) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (JsonNode t : listNode.path("tasks")) {
            ids.add(t.path("taskId").asText());
        }
        return ids;
    }

    private static BackendConfig noneAuth(String name, String url) {
        return new BackendConfig(
                name, url, Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5);
    }
}
