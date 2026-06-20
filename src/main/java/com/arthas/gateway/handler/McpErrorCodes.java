package com.arthas.gateway.handler;

/**
 * JSON-RPC 2.0 标准错误码（MCP 线契约 §5.1：仅用 arthas 实现的 5 个标准 code，不使用 -32xxx 自定义）。
 *
 * <p>SDK 2.0.0 {@code McpSchema} 未公开这些常量（{@code javap -constants} 为空），此处集中定义，
 * 供 {@link DiagnosticRequest}/{@link ToolsCallRouter} 构造 {@link io.modelcontextprotocol.spec.McpError}
 * 时引用，避免裸字面量散落。
 *
 * <p>语义（server-contract.md §6 错误传播表）：
 * <ul>
 *   <li>{@link #PARSE_ERROR}：协议解析错误</li>
 *   <li>{@link #INVALID_REQUEST}：非法 JSON-RPC 消息</li>
 *   <li>{@link #METHOD_NOT_FOUND}：未实现 method（SDK 自动处理未注册工具名）</li>
 *   <li>{@link #INVALID_PARAMS}：target 缺失/空/不在册（网关主用）</li>
 *   <li>{@link #INTERNAL_ERROR}：handler 抛非 McpError 异常时 SDK 兜底</li>
 * </ul>
 */
public final class McpErrorCodes {

    /** 协议解析错误。 */
    public static final int PARSE_ERROR = -32700;
    /** 非法 JSON-RPC 消息。 */
    public static final int INVALID_REQUEST = -32600;
    /** 未实现 method。 */
    public static final int METHOD_NOT_FOUND = -32601;
    /** 参数非法（target 缺失/空/不在册）。 */
    public static final int INVALID_PARAMS = -32602;
    /** 内部错误（非 McpError 异常兜底）。 */
    public static final int INTERNAL_ERROR = -32603;

    private McpErrorCodes() {
        // 常量集合，不实例化
    }
}
