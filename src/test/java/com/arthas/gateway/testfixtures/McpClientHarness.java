package com.arthas.gateway.testfixtures;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.Duration;
import java.util.Map;

/**
 * 官方 MCP SDK client 测试驱动座（宪法原则四「双侧契约」、CLAUDE.md「驱动分层」）。
 *
 * <p>合规 MCP 客户端：走标准 Streamable HTTP 协议（{@code HttpClientStreamableHttpTransport}），
 * 非裸 curl HTTP——承担<b>结果一致性 + 协议契约</b>的确定性断言。
 *
 * <p><b>两路复用</b>（T010）：同一夹具，仅 {@code baseUrl} 不同——
 * <ul>
 *   <li>连网关：{@code http://localhost:&lt;port&gt;/mcp}（断言服务端契约 S-* / 一致性）</li>
 *   <li>直连目标 arthas 后端：后端自身 {@code /mcp}（A/B 一致性比对的金标准一侧）</li>
 * </ul>
 *
 * <p>用法：{@code try (var h = new McpClientHarness(url)) { h.initialize(); ... h.listTools(); }}
 * （AutoCloseable，自动 {@code close()} 释放连接）。
 *
 * <p>工具<b>可用性</b>（逐工具冒烟，不做一致性比对）由真实 Claude Code 驱动，不走本夹具。
 */
public final class McpClientHarness implements AutoCloseable {

    /** 客户端单次请求默认超时（覆盖 initialize/listTools/callTool）。 */
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final McpSyncClient client;

    /**
     * 以默认 client 信息构造（{@code arthas-gateway-test-client / 0.1.0}）。
     *
     * @param baseUrl MCP 端点完整 URL（如 {@code http://localhost:8761/mcp}）
     */
    public McpClientHarness(String baseUrl) {
        this(baseUrl, "arthas-gateway-test-client", "0.1.0");
    }

    /**
     * 以自定义 client 信息构造。
     *
     * @param baseUrl        MCP 端点完整 URL
     * @param clientName     clientInfo.name
     * @param clientVersion  clientInfo.version
     */
    public McpClientHarness(String baseUrl, String clientName, String clientVersion) {
        McpClientTransport transport = HttpClientStreamableHttpTransport.builder(baseUrl).build();
        this.client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation(clientName, clientVersion))
                .requestTimeout(DEFAULT_REQUEST_TIMEOUT)
                .build();
    }

    /** 暴露底层同步客户端（需要调用 SDK 未封装方法时使用）。 */
    public McpSyncClient client() {
        return client;
    }

    /**
     * 执行 initialize 握手（含 {@code notifications/initialized}）。
     *
     * @return initialize 结果（协议版本 / serverInfo / capabilities）
     */
    public McpSchema.InitializeResult initialize() {
        return client.initialize();
    }

    /** 调 {@code tools/list}，返回工具快照（{@code nextCursor} 可能为 null）。 */
    public McpSchema.ListToolsResult listTools() {
        return client.listTools();
    }

    /**
     * 调 {@code tools/call}。
     *
     * @param name      工具名
     * @param arguments 入参（含/不含 target 取决于测试场景）
     */
    public McpSchema.CallToolResult callTool(String name, Map<String, Object> arguments) {
        return client.callTool(new McpSchema.CallToolRequest(name, arguments));
    }

    /** 发 {@code ping}，连通性探活（不依赖 initialize 协商内容）。 */
    public Object ping() {
        return client.ping();
    }

    @Override
    public void close() {
        client.close();
    }
}
