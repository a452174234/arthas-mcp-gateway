package com.arthas.gateway.admin.task;

import com.arthas.gateway.admin.task.dto.TaskExportDto;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 004 异步任务结果导出 REST 端点（admin-api-contract §2，C 能力）。
 *
 * <p>{@code GET /admin/tasks/{taskId}/export?format=json}：返回 {@code completed} 任务的完整结果
 * （含 frames）为可下载 JSON（{@code Content-Disposition: attachment}）。frames 原样透传（宪法原则二）。
 *
 * <p>{@code @ConditionalOnProperty(admin.export.enabled, matchIfMissing=true)}：默认开；
 * 关闭时端点 404（admin-invariants INV-SWITCH-2）。
 */
@RestController
@RequestMapping("/admin/tasks")
@ConditionalOnProperty(name = "arthas-gateway.admin.export.enabled", havingValue = "true", matchIfMissing = true)
public class TaskExportController {

    private final TaskExportService service;

    public TaskExportController(TaskExportService service) {
        this.service = service;
    }

    @GetMapping("/{taskId}/export")
    public ResponseEntity<TaskExportDto> export(
            @PathVariable String taskId,
            @RequestParam(name = "format", required = false, defaultValue = "json") String format) {
        TaskExportDto dto = service.export(taskId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + taskId + ".json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(dto);
    }
}
