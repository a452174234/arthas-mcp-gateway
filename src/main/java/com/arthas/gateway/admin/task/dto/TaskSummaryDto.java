package com.arthas.gateway.admin.task.dto;

import java.time.Instant;

/**
 * 004 增量 异步任务摘要 DTO（admin-api-contract §2 {@code GET /admin/tasks}，FR-015）。
 *
 * <p>列表查询的轻量摘要视图——<b>不含 frames</b>（admin-invariants INV-LIST-1）；
 * frames 仅由 {@link TaskExportDto}（{@code /{taskId}/export} 端点）提供。
 *
 * <p>字段：{@code taskId/tool/target/status/createdAt/completedAt/isError}。
 * {@code isError} 仅 {@code COMPLETED} 时据 {@code GatewayTask.result().isError()}
 * （G-TG-2 业务错误原样保留），其余态（WORKING/FAILED/CANCELLED）为 {@code false}
 * ——映射在 {@code TaskListService}，本 DTO 仅承载。
 */
public record TaskSummaryDto(
        String taskId,
        String tool,
        String target,
        String status,
        Instant createdAt,
        Instant completedAt,
        boolean isError) {
}
