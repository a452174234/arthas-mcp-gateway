package com.arthas.gateway.task;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T031 {@link GatewayTask} + {@link TaskState} 状态机单测（surefire，纯逻辑）。
 *
 * <p>覆盖方案 C 异步任务实体的核心不变量（gateway-tools-contract.md §2/§5、G-TG-1/2、G-TC）：
 * <ul>
 *   <li>新建任务为 WORKING（非终态）。</li>
 *   <li>转换：markCompleted/markFailed/markCancelled 各自设终态 + completedAt。</li>
 *   <li>终态不可逆：已终态后任何转换返 false 且 status 不变。</li>
 *   <li>G-TG-2：后端 {@code isError=true} 的结果经 markCompleted <b>原样</b>保留（status=COMPLETED，非 failed）。</li>
 * </ul>
 */
class GatewayTaskTest {

    private static final Instant CREATED_AT = Instant.parse("2026-06-20T10:00:00Z");

    private static GatewayTask newTask() {
        return new GatewayTask("t-7f3a9c", "watch", "order-service", CREATED_AT, () -> CREATED_AT);
    }

    private static CallToolResult result(String text, boolean error) {
        return new CallToolResult(List.of(new TextContent(text)), error, null, null);
    }

    @Test
    void newTaskIsWorking() {
        GatewayTask t = newTask();
        assertThat(t.taskId()).isEqualTo("t-7f3a9c");
        assertThat(t.toolName()).isEqualTo("watch");
        assertThat(t.target()).isEqualTo("order-service");
        assertThat(t.createdAt()).isEqualTo(CREATED_AT);
        assertThat(t.status()).isEqualTo(TaskState.WORKING);
        assertThat(t.isTerminal()).isFalse();
        assertThat(t.result()).isNull();
        assertThat(t.error()).isNull();
        assertThat(t.completedAt()).isNull();
    }

    @Test
    void markCompletedSetsResultAndTerminal() {
        GatewayTask t = newTask();
        CallToolResult r = result("watch 数据", false);

        boolean changed = t.markCompleted(r);

        assertThat(changed).as("WORKING→COMPLETED 转换成功").isTrue();
        assertThat(t.status()).isEqualTo(TaskState.COMPLETED);
        assertThat(t.result()).isSameAs(r);
        assertThat(t.completedAt()).as("completedAt 已设").isNotNull();
        assertThat(t.isTerminal()).isTrue();
    }

    @Test
    void markFailedSetsErrorAndTerminal() {
        GatewayTask t = newTask();
        TaskError err = new TaskError(TaskError.REASON_BACKEND_TIMEOUT, "后端 11min 未响应");

        boolean changed = t.markFailed(err);

        assertThat(changed).isTrue();
        assertThat(t.status()).isEqualTo(TaskState.FAILED);
        assertThat(t.error()).isSameAs(err);
        assertThat(t.completedAt()).isNotNull();
        assertThat(t.isTerminal()).isTrue();
    }

    @Test
    void markCancelledSetsTerminalWithoutResultOrError() {
        GatewayTask t = newTask();

        boolean changed = t.markCancelled();

        assertThat(changed).isTrue();
        assertThat(t.status()).isEqualTo(TaskState.CANCELLED);
        assertThat(t.result()).isNull();
        assertThat(t.error()).isNull();
        assertThat(t.completedAt()).isNotNull();
    }

    @Test
    void terminalStateIsImmutableCompletedRejectsFurtherTransitions() {
        GatewayTask t = newTask();
        t.markCompleted(result("ok", false));

        assertThat(t.markFailed(new TaskError(TaskError.REASON_BACKEND_UNREACHABLE, "x")))
                .as("COMPLETED 后 markFailed 无效").isFalse();
        assertThat(t.markCancelled()).as("COMPLETED 后 markCancelled 无效").isFalse();
        assertThat(t.status()).as("status 仍 COMPLETED（终态不可逆）").isEqualTo(TaskState.COMPLETED);
    }

    @Test
    void terminalStateIsImmutableCancelledRejectsFurtherTransitions() {
        GatewayTask t = newTask();
        t.markCancelled();

        assertThat(t.markCompleted(result("late", false)))
                .as("CANCELLED 后后台迟到完成不覆盖").isFalse();
        assertThat(t.status()).isEqualTo(TaskState.CANCELLED);
    }

    @Test
    void gtg_2_backendIsErrorPreservedAsCompletedNotFailed() {
        // G-TG-2：后端 isError=true 是正常响应（业务错误），原样保留在 completed.result，不转 failed
        GatewayTask t = newTask();
        CallToolResult backendError = result("arthas 业务错误", true);

        t.markCompleted(backendError);

        assertThat(t.status())
                .as("后端 isError=true → COMPLETED（非 failed），结果原样保留")
                .isEqualTo(TaskState.COMPLETED);
        assertThat(t.result()).isSameAs(backendError);
        assertThat(t.result().isError()).isTrue();
        assertThat(t.error()).as("无 infrastructure error").isNull();
    }
}
