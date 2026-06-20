package com.arthas.gateway.contract.client;

import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.BackendEntry;
import com.arthas.gateway.backend.BackendEntryFactory;
import com.arthas.gateway.backend.BackendRegistry;
import com.arthas.gateway.backend.Protocol;
import com.arthas.gateway.backend.RegistryHolder;
import com.arthas.gateway.testfixtures.ArthasMcpBackend;
import com.arthas.gateway.testfixtures.McpClientHarness;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T043 故障隔离与限流契约测试（failsafe，真实故障条件 + 真实 arthas + 真实网关，零桩，backend-client-contract.md §5/§8）。
 *
 * <p>覆盖可真实驱动的契约点：
 * <ul>
 *   <li><b>C-CB-1</b>：{@code dead} 后端指向<b>关闭端口</b>（真实不可达，连接拒绝）→ 连续 3 次同步调用各计失败 →
 *       熔断 OPEN → 第 4 次 {@code guardCircuit} <b>立即</b>返 S-ERR-5（不发连接、不等 30s，{@code retryAfterMs&gt;0}）。</li>
 *   <li><b>C-CB-2</b>：{@code ognl 非法表达式} 连打 <b>5 次（&gt; 阈值 3）</b>→ 后端业务级响应（isError / 正常带错误文本
 *       / McpError，均属"后端已响应"）<b>不计</b>熔断——之后 {@code list-targets} 仍 {@code healthy=true}，后续 {@code jvm} 成功。
 *       判别证据：infra 失败 3 次即 OPEN（见 C-CB-1），业务响应连 5 次仍 CLOSED。</li>
 *   <li><b>C-LIMIT-1</b>：对同一 target 提交 5 个 async watch（numberOfExecutions=999 不触发，持槽 working）→
 *       第 6 个 {@code tryAcquireSlot} 失败 → 前置返 INVALID_PARAMS + {@code reason:concurrency_limit}（不越界打后端）。</li>
 *   <li><b>C-ISO-1</b>：order 提交一个 pending async watch（后台轮询占资源），同时 payment 同步 {@code jvm}
 *       <b>即时</b>返回（独立连接池/线程，order 的占用不拖慢 payment）。</li>
 * </ul>
 *
 * <p><b>显式延后（真实条件不可得，非桩替代）</b>：
 * <ul>
 *   <li><b>C-AUTH-1</b>（401+WWW-Authenticate）：需真实带认证 arthas 后端 + 错误 token 触发真实 401。
 *       当前 {@link ArthasMcpBackend} 仅 NONE 认证；认证夹具未构建。延后至认证后端夹具就绪。</li>
 *   <li><b>C-STATELESS-1</b>（无 Mcp-Session-Id 纯 JSON）：arthas MCP 为 Streamable <b>有状态</b>后端
 *       （每次 initialize 返 session-id），项目范围内无 STATELESS 后端，不适用、无法真实驱动。</li>
 * </ul>
 * 两者不计入熔断/隔离核心逻辑，由 {@code CircuitBreakerTest}（单测）+ {@code FailedTargetErrorIT}（SC-003/隔离）旁证。
 *
 * <p>槽位卫生：C-LIMIT-1/C-ISO-1 提交的 async watch 持 target 槽位，每个测试末尾 {@code task-cancel} 释放，
 * 避免跨测试槽位饱和。{@code @TestMethodOrder} 固定顺序。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class FaultIsolationContractIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TARGET_CLASS = "com.arthas.gateway.testfixtures.OrderService";
    private static final String TARGET_METHOD = "hotMethod";
    private static final String[] JVM_DIAGNOSTIC_MARKERS =
            {"jvmInfo", "RUNTIME", "resultCount", "MACHINE-NAME", "SPEC-NAME"};
    /** 关闭端口（discard/9 通常未监听）→ 真实连接拒绝，非桩。 */
    private static final String DEAD_URL = "http://127.0.0.1:9";

    @LocalServerPort
    private int port;

    @Autowired
    private RegistryHolder holder;

    @Autowired
    private BackendEntryFactory factory;

    private ArthasMcpBackend order;
    private ArthasMcpBackend payment;

    private String gatewayUrl() {
        return "http://localhost:" + port + "/mcp";
    }

    @BeforeAll
    void startBackendsAndRegister() throws Exception {
        order = ArthasMcpBackend.start("order");
        payment = ArthasMcpBackend.start("payment");
        Map<String, BackendEntry> byName = new LinkedHashMap<>();
        byName.put("order", factory.create(noneAuth("order", order.baseUrl())));
        byName.put("payment", factory.create(noneAuth("payment", payment.baseUrl())));
        byName.put("dead", factory.create(noneAuth("dead", DEAD_URL))); // 关闭端口，构造不连
        holder.getAndSet(new BackendRegistry(1L, byName));
    }

    @AfterAll
    void stopBackends() {
        if (order != null) {
            order.close();
        }
        if (payment != null) {
            payment.close();
        }
    }

    // ===== C-CB-1：连续 3 次连接失败 → 熔断 OPEN → 第 4 次立即返明确错误 =====

    @Test
    @Order(1)
    void c_cb_1_threeFailuresOpenCircuit_thenImmediateRejection() throws Exception {
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();
            // 前 3 次：真实连接拒绝 → 各 recordFailure（CLOSED 累计）
            for (int i = 1; i <= 3; i++) {
                assertThatThrownBy(() -> h.callTool("jvm", Map.of("target", "dead")))
                        .as("第 " + i + " 次连接失败 → S-ERR-5 backend_unreachable")
                        .isInstanceOf(McpError.class)
                        .satisfies(t -> assertThat(((McpError) t).getJsonRpcError().code()).isEqualTo(-32602));
            }
            // 第 4 次：熔断 OPEN → guardCircuit 立即拒（不发连接）
            long start = System.nanoTime();
            assertThatThrownBy(() -> h.callTool("jvm", Map.of("target", "dead")))
                    .as("OPEN 后立即拒").isInstanceOf(McpError.class)
                    .satisfies(t -> {
                        McpError err = (McpError) t;
                        Map<?, ?> data = (Map<?, ?>) err.getJsonRpcError().data();
                        assertThat(data.get("reason")).isEqualTo("backend_unreachable");
                        assertThat(((Number) data.get("retryAfterMs")).longValue())
                                .as("OPEN → retryAfterMs>0（退避未满）").isPositive();
                    });
            long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertThat(elapsedMs).as("立即拒（无 30s 等待）").isLessThan(2_000L);
        }
    }

    // ===== C-CB-2：后端业务级响应不计熔断（ognl 非法表达式连打 5 次 > 阈值 3，熔断仍 CLOSED） =====

    @Test
    @Order(2)
    void c_cb_2_businessErrorDoesNotTripBreaker() throws Exception {
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();
            // 触发后端业务级响应：ognl 非法表达式 → arthas 真实返（isError 或正常带错误文本，均"后端已响应"，非基础设施故障）。
            // 连打 5 次（> 熔断阈值 3）：若误计入失败，第 3 次即应 OPEN；此处验证 5 次业务响应后熔断仍 CLOSED。
            for (int i = 1; i <= 5; i++) {
                invokeBusinessOutcome(h, "ognl",
                        Map.of("target", "order", "expression", "@@@not valid ognl@@@"));
            }
            // 熔断未开：list-targets order healthy=true，且后续 jvm 成功
            assertThat(healthy(h, "order")).as("业务错误不计熔断 → 连 5 次后 healthy 仍 true").isTrue();
            CallToolResult jvm = h.callTool("jvm", Map.of("target", "order"));
            assertThat(jvm.isError()).as("后续 jvm 仍成功（熔断未开）").isNotEqualTo(Boolean.TRUE);
            assertThat(extractText(jvm)).containsAnyOf(JVM_DIAGNOSTIC_MARKERS);
        }
    }

    // ===== C-LIMIT-1：并发超 5 → 第 6 个前置限流 INVALID_PARAMS =====

    @Test
    @Order(3)
    void c_limit_1_sixthConcurrentTaskRejected() throws Exception {
        try (McpClientHarness h = new McpClientHarness(gatewayClientWithLongTimeout())) {
            h.initialize();
            List<String> taskIds = new ArrayList<>();
            // 5 个 watch（numberOfExecutions=999 不触发 → 持槽 working）
            for (int i = 0; i < 5; i++) {
                String taskId = submitWatch(h, "order");
                taskIds.add(taskId);
            }
            // 第 6 个 → tryAcquireSlot 失败 → INVALID_PARAMS + reason:concurrency_limit
            assertThatThrownBy(() -> submitWatch(h, "order"))
                    .as("第 6 个并发任务被前置限流").isInstanceOf(McpError.class)
                    .satisfies(t -> {
                        McpError err = (McpError) t;
                        assertThat(err.getJsonRpcError().code()).isEqualTo(-32602);
                        Map<?, ?> data = (Map<?, ?>) err.getJsonRpcError().data();
                        assertThat(data.get("reason")).isEqualTo("concurrency_limit");
                        assertThat(((Number) data.get("maxConcurrentTasks")).intValue()).isEqualTo(5);
                    });
            // 槽位卫生：取消 5 个持槽任务，释放 order 槽位供后续测试
            cancelAll(h, taskIds);
        }
    }

    // ===== C-ISO-1：target A 后端占用不拖慢 target B =====

    @Test
    @Order(4)
    void c_iso_1_targetAOccupancyDoesNotSlowTargetB() throws Exception {
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();
            // order 提交 pending async watch（后台轮询占资源）
            String orderTask = submitWatch(h, "order");

            // 同时 payment 同步 jvm → 即时返回（独立连接池/线程，不受 order 占用影响）
            long start = System.nanoTime();
            CallToolResult paymentJvm = h.callTool("jvm", Map.of("target", "payment"));
            long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

            assertThat(paymentJvm.isError()).as("payment jvm 成功").isNotEqualTo(Boolean.TRUE);
            assertThat(extractText(paymentJvm)).containsAnyOf(JVM_DIAGNOSTIC_MARKERS);
            assertThat(elapsedMs).as("payment 不被 order 占用拖慢（&lt;10s）").isLessThan(10_000L);

            cancelAll(h, List.of(orderTask));
        }
    }

    // ===== 辅助 =====

    /** 提交一个不触发的 async watch（numberOfExecutions=999），返 taskId。 */
    private static String submitWatch(McpClientHarness h, String target) throws java.io.IOException {
        CallToolResult acc = h.callTool("watch", Map.of(
                "target", target,
                "classPattern", TARGET_CLASS,
                "methodPattern", TARGET_METHOD,
                "numberOfExecutions", 999,
                "timeout", 30));
        JsonNode node = JSON.readTree(extractText(acc));
        String taskId = node.path("taskId").asText();
        assertThat(taskId).as("watch 立即返 taskId").matches("t-[0-9a-f]{6,}");
        return taskId;
    }

    private static void cancelAll(McpClientHarness h, List<String> taskIds) {
        for (String id : taskIds) {
            try {
                h.callTool("arthas-gateway.task-cancel", Map.of("taskId", id));
            } catch (RuntimeException ignored) {
                // 个别取消失败不影响断言（已终态等）
            }
        }
        try {
            Thread.sleep(500); // 让后台中断释放槽位
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 发起一次后端业务级调用，接受 isError=true/false 或 McpError（均为"后端已响应"，非基础设施故障）。 */
    private static void invokeBusinessOutcome(McpClientHarness h, String tool, Map<String, Object> args) {
        try {
            h.callTool(tool, args); // isError 与否均视为业务响应（成功或业务错误）
        } catch (McpError e) {
            // 后端 JSON-RPC error（如 INVALID_PARAMS）也是业务错误，不计熔断
        }
    }

    private static boolean healthy(McpClientHarness h, String target) throws java.io.IOException {
        CallToolResult r = h.callTool("arthas-gateway.list-targets", Map.of());
        for (JsonNode t : JSON.readTree(extractText(r)).path("targets")) {
            if (target.equals(t.path("name").asText())) {
                return t.path("healthy").asBoolean();
            }
        }
        return false;
    }

    private static String extractText(CallToolResult result) {
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

    /** C-LIMIT-1 用默认超时座（submit 不阻塞，OK）；此方法留作扩展锚点。 */
    private String gatewayClientWithLongTimeout() {
        return gatewayUrl();
    }

    private static BackendConfig noneAuth(String name, String url) {
        return new BackendConfig(
                name, url, Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5);
    }
}
