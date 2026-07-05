package com.arthas.gateway.admin.task;

import com.arthas.gateway.admin.task.dto.TaskExportDto;
import com.arthas.gateway.admin.task.exception.TaskNotCompletedException;
import com.arthas.gateway.admin.task.exception.TaskNotFoundException;
import com.arthas.gateway.task.GatewayTask;
import com.arthas.gateway.task.TaskState;
import com.arthas.gateway.task.TaskStore;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 004 异步任务结果导出业务（admin-api-contract §2，research.md R5）。
 *
 * <p>从 {@link TaskStore} 取 {@link GatewayTask}，仅 {@code COMPLETED} 任务可导出；frames
 * <b>原样来自 {@code result().content()} 的 TextContent.text</b>（不篡改/截断，admin-invariants INV-EXP-1 / 宪法原则二）。
 */
@Service
public class TaskExportService {

    private final TaskStore store;

    public TaskExportService(TaskStore store) {
        this.store = store;
    }

    public TaskExportDto export(String taskId) {
        GatewayTask task = store.get(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        if (task.status() != TaskState.COMPLETED) {
            throw new TaskNotCompletedException(taskId, task.status().name());
        }
        CallToolResult result = task.result();
        List<String> frames = result.content().stream()
                .filter(c -> c instanceof TextContent)
                .map(c -> ((TextContent) c).text())
                .toList();
        return new TaskExportDto(
                task.taskId(),
                task.toolName(),
                task.target(),
                task.status().name(),
                task.createdAt(),
                task.completedAt(),
                result.isError(),
                frames);
    }
}
