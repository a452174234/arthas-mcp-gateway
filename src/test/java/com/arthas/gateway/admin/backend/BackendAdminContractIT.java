package com.arthas.gateway.admin.backend;

import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.DynamicBackendStore;
import com.arthas.gateway.backend.Protocol;
import com.arthas.gateway.backend.Source;
import org.junit.jupiter.api.Test;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 004 后端 CRUD 管理面契约 IT（T016，failsafe *IT）。
 *
 * <p>@SpringBootTest 启动完整网关（含 /admin controller + 真实注册表），JDK {@link HttpClient} 真实 HTTP 调
 * /admin/backends。验证 admin-api-contract §1（A-LIST/ADD/UPD/DEL）+ admin-invariants（INV-DYN-1/INV-ERR-1/INV-SECRET-1）。
 *
 * <p>Spring Boot 4 移除了 TestRestTemplate，改用 JDK HttpClient（与 003 K8S IT 同范式）。
 * backends-file 指向临时文件（@DynamicPropertySource），隔离生产 config/backends.yaml。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "arthas-gateway.admin.crud.enabled=true",
        "arthas-gateway.admin.export.enabled=true"
})
class BackendAdminContractIT {

    private static final Path BACKENDS_FILE;

    static {
        try {
            BACKENDS_FILE = Files.createTempFile("backends-it", ".yaml");
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
    private DynamicBackendStore dynamicStore;

    private final HttpClient http = HttpClient.newHttpClient();

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url(path))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> json(String path, String method, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url(path)))
                .header("Content-Type", "application/json");
        switch (method) {
            case "POST" -> b.POST(HttpRequest.BodyPublishers.ofString(body));
            case "PUT" -> b.method("PUT", HttpRequest.BodyPublishers.ofString(body));
            case "DELETE" -> b.DELETE();
            default -> { }
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void registerDynamic(String name, String url, AuthMode mode, String token) {
        dynamicStore.register(new BackendConfig(name, url, Protocol.STREAMABLE,
                new BackendConfig.Auth(mode, token, null, null), 5000, 30000, 5, Source.DYNAMIC));
    }

    @Test
    void getListReturnsSeedWithSummary_aList1() throws Exception {
        HttpResponse<String> r = get("/admin/backends");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("backends", "summary", "order-service");
    }

    @Test
    void postStaticAddsAndWritesYaml_aAdd1() throws Exception {
        String json = "{\"name\":\"pay-it\",\"url\":\"http://127.0.0.1:8564\","
                + "\"protocol\":\"STREAMABLE\",\"authMode\":\"NONE\","
                + "\"connectTimeoutMs\":5000,\"callTimeoutMs\":30000,\"maxConcurrentTasks\":3}";
        HttpResponse<String> r = json("/admin/backends", "POST", json);
        assertThat(r.statusCode()).isEqualTo(201);
        assertThat(Files.readString(BACKENDS_FILE)).contains("pay-it");
    }

    @Test
    void putDynamicRejected400_invDyn1() throws Exception {
        registerDynamic("dyn-it", "http://127.0.0.1:8565", AuthMode.NONE, null);
        HttpResponse<String> r = json("/admin/backends/dyn-it", "PUT", "{\"url\":\"http://new\"}");
        assertThat(r.statusCode()).isEqualTo(400);
        assertThat(r.body()).contains("dynamic_backend_not_editable");
    }

    @Test
    void deleteDynamicCallsUnregister() throws Exception {
        registerDynamic("dyn-del", "http://127.0.0.1:8567", AuthMode.NONE, null);
        HttpResponse<String> r = json("/admin/backends/dyn-del", "DELETE", "");
        assertThat(r.statusCode()).isEqualTo(204);
    }

    @Test
    void getUnknownReturns404WithAvailable_invErr1() throws Exception {
        HttpResponse<String> r = get("/admin/backends/no-such");
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(r.body()).contains("available");
    }

    @Test
    void authNeverExposed_invSecret1() throws Exception {
        registerDynamic("secret-it", "http://127.0.0.1:8568", AuthMode.BEARER, "super-secret-token-it");
        HttpResponse<String> r = get("/admin/backends");
        assertThat(r.body()).doesNotContain("super-secret-token-it");
    }
}
