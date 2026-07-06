package com.arthas.gateway.admin.task;

import com.arthas.gateway.admin.task.dto.TaskListPageDto;
import com.arthas.gateway.admin.task.dto.TaskSummaryDto;
import com.arthas.gateway.task.GatewayTask;
import com.arthas.gateway.task.TaskState;
import com.arthas.gateway.task.TaskStore;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 004 增量 TaskListService 契约（T035）：过滤/排序/分页/clamp/isError 映射。
 *
 * <p>mock TaskStore（存储边界已由 TaskStoreTest 验证）+ mock GatewayTask（final class，mock-maker-inline）。
 * LENIENT：task() 辅助批量 stub，部分测试用不到全部字段。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskListServiceTest {

    @Mock
    TaskStore store;

    /** 构造 mock GatewayTask，预设全字段 + 按 status 决定 result/completedAt。 */
    private GatewayTask task(String id, String tool, String target, String createdIso,
                             TaskState status, boolean isError) {
        GatewayTask t = mock(GatewayTask.class);
        Instant created = Instant.parse(createdIso);
        when(t.taskId()).thenReturn(id);
        when(t.toolName()).thenReturn(tool);
        when(t.target()).thenReturn(target);
        when(t.createdAt()).thenReturn(created);
        when(t.status()).thenReturn(status);
        when(t.completedAt()).thenReturn(status == TaskState.WORKING ? null : created.plusSeconds(5));
        if (status == TaskState.COMPLETED) {
            CallToolResult result = mock(CallToolResult.class);
            when(result.isError()).thenReturn(isError);
            when(t.result()).thenReturn(result);
        } else {
            when(t.result()).thenReturn(null);
        }
        return t;
    }

    @Test
    void listReturnsAllSortedByCreatedAtDesc_invList3() {
        // store 顺序为 t1,t2,t3（createdAt 递增）；service 须倒序为 t3,t2,t1
        GatewayTask t1 = task("t1", "watch", "x", "2026-07-06T00:00:01Z", TaskState.COMPLETED, false);
        GatewayTask t2 = task("t2", "jvm", "x", "2026-07-06T00:00:02Z", TaskState.COMPLETED, false);
        GatewayTask t3 = task("t3", "watch", "x", "2026-07-06T00:00:03Z", TaskState.COMPLETED, false);
        when(store.list()).thenReturn(List.of(t1, t2, t3));

        TaskListPageDto page = new TaskListService(store).list(null, null, null, 0, 20);

        assertThat(page.items()).extracting(TaskSummaryDto::taskId).containsExactly("t3", "t2", "t1");
        assertThat(page.total()).isEqualTo(3);
    }

    @Test
    void filterByStatusUsesStoreListOverload() {
        GatewayTask t1 = task("t1", "watch", "x", "2026-07-06T00:00:01Z", TaskState.COMPLETED, false);
        when(store.list(TaskState.COMPLETED)).thenReturn(List.of(t1));

        TaskListPageDto page = new TaskListService(store).list(TaskState.COMPLETED, null, null, 0, 20);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).status()).isEqualTo("COMPLETED");
    }

    @Test
    void filterByTool() {
        GatewayTask t1 = task("t1", "watch", "x", "2026-07-06T00:00:01Z", TaskState.COMPLETED, false);
        GatewayTask t2 = task("t2", "jvm", "x", "2026-07-06T00:00:02Z", TaskState.COMPLETED, false);
        when(store.list()).thenReturn(List.of(t1, t2));

        TaskListPageDto page = new TaskListService(store).list(null, "watch", null, 0, 20);

        assertThat(page.items()).extracting(TaskSummaryDto::tool).containsOnly("watch");
        assertThat(page.total()).isEqualTo(1);
    }

    @Test
    void filterByTarget() {
        GatewayTask t1 = task("t1", "watch", "alpha", "2026-07-06T00:00:01Z", TaskState.COMPLETED, false);
        GatewayTask t2 = task("t2", "watch", "beta", "2026-07-06T00:00:02Z", TaskState.COMPLETED, false);
        when(store.list()).thenReturn(List.of(t1, t2));

        TaskListPageDto page = new TaskListService(store).list(null, null, "beta", 0, 20);

        assertThat(page.items()).extracting(TaskSummaryDto::target).containsOnly("beta");
        assertThat(page.total()).isEqualTo(1);
    }

    @Test
    void paginationPageAndSize_totalIndependentOfItems() {
        // 5 任务 createdAt 递增；倒序 = t5..t1
        List<GatewayTask> tasks = IntStream.rangeClosed(1, 5)
                .mapToObj(i -> task("t" + i, "watch", "x",
                        String.format("2026-07-06T00:00:0%dZ", i), TaskState.COMPLETED, false))
                .toList();
        when(store.list()).thenReturn(tasks);

        TaskListPageDto p0 = new TaskListService(store).list(null, null, null, 0, 2);
        assertThat(p0.items()).extracting(TaskSummaryDto::taskId).containsExactly("t5", "t4");
        assertThat(p0.total()).isEqualTo(5);  // total = 过滤后全量，与分页独立（INV-LIST-2）

        TaskListPageDto p2 = new TaskListService(store).list(null, null, null, 2, 2);
        assertThat(p2.items()).extracting(TaskSummaryDto::taskId).containsExactly("t1");
        assertThat(p2.total()).isEqualTo(5);
    }

    @Test
    void sizeClampAbove100() {
        when(store.list()).thenReturn(List.of());
        TaskListPageDto page = new TaskListService(store).list(null, null, null, 0, 200);
        assertThat(page.size()).isEqualTo(100);
    }

    @Test
    void sizeClampBelow1() {
        when(store.list()).thenReturn(List.of());
        assertThat(new TaskListService(store).list(null, null, null, 0, 0).size()).isEqualTo(1);
        assertThat(new TaskListService(store).list(null, null, null, 0, -5).size()).isEqualTo(1);
    }

    @Test
    void pageClampBelow0() {
        when(store.list()).thenReturn(List.of());
        TaskListPageDto page = new TaskListService(store).list(null, null, null, -1, 20);
        assertThat(page.page()).isEqualTo(0);
    }

    @Test
    void isErrorMappingCompletedTrueOthersFalse() {
        GatewayTask completedErr = task("t1", "watch", "x", "2026-07-06T00:00:01Z", TaskState.COMPLETED, true);
        GatewayTask completedOk = task("t2", "watch", "x", "2026-07-06T00:00:02Z", TaskState.COMPLETED, false);
        GatewayTask working = task("t3", "watch", "x", "2026-07-06T00:00:03Z", TaskState.WORKING, false);
        when(store.list()).thenReturn(List.of(completedErr, completedOk, working));

        TaskListPageDto page = new TaskListService(store).list(null, null, null, 0, 20);

        assertThat(page.items()).extracting(TaskSummaryDto::isError)
                .containsExactlyInAnyOrder(true, false, false);
    }

    @Test
    void emptyResultReturnsEmptyPage() {
        when(store.list()).thenReturn(List.of());
        TaskListPageDto page = new TaskListService(store).list(null, null, null, 0, 20);
        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();
    }
}
