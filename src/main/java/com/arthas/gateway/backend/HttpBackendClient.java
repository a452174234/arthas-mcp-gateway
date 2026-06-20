package com.arthas.gateway.backend;

import com.arthas.gateway.auth.BackendAuthCustomizer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Implementation;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * 真实 HTTP Streamable 后端客户端（T024 实装，波次 C）。
 *
 * <p>官方 SDK {@link HttpClientStreamableHttpTransport} + {@link McpSyncClient} 封装，每后端独立一个实例
 * （独立连接池 + 独立 {@code McpClient} 会话 + 独立 SSE 解析，宪法原则三「局部故障韧性」：互不阻塞）。
 *
 * <p>认证头经 {@link BackendAuthCustomizer}（transport builder 的 {@code httpRequestCustomizer}）注入
 * （NONE 不发头、BEARER/BASIC 注入 {@code Authorization}）。连接/请求超时取自 {@link BackendConfig}。
 *
 * <p><b>端点为根 URL</b>（{@link BackendConfig#url()}，§9 实证：arthas 4.3.0 MCP 端点无 {@code /mcp} 后缀，
 * T009 首测 GREEN 裁决；{@code backend-client-contract.md §1} 的 {@code /mcp} 假设以实测为准）。
 *
 * <h3>故障语义（契约 §5；调用方 {@code ToolsCallRouter} 计入熔断）</h3>
 * <ul>
 *   <li>基础设施故障（连接拒绝/超时/initialize 失败/读超时/SSE 中断）：SDK 抛运行时异常 →
 *       调用方 {@code breaker.recordFailure()}（C-CB-1，T043 真实 GREEN）。</li>
 *   <li>后端业务错误（{@code isError=true}/INVALID_PARAMS）：SDK 返回 {@code CallToolResult}
 *       （{@code isError=true}）或抛 {@code McpError} → 调用方 {@code breaker.recordSuccess()}，
 *       <b>不计入</b>熔断（C-CB-2，T043 真实 GREEN）。</li>
 *   <li>401 精细化（特定异常 + 标记 target 不可用 + 不透传 HTTP，C-AUTH-1）：<b>随认证后端夹具一并落地</b>
 *       ——当前 NONE 认证夹具无 401 路径，TDD 硬约束（零桩）下不得用桩提前实装。</li>
 * </ul>
 *
 * <p>{@link #callTool} 直接转发 SDK {@code McpSyncClient#callTool}——结果（content/isError/_meta）
 * <b>原样</b>返回，不改写（FR-004、契约 C-RESULT-1）。
 */
public final class HttpBackendClient implements BackendClient {

    private final BackendConfig config;
    private final McpSyncClient client;
    private volatile boolean initialized;

    /**
     * 按后端配置构造（独立连接池 + 会话）。
     *
     * @param config 后端声明（url=arthas MCP 根 URL、auth=认证模式、超时）
     */
    public HttpBackendClient(BackendConfig config) {
        this.config = Objects.requireNonNull(config, "config 不可为空");
        McpClientTransport transport = HttpClientStreamableHttpTransport.builder(config.url())
                .connectTimeout(Duration.ofMillis(config.connectTimeoutMs()))
                .httpRequestCustomizer(BackendAuthCustomizer.forBackend(config))
                .build();
        this.client = McpClient.sync(transport)
                .clientInfo(new Implementation("arthas-mcp-gateway", "0.1.0"))
                .requestTimeout(Duration.ofMillis(config.callTimeoutMs()))
                .build();
    }

    @Override
    public void initialize() {
        if (!initialized) {
            client.initialize();
            initialized = true;
        }
    }

    @Override
    public McpSchema.CallToolResult callTool(String name, Map<String, Object> arguments) {
        return client.callTool(new CallToolRequest(name, arguments));
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    /** 后端配置声明（诊断/可观测/list-targets 用）。 */
    public BackendConfig config() {
        return config;
    }

    @Override
    public void close() {
        client.close();
    }
}
