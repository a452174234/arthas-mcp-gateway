package com.arthas.gateway.handler;

import com.arthas.gateway.tool.ExposedTool;
import com.arthas.gateway.tool.RoutingMode;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 一次诊断调用的解析结果（tools/call 入参解析，server-contract.md §5.1 步骤 1-2，T026）。
 *
 * <p>纯逻辑值对象，承载从 {@link CallToolRequest} + {@link ExposedTool} 解算出的路由依据：
 * <ul>
 *   <li>{@code target}——从 {@code arguments} 取出并<b>剥离</b>的目标 JVM 逻辑名（剥离后永不进后端参数，S-CALL-2）。</li>
 *   <li>{@code backendArgs}——剥离 target 后的剩余参数，<b>原样</b>转发后端（不改写 arthas 参数名）。</li>
 *   <li>{@code routingMode}——取自 {@link ExposedTool#routingMode()}，决定 {@link ToolsCallRouter} 分流。</li>
 * </ul>
 *
 * <p><b>校验边界</b>：本类仅校验 target 的<b>格式</b>（缺失/空/非字符串 → INVALID_PARAMS(-32602)，S-ERR-1）。
 * 「target 不在册 → INVALID_PARAMS + data.available」（S-ERR-2）依赖注册表，属 {@link ToolsCallRouter} 职责。
 *
 * <p>「toolName 命中」（S-ERR-3）由 SDK 保证——只有 {@code tools/list} 注册的工具才会路由到 handler，
 * 未注册工具名 SDK 自动返 INVALID_PARAMS，不到本类。
 *
 * <p>构造时对 {@code backendArgs} 做 {@link Map#copyOf} 防御性拷贝，外部篡改不泄漏。
 */
public record DiagnosticRequest(
        String target,
        Map<String, Object> backendArgs,
        RoutingMode routingMode) {

    public DiagnosticRequest {
        Objects.requireNonNull(target, "target 不可为空");
        Objects.requireNonNull(backendArgs, "backendArgs 不可为空");
        Objects.requireNonNull(routingMode, "routingMode 不可为空");
        backendArgs = Map.copyOf(backendArgs); // 防御性不可变拷贝
    }

    /**
     * 从工具与调用请求解析诊断请求。
     *
     * @param tool    注册表中的工具（提供 routingMode）
     * @param request SDK 反序列化的调用请求（arguments 含 target）
     * @return 解析结果（target 已剥离、backendArgs 不可变）
     * @throws McpError target 缺失/空/非字符串时抛 INVALID_PARAMS(-32602)
     */
    public static DiagnosticRequest parse(ExposedTool tool, CallToolRequest request) {
        Objects.requireNonNull(tool, "tool 不可为空");
        Objects.requireNonNull(request, "request 不可为空");
        Map<String, Object> arguments = request.arguments();
        Object rawTarget = arguments == null ? null : arguments.get("target");
        String target = requireTarget(rawTarget);
        Map<String, Object> backendArgs = stripTarget(arguments);
        return new DiagnosticRequest(target, backendArgs, tool.routingMode());
    }

    /** target 格式校验：缺失/非字符串/空白 → INVALID_PARAMS。 */
    private static String requireTarget(Object raw) {
        if (raw == null) {
            throw invalidParams("tools/call 缺少 target 参数：目标 JVM 逻辑名（见 list-targets）");
        }
        if (!(raw instanceof String s)) {
            throw invalidParams("tools/call 的 target 须为字符串（目标 JVM 逻辑名），实得类型："
                    + raw.getClass().getSimpleName());
        }
        if (s.isBlank()) {
            throw invalidParams("tools/call 的 target 不可为空（目标 JVM 逻辑名）");
        }
        return s.trim();
    }

    /** 剥离 target 的 backendArgs（原样保留其余参数；arguments 为 null/空时返空 Map）。 */
    private static Map<String, Object> stripTarget(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>(arguments);
        copy.remove("target");
        return copy;
    }

    /** 构造 INVALID_PARAMS McpError（协议层错误，SDK 转 JSON-RPC error response）。 */
    private static McpError invalidParams(String message) {
        return McpError.builder(McpErrorCodes.INVALID_PARAMS)
                .message(message)
                .build();
    }
}
