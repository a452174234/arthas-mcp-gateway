package com.arthas.gateway.admin.task;

import com.arthas.gateway.task.GatewayTask;
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
 * 004 任务导出契约 IT（T024，failsafe *IT）。
 *
 * <p>@SpringBootTest 启动完整网关，JDK HttpClient 调 /admin/tasks/{id}/export。注入 {@link TaskStore}
 * 植入 completed/working 任务（mock CallToolResult/TextContent 避免多参构造器），验证
 * admin-api-contract §2（A-EXP-1/2）+ admin-invariants INV-EXP-1。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "arthas-gateway.admin.crud.enabled=true",
        "arthas-gateway.admin.export.enabled=true"
})
class TaskExportContractIT {

    private static final Path BACKENDS_FILE;

    static {
        try {
            BACKENDS_FILE = Files.createTempFile("backends-export-it", ".yaml");
            Files.writeString(BACKENDS_FILE, """
                    version: 1
                    backends:
                      - name: order-service
                        url: http://127.0.0.1:8563
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

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url(path))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void seedCompleted(String taskId, String frame) {
        GatewayTask task = new GatewayTask(taskId, "watch", "order-service", Instant.now(), Instant::now);
        CallToolResult result = Mockito.mock(CallToolResult.class);
        TextContent tc = Mockito.mock(TextContent.class);
        Mockito.when(tc.text()).thenReturn(frame);
        Mockito.when(result.content()).thenReturn(List.of(tc));
        Mockito.when(result.isError()).thenReturn(false);
        task.markCompleted(result);
        store.put(task);
    }

    @Test
    void exportCompletedReturnsJsonWithFrames_aExp1_invExp1() throws Exception {
        seedCompleted("t-exp-it", "frame-data-exp-it");
        HttpResponse<String> r = get("/admin/tasks/t-exp-it/export?format=json");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("frame-data-exp-it", "COMPLETED", "t-exp-it");
        assertThat(r.headers().firstValue("Content-Disposition").orElse(""))
                .contains("attachment", "t-exp-it.json");
    }

    @Test
    void exportUnknownReturns404_aExp2() throws Exception {
        HttpResponse<String> r = get("/admin/tasks/no-such-task/export");
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(r.body()).contains("task_not_found");
    }

    @Test
    void exportWorkingReturns409_aExp2() throws Exception {
        GatewayTask task = new GatewayTask("t-working-it", "watch", "order-service", Instant.now(), Instant::now);
        store.put(task); // WORKING（未 markCompleted）
        HttpResponse<String> r = get("/admin/tasks/t-working-it/export");
        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(r.body()).contains("task_not_completed");
    }
}
