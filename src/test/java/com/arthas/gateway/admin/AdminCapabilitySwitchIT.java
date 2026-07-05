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
 * 004 能力开关端到端 IT（T029，admin-invariants INV-SWITCH-1）。
 *
 * <p>admin.crud.enabled=false + admin.export.enabled=true：后端 CRUD 端点缺失
 * （Controller 不装配 → Spring 无映射 → 404 无错误体），任务导出端点存在
 * （未知任务 → 404 + task_not_found 错误体）。两者独立、互不影响。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "arthas-gateway.admin.crud.enabled=false",
        "arthas-gateway.admin.export.enabled=true"
})
class AdminCapabilitySwitchIT {

    private static final Path BACKENDS_FILE;

    static {
        try {
            BACKENDS_FILE = Files.createTempFile("backends-switch-it", ".yaml");
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

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void crudDisabled_backendsEndpointMissing404_invSwitch1() throws Exception {
        HttpResponse<String> r = get("/admin/backends");
        assertThat(r.statusCode()).isEqualTo(404);
        // Controller 未装配 → Spring 默认 404（无管理面错误体 reason）
        assertThat(r.body()).doesNotContain("backend_not_found");
    }

    @Test
    void exportStillEnabled_taskEndpointPresent_invSwitch1() throws Exception {
        // export 端点存在：未知任务 → 404 + task_not_found（端点在、任务不存在）
        HttpResponse<String> r = get("/admin/tasks/no-such/export");
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(r.body()).contains("task_not_found");
    }
}
