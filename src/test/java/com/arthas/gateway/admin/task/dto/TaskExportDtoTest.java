package com.arthas.gateway.admin.task.dto;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 004 TaskExportDto 契约（T019）：任务元信息 + frames 原样（INV-EXP-1）。
 */
class TaskExportDtoTest {

    @Test
    void dtoCarriesTaskMetadataAndFrames() {
        Instant created = Instant.parse("2026-07-06T00:00:00Z");
        Instant completed = Instant.parse("2026-07-06T00:00:05Z");
        TaskExportDto dto = new TaskExportDto("t-abc123", "watch", "order-service",
                "COMPLETED", created, completed, false,
                List.of("{\"accessPoint\":\"AtExit\",\"methodName\":\"hotMethod\",\"cost\":0.32}"));

        assertThat(dto.taskId()).isEqualTo("t-abc123");
        assertThat(dto.tool()).isEqualTo("watch");
        assertThat(dto.target()).isEqualTo("order-service");
        assertThat(dto.status()).isEqualTo("COMPLETED");
        assertThat(dto.completedAt()).isEqualTo(completed);
        assertThat(dto.frames()).hasSize(1);
        assertThat(dto.frames().get(0)).contains("hotMethod");
    }

    @Test
    void framesAreRawText_invExp1() {
        TaskExportDto dto = new TaskExportDto("t", "watch", "x", "COMPLETED",
                Instant.EPOCH, Instant.EPOCH, false, List.of("raw frame text 原样"));
        assertThat(dto.frames()).containsExactly("raw frame text 原样");
    }

    @Test
    void nullFramesDefensiveEmpty() {
        TaskExportDto dto = new TaskExportDto("t", "watch", "x", "COMPLETED",
                Instant.EPOCH, Instant.EPOCH, false, null);
        assertThat(dto.frames()).isEmpty();
    }
}
