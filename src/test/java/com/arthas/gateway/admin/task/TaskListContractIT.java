package com.arthas.gateway.admin.task;

import com.arthas.gateway.task.GatewayTask;
import com.arthas.gateway.task.TaskState;
import com.arthas.gateway.task.TaskStore;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 004 增量 任务列表查询契约 IT（T038，failsafe *IT）。
 *
 * <p>@SpringBootTest 启动完整网关，JDK HttpClient 调 {@code GET /admin/tasks}。注入真实 {@link TaskStore}
 * 植入任务（真实 {@link GatewayTask} + mock CallToolResult/TextContent 避免多参构造器），验证
 * admin-api-contract §2（A-LIST-TASKS-1/2）+ INV-LIST-1/2/3。
 *
 * <p>store 为单例 Bean、跨测试方法共享，故每测试用**唯一 tool 名** seed + 过滤隔离（避免相互污染）。
 *
 * <p><b>时间</b>：seed 用 {@code Instant.now().minusSeconds(N)}（clock=Instant::now）——确保 completedAt
 * 距当前不足 TTL（1h），不被 {@link TaskStore#list()} 的惰性 {@code cleanExpired} 移除。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "arthas-gateway.admin.crud.enabled=true",
        "arthas-gateway.admin.export.enabled=true"
})
class TaskListContractIT {

    private static final Path BACKENDS_FILE;

    static {
        try {
            BACKENDS_FILE = Files.createTempFile("backends-list-it", ".yaml");
            Files.writeString(BACKENDS_FILE, """
                    version: 1
                    backends:
                      - name: order-service
                        url: http://127.0.0.1:8564
                        protocol: STREAMABLE
                        auth: { mode: NONE }
                        connectTimeoutMs: 5000
                        callTimeoutMs: 30000
                        maxConcurrentTasks: 5
                    """);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @DynamicPropertySource
    static void backendsFile(DynamicPropertyRegistry r) {
        r.add("arthas-gateway.backends-file", () -> BACKENDS_FILE.toString());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TaskStore store;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** 植入任务（真实 GatewayTask；created = now - secondsAgo；clock=Instant::now 保证 completedAt 不过期）。 */
    private void seed(String taskId, String tool, String target, long secondsAgo, TaskState status) {
        Instant created = Instant.now().minusSeconds(secondsAgo);
        GatewayTask task = new GatewayTask(taskId, tool, target, created, Instant::now);
        switch (status) {
            case COMPLETED -> {
                CallToolResult result = Mockito.mock(CallToolResult.class);
                TextContent tc = Mockito.mock(TextContent.class);
                Mockito.when(tc.text()).thenReturn("frame-" + taskId);
                Mockito.when(result.content()).thenReturn(List.of(tc));
                Mockito.when(result.isError()).thenReturn(false);
                task.markCompleted(result);
            }
            case CANCELLED -> task.markCancelled();
            case WORKING -> { /* 保持 WORKING */ }
            default -> { /* FAILED 等略 */ }
        }
        store.put(task);
    }

    @Test
    void listSortedDescAndTotal_aListTasks1_invList2_3() throws Exception {
        seed("t-list-1", "listToolA", "tgt-a", 60, TaskState.COMPLETED);  // 最早
        seed("t-list-2", "listToolA", "tgt-a", 40, TaskState.COMPLETED);
        seed("t-list-3", "listToolA", "tgt-a", 20, TaskState.COMPLETED);  // 最新

        HttpResponse<String> r = get("/admin/tasks?tool=listToolA");
        assertThat(r.statusCode()).isEqualTo(200);
        // 倒序：t-list-3（最新）→ t-list-2 → t-list-1（INV-LIST-3）
        int i3 = r.body().indexOf("\"taskId\":\"t-list-3\"");
        int i2 = r.body().indexOf("\"taskId\":\"t-list-2\"");
        int i1 = r.body().indexOf("\"taskId\":\"t-list-1\"");
        assertThat(i3).isLessThan(i2);
        assertThat(i2).isLessThan(i1);
        assertThat(r.body()).contains("\"total\":3");  // INV-LIST-2
    }

    @Test
    void filterByStatus_aListTasks2() throws Exception {
        seed("t-stat-com", "statToolX", "tgt-x", 60, TaskState.COMPLETED);
        seed("t-stat-wor", "statToolX", "tgt-x", 40, TaskState.WORKING);

        HttpResponse<String> r = get("/admin/tasks?tool=statToolX&status=COMPLETED");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("t-stat-com");
        assertThat(r.body()).doesNotContain("t-stat-wor");
        assertThat(r.body()).contains("\"total\":1");
    }

    @Test
    void filterByTarget_aListTasks2() throws Exception {
        seed("t-tg-1", "tgTool", "tgt-alpha", 60, TaskState.COMPLETED);
        seed("t-tg-2", "tgTool", "tgt-beta", 40, TaskState.COMPLETED);

        HttpResponse<String> r = get("/admin/tasks?tool=tgTool&target=tgt-alpha");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("t-tg-1");
        assertThat(r.body()).doesNotContain("t-tg-2");
        assertThat(r.body()).contains("\"total\":1");
    }

    @Test
    void pagination_totalIndependentOfItems_invList2() throws Exception {
        seed("t-page-1", "pageToolA", "tgt-p", 30, TaskState.COMPLETED);  // 倒序第 3
        seed("t-page-2", "pageToolA", "tgt-p", 20, TaskState.COMPLETED);
        seed("t-page-3", "pageToolA", "tgt-p", 10, TaskState.COMPLETED);  // 倒序第 1（最新）

        HttpResponse<String> p0 = get("/admin/tasks?tool=pageToolA&page=0&size=2");
        assertThat(p0.body()).contains("\"total\":3");
        assertThat(p0.body()).contains("\"page\":0").contains("\"size\":2");
        assertThat(p0.body()).contains("t-page-3").contains("t-page-2");  // 倒序前 2
        assertThat(p0.body()).doesNotContain("t-page-1");

        HttpResponse<String> p1 = get("/admin/tasks?tool=pageToolA&page=1&size=2");
        // page1 仅 t-page-1（倒序第 3），但 total 仍 3
        assertThat(p1.body()).contains("t-page-1");
        assertThat(p1.body()).doesNotContain("t-page-3");
        assertThat(p1.body()).contains("\"total\":3");
    }

    @Test
    void emptyResultReturns200_not404() throws Exception {
        HttpResponse<String> r = get("/admin/tasks?tool=nonexistentToolXYZ");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"items\":[]");
        assertThat(r.body()).contains("\"total\":0");
    }

    @Test
    void summaryHasNoFrames_invList1() throws Exception {
        seed("t-frame", "frameTool", "tgt-f", 50, TaskState.COMPLETED);

        HttpResponse<String> r = get("/admin/tasks?tool=frameTool");
        assertThat(r.statusCode()).isEqualTo(200);
        // INV-LIST-1：摘要禁含 frames（即使 COMPLETED 任务有 frame 数据，列表也不暴露）
        assertThat(r.body()).doesNotContain("\"frames\"");
        assertThat(r.body()).doesNotContain("frame-t-frame");
    }
}
