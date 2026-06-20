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
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T020 结果一致性测试（SC-005 A/B，网关 vs 直连 arthas，零桩）。
 *
 * <p>同一真实 arthas 后端，两侧各调一次 {@code jvm}：
 * <ul>
 *   <li><b>A（经网关）</b>：SDK client 连网关 {@code /mcp}，{@code jvm target=order}（网关剥离 target 后转发）。</li>
 *   <li><b>B（直连）</b>：SDK client 直连 arthas 后端根 URL，{@code jvm} 无参（金标准一侧）。</li>
 * </ul>
 * 断言两侧<b>结构一致</b>：{@code isError} 相同、都返回真实 JVM 诊断（含进程级结构标记）。
 *
 * <p><b>不要求文本逐字相同</b>：jvm 诊断含动态值（运行时时间戳、线程数等），每次调用不同；一致性
 * 指「网关透传不破坏结果结构」（宪法原则二：透明无损聚合），而非快照相等。
 *
 * <p>动态注册表注入同 {@code ToolsCallRoutingContractIT}（@SpringBootTest + getAndSet 覆盖示例占位）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResultConsistencyIT {

    private static final String[] JVM_DIAGNOSTIC_MARKERS =
            {"jvmInfo", "RUNTIME", "resultCount", "MACHINE-NAME", "SPEC-NAME"};

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

    @Test
    void sc_005_gatewayResultConsistentWithDirectArthas() {
        // A：经网关（target 剥离后转发到同一后端）
        CallToolResult viaGateway;
        try (McpClientHarness gw = new McpClientHarness(gatewayUrl())) {
            gw.initialize();
            viaGateway = gw.callTool("jvm", Map.of("target", "order"));
        }

        // B：直连 arthas 后端（金标准）
        CallToolResult direct;
        try (McpClientHarness dir = new McpClientHarness(backend.baseUrl())) {
            dir.initialize();
            direct = dir.callTool("jvm", Map.of());
        }

        // 一致性：isError 相同（网关不吞/不改写错误标记）
        assertThat(viaGateway.isError())
                .as("经网关与直连的 isError 一致（透传不改写）")
                .isEqualTo(direct.isError());

        // 两侧都返回真实 JVM 诊断（结构标记存在 → 真实进程级数据，非桩）
        assertThat(extractText(viaGateway))
                .as("经网关的 jvm 诊断含真实进程级结构")
                .containsAnyOf(JVM_DIAGNOSTIC_MARKERS);
        assertThat(extractText(direct))
                .as("直连的 jvm 诊断含真实进程级结构")
                .containsAnyOf(JVM_DIAGNOSTIC_MARKERS);
    }

    private static BackendConfig noneAuth(String name, String url) {
        return new BackendConfig(
                name, url, Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5);
    }

    private static String extractText(CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        for (Content content : result.content()) {
            if (content instanceof TextContent tc && tc.text() != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(tc.text());
            }
        }
        return sb.toString();
    }
}
