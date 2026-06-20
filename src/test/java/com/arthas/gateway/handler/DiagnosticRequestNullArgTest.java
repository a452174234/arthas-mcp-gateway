package com.arthas.gateway.handler;

import com.arthas.gateway.tool.ExposedTool;
import com.arthas.gateway.tool.RoutingMode;
import com.arthas.gateway.tool.TaskSupport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T014 {@link DiagnosticRequest} null 值参数容忍单测(002 整改 · P2-2,US4)。
 *
 * <p>验证后端可选参数在某些客户端(MCP SDK 反序列化)下会以 <b>null value</b> 显式出现(如 {@code {"target":"x","timeout":null}})。
 * 修复前 {@code Map.copyOf} 对 null value 抛 NPE 导致整次解析失败(误伤合法调用);修复后须<b>容忍 null value</b>,
 * 原样保留(剥离 target)、保持不可变 + 稳定序(LinkedHashMap)。
 */
class DiagnosticRequestNullArgTest {

    @Test
    void nullValueInArgumentsIsPreservedTargetStrippedImmutable() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("target", "order");
        arguments.put("timeout", null); // 可选参数显式 null
        arguments.put("express", "@params[0]");

        DiagnosticRequest dr = DiagnosticRequest.parse(syncTool(), new CallToolRequest("jvm", arguments));

        assertThat(dr.target()).isEqualTo("order");
        // target 已剥离,永不进后端参数(S-CALL-2)
        assertThat(dr.backendArgs()).doesNotContainKey("target");
        // null value 原样保留(容忍,不抛 NPE)
        assertThat(dr.backendArgs()).containsEntry("timeout", null);
        assertThat(dr.backendArgs()).containsEntry("express", "@params[0]");
        // 稳定序保留
        assertThat(dr.backendArgs().keySet()).containsExactly("timeout", "express");
        // 不可变(防御拷贝)
        assertThatThrownBy(() -> dr.backendArgs().put("extra", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static ExposedTool syncTool() {
        return new ExposedTool(
                "jvm",
                "JVM 诊断",
                Map.of(
                        "type", "object",
                        "properties", new LinkedHashMap<>(),
                        "required", List.of(),
                        "additionalProperties", false),
                TaskSupport.FORBIDDEN,
                RoutingMode.SYNC_DIRECT);
    }
}
