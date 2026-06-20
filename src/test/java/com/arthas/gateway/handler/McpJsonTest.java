package com.arthas.gateway.handler;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T025 {@link McpJson} 单例/封装单测(002 整改 · P3-3/FR-013,US5)。
 *
 * <p>验证全局共享 {@code ObjectMapper} 单例 + {@code json(Object)} 把对象序列化为
 * {@code CallToolResult}(单 {@link TextContent} JSON、{@code isError=false})——
 * 供 list-targets、task 系列自有工具、asyncAcceptedResponse 与错误体整形共用,
 * 替代散在三处的 {@code new ObjectMapper()}。
 */
class McpJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void mapperSingletonIsAvailable() {
        // 全局单例非空(供 StaticToolRegistry.readValue 等复用)
        assertThat(McpJson.MAPPER).isNotNull();
        assertThat(McpJson.MAPPER).isSameAs(McpJson.MAPPER); // 静态 final,恒等
    }

    @Test
    void jsonSerializesObjectToTextContent() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", "t-1");
        body.put("status", "working");

        CallToolResult result = McpJson.json(body);

        assertThat(result.isError()).as("非错误响应").isFalse();
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0)).isInstanceOf(TextContent.class);

        TextContent tc = (TextContent) result.content().get(0);
        JsonNode node = MAPPER.readTree(tc.text());
        assertThat(node.path("taskId").asText()).isEqualTo("t-1");
        assertThat(node.path("status").asText()).isEqualTo("working");
    }

    @Test
    void jsonPreservesNestedStructure() throws Exception {
        // 嵌套 _meta(异步接受响应用)正确序列化——证明与修复前 writeValueAsString 行为一致
        Map<String, Object> body = Map.of("taskId", "t-2", "_meta",
                Map.of("toolName", "watch", "target", "order"));

        JsonNode node = MAPPER.readTree(((TextContent) McpJson.json(body).content().get(0)).text());
        assertThat(node.path("_meta").path("toolName").asText()).isEqualTo("watch");
        assertThat(node.path("_meta").path("target").asText()).isEqualTo("order");
    }
}
