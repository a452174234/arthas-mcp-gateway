package com.arthas.gateway.backend;

/**
 * 目标并发槽已满(002 整改 · 统一拦截层域异常)。
 *
 * <p>{@code BackendEntry.admit} 取槽({@code tryAcquireSlot})失败(并发已达 {@code maxConcurrentTasks})时抛出。
 * 由 {@code ToolsCallRouter} 翻译为结构化 {@code McpError}({@code INVALID_PARAMS}，
 * data 含 {@code maxConcurrentTasks})——与修复前逐字一致(FR-016)。
 */
public final class ConcurrencyLimitException extends RuntimeException {

    private final int maxConcurrentTasks;

    public ConcurrencyLimitException(int maxConcurrentTasks) {
        super("target 并发已达上限：maxConcurrentTasks=" + maxConcurrentTasks);
        this.maxConcurrentTasks = maxConcurrentTasks;
    }

    /** 该目标的并发上限，填入 {@code data.maxConcurrentTasks}。 */
    public int maxConcurrentTasks() {
        return maxConcurrentTasks;
    }
}
