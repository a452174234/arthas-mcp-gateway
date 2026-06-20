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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T044 失效 target 错误时效测试（failsafe，真实 arthas + 真实网关，零桩，SC-003 / S-ERR-5）。
 *
 * <p>真实故障条件：起 2 个真实 arthas 后端（order + payment）并注册，<b>停掉 order 的 JVM</b>（真实不可达，
 * 非桩），对其 target 调用 → 30s 内返结构化错误（连接拒绝/断连即时或受 callTimeout 30s 兜底）；
 * 同时 payment 仍可正常诊断（故障隔离，C-ISO-1 / 宪法原则三「局部故障韧性」）。
 *
 * <p>断言：
 * <ul>
 *   <li><b>S-ERR-5 / SC-003</b>：停掉 order 后 {@code jvm target=order} → 30s 内 INVALID_PARAMS(-32602) +
 *       {@code data{target:"order", reason:"backend_unreachable", available:["order","payment"], retryAfterMs}}。</li>
 *   <li><b>故障隔离</b>：order 失效<b>不</b>影响 payment——{@code jvm target=payment} 仍返真实 JVM 诊断。</li>
 * </ul>
 *
 * <p>熔断 OPEN（连续 3 次→立即拒绝）由 {@code FaultIsolationContractIT}（C-CB-1）覆盖；本测聚焦
 * 单次失效 target 的 30s 明确错误 + 跨 target 隔离。breaker 在首次失败后仍 CLOSED（retryAfterMs=0）。
 *
 * <p>{@code @TestInstance(PER_CLASS)} 使 {@code @BeforeAll/@AfterAll} 为非静态实例方法，访问注入的 RegistryHolder。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class FailedTargetErrorIT {

    /** arthas jvm 真实进程级诊断标记（非桩硬证据）。 */
    private static final String[] JVM_DIAGNOSTIC_MARKERS =
            {"jvmInfo", "RUNTIME", "resultCount", "MACHINE-NAME", "SPEC-NAME"};

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
    void startTwoBackendsAndRegister() throws Exception {
        order = ArthasMcpBackend.start("order");
        payment = ArthasMcpBackend.start("payment");
        Map<String, BackendEntry> byName = new LinkedHashMap<>();
        byName.put("order", factory.create(noneAuth("order", order.baseUrl())));
        byName.put("payment", factory.create(noneAuth("payment", payment.baseUrl())));
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

    // ===== 基线：两 target 均健康可诊断（建立 session，验证夹具就绪） =====

    @Test
    @Order(1)
    void baseline_bothTargetsHealthy() {
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();
            assertThat(extractText(h.callTool("jvm", Map.of("target", "order"))))
                    .as("order 基线可诊断").containsAnyOf(JVM_DIAGNOSTIC_MARKERS);
            assertThat(extractText(h.callTool("jvm", Map.of("target", "payment"))))
                    .as("payment 基线可诊断").containsAnyOf(JVM_DIAGNOSTIC_MARKERS);
        }
    }

    // ===== S-ERR-5 / SC-003：停掉 order → 30s 内明确错误；payment 不受影响 =====

    @Test
    @Order(2)
    void stoppedTarget_returnsStructuredErrorWithin30s_andIsolationHolds() {
        // 真实故障条件：停掉 order 的 JVM（端口关闭 → 连接拒绝/断连，非桩）
        order.close();

        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();
            long start = System.nanoTime();

            assertThatThrownBy(() -> h.callTool("jvm", Map.of("target", "order")))
                    .as("失效 target 返结构化错误").isInstanceOf(McpError.class)
                    .satisfies(t -> {
                        McpError err = (McpError) t;
                        assertThat(err.getJsonRpcError().code())
                                .as("INVALID_PARAMS(-32602)").isEqualTo(-32602);
                        Map<?, ?> data = (Map<?, ?>) err.getJsonRpcError().data();
                        assertThat(data).as("data 非空").isNotNull();
                        assertThat(data.get("target")).isEqualTo("order");
                        assertThat(data.get("reason")).isEqualTo("backend_unreachable");
                        assertThat(data.containsKey("retryAfterMs"))
                                .as("data 含 retryAfterMs").isTrue();
                        assertThat(data.get("available").toString())
                                .as("available 列出全部在册 target").contains("order", "payment");
                    });

            long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertThat(elapsedMs).as("SC-003：30s 内返回明确错误").isLessThan(30_000L);

            // 故障隔离：order 失效不影响 payment（独立连接池 + 独立熔断，宪法原则三）
            CallToolResult paymentResult = h.callTool("jvm", Map.of("target", "payment"));
            assertThat(paymentResult.isError()).as("payment 仍成功").isNotEqualTo(Boolean.TRUE);
            assertThat(extractText(paymentResult))
                    .as("payment 真实诊断不受 order 故障影响").containsAnyOf(JVM_DIAGNOSTIC_MARKERS);
        }
    }

    // ===== 辅助 =====

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

    private static BackendConfig noneAuth(String name, String url) {
        return new BackendConfig(
                name, url, Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5);
    }
}
