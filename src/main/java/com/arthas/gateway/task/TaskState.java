package com.arthas.gateway.task;

/**
 * 异步任务状态（方案 C，data-model.md §6 / gateway-tools-contract.md §2）。
 *
 * <pre>
 *   WORKING ──markCompleted──► COMPLETED   （后端返回结果，含 isError=true 原样保留）
 *          ├──markFailed──────► FAILED     （基础设施故障：超时/不可达/熔断 OPEN）
 *          └──markCancelled───► CANCELLED  （task-cancel 或终态幂等）
 * </pre>
 *
 * <p>除 {@link #WORKING} 外均为<b>终态</b>（{@link #isTerminal()}=true），不可逆——
 * {@code GatewayTask} 的转换方法对终态任务返 false（不覆盖）。
 */
public enum TaskState {
    WORKING,
    COMPLETED,
    FAILED,
    CANCELLED;

    /** 是否终态（非 WORKING 即终态，不可逆）。 */
    public boolean isTerminal() {
        return this != WORKING;
    }
}
