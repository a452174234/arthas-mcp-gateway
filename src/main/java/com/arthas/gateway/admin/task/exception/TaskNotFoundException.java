package com.arthas.gateway.admin.task.exception;

/**
 * 004 导出目标任务不存在（admin-api-contract §2：404）。
 */
public class TaskNotFoundException extends RuntimeException {

    private final String taskId;

    public TaskNotFoundException(String taskId) {
        super("未知任务：" + taskId);
        this.taskId = taskId;
    }

    public String getTaskId() {
        return taskId;
    }
}
