package com.arthas.gateway.handler;

import com.arthas.gateway.tool.ExposedTool;
import com.arthas.gateway.tool.RoutingMode;
import com.arthas.gateway.tool.TaskSupport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T026 {@link DiagnosticRequest} 纯逻辑单测（surefire，无 arthas）。
 *
 * <p>覆盖 server-contract §5.1 的「解析」职责：toolName 命中（由 SDK 保证，本类不验）、
 * {@code target} 取出并剥离、{@code backendArgs} 原样保留（target 永不进后端参数，S-CALL-2）、
 * {@code routingMode} 判定、target 缺失/空/类型非法 → INVALID_PARAMS(-32602)（S-ERR-1）。
 *
 * <p>「target 不在册 → INVALID_PARAMS + data.available」（S-ERR-2）依赖注册表，属
 * {@link ToolsCallRouter} 职责，由路由测试覆盖，不在此。
 */
class DiagnosticRequestTest {

    /** 构造一个 routingMode 可配的 arthas 工具（inputSchema 占位，本类不验 schema）。 */
    private static ExposedTool tool(RoutingMode mode) {
        return new ExposedTool(
                "test-tool",
                "测试工具",
                Map.of(
                        "type", "object",
                        "properties", new LinkedHashMap<>(),
                        "required", List.of(),
                        "additionalProperties", false),
                TaskSupport.FORBIDDEN,
                mode);
    }

    // ===== 正常解析 =====

    @Test
    void parseExtractsTargetAndPreservesBackendArgsVerbatim() {
        ExposedTool tool = tool(RoutingMode.SYNC_DIRECT);
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("target", "order-service");
        arguments.put("classPattern", "com.example.OrderService");
        arguments.put("express", "@params[0]");

        DiagnosticRequest dr = DiagnosticRequest.parse(tool, new CallToolRequest("test-tool", arguments));

        assertThat(dr.target()).isEqualTo("order-service");
        assertThat(dr.routingMode()).isEqualTo(RoutingMode.SYNC_DIRECT);
        // backendArgs 原样保留除 target 外的全部参数
        assertThat(dr.backendArgs())
                .containsEntry("classPattern", "com.example.OrderService")
                .containsEntry("express", "@params[0]");
    }

    @Test
    void parseStripsTargetSoItNeverReachesBackend() {
        ExposedTool tool = tool(RoutingMode.SYNC_DIRECT);

        DiagnosticRequest dr = DiagnosticRequest.parse(
                tool, new CallToolRequest("test-tool", Map.of("target", "order", "classPattern", "X")));

        assertThat(dr.backendArgs())
                .as("target 永不进后端参数（剥离验证，S-CALL-2）")
                .doesNotContainKey("target");
    }

    @Test
    void parsePreservesRoutingModeForEachMode() {
        for (RoutingMode mode : RoutingMode.values()) {
            ExposedTool tool = tool(mode);
            DiagnosticRequest dr = DiagnosticRequest.parse(
                    tool, new CallToolRequest("test-tool", Map.of("target", "t")));
            assertThat(dr.routingMode())
                    .as("routingMode 取自 ExposedTool（%s）", mode)
                    .isEqualTo(mode);
        }
    }

    @Test
    void parseBackendArgsIsDefensiveCopy() {
        ExposedTool tool = tool(RoutingMode.SYNC_DIRECT);
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("target", "t");
        arguments.put("classPattern", "X");

        DiagnosticRequest dr = DiagnosticRequest.parse(tool, new CallToolRequest("test-tool", arguments));

        // 事后篡改原 Map，不得泄漏到 backendArgs（不可变快照语义）
        arguments.put("classPattern", "TAMPERED");
        arguments.put("extra", "leak");
        assertThat(dr.backendArgs())
                .as("backendArgs 为防御性拷贝，原 arguments 篡改不泄漏")
                .containsEntry("classPattern", "X")
                .doesNotContainKey("extra");
    }

    // ===== target 校验 → INVALID_PARAMS(-32602) =====

    @Test
    void parseNullArgumentsThrowsInvalidParams() {
        ExposedTool tool = tool(RoutingMode.SYNC_DIRECT);
        assertThatThrownBy(() -> DiagnosticRequest.parse(tool, new CallToolRequest("test-tool", null)))
                .isInstanceOf(McpError.class)
                .hasFieldOrPropertyWithValue("jsonRpcError.code", -32602);
    }

    @Test
    void parseMissingTargetThrowsInvalidParams() {
        ExposedTool tool = tool(RoutingMode.SYNC_DIRECT);
        assertThatThrownBy(() -> DiagnosticRequest.parse(tool, new CallToolRequest("test-tool", Map.of())))
                .isInstanceOf(McpError.class)
                .hasFieldOrPropertyWithValue("jsonRpcError.code", -32602);
    }

    @Test
    void parseBlankTargetThrowsInvalidParams() {
        ExposedTool tool = tool(RoutingMode.SYNC_DIRECT);
        assertThatThrownBy(() -> DiagnosticRequest.parse(
                tool, new CallToolRequest("test-tool", Map.of("target", "   "))))
                .isInstanceOf(McpError.class)
                .hasFieldOrPropertyWithValue("jsonRpcError.code", -32602);
    }

    @Test
    void parseNonStringTargetThrowsInvalidParams() {
        ExposedTool tool = tool(RoutingMode.SYNC_DIRECT);
        assertThatThrownBy(() -> DiagnosticRequest.parse(
                tool, new CallToolRequest("test-tool", Map.of("target", 123))))
                .isInstanceOf(McpError.class)
                .hasFieldOrPropertyWithValue("jsonRpcError.code", -32602);
    }

    @Test
    void invalidParamsErrorCarriesDescriptiveMessage() {
        ExposedTool tool = tool(RoutingMode.SYNC_DIRECT);
        assertThatThrownBy(() -> DiagnosticRequest.parse(tool, new CallToolRequest("test-tool", Map.of())))
                .isInstanceOf(McpError.class)
                .satisfies(e -> {
                    McpError err = (McpError) e;
                    assertThat(err.getJsonRpcError().message())
                            .as("INVALID_PARAMS 附可读消息（含 target 字段名）")
                            .contains("target");
                });
    }
}
