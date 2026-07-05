package com.arthas.gateway.admin.task.exception;

/**
 * 004 导出任务未完成（admin-api-contract §2：409）。仅 {@code COMPLETED} 任务可导出。
 */
public class TaskNotCompletedException extends RuntimeException {

    private final String taskId;
    private final String status;

    public TaskNotCompletedException(String taskId, String status) {
        super("任务未完成，不可导出：" + taskId + "（当前状态 " + status + "）");
        this.taskId = taskId;
        this.status = status;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getStatus() {
        return status;
    }
}
