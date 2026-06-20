package com.arthas.gateway.backend;

/**
 * 无状态后端不支持异步任务(002 整改 · P1-2 契约修复)。
 *
 * <p>{@code BackendEntry.admit} 检测 {@code config().protocol()==Protocol.STATELESS} 时抛出——
 * 无状态后端无法承载带任务语义、需轮询的异步诊断调用。
 * 由 {@code ToolsCallRouter} 翻译为结构化 {@code McpError}({@code INVALID_PARAMS}，
 * {@code reason=stateless_unsupported_async})。
 *
 * <p><b>仅异步路径</b>校验(同步 {@code execute} 不经此检查，STATELESS 同步调用仍正常)。
 */
public final class StatelessAsyncException extends RuntimeException {

    public StatelessAsyncException() {
        super("无状态(STATELESS)后端不支持异步任务");
    }
}
