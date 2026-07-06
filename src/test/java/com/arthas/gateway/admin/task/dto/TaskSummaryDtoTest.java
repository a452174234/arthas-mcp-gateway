package com.arthas.gateway.admin.task.dto;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 004 增量 TaskSummaryDto 契约（T033）：7 字段摘要、**无 frames**（INV-LIST-1）、isError 承载。
 */
class TaskSummaryDtoTest {

    @Test
    void dtoCarriesSevenSummaryFields() {
        Instant created = Instant.parse("2026-07-06T00:00:00Z");
        Instant completed = Instant.parse("2026-07-06T00:00:05Z");
        TaskSummaryDto dto = new TaskSummaryDto("t-abc123", "watch", "debian-demo-business",
                "COMPLETED", created, completed, false);

        assertThat(dto.taskId()).isEqualTo("t-abc123");
        assertThat(dto.tool()).isEqualTo("watch");
        assertThat(dto.target()).isEqualTo("debian-demo-business");
        assertThat(dto.status()).isEqualTo("COMPLETED");
        assertThat(dto.createdAt()).isEqualTo(created);
        assertThat(dto.completedAt()).isEqualTo(completed);
        assertThat(dto.isError()).isFalse();
    }

    @Test
    void summaryHasNoFrames_invList1() {
        // INV-LIST-1：摘要禁含 frames（frames 仅由 TaskExportDto / export 端点提供）
        RecordComponent[] components = TaskSummaryDto.class.getRecordComponents();
        assertThat(components)
                .extracting(RecordComponent::getName)
                .containsExactlyInAnyOrder(
                        "taskId", "tool", "target", "status", "createdAt", "completedAt", "isError")
                .doesNotContain("frames");
    }

    @Test
    void isErrorFlagCarriedForBusinessError() {
        // G-TG-2：后端 isError=true 是正常业务响应，COMPLETED + isError=true 原样保留（承载于摘要）
        TaskSummaryDto dto = new TaskSummaryDto("t", "watch", "x",
                "COMPLETED", Instant.EPOCH, Instant.EPOCH, true);
        assertThat(dto.isError()).isTrue();
    }
}
