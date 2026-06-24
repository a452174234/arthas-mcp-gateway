package com.arthas.gateway.orchestration;

import com.arthas.gateway.testfixtures.McpClientHarness;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T028 SC-001 端到端冒烟（波次 C，确定性补证）：对<b>运行中的 uber-jar 网关</b>（非 @SpringBootTest 嵌入式）
 * 经<b>官方 MCP SDK client</b> 驱动「ensure → watch → 轮询 task-get → 真实诊断」全链路。
 *
 * <p>背景：T028 的「可用性冒烟」主路径走真实 Claude Code（claude -p）。本类为<b>模型无关的确定性补证</b>——
 * 在 claude -p 受环境约束（配额 / 模型配置）时，仍能用 SDK client 确证 SC-001 闭环：动态纳管 target 的
 * watch 能捕获<b>该 pod JVM</b> 的真实诊断。与 {@link K8sEnsureContractIT}（嵌入式网关）互补——本类针对
 * <b>独立运行的真实 uber-jar 进程</b>（验证 spring-boot 重打包后的运行时完整性，含 fabric8 commons-compress 依赖）。
 *
 * <p><b>启用门禁</b>：外置网关 {@code http://127.0.0.1:8761/mcp} 健康端点不可达 → {@link org.junit.jupiter.api.Assumptions#assumeTrue}
 * 跳过（CI 无常驻网关）。本类<b>非</b> @SpringBootTest，不启动嵌入式上下文。
 */
class K8sExternalGatewaySmokeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GATEWAY_URL = "http://127.0.0.1:8761/mcp";
    private static final String HEALTH_URL = "http://127.0.0.1:8761/actuator/health";
    private static final String SERVER = "debian";
    private static final String POD = "demo-business";
    private static final String TARGET = "debian-demo-business";
    private static final String TARGET_CLASS = "com.arthas.gateway.testfixtures.OrderService";
    private static final String TARGET_METHOD = "hotMethod";

    @Test
    void sc001_ensureWatchPollRealDiagnosisAgainstRunningGateway() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(isGatewayUp(),
                "跳过：外置网关 " + HEALTH_URL + " 不可达（需常驻 uber-jar 网关进程）");

        try (McpClientHarness h = new McpClientHarness(GATEWAY_URL)) {
            h.initialize();

            // 1. ensure：已纳管则 reused（零副作用），否则供给 ready
            CallToolResult ensure = h.callTool("k8s.ensure-arthas-mcp",
                    Map.of("server", SERVER, "pod", POD));
            String ensureText = text(ensure);
            assertThat(ensureText).as("ensure 返回 ready/reused").containsAnyOf("ready", "reused");
            assertThat(ensureText).as("ensure target 确定性派生").contains(TARGET);

            // 2. watch 该 target → 真实诊断（hotMethod 由 hotLoop 自驱动命中）
            CallToolResult watch = h.callTool("watch", Map.of(
                    "target", TARGET,
                    "classPattern", TARGET_CLASS,
                    "methodPattern", TARGET_METHOD,
                    "numberOfExecutions", 1,
                    "timeout", 30));
            String taskId = JSON.readTree(text(watch)).path("taskId").asText();
            assertThat(taskId).as("watch 立即返 taskId").matches("t-[0-9a-f]{6,}");

            // 3. 轮询 task-get 直到终态（completed）→ 提取真实诊断
            String status = "working";
            JsonNode done = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
            while (System.nanoTime() < deadline) {
                CallToolResult tg = h.callTool("arthas-gateway.task-get", Map.of("taskId", taskId));
                JsonNode node = JSON.readTree(text(tg));
                status = node.path("status").asText();
                if (!"working".equals(status)) {
                    done = node;
                    break;
                }
                Thread.sleep(2000);
            }
            assertThat(status).as("watch 完成（真实 arthas 诊断）").isEqualTo("completed");
            assertThat(done.path("result").path("isError").asBoolean())
                    .as("watch 成功（非业务错误）").isFalse();
            assertThat(extractResultText(done))
                    .as("结果含命中该 pod JVM 方法的真实诊断")
                    .containsAnyOf("hotMethod", "OrderService", "watch");
        }
    }

    private static boolean isGatewayUp() {
        try (HttpClient c = HttpClient.newHttpClient()) {
            HttpResponse<Void> r = c.send(HttpRequest.newBuilder(URI.create(HEALTH_URL))
                            .timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return r.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static String text(CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        for (Content c : result.content()) {
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
}
