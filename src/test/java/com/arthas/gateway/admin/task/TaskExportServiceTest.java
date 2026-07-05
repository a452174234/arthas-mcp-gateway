package com.arthas.gateway.admin.task;

import com.arthas.gateway.admin.task.dto.TaskExportDto;
import com.arthas.gateway.admin.task.exception.TaskNotCompletedException;
import com.arthas.gateway.admin.task.exception.TaskNotFoundException;
import com.arthas.gateway.task.GatewayTask;
import com.arthas.gateway.task.TaskState;
import com.arthas.gateway.task.TaskStore;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 004 TaskExportService 单元测试（T021）：completed 导出 + frames 原样（INV-EXP-1）+ 404/409。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskExportServiceTest {

    @Mock
    private TaskStore store;

    private TaskExportService service;

    @BeforeEach
    void setUp() {
        service = new TaskExportService(store);
    }

    @Test
    void exportCompletedReturnsRawFrames_invExp1() {
        GatewayTask task = mock(GatewayTask.class);
        CallToolResult result = mock(CallToolResult.class);
        TextContent textContent = mock(TextContent.class);
        when(textContent.text()).thenReturn("frame-raw-text");
        when(result.content()).thenReturn(List.of(textContent));
        when(result.isError()).thenReturn(false);
        when(task.status()).thenReturn(TaskState.COMPLETED);
        when(task.result()).thenReturn(result);
        when(task.taskId()).thenReturn("t1");
        when(task.toolName()).thenReturn("watch");
        when(task.target()).thenReturn("order-service");
        when(task.createdAt()).thenReturn(Instant.EPOCH);
        when(task.completedAt()).thenReturn(Instant.parse("2026-07-06T00:00:05Z"));
        when(store.get("t1")).thenReturn(Optional.of(task));

        TaskExportDto dto = service.export("t1");

        assertThat(dto.frames()).containsExactly("frame-raw-text");
        assertThat(dto.status()).isEqualTo("COMPLETED");
        assertThat(dto.tool()).isEqualTo("watch");
        assertThat(dto.isError()).isFalse();
    }

    @Test
    void exportUnknownThrows404() {
        when(store.get("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.export("nope"))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void exportWorkingThrows409() {
        GatewayTask task = mock(GatewayTask.class);
        when(task.status()).thenReturn(TaskState.WORKING);
        when(store.get("t2")).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> service.export("t2"))
                .isInstanceOf(TaskNotCompletedException.class)
                .hasFieldOrPropertyWithValue("status", "WORKING");
    }

    @Test
    void exportCancelledThrows409() {
        GatewayTask task = mock(GatewayTask.class);
        when(task.status()).thenReturn(TaskState.CANCELLED);
        when(store.get("t3")).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> service.export("t3"))
                .isInstanceOf(TaskNotCompletedException.class);
    }
}
