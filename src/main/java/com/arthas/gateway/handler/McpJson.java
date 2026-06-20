package com.arthas.gateway.handler;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * MCP JSON 单例(002 整改 · P3-3/FR-013,US5)。
 *
 * <p>全局共享 {@link ObjectMapper},替代散在 {@code ToolsCallRouter}/{@code GatewayToolHandlers}/
 * {@code StaticToolRegistry} 的 {@code new ObjectMapper()} 多实例。动机:
 * <ul>
 *   <li>{@code ObjectMapper} 线程安全且构造较重(序列化器缓存/模块发现),单例避免重复构造开销;</li>
 *   <li>序列化配置统一——未来加模块/特性,一处生效,三处行为一致;</li>
 *   <li>消除「同作用域多 mapper」带来的配置漂移风险。</li>
 * </ul>
 *
 * <p>{@link #json(Object)} 封装「对象 → {@code CallToolResult}(单 {@link TextContent} JSON、
 * {@code isError=false})」常见模式——网关自有工具响应(list-targets/task-*)与异步接受响应、错误体整形共用。
 */
public final class McpJson {

    /** 全局共享 {@link ObjectMapper}(线程安全,只读复用)。 */
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private McpJson() {
    }

    /**
     * 对象序列化为 {@code CallToolResult}:单 {@link TextContent}(JSON 文本),{@code isError=false}。
     * 供网关自有工具响应与异步接受响应整形(002 整改 P3-3/FR-013)。
     *
     * @param node 任意可序列化对象(Map/POJO)
     * @return 含 JSON 文本的 {@code CallToolResult}(非错误)
     */
    public static CallToolResult json(Object node) {
        return new CallToolResult(List.of(new TextContent(MAPPER.writeValueAsString(node))), false, null, null);
    }
}
