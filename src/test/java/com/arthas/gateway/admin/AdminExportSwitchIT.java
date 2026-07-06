package com.arthas.gateway.admin;

import org.junit.jupiter.api.Test;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 004 增量 任务能力开关端到端 IT（T039，admin-invariants INV-LIST-4 / INV-SWITCH-2）。
 *
 * <p>{@code admin.export.enabled=false} + {@code admin.crud.enabled=true}：
 * <b>列表与导出共用 {@code export.enabled} 开关</b>，关则 {@code GET /admin/tasks}（列表）
 * 与 {@code GET /admin/tasks/{id}/export}（导出）**同 404**（Controller 不装配 → Spring 默认 404 无错误体）；
 * 后端 CRUD 不受影响（{@code GET /admin/backends} 200）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "arthas-gateway.admin.crud.enabled=true",
        "arthas-gateway.admin.export.enabled=false"
})
class AdminExportSwitchIT {

    private static final Path BACKENDS_FILE;

    static {
        try {
            BACKENDS_FILE = Files.createTempFile("backends-export-switch-it", ".yaml");
            Files.writeString(BACKENDS_FILE, """
                    version: 1
                    backends:
                      - name: order-service
                        url: http://127.0.0.1:8571
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

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void exportDisabled_taskListEndpoint404_invList4() throws Exception {
        HttpResponse<String> r = get("/admin/tasks");
        assertThat(r.statusCode()).isEqualTo(404);  // 列表端点不装配
        assertThat(r.body()).doesNotContain("\"items\"");  // 无列表响应体
    }

    @Test
    void exportDisabled_taskExportEndpoint404_invSwitch2() throws Exception {
        HttpResponse<String> r = get("/admin/tasks/no-such/export");
        assertThat(r.statusCode()).isEqualTo(404);  // 导出端点不装配
        assertThat(r.body()).doesNotContain("task_not_found");  // Controller 未装配 → 无业务错误体
    }

    @Test
    void crudStillEnabled_backendsEndpointPresent_invSwitch2() throws Exception {
        // crud 开 → /admin/backends 200（与 export 开关独立）
        HttpResponse<String> r = get("/admin/backends");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"backends\"");
    }
}
