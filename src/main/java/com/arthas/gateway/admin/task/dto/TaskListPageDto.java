package com.arthas.gateway.admin.task.dto;

import java.util.List;

/**
 * 004 增量 异步任务列表分页响应（admin-api-contract §2 {@code GET /admin/tasks}，FR-015）。
 *
 * <p>{@code items} = 当前页摘要（按 {@code createdAt} 倒序）；{@code total} = 过滤后、分页前的总数
 * （与分页独立，INV-LIST-2）；{@code page}/{@code size} = 归一化后的实际页码/页大小。
 */
public record TaskListPageDto(
        List<TaskSummaryDto> items,
        long total,
        int page,
        int size) {

    public TaskListPageDto {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
