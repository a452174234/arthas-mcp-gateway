package com.arthas.gateway.backend;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.Map;

/**
 * 单后端 MCP 客户端契约（data-model.md §3 {@code client}；行为承诺见
 * {@code contracts/backend-client-contract.md}）。
 *
 * <p>网关作为 <b>MCP 客户端</b>，每后端独立一个 {@code BackendClient}（独立连接池 + 独立
 * {@code McpClient} 会话 + 独立 SSE 解析，宪法原则三「局部故障韧性」：互不阻塞）。
 *
 * <p><b>真实实装属波次 C（T024）</b>：官方 SDK {@code HttpClientStreamableHttpTransport} 封装。
 * 本接口在波次 A 提前定义，使 {@link BackendEntry}（T025）与 {@link BackendRegistry}（T022）
 * 可在无真实 arthas 的纯逻辑单测中构建与编排（依赖倒置：定义抽象、延后实装）。
 *
 * <h3>方法语义（契约 §3/§4/§5）</h3>
 * <ul>
 *   <li>{@link #initialize()}：MCP {@code initialize} 握手（C-INIT-1/2/3）——发送 {@code protocolVersion}
 *       2025-11-25、{@code Accept} 含 json+SSE、启用认证带 {@code Authorization}；后端响应
 *       {@code Mcp-Session-Id} 保存后续回带。{@code STATELESS} 后端无 session。幂等：已握手则空操作。</li>
 *   <li>{@link #callTool(String, Map)}：同步 {@code tools/call} 转发（C-CALL-1/2/3、C-RESULT-1/2）。
 *       入参为<b>剥离 {@code target} 后</b>的 {@code backendArgs}；返回后端 {@code CallToolResult}
 *       <b>原样</b>（含 {@code isError=true}，不吞为成功）。dashboard SSE 多帧由实装聚合为一次结果。</li>
 *   <li>{@link #isInitialized()}：是否已握手（list-targets {@code healthy} 判定用）。</li>
 *   <li>{@link #close()}：优雅下线（§7）——Streamable 发 {@code DELETE /mcp} 关 session，或关连接池。</li>
 * </ul>
 *
 * <h3>故障语义（调用方 {@code ToolsCallRouter} 据异常计入熔断，T048 接线 / T043·T044 真实验证）</h3>
 * <ul>
 *   <li>基础设施故障（连接拒绝/超时、initialize 失败、读超时、SSE 中断）：<b>抛运行时异常</b> → 调用方
 *       {@code breaker.recordFailure()}（C-CB-1，T043 C-CB-1 真实 GREEN）。</li>
 *   <li>后端业务错误（{@code isError=true}/INVALID_PARAMS/JSON-RPC error）：<b>正常返回</b> CallToolResult 或
 *       SDK 抛 {@code McpError} → 调用方 {@code breaker.recordSuccess()}，<b>不计入</b>熔断（C-CB-2，
 *       T043 C-CB-2 真实 GREEN；宪法原则五）。</li>
 *   <li>401 + {@code WWW-Authenticate}（C-AUTH-1）：<b>随认证后端夹具一并落地</b>——需真实带认证 arthas
 *       后端 + 错 token 触发<b>真实 401</b>（TDD 硬约束：零桩）。当前 {@code ArthasMcpBackend} 仅 NONE
 *       认证、无 401 路径；落地时由调用方标记 target 不可用、对调用方返 JSON-RPC error（<b>不透传 HTTP</b>）。</li>
 * </ul>
 *
 * <p>{@link #close()} 缩窄为不抛 checked 异常（对齐 SDK {@code McpSyncClient.close()}）。
 */
public interface BackendClient extends AutoCloseable {

    /** 执行 {@code initialize} 握手（C-INIT-1/2/3）。幂等：已握手则空操作。 */
    void initialize();

    /**
     * 同步 {@code tools/call} 转发（C-CALL-1/2/3、C-RESULT-1/2）。
     *
     * @param name      工具名（如 {@code jvm}/{@code watch}）
     * @param arguments <b>剥离 {@code target} 后</b>的剩余键（原样转发后端）
     * @return 后端 {@code CallToolResult}（原样，含 {@code isError=true}）
     */
    McpSchema.CallToolResult callTool(String name, Map<String, Object> arguments);

    /** 是否已完成 initialize 握手（list-targets {@code healthy} 判定用）。 */
    boolean isInitialized();

    /** 优雅下线（§7）：关 session / 连接池。不抛 checked。 */
    @Override
    void close();
}
