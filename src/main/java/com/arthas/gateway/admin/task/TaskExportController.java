package com.arthas.gateway.admin.task;

import com.arthas.gateway.admin.task.dto.TaskExportDto;
import com.arthas.gateway.admin.task.dto.TaskListPageDto;
import com.arthas.gateway.task.TaskState;
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
 * 004 异步任务管理面 REST 端点（admin-api-contract §2，C 能力）。管 {@code /admin/tasks} 下全部端点：
 *
 * <ul>
 *   <li>{@code GET /admin/tasks}（增量，FR-015）：任务摘要列表（无 frames）+ 过滤 + 分页 + 倒序。</li>
 *   <li>{@code GET /admin/tasks/{taskId}/export}：{@code completed} 任务完整结果（含 frames）下载。</li>
 * </ul>
 *
 * <p>{@code @ConditionalOnProperty(admin.export.enabled, matchIfMissing=true)}：默认开；列表与导出
 * <b>共用此开关</b>，关闭时两者都 404（admin-invariants INV-LIST-4 / INV-SWITCH-2）。
 */
@RestController
@RequestMapping("/admin/tasks")
@ConditionalOnProperty(name = "arthas-gateway.admin.export.enabled", havingValue = "true", matchIfMissing = true)
public class TaskExportController {

    private final TaskExportService exportService;
    private final TaskListService listService;

    public TaskExportController(TaskExportService exportService, TaskListService listService) {
        this.exportService = exportService;
        this.listService = listService;
    }

    /** 004 增量：异步任务列表查询（FR-015 / admin-api-contract §2）。 */
    @GetMapping
    public TaskListPageDto list(
            @RequestParam(name = "status", required = false) TaskState status,
            @RequestParam(name = "tool", required = false) String tool,
            @RequestParam(name = "target", required = false) String target,
            @RequestParam(name = "page", required = false, defaultValue = "0") int page,
            @RequestParam(name = "size", required = false, defaultValue = "20") int size) {
        return listService.list(status, tool, target, page, size);
    }

    @GetMapping("/{taskId}/export")
    public ResponseEntity<TaskExportDto> export(
            @PathVariable String taskId,
            @RequestParam(name = "format", required = false, defaultValue = "json") String format) {
        TaskExportDto dto = exportService.export(taskId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + taskId + ".json\"")
                .contentType(MediaType.APPLICATION_JSON)
                .body(dto);
    }
}
