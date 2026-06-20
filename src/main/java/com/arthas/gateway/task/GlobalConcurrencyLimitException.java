package com.arthas.gateway.task;

/**
 * 全局在途异步任务上限越界(002 整改 · P2-4/FR-010)。
 *
 * <p>{@code AsyncTaskExecutor.submit} 在取槽后、提交后台前检测<b>跨 target 累计</b> inflight 是否超全局上限
 * (配置 {@code arthas-gateway.task.global-max-inflight},未设→动态默认 = 注册表后端数 × 5)。超限时抛本异常,
 * 由 {@code ToolsCallRouter} 翻译为结构化 {@code McpError}({@code INVALID_PARAMS} +
 * {@code reason=global_concurrency_limit}),防集群触顶(虚拟线程虽廉价,但后端总数有限,跨 target 须有全局闸门)。
 *
 * <p>与 {@code ConcurrencyLimitException}(per-target 单后端槽)并列:后者限<b>单 target</b>,本异常限<b>全集群</b>。
 */
public final class GlobalConcurrencyLimitException extends RuntimeException {

    private final int globalMaxInflight;

    public GlobalConcurrencyLimitException(int globalMaxInflight) {
        super("全局在途异步任务已达上限:globalMaxInflight=" + globalMaxInflight);
        this.globalMaxInflight = globalMaxInflight;
    }

    /** 触发限流时的全局上限(配置值或动态默认 = 后端数 × 5)。 */
    public int globalMaxInflight() {
        return globalMaxInflight;
    }
}
