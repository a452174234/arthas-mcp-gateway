package com.arthas.gateway.admin.task;

import com.arthas.gateway.admin.task.dto.TaskListPageDto;
import com.arthas.gateway.admin.task.dto.TaskSummaryDto;
import com.arthas.gateway.task.GatewayTask;
import com.arthas.gateway.task.TaskState;
import com.arthas.gateway.task.TaskStore;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 004 增量 异步任务列表查询业务（admin-api-contract §2，FR-015）。
 *
 * <p>从 {@link TaskStore} 取任务（{@code status} 非空走 {@link TaskStore#list(TaskState)} 重载，
 * 否则 {@link TaskStore#list()} 全量），按 {@code tool}/{@code target} 精确过滤、{@code createdAt} 倒序、
 * offset 分页，映射为 {@link TaskSummaryDto} 摘要（无 frames，INV-LIST-1）。
 *
 * <p>参数归一化：{@code page<0→0}；{@code size<1→1}、{@code size>100→100}（不报 400，clamp）。
 * {@code tool}/{@code target} 为 {@code null}/空白时不参与过滤（=全部）。
 *
 * <p>{@code isError} 仅 {@code COMPLETED} 时据 {@code result().isError()}（G-TG-2 业务错误原样保留），
 * 其余态（{@code result()==null}）为 {@code false}。
 */
@Service
public class TaskListService {

    /** 单页上限（admin-api-contract §2 size 上限）。 */
    public static final int MAX_SIZE = 100;

    private final TaskStore store;

    public TaskListService(TaskStore store) {
        this.store = store;
    }

    /**
     * @param status 状态过滤（null=全部）
     * @param tool   工具名精确匹配（null/空白=不过滤）
     * @param target target 名精确匹配（null/空白=不过滤）
     * @param page   页码（&lt;0 → 0）
     * @param size   页大小（&lt;1 → 1，&gt;100 → 100）
     */
    public TaskListPageDto list(TaskState status, String tool, String target, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_SIZE, Math.max(1, size));

        List<GatewayTask> all = (status != null)
                ? store.list(status)
                : store.list();

        List<GatewayTask> filtered = all.stream()
                .filter(t -> isBlankOrEquals(tool, t.toolName()))
                .filter(t -> isBlankOrEquals(target, t.target()))
                .sorted(Comparator.comparing(GatewayTask::createdAt).reversed())
                .toList();

        long total = filtered.size();
        List<TaskSummaryDto> items = filtered.stream()
                .skip((long) safePage * safeSize)
                .limit(safeSize)
                .map(TaskListService::toSummary)
                .toList();

        return new TaskListPageDto(items, total, safePage, safeSize);
    }

    /** {@code filter} 为 null/空白 → 不过滤（返 true）；否则精确相等。 */
    private static boolean isBlankOrEquals(String filter, String actual) {
        return filter == null || filter.isBlank() || Objects.equals(filter, actual);
    }

    /** GatewayTask → 摘要 DTO（isError 仅 COMPLETED 有 result 时映射，其余 false）。 */
    private static TaskSummaryDto toSummary(GatewayTask t) {
        boolean isError = false;
        CallToolResult result = t.result();
        if (result != null && Boolean.TRUE.equals(result.isError())) {
            isError = true;
        }
        return new TaskSummaryDto(
                t.taskId(),
                t.toolName(),
                t.target(),
                t.status().name(),
                t.createdAt(),
                t.completedAt(),
                isError);
    }
}
