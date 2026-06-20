package com.arthas.gateway.backend;

/**
 * 目标后端不可达(002 整改 · 统一拦截层域异常，P1-3)。
 *
 * <p>{@code BackendEntry.invoke} 调后端抛基础设施故障(连接拒绝/超时/initialize 失败/SSE 中断)时，
 * 经故障分类 {@code recordFailure} 后包装抛出。<b>取消中断导致的异常不计入熔断</b>
 * (invoke 内检测 {@code Thread.interrupted()} 跳过 recordFailure，但仍抛此异常供上层标 cancelled/failed)。
 *
 * <p>由 {@code ToolsCallRouter} 翻译为结构化 {@code McpError}({@code backend_unreachable}，
 * data 含 {@code available})——与修复前逐字一致(FR-016)。
 */
public final class BackendUnreachableException extends RuntimeException {

    public BackendUnreachableException(Throwable cause) {
        super("target 不可达：" + cause.getClass().getSimpleName()
                + ": " + String.valueOf(cause.getMessage()), cause);
    }
}
