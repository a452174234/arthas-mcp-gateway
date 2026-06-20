package com.arthas.gateway.integration;

import com.arthas.gateway.testfixtures.ArthasMcpBackend;
import com.arthas.gateway.testfixtures.McpClientHarness;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T009 真实 arthas MCP 后端夹具首测（宪法原则七 TDD、§9 互操作性裁决）。
 *
 * <p>验证 {@link ArthasMcpBackend} 能经 {@code java -jar tools/arthas-boot-4.3.0.jar} attach 到
 * 真实 {@code DemoBusinessApp} 子进程，并暴露可被官方 SDK client（{@link McpClientHarness}）
 * {@code initialize}+{@code listTools}+{@code callTool} 的 MCP 端点。<b>零桩</b>：arthas、业务服务、
 * 诊断数据全真实（驱动分层：夹具协议契约用官方 SDK client，非 curl 裸打）。
 *
 * <p>本测试<b>裁决设计文档 §9 残留风险</b>：
 * <ul>
 *   <li>arthas 4.3.0（SDK 0.17.0）↔ 网关 SDK 2.0.0 协议互操作性（initialize 握手 + 2025-11-25 回显）；</li>
 *   <li>Windows 原生 Java Attach API（reference IT 因 bash/as.sh 跳 Windows，arthas-boot.jar 移除此障碍）；</li>
 *   <li>MCP 端点路径：根 URL {@code http://127.0.0.1:<port>}（reference 实证）vs {@code /mcp}（契约假设）。</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ArthasMcpBackendIT {

    /** 核心诊断工具名（跨平台稳定可用；不强制 31 全集，因 arthas 版本/平台差异可能微调工具集）。 */
    private static final Set<String> CORE_TOOLS = Set.of(
            "jvm", "thread", "memory", "dashboard", "watch", "trace", "stack", "tt", "sc", "jad");

    /**
     * attach 成功 + 端点暴露：initialize 协商 2025-11-25 + listTools 返回核心 arthas 工具。
     *
     * <p>这是 §9 互操作性的硬裁决点——若 arthas 4.3.0 端点路径或握手与 SDK 2.0.0 不兼容，本测试失败。
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void attachesArthasAndExposesMcpEndpoint() throws Exception {
        try (ArthasMcpBackend backend = ArthasMcpBackend.start("order")) {
            assertThat(backend.name()).isEqualTo("order");
            assertThat(backend.baseUrl()).startsWith("http://127.0.0.1:");
            assertThat(backend.mcpPort()).isPositive();
            assertThat(backend.appPort()).isPositive();

            try (McpClientHarness harness = new McpClientHarness(backend.baseUrl())) {
                McpSchema.InitializeResult init = harness.initialize();
                assertThat(init.protocolVersion()).isEqualTo("2025-11-25");

                Set<String> toolNames = harness.listTools().tools().stream()
                        .map(McpSchema.Tool::name)
                        .collect(Collectors.toSet());
                assertThat(toolNames).containsAll(CORE_TOOLS);
            }
        }
    }

    /**
     * 真实诊断非桩：{@code jvm} 工具返回真实目标 JVM 进程级诊断（证明 attach 到真实进程、数据非桩）。
     *
     * <p>{@code DemoBusinessApp} 后台守护线程持续触发 {@code hotMethod}，arthas 注入其 JVM 后，
     * {@code jvm} 返回该进程的运行时/线程/类加载等真实数据。
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void realJvmDiagnosticReturnsLiveProcessData() throws Exception {
        try (ArthasMcpBackend backend = ArthasMcpBackend.start("diag")) {
            try (McpClientHarness harness = new McpClientHarness(backend.baseUrl())) {
                harness.initialize();
                McpSchema.CallToolResult result = harness.callTool("jvm", Map.of());

                assertThat(result).isNotNull();
                assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
                assertThat(result.content()).isNotNull().isNotEmpty();

                String text = extractText(result);
                assertThat(text).as("jvm 工具应返回真实 JVM 诊断文本").isNotBlank();
                // arthas jvm 返回 JSON，含 jvmInfo/RUNTIME/resultCount 等真实 JVM 诊断结构
                // （实证：MACHINE-NAME=真实机器名、RUNTIME/SPEC-NAME 等进程级数据——非桩硬证据）。
                // 字段为全大写（arthas 输出约定），故关键词对齐实测。
                assertThat(text).containsAnyOf("jvmInfo", "RUNTIME", "resultCount", "MACHINE-NAME", "SPEC-NAME");
            }
        }
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
