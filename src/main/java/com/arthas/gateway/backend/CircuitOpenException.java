package com.arthas.gateway.backend;

/**
 * 目标熔断中(002 整改 · 统一拦截层域异常，P1-3)。
 *
 * <p>{@code BackendEntry.admit} 读熔断守卫发现 breaker 处于 OPEN(未满退避)时抛出。
 * 由 {@code ToolsCallRouter} 翻译为结构化 {@code McpError}({@code backend_unreachable}，
 * data 含 {@code retryAfterMs}、{@code available})——字段与修复前逐字一致(FR-016)。
 *
 * <p>携带 {@code retryAfterMs} 供翻译层填充 {@code data.retryAfterMs}，<b>不</b>依赖 {@code McpError}/注册表
 * (错误边界：BackendEntry 抛域异常，路由器翻译)。
 */
public final class CircuitOpenException extends RuntimeException {

    private final long retryAfterMs;

    public CircuitOpenException(long retryAfterMs) {
        super("target 熔断中(OPEN)，剩余退避 " + retryAfterMs + "ms");
        this.retryAfterMs = retryAfterMs;
    }

    /** 熔断建议重试等待(毫秒)，填入 {@code data.retryAfterMs}。 */
    public long retryAfterMs() {
        return retryAfterMs;
    }
}
