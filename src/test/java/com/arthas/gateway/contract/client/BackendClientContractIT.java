package com.arthas.gateway.contract.client;

import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendClient;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.HttpBackendClient;
import com.arthas.gateway.backend.Protocol;
import com.arthas.gateway.testfixtures.ArthasMcpBackend;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T018 客户端契约测试（网关→arthas 后端，C-INIT/C-CALL/C-RESULT）。
 *
 * <p>用真实 {@link ArthasMcpBackend}（T009 夹具）驱动 {@link HttpBackendClient}，验证网关作为 MCP 客户端
 * 连真实 arthas 的行为承诺（{@code backend-client-contract.md} §3/§4）：<b>零桩</b>。
 *
 * <p><b>命名 *IT（failsafe）</b>：本测试连真实 arthas（attach + 真实诊断），按 pom surefire/failsafe
 * 分离约定（surefire=纯逻辑/状态机单测，非真实后端；failsafe=*IT 真实 arthas）走集成测试阶段。
 * tasks.md T018 原命名 *Test，此处调整为 *IT 以对齐工程约定。
 *
 * <p>断言（间接验证，证据驱动）：
 * <ul>
 *   <li>C-INIT：{@code initialize} 成功（Accept/Authorization 头正确——否则 arthas 拒绝握手）、
 *       {@code isInitialized} 翻转、二次 {@code initialize} 幂等不抛。</li>
 *   <li>C-CALL：{@code callTool("jvm", {})} 返回真实 JVM 诊断（转发链路通）。</li>
 *   <li>C-RESULT：结果 {@code content} 非空、{@code isError!=true}、含真实进程级数据（原样，非桩）。</li>
 * </ul>
 * target 剥离（C-CALL-1）属 {@code DiagnosticRequest}/路由层职责，由服务端路由契约测试覆盖，不在此。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BackendClientContractIT {

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void initializeHandshakeAndCallToolAgainstRealArthas() throws Exception {
        try (ArthasMcpBackend backend = ArthasMcpBackend.start("order")) {
            BackendConfig config = noneAuth("order", backend.baseUrl());
            try (BackendClient client = new HttpBackendClient(config)) {
                // C-INIT：握手前 isInitialized=false
                assertThat(client.isInitialized()).isFalse();
                client.initialize();
                assertThat(client.isInitialized()).isTrue();
                // 幂等：二次 initialize 不抛（C-INIT 已握手则空操作）
                client.initialize();

                // C-CALL / C-RESULT：同步 tools/call 转发，真实诊断原样返回
                McpSchema.CallToolResult result = client.callTool("jvm", Map.of());
                assertThat(result).isNotNull();
                assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
                assertThat(result.content()).isNotNull().isNotEmpty();

                String text = extractText(result);
                assertThat(text).as("jvm 工具应返回真实 JVM 诊断").isNotBlank();
                // arthas jvm 返回 JSON 含 jvmInfo/RUNTIME 等真实进程级结构（非桩硬证据）
                assertThat(text).containsAnyOf("jvmInfo", "RUNTIME", "resultCount", "MACHINE-NAME", "SPEC-NAME");
            }
        }
    }

    /** NONE 认证后端配置（测试夹具：url 指向 ArthasMcpBackend 动态端口）。 */
    private static BackendConfig noneAuth(String name, String url) {
        return new BackendConfig(
                name, url, Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5);
    }

    private static String extractText(McpSchema.CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent tc && tc.text() != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(tc.text());
            }
        }
        return sb.toString();
    }
}
