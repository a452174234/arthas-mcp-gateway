package com.arthas.gateway.admin.task.dto;

import java.time.Instant;
import java.util.List;

/**
 * 004 异步任务结果导出 DTO（admin-api-contract §2，data-model §1.2）。
 *
 * <p>frames <b>原样来自 {@code GatewayTask.result().content()} 的文本</b>（每个 TextContent.text），
 * 不篡改/摘要/截断（admin-invariants INV-EXP-1 / 宪法原则二）。仅 {@code COMPLETED} 任务有 frames。
 */
public record TaskExportDto(
        String taskId,
        String tool,
        String target,
        String status,
        Instant createdAt,
        Instant completedAt,
        boolean isError,
        List<String> frames) {

    public TaskExportDto {
        frames = frames == null ? List.of() : List.copyOf(frames);
    }
}
