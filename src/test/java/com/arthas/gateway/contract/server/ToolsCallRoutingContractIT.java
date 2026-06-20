package com.arthas.gateway.contract.server;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T019 工具调用路由契约测试（服务端，S-CALL / S-ERR-2，真实 arthas + 真实网关，零桩）。
 *
 * <p>用真实 {@link ArthasMcpBackend}（T009 夹具）拉起 arthas MCP 后端，经 {@link RegistryHolder#getAndSet}
 * 将真实后端注入网关装配的注册表（覆盖 {@code backends.yaml} 的示例占位），再由官方 SDK client
 * （{@link McpClientHarness}）连<b>网关</b> {@code /mcp} 端点驱动 {@code tools/call}——验证整条路由链路：
 * MCP 协议 → 网关 handler → {@code ToolsCallRouter} → {@code BackendClient} → 真实 arthas。
 *
 * <p><b>动态注册表注入</b>：{@code @SpringBootTest} context 启动时装配 RegistryHolder 指向 {@code backends.yaml}
 * 示例 url（虚构，{@code HttpBackendClient} 构造不连）；{@code @BeforeAll} 启动真实后端后 {@code getAndSet}
 * 覆盖为指向真实 {@code baseUrl} 的 {@link BackendEntry}。{@code HttpBackendClient.initialize} 首次路由时才握手。
 *
 * <p>断言：
 * <ul>
 *   <li>S-CALL：{@code jvm target=order} 经网关路由到真实 arthas，返回真实 JVM 诊断（透传无损）。</li>
 *   <li>S-CALL-2（间接）：jvm 成功证明剥离工作——网关注入的 {@code target} 未透传给后端
 *       （arthas jvm schema 无 target；若未剥离，arthas 因 additionalProperties:false 拒绝）。target 剥离的
 *       强保证在 {@code DiagnosticRequestTest}（单元）。</li>
 *   <li>S-ERR-2：{@code target=ghost}（不在册）→ INVALID_PARAMS(-32602) + {@code data.available} 含真实在册 target。</li>
 * </ul>
 *
 * <p>{@code @TestInstance(PER_CLASS)} 使 {@code @BeforeAll/@AfterAll} 可为非静态实例方法，从而访问
 * {@code @Autowired} 注入的 RegistryHolder（Spring 在 beforeAll 前完成字段注入）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ToolsCallRoutingContractIT {

    /** arthas jvm 返回 JSON 含的真实进程级结构标记（与 BackendClientContractIT 一致，非桩硬证据）。 */
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
        // 用真实后端覆盖装配的示例注册表（指向 backends.yaml 虚构 url）
        BackendEntry entry = factory.create(noneAuth("order", backend.baseUrl()));
        holder.getAndSet(new BackendRegistry(1L, Map.of("order", entry)));
    }

    @AfterAll
    void stopBackend() {
        if (backend != null) {
            backend.close();
        }
    }

    // ===== S-CALL / S-CALL-2 =====

    @Test
    void s_call_jvmRealDiagnosticRoutesThroughGateway() {
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();
            CallToolResult result = h.callTool("jvm", Map.of("target", "order"));

            assertThat(result).as("jvm 经网关路由返回非空结果").isNotNull();
            assertThat(result.isError())
                    .as("jvm 诊断成功（isError 非 true）")
                    .isNotEqualTo(Boolean.TRUE);
            assertThat(result.content()).as("content 非空").isNotEmpty();
            assertThat(extractText(result))
                    .as("网关透传真实 JVM 诊断（含进程级结构标记）")
                    .containsAnyOf(JVM_DIAGNOSTIC_MARKERS);
        }
    }

    // ===== S-ERR-2：target 不在册（端到端） =====

    @Test
    void s_err_2_unknownTargetReturnsInvalidParamsWithAvailable() {
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();
            assertThatThrownBy(() -> h.callTool("jvm", Map.of("target", "ghost")))
                    .as("不在册的 target 经网关返 INVALID_PARAMS")
                    .isInstanceOf(McpError.class)
                    .satisfies(t -> {
                        McpError err = (McpError) t;
                        assertThat(err.getJsonRpcError().code()).isEqualTo(-32602);
                        Map<?, ?> data = (Map<?, ?>) err.getJsonRpcError().data();
                        assertThat(data).as("data 含 available").isNotNull();
                        assertThat(data.containsKey("available"))
                                .as("data.available 字段存在")
                                .isTrue();
                        assertThat(data.get("available").toString())
                                .as("available 含真实在册 target")
                                .contains("order");
                    });
        }
    }

    // ===== S-ERR-3：未知工具（端到端，SDK 协议层） =====

    @Test
    void s_err_3_unknownToolReturnsProtocolError() {
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();
            // 未知工具名：SDK 注册表无此工具 → 协议层 error（不到网关 handler）
            assertThatThrownBy(() -> h.callTool("nonexistent-tool-xyz", Map.of("target", "order")))
                    .as("未知工具名 → 协议层错误（不到网关 handler）")
                    .isInstanceOf(McpError.class)
                    .satisfies(t -> {
                        int code = ((McpError) t).getJsonRpcError().code();
                        assertThat(code)
                                .as("未知工具返 INVALID_PARAMS(-32602) 或 METHOD_NOT_FOUND(-32601)")
                                .isIn(-32602, -32601);
                    });
        }
    }

    /** NONE 认证后端配置（指向真实 ArthasMcpBackend 动态端口）。 */
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
