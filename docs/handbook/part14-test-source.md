# Part 14 · 测试源码完整摘录（src/test/java）

> 本附录摘录全部测试代码（契约 IT + 单测 + 夹具），作为测试体系的代码级参考。


---

## com/arthas/gateway/admin/AdminCapabilitySwitchIT.java

**文件**：`src/test/java/com/arthas/gateway/admin/AdminCapabilitySwitchIT.java`

```java
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

    @Test
    void exportStillEnabled_taskListEndpointPresent_invList4() throws Exception {
        // 004 增量（INV-LIST-4 反向）：export 开 → GET /admin/tasks 列表端点也装配（200）
        HttpResponse<String> r = get("/admin/tasks");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"items\"");
    }
}
```


---

## com/arthas/gateway/admin/AdminCapabilitySwitchTest.java

**文件**：`src/test/java/com/arthas/gateway/admin/AdminCapabilitySwitchTest.java`

```java
package com.arthas.gateway.admin;

import com.arthas.gateway.admin.backend.BackendCrudAutoConfig;
import com.arthas.gateway.admin.task.TaskExportAutoConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 004 管理面能力开关契约（admin-invariants INV-SWITCH-1/2，T005）。
 *
 * <p>验证 {@code @ConditionalOnProperty}：后端 CRUD 与任务导出各自独立装配，
 * 默认开（matchIfMissing=true）、关闭则配置类不装配（对应 /admin 端点 404、前端降级）。
 * 用 {@link ApplicationContextRunner} 轻量评估条件（不启完整 Spring 上下文）。
 *
 * <p>Phase 3/4 在 AutoConfig 内注册 Controller @Bean 后，关闭开关 → Controller 不在 → 端点 404。
 */
class AdminCapabilitySwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner();

    @Test
    void crudSwitchDefaultEnabled_loadsBackendCrudAutoConfig() {
        runner.withUserConfiguration(BackendCrudAutoConfig.class)
                .run(ctx -> assertThat(ctx).hasSingleBean(BackendCrudAutoConfig.class));
    }

    @Test
    void crudSwitchDisabled_skipsBackendCrudAutoConfig() {
        runner.withUserConfiguration(BackendCrudAutoConfig.class)
                .withPropertyValues("arthas-gateway.admin.crud.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(BackendCrudAutoConfig.class));
    }

    @Test
    void exportSwitchDefaultEnabled_loadsTaskExportAutoConfig() {
        runner.withUserConfiguration(TaskExportAutoConfig.class)
                .run(ctx -> assertThat(ctx).hasSingleBean(TaskExportAutoConfig.class));
    }

    @Test
    void exportSwitchDisabled_skipsTaskExportAutoConfig() {
        runner.withUserConfiguration(TaskExportAutoConfig.class)
                .withPropertyValues("arthas-gateway.admin.export.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(TaskExportAutoConfig.class));
    }

    @Test
    void crudAndExportSwitchesIndependent() {
        // INV-SWITCH-1：关 crud、开 export → crud 不装配、export 装配
        runner.withUserConfiguration(BackendCrudAutoConfig.class, TaskExportAutoConfig.class)
                .withPropertyValues("arthas-gateway.admin.crud.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(BackendCrudAutoConfig.class);
                    assertThat(ctx).hasSingleBean(TaskExportAutoConfig.class);
                });
    }

    @Test
    void bothSwitchesDisabled_skipsBoth() {
        runner.withUserConfiguration(BackendCrudAutoConfig.class, TaskExportAutoConfig.class)
                .withPropertyValues(
                        "arthas-gateway.admin.crud.enabled=false",
                        "arthas-gateway.admin.export.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(BackendCrudAutoConfig.class);
                    assertThat(ctx).doesNotHaveBean(TaskExportAutoConfig.class);
                });
    }
}
```


---

## com/arthas/gateway/admin/AdminExportSwitchIT.java

**文件**：`src/test/java/com/arthas/gateway/admin/AdminExportSwitchIT.java`

```java
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
```


---

## com/arthas/gateway/admin/backend/BackendAdminContractIT.java

**文件**：`src/test/java/com/arthas/gateway/admin/backend/BackendAdminContractIT.java`

```java
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
```


---

## com/arthas/gateway/admin/backend/BackendAdminServiceTest.java

**文件**：`src/test/java/com/arthas/gateway/admin/backend/BackendAdminServiceTest.java`

```java
package com.arthas.gateway.admin.backend;

import com.arthas.gateway.admin.backend.dto.BackendDto;
import com.arthas.gateway.admin.backend.dto.CreateBackendRequest;
import com.arthas.gateway.admin.backend.dto.UpdateBackendRequest;
import com.arthas.gateway.admin.backend.exception.BackendConflictException;
import com.arthas.gateway.admin.backend.exception.BackendNotFoundException;
import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.BackendConfigLoader;
import com.arthas.gateway.backend.BackendEntry;
import com.arthas.gateway.backend.BackendRegistry;
import com.arthas.gateway.backend.BackendState;
import com.arthas.gateway.backend.CircuitBreaker;
import com.arthas.gateway.backend.DynamicBackendStore;
import com.arthas.gateway.backend.Protocol;
import com.arthas.gateway.backend.RegistryHolder;
import com.arthas.gateway.backend.Source;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 004 BackendAdminService 单元测试（T013）：CRUD 编排 + 动态语义（INV-DYN-1）+ version 递增触发热重载。
 *
 * <p>mock RegistryHolder/DynamicBackendStore/BackendsYamlWriter；真实 BackendConfigLoader + temp backends.yaml。
 * 注意：BackendEntry stubbing 必须在 thenReturn 参数求值<b>之前</b>完成（避免 Mockito 嵌套 stubbing）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BackendAdminServiceTest {

    @Mock
    private RegistryHolder registryHolder;
    @Mock
    private DynamicBackendStore dynamicStore;
    @Mock
    private BackendsYamlWriter yamlWriter;

    @TempDir
    Path tmp;

    private BackendAdminService service;
    private Path backendsFile;

    @BeforeEach
    void setUp() throws IOException {
        backendsFile = tmp.resolve("backends.yaml");
        Files.writeString(backendsFile, """
                version: 1
                backends:
                  - name: seed
                    url: http://h:8563
                    protocol: STREAMABLE
                    auth: { mode: NONE }
                    connectTimeoutMs: 5000
                    callTimeoutMs: 30000
                    maxConcurrentTasks: 5
                """);
        service = new BackendAdminService(registryHolder, dynamicStore, yamlWriter,
                new BackendConfigLoader(), backendsFile);
    }

    @Test
    void list_mapsEntriesToDtosWithRuntimeState() {
        BackendEntry seed = entry("seed", Source.STATIC, true, "CLOSED");
        BackendEntry dyn = entry("dyn", Source.DYNAMIC, false, "OPEN");
        BackendRegistry reg = registry(seed, dyn);
        when(registryHolder.current()).thenReturn(reg);

        List<BackendDto> dtos = service.list();
        assertThat(dtos).hasSize(2);
        assertThat(dtos).extracting(BackendDto::name).containsExactlyInAnyOrder("seed", "dyn");
        assertThat(dtos).extracting(BackendDto::source).contains("STATIC", "DYNAMIC");
    }

    @Test
    void create_staticWritesYamlWithIncrementedVersion_aAdd1() throws IOException {
        BackendEntry seed = entry("seed", Source.STATIC, true, "CLOSED");
        BackendRegistry reg = registry(seed);
        when(registryHolder.current()).thenReturn(reg);

        BackendDto dto = service.create(new CreateBackendRequest(
                "new", "http://h:8564", null, null, null, null, null, null, null, null));

        assertThat(dto.name()).isEqualTo("new");
        assertThat(dto.source()).isEqualTo("STATIC");
        ArgumentCaptor<Long> version = ArgumentCaptor.forClass(Long.class);
        verify(yamlWriter).write(eq(backendsFile), version.capture(), anyList());
        assertThat(version.getValue()).isEqualTo(2L);
    }

    @Test
    void create_duplicateNameRejected() throws IOException {
        BackendEntry seed = entry("seed", Source.STATIC, true, "CLOSED");
        BackendRegistry reg = registry(seed);
        when(registryHolder.current()).thenReturn(reg);

        assertThatThrownBy(() -> service.create(
                new CreateBackendRequest("seed", "http://h:8563", null, null, null, null, null, null, null, null)))
                .isInstanceOf(BackendConflictException.class)
                .hasMessageContaining("name 已存在");
        verify(yamlWriter, never()).write(any(), anyLong(), any());
    }

    @Test
    void create_missingNameRejected() {
        when(registryHolder.current()).thenReturn(BackendRegistry.empty());
        assertThatThrownBy(() -> service.create(
                new CreateBackendRequest(null, "http://h:8563", null, null, null, null, null, null, null, null)))
                .isInstanceOf(BackendConflictException.class);
    }

    @Test
    void update_dynamicRejected_invDyn1() {
        BackendEntry dyn = entry("dyn", Source.DYNAMIC, false, "OPEN");
        when(registryHolder.get("dyn")).thenReturn(Optional.of(dyn));

        assertThatThrownBy(() -> service.update("dyn",
                new UpdateBackendRequest("http://new", null, null, null, null, null, null, null)))
                .isInstanceOf(BackendConflictException.class)
                .hasFieldOrPropertyWithValue("reason", "dynamic_backend_not_editable");
        verifyNoInteractions(yamlWriter);
    }

    @Test
    void update_staticWritesYamlWithIncrementedVersion() throws IOException {
        BackendEntry seed = entry("seed", Source.STATIC, true, "CLOSED");
        when(registryHolder.get("seed")).thenReturn(Optional.of(seed));

        BackendDto dto = service.update("seed",
                new UpdateBackendRequest("http://h:9999", null, null, null, null, null, null, null));

        assertThat(dto.url()).isEqualTo("http://h:9999");
        verify(yamlWriter).write(eq(backendsFile), eq(2L), anyList());
    }

    @Test
    void delete_dynamicCallsUnregister() {
        BackendEntry dyn = entry("dyn", Source.DYNAMIC, false, "OPEN");
        when(registryHolder.get("dyn")).thenReturn(Optional.of(dyn));

        service.delete("dyn");

        verify(dynamicStore).unregister("dyn");
        verifyNoInteractions(yamlWriter);
    }

    @Test
    void delete_staticWritesYamlWithoutTarget() throws IOException {
        BackendEntry seed = entry("seed", Source.STATIC, true, "CLOSED");
        when(registryHolder.get("seed")).thenReturn(Optional.of(seed));

        service.delete("seed");

        ArgumentCaptor<List<BackendConfig>> backends = ArgumentCaptor.forClass(List.class);
        verify(yamlWriter).write(eq(backendsFile), eq(2L), backends.capture());
        assertThat(backends.getValue()).isEmpty();
    }

    @Test
    void get_unknownThrowsNotFoundWithAvailable() {
        BackendEntry seed = entry("seed", Source.STATIC, true, "CLOSED");
        BackendRegistry reg = registry(seed);
        when(registryHolder.current()).thenReturn(reg);
        when(registryHolder.get("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("nope"))
                .isInstanceOf(BackendNotFoundException.class);
    }

    // ===== helpers =====

    private static BackendRegistry registry(BackendEntry... entries) {
        Map<String, BackendEntry> byName = new LinkedHashMap<>();
        for (BackendEntry e : entries) {
            byName.put(e.config().name(), e);
        }
        return new BackendRegistry(1L, byName);
    }

    /** mock BackendEntry（Mockito inline 支持 final class）+ 其 config/state/healthy/breaker。 */
    private static BackendEntry entry(String name, Source source, boolean healthy, String breakerState) {
        BackendConfig cfg = new BackendConfig(name, "http://h:8563", Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null), 5000, 30000, 5, source);
        BackendEntry e = mock(BackendEntry.class);
        when(e.config()).thenReturn(cfg);
        when(e.state()).thenReturn(BackendState.ACTIVE);
        when(e.isHealthy()).thenReturn(healthy);
        CircuitBreaker breaker = mock(CircuitBreaker.class);
        when(breaker.state()).thenReturn(CircuitBreaker.State.valueOf(breakerState));
        when(e.breaker()).thenReturn(breaker);
        return e;
    }
}
```


---

## com/arthas/gateway/admin/backend/BackendsYamlWriterTest.java

**文件**：`src/test/java/com/arthas/gateway/admin/backend/BackendsYamlWriterTest.java`

```java
package com.arthas.gateway.admin.backend;

import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.BackendConfigLoader;
import com.arthas.gateway.backend.BackendConfigLoader.LoadedBackends;
import com.arthas.gateway.backend.Protocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 004 BackendsYamlWriter 契约（T011）：写回 round-trip + 不保留注释（R2）+ auth 保留。
 */
class BackendsYamlWriterTest {

    @TempDir
    Path tmp;

    @Test
    void writeRoundTripsThroughLoader_invFile1() throws IOException {
        Path file = tmp.resolve("backends.yaml");
        BackendConfig cfg = new BackendConfig("svc", "http://h:8563",
                Protocol.STREAMABLE, new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5);
        new BackendsYamlWriter().write(file, 2L, List.of(cfg));

        try (InputStream in = Files.newInputStream(file)) {
            LoadedBackends loaded = new BackendConfigLoader().load(in);
            assertThat(loaded.version()).isEqualTo(2L);
            assertThat(loaded.backends()).hasSize(1);
            BackendConfig rt = loaded.backends().get(0);
            assertThat(rt.name()).isEqualTo("svc");
            assertThat(rt.url()).isEqualTo("http://h:8563");
            assertThat(rt.auth().mode()).isEqualTo(AuthMode.NONE);
        }
    }

    @Test
    void writeDoesNotPreserveComments_r2() throws IOException {
        Path file = tmp.resolve("backends.yaml");
        Files.writeString(file, "# 顶部注释\nversion: 1\nbackends: []\n# 尾注释\n");
        new BackendsYamlWriter().write(file, 2L, List.of());

        String content = Files.readString(file);
        assertThat(content).as("R2：SnakeYAML dump 不保留原文注释").doesNotContain("# 顶部注释", "# 尾注释");
        assertThat(content).contains("version: 2");
    }

    @Test
    void writeBearerAuthPreserved() throws IOException {
        Path file = tmp.resolve("backends.yaml");
        BackendConfig cfg = new BackendConfig("pay", "http://h:8564",
                Protocol.STREAMABLE, new BackendConfig.Auth(AuthMode.BEARER, "tok", null, null),
                5000, 30000, 3);
        new BackendsYamlWriter().write(file, 1L, List.of(cfg));

        try (InputStream in = Files.newInputStream(file)) {
            LoadedBackends loaded = new BackendConfigLoader().load(in);
            assertThat(loaded.backends().get(0).auth().mode()).isEqualTo(AuthMode.BEARER);
            assertThat(loaded.backends().get(0).auth().token()).isEqualTo("tok");
            assertThat(loaded.backends().get(0).maxConcurrentTasks()).isEqualTo(3);
        }
    }
}
```


---

## com/arthas/gateway/admin/backend/dto/BackendDtoTest.java

**文件**：`src/test/java/com/arthas/gateway/admin/backend/dto/BackendDtoTest.java`

```java
package com.arthas.gateway.admin.backend.dto;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 004 BackendDto 契约（T009）：字段全集 + 凭据脱敏（admin-invariants INV-SECRET-1）。
 */
class BackendDtoTest {

    @Test
    void dtoCarriesAllProjectionFields() {
        BackendDto dto = new BackendDto(
                "order-service", "STATIC", "ACTIVE", true, "CLOSED",
                "http://10.0.0.10:8563", "STREAMABLE", "BEARER",
                5000, 30000, 5);

        assertThat(dto.name()).isEqualTo("order-service");
        assertThat(dto.source()).isEqualTo("STATIC");
        assertThat(dto.state()).isEqualTo("ACTIVE");
        assertThat(dto.healthy()).isTrue();
        assertThat(dto.breaker()).isEqualTo("CLOSED");
        assertThat(dto.url()).isEqualTo("http://10.0.0.10:8563");
        assertThat(dto.protocol()).isEqualTo("STREAMABLE");
        assertThat(dto.authMode()).isEqualTo("BEARER");
        assertThat(dto.connectTimeoutMs()).isEqualTo(5000);
        assertThat(dto.callTimeoutMs()).isEqualTo(30000);
        assertThat(dto.maxConcurrentTasks()).isEqualTo(5);
    }

    @Test
    void dtoNeverExposesSecrets_invSecret1() {
        // INV-SECRET-1：BackendDto record 组件不含 token/username/password（凭据脱敏，仅 authMode）
        var componentNames = Arrays.stream(BackendDto.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertThat(componentNames)
                .as("BackendDto 不得含机密字段（仅 authMode）")
                .doesNotContain("token", "username", "password")
                .contains("authMode");
    }

    @Test
    void dynamicBackendAndOpenBreakerProjected() {
        BackendDto dynamic = new BackendDto(
                "debian-demo-business", "DYNAMIC", "ACTIVE", false, "OPEN",
                "http://192.168.31.92:32017", "STREAMABLE", "BEARER",
                5000, 30000, 5);
        assertThat(dynamic.source()).isEqualTo("DYNAMIC");
        assertThat(dynamic.healthy()).isFalse();
        assertThat(dynamic.breaker()).isEqualTo("OPEN");
    }
}
```


---

## com/arthas/gateway/admin/task/dto/TaskExportDtoTest.java

**文件**：`src/test/java/com/arthas/gateway/admin/task/dto/TaskExportDtoTest.java`

```java
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
```


---

## com/arthas/gateway/admin/task/dto/TaskSummaryDtoTest.java

**文件**：`src/test/java/com/arthas/gateway/admin/task/dto/TaskSummaryDtoTest.java`

```java
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
```


---

## com/arthas/gateway/admin/task/TaskExportContractIT.java

**文件**：`src/test/java/com/arthas/gateway/admin/task/TaskExportContractIT.java`

```java
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
```


---

## com/arthas/gateway/admin/task/TaskExportServiceTest.java

**文件**：`src/test/java/com/arthas/gateway/admin/task/TaskExportServiceTest.java`

```java
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
```


---

## com/arthas/gateway/admin/task/TaskListContractIT.java

**文件**：`src/test/java/com/arthas/gateway/admin/task/TaskListContractIT.java`

```java
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
```


---

## com/arthas/gateway/admin/task/TaskListServiceTest.java

**文件**：`src/test/java/com/arthas/gateway/admin/task/TaskListServiceTest.java`

```java
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
```


---

## com/arthas/gateway/architecture/PackageBoundaryTest.java

**文件**：`src/test/java/com/arthas/gateway/architecture/PackageBoundaryTest.java`

```java
package com.arthas.gateway.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * T030 包级边界守护（003 特性，research.md R1/R6：gateway-core 诊断核心<b>零 K8S 感知</b>）。
 *
 * <p>宪法原则二 + R6 内聚性纪律：K8S 编排是<b>独立编排面</b>，仅 {@code orchestration} 包 +
 * {@code config} 组合根可触及 K8S；诊断核心（backend / handler / tool / task / auth / obs）<b>不得</b>
 * 依赖编排包或 K8S 客户端 API。3 个编排工具的 handler 自带闭包、<b>不经 {@code ToolsCallRouter}</b>
 * （路由器零 K8S 分支），故 {@code tools/list}=38 与 kubeconfig 是否存在无关（回归守护 T029）。
 *
 * <p>本测试为<b>纯逻辑 surefire</b>（CI 可跑，无 K8S 依赖）：ArchUnit 静态扫描 {@code com.arthas.gateway..}
 * 主代码字节码，断言诊断核心包的依赖方向。{@code config} 包（组合根，装配 orchestration bean）刻意<b>不在</b>
 * 禁止范围——边界只锁诊断核心（见 {@code K8sOrchestrationConfig} 类注释）。
 *
 * <p>防止未来回归：任何在诊断核心包内引入 {@code orchestration} 或 {@code io.fabric8} 的提交都会被本测试拦截。
 */
class PackageBoundaryTest {

    /** gateway-core 诊断核心包（K8S 编排隔离边界内的"洁浄区"）。 */
    private static final String[] DIAGNOSTIC_CORE = {
            "com.arthas.gateway.backend..",
            "com.arthas.gateway.handler..",
            "com.arthas.gateway.tool..",
            "com.arthas.gateway.task..",
            "com.arthas.gateway.auth..",
            "com.arthas.gateway.obs.."
    };

    private static JavaClasses classes;

    @BeforeAll
    static void importGatewayClasses() {
        // 仅导入 com.arthas.gateway.. 主代码（排除测试类），分析其依赖方向；不导入外部 jar
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.arthas.gateway..");
    }

    /** 诊断核心包不得依赖 orchestration 包（K8S 编排隔离，R1/R6）。ArchUnit 否定式：noClasses()...should().dependOn()。 */
    @Test
    void diagnosticCoreDoesNotDependOnOrchestration() {
        noClasses()
                .that().resideInAnyPackage(DIAGNOSTIC_CORE)
                .should().dependOnClassesThat().resideInAPackage("com.arthas.gateway.orchestration..")
                .because("gateway-core 诊断核心零 K8S 依赖（包级边界，R1/R6）；"
                        + "K8S 编排仅 orchestration 包 + config 组合根可触及")
                .check(classes);
    }

    /** 诊断核心包不得直接依赖 fabric8/kubernetes-client API（K8S 客户端仅 orchestration 包使用）。 */
    @Test
    void diagnosticCoreDoesNotDependOnK8sClientApi() {
        noClasses()
                .that().resideInAnyPackage(DIAGNOSTIC_CORE)
                .should().dependOnClassesThat().resideInAnyPackage("io.fabric8..", "io.kubernetes..")
                .because("fabric8/kubernetes-client API 仅 orchestration 包使用（R6 内聚性纪律）；"
                        + "诊断核心经既有 MCP 路由管线，不直接操 K8S")
                .check(classes);
    }

    /**
     * 004 增量：诊断核心不得依赖管理面（admin 包，admin-invariants INV-ISOL-1）。
     *
     * <p>admin 管理面消费 backend（CRUD/导出），反向依赖禁止——诊断核心（/mcp）不被管理面（/admin）污染，
     * 保证管理面操作不影响诊断面（回归守护 SC-004）。
     */
    @Test
    void diagnosticCoreDoesNotDependOnAdmin() {
        noClasses()
                .that().resideInAnyPackage(DIAGNOSTIC_CORE)
                .should().dependOnClassesThat().resideInAPackage("com.arthas.gateway.admin..")
                .because("诊断核心（/mcp）与管理面（/admin）隔离（004 INV-ISOL-1/SC-004）；"
                        + "admin 消费 backend，反向依赖禁止")
                .check(classes);
    }
}
```

> **005 扩展（+2 ArchUnit 规则，INV-BOUNDARY-1/2）**：005 K8S 编排迭代为本测试新增 2 条规则（并补
> `import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes`），锁定 `BackendResolver` 接口倒置分离——
> 接口驻 gateway-core `backend` 包（零 fabric8 依赖），实现 `K8sBackendResolver` 驻 `orchestration` 包（持 fabric8/
> ArthasProvisioner），确保 K8S 懒 resolve 实现不漂入 gateway-core 破零 K8S 依赖。新增规则源码：
>
> ```java
>     /**
>      * 005 US2 INV-BOUNDARY-1：{@code BackendResolver} 接口 gateway-core 定义（backend 包，零 fabric8/orchestration 依赖）。
>      *
>      * <p>接口倒置——诊断核心依赖 backend.BackendResolver（零 K8S），实现在 orchestration（K8sBackendResolver）。
>      * 被 {@link #diagnosticCoreDoesNotDependOnK8sClientApi}（backend 包零 fabric8）覆盖，本规则显式锁定接口位置。
>      */
>     @Test
>     void backendResolverInterfaceResidesInBackendPackage() {
>         classes().that().haveSimpleName("BackendResolver")
>                 .should().resideInAPackage("com.arthas.gateway.backend")
>                 .because("005 INV-BOUNDARY-1: BackendResolver 接口 gateway-core 定义（backend 包，零 fabric8）；"
>                         + "实现在 orchestration（K8sBackendResolver），ArchUnit 锁定接口位置防漂移")
>                 .check(classes);
>     }
>
>     /**
>      * 005 US2 INV-BOUNDARY-2：{@code K8sBackendResolver} 实现驻 orchestration 包（依赖 fabric8/ArthasProvisioner）。
>      *
>      * <p>确保 K8S 懒 resolve 实现（持编排依赖）不误放 gateway-core；与 BackendResolver 接口（backend 包）的倒置分离。
>      */
>     @Test
>     void k8sBackendResolverResidesInOrchestration() {
>         classes().that().haveSimpleName("K8sBackendResolver")
>                 .should().resideInAPackage("com.arthas.gateway.orchestration")
>                 .because("005 INV-BOUNDARY-2: K8sBackendResolver 实现在 orchestration 包（依赖 fabric8/"
>                         + "ArthasProvisioner），不漂入 gateway-core 破零 K8S 依赖")
>                 .check(classes);
>     }
> ```


---

## com/arthas/gateway/auth/BackendAuthCustomizerTest.java

**文件**：`src/test/java/com/arthas/gateway/auth/BackendAuthCustomizerTest.java`

```java
package com.arthas.gateway.auth;

import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BackendAuthCustomizer 测试（T023）。
 *
 * <p>覆盖 BEARER/BASIC/NONE 三种 Authorization 头的注入（data-model.md §2 auth；
 * 认证头语义见 {@code reference/arthas-docs/03-MCP/后端接入契约.md}）：
 * <ul>
 *   <li>NONE：不发 Authorization 头</li>
 *   <li>BEARER：{@code Authorization: Bearer <token>}</li>
 *   <li>BASIC：{@code Authorization: Basic <base64(user:pass)>}</li>
 * </ul>
 * 经官方 SDK {@code McpSyncHttpClientRequestCustomizer}（非已弃用 customizeRequest）注入。
 * 纯逻辑单测（surefire）——直接驱动 customize() 应用到 {@link HttpRequest.Builder}，无需真实后端。
 */
class BackendAuthCustomizerTest {

    private static BackendConfig.Auth auth(AuthMode mode, String token, String user, String pass) {
        return new BackendConfig.Auth(mode, token, user, pass);
    }

    /** 应用 customizer 后取 Authorization 头值（缺失返 null）。 */
    private static String appliedAuthorizationHeader(BackendConfig.Auth auth) {
        URI uri = URI.create("http://127.0.0.1:8563/mcp");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri);
        new BackendAuthCustomizer(auth).customize(builder, "POST", uri, "{}", null);
        return builder.POST(HttpRequest.BodyPublishers.noBody()).build()
                .headers().firstValue("Authorization").orElse(null);
    }

    @Test
    void noneAuthAddsNoAuthorizationHeader() {
        assertThat(appliedAuthorizationHeader(auth(AuthMode.NONE, null, null, null)))
                .as("NONE 不发 Authorization 头").isNull();
    }

    @Test
    void bearerAuthAddsBearerHeader() {
        assertThat(appliedAuthorizationHeader(auth(AuthMode.BEARER, "tok-123", null, null)))
                .isEqualTo("Bearer tok-123");
    }

    @Test
    void basicAuthAddsBase64EncodedUserPassword() {
        String header = appliedAuthorizationHeader(auth(AuthMode.BASIC, null, "ops", "p@ss"));
        assertThat(header).as("BASIC 头前缀").startsWith("Basic ");
        String decoded = new String(
                Base64.getDecoder().decode(header.substring("Basic ".length())), StandardCharsets.UTF_8);
        assertThat(decoded).as("解码后为 user:pass").isEqualTo("ops:p@ss");
    }

    @Test
    void headerValuePureFunctionIsDeterministic() {
        assertThat(BackendAuthCustomizer.headerValue(auth(AuthMode.BEARER, "xyz", null, null)))
                .isEqualTo("Bearer xyz");
        assertThat(BackendAuthCustomizer.headerValue(auth(AuthMode.NONE, null, null, null)))
                .as("NONE 头值为 null").isNull();
    }

    @Test
    void customizerOnlySetsAuthorizationHeader() {
        // 预置一个无关头，确认 customizer 仅加 Authorization、不破坏既有头
        URI uri = URI.create("http://127.0.0.1:8563/mcp");
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).header("X-Trace", "1");
        new BackendAuthCustomizer(auth(AuthMode.BEARER, "t", null, null))
                .customize(builder, "POST", uri, "{}", null);
        HttpRequest request = builder.POST(HttpRequest.BodyPublishers.noBody()).build();
        assertThat(request.headers().firstValue("Authorization")).contains("Bearer t");
        assertThat(request.headers().firstValue("X-Trace")).as("既有头保留").contains("1");
    }
}
```


---

## com/arthas/gateway/auth/NoopGatewayAuthenticatorTest.java

**文件**：`src/test/java/com/arthas/gateway/auth/NoopGatewayAuthenticatorTest.java`

```java
package com.arthas.gateway.auth;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T055 Noop 认证器单测（surefire，纯逻辑）——锚定 MVP 语义：受控内网恒放行。
 *
 * <p>演进时新增真实认证器（如 Bearer）替换此 Noop bean，接入点不变。
 */
class NoopGatewayAuthenticatorTest {

    @Test
    void alwaysAllows_anyHeaders_mvpTrustedIntranet() {
        GatewayAuthenticator auth = new NoopGatewayAuthenticator();
        assertThat(auth.authenticate(Map.of())).isTrue();
        assertThat(auth.authenticate(Map.of("Authorization", "Bearer anything"))).isTrue();
        assertThat(auth.authenticate(Map.of("X-Custom", "x"))).isTrue();
    }
}
```


---

## com/arthas/gateway/backend/BackendConfigAuthMaskingTest.java

**文件**：`src/test/java/com/arthas/gateway/backend/BackendConfigAuthMaskingTest.java`

```java
package com.arthas.gateway.backend;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T021 {@link BackendConfig.Auth} 凭据脱敏单测(002 整改 · P3-1/FR-011,US5)。
 *
 * <p>验证各 {@link AuthMode} 下 {@code Auth.toString()} <b>不含明文</b> token/username/password——
 * 仅 mode + 掩码({@code ****XX},末 2 位)。修复前 record 默认 toString 泄漏全部明文凭据(日志/异常栈中可见)。
 */
class BackendConfigAuthMaskingTest {

    @Test
    void bearerTokenIsMasked() {
        BackendConfig.Auth auth = new BackendConfig.Auth(AuthMode.BEARER, "super-secret-token", null, null);
        String s = auth.toString();
        assertThat(s).contains("BEARER");
        assertThat(s).as("不含明文 token").doesNotContain("super-secret-token");
        assertThat(s).as("含掩码前缀").contains("****");
        assertThat(s).as("含末 2 位定位").contains("en"); // "super-secret-tok**en**"
    }

    @Test
    void basicCredentialsAreMasked() {
        BackendConfig.Auth auth = new BackendConfig.Auth(AuthMode.BASIC, null, "admin-user", "p@ssw0rd!");
        String s = auth.toString();
        assertThat(s).contains("BASIC");
        assertThat(s).as("不含明文 username").doesNotContain("admin-user");
        assertThat(s).as("不含明文 password").doesNotContain("p@ssw0rd!");
        assertThat(s).as("username/password 均掩码").contains("****");
    }

    @Test
    void noneModeHasNoCredentialFields() {
        BackendConfig.Auth auth = new BackendConfig.Auth(AuthMode.NONE, null, null, null);
        String s = auth.toString();
        assertThat(s).contains("NONE");
        assertThat(s).as("NONE 不暴露凭据字段").doesNotContain("token").doesNotContain("password");
    }

    @Test
    void shortCredentialStillMaskedWithoutLeak() {
        // 短凭据(<=2 位)不泄露任何明文片段
        BackendConfig.Auth auth = new BackendConfig.Auth(AuthMode.BEARER, "ab", null, null);
        String s = auth.toString();
        assertThat(s).doesNotContain("ab");
        assertThat(s).contains("****");
    }
}
```


---

## com/arthas/gateway/backend/BackendConfigLoaderParsingTest.java

**文件**：`src/test/java/com/arthas/gateway/backend/BackendConfigLoaderParsingTest.java`

```java
package com.arthas.gateway.backend;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T022 {@link BackendConfigLoader} 严格数值解析单测(002 整改 · P3-4/FR-014,US5)。
 *
 * <p>验证 {@code asInt}/{@code readVersion} <b>拒浮点/超界</b>且错误信息含原始值(便于定位配置错误),
 * 合法整数通过。修复前 {@code instanceof Number} 静默截断 {@code 5.0→5}、超 int 的 Long 截断为负数/错值,
 * 掩盖配置错误。
 *
 * <p>用真实 {@link BackendConfigLoader}(真实 SnakeYAML 解析)读 YAML 字节流,非桩。
 */
class BackendConfigLoaderParsingTest {

    private static BackendConfigLoader loader() {
        return new BackendConfigLoader(s -> null);
    }

    private static void load(String yaml) {
        loader().load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void floatMaxConcurrentTasksRejectedWithOriginalValue() {
        // SnakeYAML 解析 5.0 为 Double → asInt 拒绝(修复前 instanceof Number 截断为 5,掩盖错误)
        assertThatThrownBy(() -> load(yaml("maxConcurrentTasks: 5.0")))
                .isInstanceOf(BackendConfigException.class)
                .hasMessageContaining("maxConcurrentTasks")
                .hasMessageContaining("5.0");
    }

    @Test
    void outOfRangeLongMaxConcurrentTasksRejectedWithOriginalValue() {
        // 2147483648 > Integer.MAX_VALUE(2147483647)→ Long 超界 → 拒(修复前 n.intValue() 截断为 -2147483648)
        assertThatThrownBy(() -> load(yaml("maxConcurrentTasks: 2147483648")))
                .isInstanceOf(BackendConfigException.class)
                .hasMessageContaining("2147483648");
    }

    @Test
    void floatVersionRejectedWithOriginalValue() {
        // version: 1.0 → Double → readVersion 拒(修复前 n.longValue() → 1L,掩盖非整数版本号)
        assertThatThrownBy(() -> load("version: 1.0\nbackends: []\n"))
                .isInstanceOf(BackendConfigException.class)
                .hasMessageContaining("version")
                .hasMessageContaining("1.0");
    }

    @Test
    void validIntegerConfigLoads() {
        // 合法整数:version=1、maxConcurrentTasks=5(均在 int 范围)→ 正常加载 1 个后端
        BackendConfigLoader.LoadedBackends loaded = loader().load(
                new ByteArrayInputStream(yaml("maxConcurrentTasks: 5").getBytes(StandardCharsets.UTF_8)));
        assertThat(loaded.version()).isEqualTo(1L);
        assertThat(loaded.backends()).hasSize(1);
        assertThat(loaded.backends().get(0).maxConcurrentTasks()).isEqualTo(5);
    }

    /** 构造含单后端、可指定 maxConcurrentTasks 原始文本的最小合法 YAML(version=1)。 */
    private static String yaml(String maxConcurrentTasksLine) {
        return "version: 1\n"
                + "backends:\n"
                + "  - name: order\n"
                + "    url: http://localhost:8563\n"
                + "    protocol: STREAMABLE\n"
                + "    auth:\n"
                + "      mode: NONE\n"
                + "    " + maxConcurrentTasksLine + "\n";
    }
}
```


---

## com/arthas/gateway/backend/BackendConfigLoaderTest.java

**文件**：`src/test/java/com/arthas/gateway/backend/BackendConfigLoaderTest.java`

```java
package com.arthas.gateway.backend;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BackendConfig + BackendConfigLoader 契约测试（T021）。
 *
 * <p>纯逻辑单测（surefire，无需真实 arthas）。覆盖 data-model.md §2/§11 的解析与校验：
 * <ul>
 *   <li>解析多后端：字段、顺序、version 原样读取</li>
 *   <li>默认值：省略 protocol/connectTimeoutMs/callTimeoutMs/maxConcurrentTasks 时取 STREAMABLE/5000/30000/5</li>
 *   <li>${ENV:default} 占位解析（token 等机密字段从环境变量取，避免明文入库）</li>
 *   <li>校验失败（重名/非法 URL/非 http scheme/auth 与 mode 不对应/未知枚举/越界/缺 version）→ {@link BackendConfigException}，
 *       以便热重载调用方捕获后<b>保留旧注册表</b>、不半替换（data-model.md §11 规则 7）</li>
 * </ul>
 *
 * <p>TDD：先于实现编写，预期编译失败（BackendConfig/Loader/Exception/LodedBackends 尚不存在）。
 */
class BackendConfigLoaderTest {

    /** 默认 loader（环境变量解析，测试不依赖具体环境变量值）。 */
    private static BackendConfigLoader loader() {
        return new BackendConfigLoader();
    }

    /** 构造测试用 YAML 输入流。 */
    private static InputStream yaml(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    // ===== 解析（绿） =====

    @Test
    void loadsMultipleBackendsWithFieldsVersionAndOrder() {
        BackendConfigLoader.LoadedBackends loaded = loader().load(yaml("""
                version: 2
                backends:
                  - name: order-service
                    url: http://127.0.0.1:8563/mcp
                    protocol: STREAMABLE
                    auth:
                      mode: NONE
                    connectTimeoutMs: 5000
                    callTimeoutMs: 30000
                    maxConcurrentTasks: 5
                  - name: payment
                    url: http://127.0.0.1:8564/mcp
                    protocol: STREAMABLE
                    auth:
                      mode: BEARER
                      token: secret-pay
                    connectTimeoutMs: 7000
                    callTimeoutMs: 40000
                    maxConcurrentTasks: 3
                """));

        assertThat(loaded.version()).isEqualTo(2L);
        assertThat(loaded.backends()).extracting(BackendConfig::name)
                .containsExactly("order-service", "payment");

        BackendConfig order = find(loaded, "order-service");
        assertThat(order.url()).isEqualTo("http://127.0.0.1:8563/mcp");
        assertThat(order.protocol()).isEqualTo(Protocol.STREAMABLE);
        assertThat(order.auth().mode()).isEqualTo(AuthMode.NONE);
        assertThat(order.connectTimeoutMs()).isEqualTo(5000);
        assertThat(order.callTimeoutMs()).isEqualTo(30000);
        assertThat(order.maxConcurrentTasks()).isEqualTo(5);

        BackendConfig pay = find(loaded, "payment");
        assertThat(pay.auth().mode()).isEqualTo(AuthMode.BEARER);
        assertThat(pay.auth().token()).isEqualTo("secret-pay");
        assertThat(pay.maxConcurrentTasks()).isEqualTo(3);
    }

    @Test
    void appliesDefaultsWhenOptionalFieldsOmitted() {
        BackendConfigLoader.LoadedBackends loaded = loader().load(yaml("""
                version: 1
                backends:
                  - name: minimal
                    url: http://127.0.0.1:9000/mcp
                    auth:
                      mode: NONE
                """));

        BackendConfig cfg = find(loaded, "minimal");
        assertThat(cfg.protocol()).as("省略 protocol 默认 STREAMABLE").isEqualTo(Protocol.STREAMABLE);
        assertThat(cfg.connectTimeoutMs()).as("省略 connectTimeoutMs 默认 5000").isEqualTo(5000);
        assertThat(cfg.callTimeoutMs()).as("省略 callTimeoutMs 默认 30000").isEqualTo(30000);
        assertThat(cfg.maxConcurrentTasks()).as("省略 maxConcurrentTasks 默认 5").isEqualTo(5);
    }

    @Test
    void parsesBasicAuthCredentials() {
        BackendConfigLoader.LoadedBackends loaded = loader().load(yaml("""
                version: 1
                backends:
                  - name: legacy
                    url: http://127.0.0.1:8565/mcp
                    auth:
                      mode: BASIC
                      username: ops
                      password: p@ss
                """));

        BackendConfig cfg = find(loaded, "legacy");
        assertThat(cfg.auth().mode()).isEqualTo(AuthMode.BASIC);
        assertThat(cfg.auth().username()).isEqualTo("ops");
        assertThat(cfg.auth().password()).isEqualTo("p@ss");
    }

    // ===== ${ENV:default} 占位解析 =====

    @Test
    void resolvesBearerTokenFromEnvPlaceholderWhenEnvPresent() {
        Function<String, String> env = key -> "PAYMENT_TOKEN".equals(key) ? "env-secret" : null;
        BackendConfigLoader.LoadedBackends loaded = new BackendConfigLoader(env).load(yaml("""
                version: 1
                backends:
                  - name: payment
                    url: http://127.0.0.1:8564/mcp
                    auth:
                      mode: BEARER
                      token: ${PAYMENT_TOKEN:change-me}
                """));

        assertThat(find(loaded, "payment").auth().token())
                .as("环境变量存在时用其值（机密不入库）").isEqualTo("env-secret");
    }

    @Test
    void resolvesPlaceholderToDefaultWhenEnvAbsent() {
        Function<String, String> alwaysAbsent = key -> null;
        BackendConfigLoader.LoadedBackends loaded = new BackendConfigLoader(alwaysAbsent).load(yaml("""
                version: 1
                backends:
                  - name: payment
                    url: http://127.0.0.1:8564/mcp
                    auth:
                      mode: BEARER
                      token: ${PAYMENT_TOKEN:change-me}
                """));

        assertThat(find(loaded, "payment").auth().token())
                .as("环境变量缺失时用占位默认值").isEqualTo("change-me");
    }

    // ===== 校验失败（保留旧表） =====

    @Test
    void rejectsDuplicateBackendNames() {
        assertThatThrownBy(() -> loader().load(yaml("""
                version: 1
                backends:
                  - name: dup
                    url: http://127.0.0.1:1/mcp
                    auth: { mode: NONE }
                  - name: dup
                    url: http://127.0.0.1:2/mcp
                    auth: { mode: NONE }
                """)))
                .isInstanceOf(BackendConfigException.class)
                .hasMessageContaining("dup");
    }

    @Test
    void rejectsUrlWithoutScheme() {
        assertThatThrownBy(() -> loader().load(yaml("""
                version: 1
                backends:
                  - name: bad
                    url: not-a-url
                    auth: { mode: NONE }
                """)))
                .isInstanceOf(BackendConfigException.class);
    }

    @Test
    void rejectsNonHttpScheme() {
        assertThatThrownBy(() -> loader().load(yaml("""
                version: 1
                backends:
                  - name: ftp
                    url: ftp://127.0.0.1:8563/mcp
                    auth: { mode: NONE }
                """)))
                .isInstanceOf(BackendConfigException.class);
    }

    @Test
    void rejectsBearerWithoutToken() {
        assertThatThrownBy(() -> loader().load(yaml("""
                version: 1
                backends:
                  - name: b
                    url: http://127.0.0.1:1/mcp
                    auth: { mode: BEARER }
                """)))
                .isInstanceOf(BackendConfigException.class)
                .hasMessageContaining("b");
    }

    @Test
    void rejectsBasicWithoutCredentials() {
        assertThatThrownBy(() -> loader().load(yaml("""
                version: 1
                backends:
                  - name: c
                    url: http://127.0.0.1:1/mcp
                    auth: { mode: BASIC }
                """)))
                .isInstanceOf(BackendConfigException.class);
    }

    @Test
    void rejectsBasicWithOnlyUsername() {
        assertThatThrownBy(() -> loader().load(yaml("""
                version: 1
                backends:
                  - name: c2
                    url: http://127.0.0.1:1/mcp
                    auth: { mode: BASIC, username: ops }
                """)))
                .isInstanceOf(BackendConfigException.class);
    }

    @Test
    void rejectsUnknownProtocol() {
        assertThatThrownBy(() -> loader().load(yaml("""
                version: 1
                backends:
                  - name: p
                    url: http://127.0.0.1:1/mcp
                    protocol: WEIRD
                    auth: { mode: NONE }
                """)))
                .isInstanceOf(BackendConfigException.class);
    }

    @Test
    void rejectsUnknownAuthMode() {
        assertThatThrownBy(() -> loader().load(yaml("""
                version: 1
                backends:
                  - name: a
                    url: http://127.0.0.1:1/mcp
                    auth: { mode: OAUTH }
                """)))
                .isInstanceOf(BackendConfigException.class);
    }

    @Test
    void rejectsMaxConcurrentTasksAboveHardLimit() {
        assertThatThrownBy(() -> loader().load(yaml("""
                version: 1
                backends:
                  - name: over
                    url: http://127.0.0.1:1/mcp
                    auth: { mode: NONE }
                    maxConcurrentTasks: 6
                """)))
                .isInstanceOf(BackendConfigException.class);
    }

    @Test
    void rejectsNonPositiveMaxConcurrentTasks() {
        assertThatThrownBy(() -> loader().load(yaml("""
                version: 1
                backends:
                  - name: zero
                    url: http://127.0.0.1:1/mcp
                    auth: { mode: NONE }
                    maxConcurrentTasks: 0
                """)))
                .isInstanceOf(BackendConfigException.class);
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> loader().load(yaml("""
                version: 1
                backends:
                  - name: "   "
                    url: http://127.0.0.1:1/mcp
                    auth: { mode: NONE }
                """)))
                .isInstanceOf(BackendConfigException.class);
    }

    @Test
    void rejectsMissingVersion() {
        assertThatThrownBy(() -> loader().load(yaml("""
                backends:
                  - name: nover
                    url: http://127.0.0.1:1/mcp
                    auth: { mode: NONE }
                """)))
                .isInstanceOf(BackendConfigException.class);
    }

    @Test
    void rejectsNonPositiveConnectTimeout() {
        assertThatThrownBy(() -> loader().load(yaml("""
                version: 1
                backends:
                  - name: t
                    url: http://127.0.0.1:1/mcp
                    auth: { mode: NONE }
                    connectTimeoutMs: 0
                """)))
                .isInstanceOf(BackendConfigException.class);
    }

    @Test
    void rejectsMalformedYaml() {
        assertThatThrownBy(() -> loader().load(yaml("version: 1\nbackends: \"this is a string not a list\"\n")))
                .isInstanceOf(BackendConfigException.class);
    }

    @Test
    void loadedBackendsWithEmptyListWhenNoBackendsKey() {
        // backends 缺失视为空注册表（version 仍须存在）；语义：允许「无后端」配置，非错误
        BackendConfigLoader.LoadedBackends loaded = loader().load(yaml("version: 1\n"));
        assertThat(loaded.version()).isEqualTo(1L);
        assertThat(loaded.backends()).isEmpty();
    }

    @Test
    void loadedBackendsIsImmutable() {
        BackendConfigLoader.LoadedBackends loaded = loader().load(yaml("""
                version: 1
                backends:
                  - name: x
                    url: http://127.0.0.1:1/mcp
                    auth: { mode: NONE }
                """));
        assertThatThrownBy(() -> loaded.backends().add(
                new BackendConfig("y", "http://127.0.0.1:2/mcp", Protocol.STREAMABLE,
                        new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                        5000, 30000, 5)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ===== 辅助 =====

    private static BackendConfig find(BackendConfigLoader.LoadedBackends loaded, String name) {
        return loaded.backends().stream()
                .filter(b -> b.name().equals(name))
                .findFirst().orElseThrow();
    }
}
```


---

## com/arthas/gateway/backend/BackendEntryInterceptionLayerTest.java

**文件**：`src/test/java/com/arthas/gateway/backend/BackendEntryInterceptionLayerTest.java`

```java
package com.arthas.gateway.backend;

import com.arthas.gateway.handler.McpErrorCodes;
import com.arthas.gateway.testfixtures.FakeBackendClient;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T010（前置到 US1）{@link BackendEntry} 统一拦截层单测（002 整改 · P1-3/P1-4，US1）。
 *
 * <p>验证 {@code invoke} 故障分类（P1-3）、{@code execute} 槽 RAII、{@code admit} 准入守卫、
 * {@code initializeOnce} 握手恰好一次（P1-4）、{@code isHealthy} 单一事实源。
 *
 * <p><b>真实性</b>：用受控 {@link FakeBackendClient}（触发真实失败条件：抛异常/McpError/返回 isError）+
 * 真实 {@link CircuitBreaker} + 真实线程池（CAS 并发）。FakeBackendClient 非 arthas 成功桩（见其类注释）。
 */
class BackendEntryInterceptionLayerTest {

    private static BackendConfig cfg(int maxConcurrent) {
        return new BackendConfig("order", "http://localhost:8563", Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 60000, maxConcurrent);
    }

    private static BackendEntry entry(CircuitBreaker breaker, FakeBackendClient client) {
        return new BackendEntry(cfg(5), client, breaker);
    }

    private static CircuitBreaker closedBreaker() {
        return CircuitBreaker.create(new AtomicLong(0)::get);
    }

    // ===== invoke 故障分类（P1-3：异步/同步共用，业务错误不计熔断，基础设施故障计入）=====

    @Test
    void invokeSuccessRecordsSuccess() {
        CircuitBreaker breaker = closedBreaker();
        BackendEntry e = entry(breaker, new FakeBackendClient());

        CallToolResult r = e.invoke("jvm", Map.of());

        assertThat(r).isNotNull();
        assertThat(breaker.state()).as("成功 → recordSuccess，CLOSED").isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void invokeBusinessIsErrorRecordsSuccessNotFailure() {
        CircuitBreaker breaker = closedBreaker();
        FakeBackendClient client = new FakeBackendClient();
        client.setCallToolResult(new CallToolResult(List.of(new TextContent("业务错误")), true, null, null));
        BackendEntry e = entry(breaker, client);

        CallToolResult r = e.invoke("ognl", Map.of());

        assertThat(r.isError()).as("isError=true 原样透传").isTrue();
        assertThat(breaker.state()).as("业务错误不计熔断，CLOSED").isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void invokeMcpErrorRecordsSuccessAndRethrows() {
        CircuitBreaker breaker = closedBreaker();
        FakeBackendClient client = new FakeBackendClient();
        client.setCallToolThrow(McpError.builder(McpErrorCodes.INVALID_PARAMS).message("后端 JSON-RPC error").build());
        BackendEntry e = entry(breaker, client);

        assertThatThrownBy(() -> e.invoke("watch", Map.of())).isInstanceOf(McpError.class);
        assertThat(breaker.state()).as("McpError（后端业务错误）不计熔断，CLOSED").isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void invokeInfraFailureRecordsFailureAndThrowsUnreachable() {
        CircuitBreaker breaker = closedBreaker();
        FakeBackendClient client = new FakeBackendClient();
        client.setCallToolThrow(new java.io.UncheckedIOException(new java.io.IOException("conn refused")));
        BackendEntry e = entry(breaker, client);

        assertThatThrownBy(() -> e.invoke("jvm", Map.of()))
                .isInstanceOf(BackendUnreachableException.class)
                .hasCauseInstanceOf(java.io.UncheckedIOException.class);
        assertThat(breaker.state()).as("基础设施故障 → recordFailure（阈值 3 未达仍 CLOSED）")
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void invokeThreeInfraFailuresOpenCircuit() {
        AtomicLong clock = new AtomicLong(0);
        CircuitBreaker breaker = CircuitBreaker.create(clock::get);
        FakeBackendClient client = new FakeBackendClient();
        client.setCallToolThrow(new java.io.UncheckedIOException(new java.io.IOException("down")));
        BackendEntry e = entry(breaker, client);

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> e.invoke("jvm", Map.of())).isInstanceOf(BackendUnreachableException.class);
        }
        assertThat(breaker.state()).as("纯 invoke 路径 3 次基础设施失败 → OPEN（P1-3 异步/同步统一驱动）")
                .isEqualTo(CircuitBreaker.State.OPEN);
    }

    /** P1-3/cancel 方案：worker 中断态下 invoke 抛异常 → 不 recordFailure（避免 cancel 误熔断）。 */
    @Test
    void invokeInterruptedDoesNotRecordFailure() {
        CircuitBreaker breaker = closedBreaker();
        FakeBackendClient client = new FakeBackendClient();
        client.setCallToolThrow(new RuntimeException("interrupted-call"));
        BackendEntry e = entry(breaker, client);

        try {
            Thread.currentThread().interrupt(); // 模拟 cancel(true) 中断 worker
            assertThatThrownBy(() -> e.invoke("watch", Map.of()))
                    .isInstanceOf(BackendUnreachableException.class);
        } finally {
            Thread.interrupted(); // 清理中断态，避免污染后续测试
        }
        assertThat(breaker.state()).as("取消中断不计熔断，CLOSED").isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // ===== execute 槽 RAII（同步路径）=====

    @Test
    void executeAcquiresAndReleasesSlotOnSuccess() {
        BackendEntry e = entry(closedBreaker(), new FakeBackendClient());
        assertThat(e.availableSlots()).isEqualTo(5);

        e.execute("jvm", Map.of());

        assertThat(e.availableSlots()).as("execute 成功后槽全数回收（RAII）").isEqualTo(5);
    }

    @Test
    void executeReleasesSlotOnFailure() {
        FakeBackendClient client = new FakeBackendClient();
        client.setCallToolThrow(new RuntimeException("boom"));
        BackendEntry e = entry(closedBreaker(), client);

        assertThatThrownBy(() -> e.execute("jvm", Map.of())).isInstanceOf(BackendUnreachableException.class);
        assertThat(e.availableSlots()).as("失败路径也释放槽（RAII，不泄漏）").isEqualTo(5);
    }

    // ===== admit 准入守卫 =====

    @Test
    void admitCircuitOpenThrowsBeforeAcquiringSlot() {
        AtomicLong clock = new AtomicLong(0);
        CircuitBreaker breaker = CircuitBreaker.create(clock::get);
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure();
        }
        BackendEntry e = entry(breaker, new FakeBackendClient());

        assertThatThrownBy(() -> e.admit("watch"))
                .isInstanceOf(CircuitOpenException.class)
                .satisfies(ex -> assertThat(((CircuitOpenException) ex).retryAfterMs()).isPositive());
        assertThat(e.availableSlots()).as("熔断拒绝时未取槽（不泄漏）").isEqualTo(5);
    }

    @Test
    void admitConcurrencyLimitThrowsWhenSlotsExhausted() {
        BackendEntry e = new BackendEntry(cfg(2), new FakeBackendClient(), closedBreaker());
        e.admit("watch"); // 占 1
        e.admit("watch"); // 占 2（满）

        assertThatThrownBy(() -> e.admit("watch"))
                .isInstanceOf(ConcurrencyLimitException.class)
                .satisfies(ex -> assertThat(((ConcurrencyLimitException) ex).maxConcurrentTasks()).isEqualTo(2));
    }

    // ===== initializeOnce CAS（P1-4）=====

    @Test
    void initializeOnceHandshakesExactlyOnceUnderConcurrency() throws Exception {
        FakeBackendClient client = new FakeBackendClient();
        BackendEntry e = entry(closedBreaker(), client);

        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch fire = new CountDownLatch(1);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        fire.await();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    e.invoke("jvm", Map.of());
                    return null;
                });
            }
            ready.await();
            fire.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(client.initializeCount()).as("并发 invoke 仅握手一次（P1-4 原子）").isEqualTo(1);
    }

    @Test
    void initializeOnceRetriesAfterFailure() {
        FakeBackendClient client = new FakeBackendClient();
        client.setInitializeThrow(new IllegalStateException("handshake failed"));
        BackendEntry e = entry(closedBreaker(), client);

        assertThatThrownBy(() -> e.invoke("jvm", Map.of()))
                .isInstanceOf(BackendUnreachableException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(client.initializeCount()).as("首次握手失败已尝试").isEqualTo(1);

        // 握手失败应可重试（initialized 回退），恢复成功后不再握手
        client.setInitializeThrow(null);
        CallToolResult r = e.invoke("jvm", Map.of()); // 握手成功 + callTool 返回，不抛
        assertThat(r).isNotNull();
        assertThat(client.initializeCount()).as("第二次成功握手").isEqualTo(2);
        assertThat(client.isInitialized()).isTrue();
    }

    // ===== isHealthy 单一事实源（US5 前置）=====

    @Test
    void isHealthyReflectsCircuitOpen() {
        CircuitBreaker breaker = closedBreaker();
        BackendEntry e = entry(breaker, new FakeBackendClient());

        assertThat(e.isHealthy()).as("ACTIVE + CLOSED → healthy").isTrue();

        for (int i = 0; i < 3; i++) {
            breaker.recordFailure();
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(e.isHealthy()).as("熔断 OPEN → unhealthy").isFalse();
    }

    @Test
    void isHealthyFalseWhenRetiredEvenIfBreakerClosed() {
        BackendEntry e = entry(closedBreaker(), new FakeBackendClient());
        e.markRetired();
        assertThat(e.isHealthy()).as("RETIRED → unhealthy（即便熔断 CLOSED）").isFalse();
    }
}
```


---

## com/arthas/gateway/backend/BackendEntryLazyResolveTest.java

**文件**：`src/test/java/com/arthas/gateway/backend/BackendEntryLazyResolveTest.java`

```java
package com.arthas.gateway.backend;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 005 US2 BackendEntry 懒 resolve hook 单测（T014，INV-K8SHOST-4/5）。
 *
 * <p>验证 BackendEntry.initializeOnce 的 K8S 模式懒 resolve：K8S 模式（client=null）首调 invoke 时经
 * {@link BackendResolver} resolve 出 mcpUrl 建 {@link HttpBackendClient}（覆盖 config.url）；静态模式旁路。
 * 真实 K8S ensure 由 {@code K8sBackendResolverContractIT} 覆盖——本测 mock resolver + mock client。
 */
class BackendEntryLazyResolveTest {

    private static BackendConfig k8sConfig(String host, String pod) {
        return new BackendConfig("k", null, Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5, host, pod, Source.STATIC);
    }

    private static BackendConfig staticConfig() {
        return new BackendConfig("s", "http://127.0.0.1:8563", Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5);
    }

    private static CircuitBreaker breaker() {
        return mock(CircuitBreaker.class);
    }

    /** K8S 模式（client=null）首调 invoke → 懒 resolve：resolver.resolveMcpUrl 被调（建 HttpBackendClient）。 */
    @Test
    void k8sModeLazilyResolvesMcpUrlOnFirstInvoke() {
        BackendResolver resolver = mock(BackendResolver.class);
        when(resolver.resolveMcpUrl(any())).thenReturn(Optional.of("http://resolved.invalid:30050"));
        BackendEntry entry = new BackendEntry(k8sConfig("debian", "pod"), null, breaker(), Optional.of(resolver));

        // HttpBackendClient(resolved url) initialize 连不可达地址 → BackendUnreachableException（但 resolver 已被调）
        assertThatThrownBy(() -> entry.invoke("jvm", Map.of()))
                .isInstanceOf(BackendUnreachableException.class);
        verify(resolver).resolveMcpUrl(any()); // 懒 resolve 触发
    }

    /** K8S 模式但无 resolver（未配 k8s-hosts）→ no_k8s_resolver（INV-K8SHOST-4）。 */
    @Test
    void k8sModeWithoutResolverThrowsNoK8sResolver() {
        BackendEntry entry = new BackendEntry(k8sConfig("debian", "pod"), null, breaker(), Optional.empty());

        assertThatThrownBy(() -> entry.invoke("jvm", Map.of()))
                .isInstanceOf(BackendUnreachableException.class)
                .hasMessageContaining("no_k8s_resolver");
    }

    /** 静态模式（client 预建）→ invoke 用预建 client，resolver 不被调（INV-K8SHOST-5 旁路）。 */
    @Test
    void staticModeUsesPrebuiltClientBypassingResolver() {
        BackendResolver resolver = mock(BackendResolver.class);
        BackendClient client = mock(BackendClient.class);
        when(client.callTool(any(), any())).thenReturn(mock(CallToolResult.class));

        BackendEntry entry = new BackendEntry(staticConfig(), client, breaker(), Optional.of(resolver));
        entry.invoke("jvm", Map.of());

        verifyNoInteractions(resolver); // 静态模式旁路，不懒 resolve
        verify(client).callTool("jvm", Map.of()); // 用预建 client
    }
}
```

---

## com/arthas/gateway/backend/BackendEntryTest.java

**文件**：`src/test/java/com/arthas/gateway/backend/BackendEntryTest.java`

```java
package com.arthas.gateway.backend;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BackendEntry 单测（T025 容器 + T047 per-target 限流，data-model.md §3 / §11 规则 6）。
 *
 * <p>纯逻辑单测（surefire，无真实后端）。覆盖 {@code taskSlots}（Semaphore）非阻塞限流：
 * 许可数 = {@code config.maxConcurrentTasks}；{@code tryAcquireSlot} 拿 1 个许可，
 * 并发 &gt; 上限（耗尽许可）即时返 {@code false}（前置限流→调用方返 INVALID_PARAMS，C-LIMIT-1 的网关侧逻辑，
 * 避免越界打后端）；{@code releaseSlot} 回收许可（try-finally 配对）。
 *
 * <p><b>零桩约束的边界</b>：{@code NOOP_CLIENT} 是<b>测试缝</b>（仅满足 {@code client} 字段占位，
 * 限流单测从不调用其 {@code callTool}——{@code callTool} 抛 {@link UnsupportedOperationException}
 * 以明示「非 arthas 响应替身」）。真实 6 并发越界→INVALID_PARAMS 的端到端证明（C-LIMIT-1）由
 * {@code FaultIsolationContractTest}（T043，真实并发 task）承担，非本单测职责。熔断计入/不计入裁决
 * （C-CB-1/2）在 {@link CircuitBreakerTest}，401 处理在 T046。
 */
class BackendEntryTest {

    /** 测试缝：占位 client，限流单测从不调用其响应路径（非 arthas 替身）。 */
    private static final BackendClient NOOP_CLIENT = new BackendClient() {
        @Override
        public void initialize() {
            // 占位：限流单测不握手
        }

        @Override
        public McpSchema.CallToolResult callTool(String name, Map<String, Object> arguments) {
            throw new UnsupportedOperationException("限流单测不调用 client（测试缝，非 arthas 响应替身）");
        }

        @Override
        public boolean isInitialized() {
            return false;
        }

        @Override
        public void close() {
            // 占位
        }
    };

    private static BackendConfig config(int maxConcurrentTasks) {
        return new BackendConfig(
                "order-service",
                "http://host:8563/mcp",
                Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000,
                30000,
                maxConcurrentTasks);
    }

    private static BackendEntry entry(int maxConcurrentTasks) {
        return new BackendEntry(config(maxConcurrentTasks), NOOP_CLIENT, CircuitBreaker.create(() -> 0L));
    }

    @Test
    void initiallyActiveHoldsWiringAndFullSlots() {
        BackendEntry e = entry(2);
        assertThat(e.state()).as("新建 Entry 初始 ACTIVE").isEqualTo(BackendState.ACTIVE);
        assertThat(e.config().name()).isEqualTo("order-service");
        assertThat(e.client()).isSameAs(NOOP_CLIENT);
        assertThat(e.breaker().state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(e.availableSlots()).as("许可数 == maxConcurrentTasks").isEqualTo(2);
    }

    @Test
    void acquireUpToMaxConcurrentSucceeds() {
        BackendEntry e = entry(2);
        assertThat(e.tryAcquireSlot()).isTrue();
        assertThat(e.tryAcquireSlot()).isTrue();
        assertThat(e.availableSlots()).as("两次获取后许可耗尽").isZero();
    }

    @Test
    void acquireBeyondMaxRejectedImmediately() {
        BackendEntry e = entry(2);
        e.tryAcquireSlot();
        e.tryAcquireSlot();
        // 并发 > maxConcurrentTasks（许可耗尽）→ 前置限流，非阻塞即时返 false（不等）
        assertThat(e.tryAcquireSlot()).as("并发>上限即时限流返 false").isFalse();
    }

    @Test
    void releaseReenablesSubsequentAcquire() {
        BackendEntry e = entry(2);
        e.tryAcquireSlot();
        e.tryAcquireSlot();
        assertThat(e.tryAcquireSlot()).isFalse();
        e.releaseSlot();
        assertThat(e.availableSlots()).as("释放回收 1 个许可").isEqualTo(1);
        assertThat(e.tryAcquireSlot()).as("释放后可再次获取").isTrue();
    }

    @Test
    void availableSlotsTracksAcquireAndRelease() {
        BackendEntry e = entry(5);
        assertThat(e.availableSlots()).isEqualTo(5);
        e.tryAcquireSlot();
        assertThat(e.availableSlots()).isEqualTo(4);
        e.tryAcquireSlot();
        assertThat(e.availableSlots()).isEqualTo(3);
        e.releaseSlot();
        assertThat(e.availableSlots()).isEqualTo(4);
    }

    @Test
    void defaultsToFiveSlotsWhenConfigured() {
        // 对齐后端硬上限 5（data-model.md §2）；网关 Semaphore 守护避免越界 INVALID_PARAMS
        BackendEntry e = entry(5);
        for (int i = 0; i < 5; i++) {
            assertThat(e.tryAcquireSlot()).as("第 %d 次获取应成功", i + 1).isTrue();
        }
        assertThat(e.tryAcquireSlot()).as("第 6 次获取越界，前置限流").isFalse();
    }

    @Test
    void markRetiredTransitionsStateForGracefulShutdown() {
        // US2 热重载移除时标 RETIRED（in-flight 持旧 Entry 可完成，新调用不再路由到此）
        BackendEntry e = entry(2);
        assertThat(e.state()).isEqualTo(BackendState.ACTIVE);
        e.markRetired();
        assertThat(e.state()).isEqualTo(BackendState.RETIRED);
        // RETIRED 不影响限流语义（in-flight 调用仍需配对释放槽位）
        assertThat(e.tryAcquireSlot()).isTrue();
        e.releaseSlot();
    }
}
```


---

## com/arthas/gateway/backend/BackendRegistryReloaderTest.java

**文件**：`src/test/java/com/arthas/gateway/backend/BackendRegistryReloaderTest.java`

```java
package com.arthas.gateway.backend;

import com.arthas.gateway.backend.BackendConfigLoader.LoadedBackends;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T039 {@link BackendRegistryReloader} 纯逻辑单测（surefire，无 WatchService / 无线程 / 无真实后端）。
 *
 * <p>覆盖热重载 diff 核心（data-model.md §11 规则 7/8、§3「热重载并发不串台」）：
 * <ul>
 *   <li>add：新后端进新表，无下线。</li>
 *   <li>remove：旧后端进下线列表。</li>
 *   <li>unchanged 复用：同 name 同 config → 复用<b>同一</b> Entry 实例（保连接池/session，免重连）。</li>
 *   <li>config 变更：同 name 不同 url → 新建 Entry，旧 Entry 进下线。</li>
 *   <li>version 去重：{@code next.version==current.version} → 不重建（changed=false）。</li>
 * </ul>
 * 真实文件监听（WatchService）+ 端到端由 {@code HotReloadIT}（failsafe）覆盖。
 */
class BackendRegistryReloaderTest {

    private final BackendEntryFactory factory = new BackendEntryFactory();
    private final BackendRegistryReloader reloader = new BackendRegistryReloader(factory);

    private static BackendConfig noneAuth(String name, String url) {
        return new BackendConfig(
                name, url, Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5);
    }

    private static LoadedBackends load(long version, BackendConfig... backends) {
        return new LoadedBackends(version, List.of(backends));
    }

    private static BackendRegistry registry(long version, Map<String, BackendEntry> byName) {
        return new BackendRegistry(version, byName);
    }

    // ===== add：新增后端进新表，无下线 =====

    @Test
    void reload_addsNewBackend() {
        BackendEntry order = factory.create(noneAuth("order", "http://h:1"));
        BackendRegistry current = registry(1L, Map.of("order", order));

        BackendRegistryReloader.ReloadResult r = reloader.reload(current,
                load(2L, noneAuth("order", "http://h:1"), noneAuth("payment", "http://h:2")));

        assertThat(r.changed()).isTrue();
        assertThat(r.registry().version()).isEqualTo(2L);
        assertThat(r.registry().names()).containsExactlyInAnyOrder("order", "payment");
        assertThat(r.registry().get("order").orElseThrow())
                .as("order unchanged → 复用同一实例").isSameAs(order);
        assertThat(r.toRetire()).as("无下线").isEmpty();
    }

    // ===== remove：旧后端进下线列表 =====

    @Test
    void reload_removesRetiredBackend() {
        BackendEntry order = factory.create(noneAuth("order", "http://h:1"));
        BackendEntry payment = factory.create(noneAuth("payment", "http://h:2"));
        BackendRegistry current = registry(1L, Map.of("order", order, "payment", payment));

        BackendRegistryReloader.ReloadResult r = reloader.reload(current,
                load(2L, noneAuth("order", "http://h:1")));

        assertThat(r.changed()).isTrue();
        assertThat(r.registry().names()).containsExactly("order");
        assertThat(r.toRetire()).as("payment 下线").containsExactly(payment);
        assertThat(r.registry().get("order").orElseThrow()).isSameAs(order);
    }

    // ===== unchanged 复用：同 name 同 config → 复用同一 Entry（保连接） =====

    @Test
    void reload_reusesUnchangedEntryBySameConfig() {
        BackendEntry order = factory.create(noneAuth("order", "http://h:1"));
        BackendRegistry current = registry(1L, Map.of("order", order));

        BackendRegistryReloader.ReloadResult r = reloader.reload(current,
                load(2L, noneAuth("order", "http://h:1")));

        assertThat(r.changed()).as("version 变 → changed").isTrue();
        assertThat(r.registry().get("order").orElseThrow())
                .as("复用同一 Entry 实例（连接池/session 保留）").isSameAs(order);
        assertThat(r.toRetire()).isEmpty();
    }

    // ===== config 变更：同 name 不同 url → 新建 Entry，旧 Entry 下线 =====

    @Test
    void reload_replacesEntryOnConfigChange() {
        BackendEntry orderOld = factory.create(noneAuth("order", "http://h:1"));
        BackendRegistry current = registry(1L, Map.of("order", orderOld));

        BackendRegistryReloader.ReloadResult r = reloader.reload(current,
                load(2L, noneAuth("order", "http://h:999"))); // url 变

        assertThat(r.changed()).isTrue();
        BackendEntry orderNew = r.registry().get("order").orElseThrow();
        assertThat(orderNew).as("config 变更 → 新建 Entry").isNotSameAs(orderOld);
        assertThat(orderNew.config().url()).isEqualTo("http://h:999");
        assertThat(r.toRetire()).as("旧 Entry 下线").containsExactly(orderOld);
    }

    // ===== version 去重：相同 version → 不重建 =====

    @Test
    void reload_ignoresRepeatedVersion() {
        BackendEntry order = factory.create(noneAuth("order", "http://h:1"));
        BackendRegistry current = registry(5L, Map.of("order", order));

        // 即便内容不同，version 重复也忽略（去重优先）
        BackendRegistryReloader.ReloadResult r = reloader.reload(current,
                load(5L, noneAuth("payment", "http://h:2")));

        assertThat(r.changed()).as("version 重复 → changed=false").isFalse();
        assertThat(r.registry()).as("registry 维持原样").isSameAs(current);
        assertThat(r.toRetire()).isEmpty();
    }
}
```


---

## com/arthas/gateway/backend/BackendRegistryTest.java

**文件**：`src/test/java/com/arthas/gateway/backend/BackendRegistryTest.java`

```java
package com.arthas.gateway.backend;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BackendRegistry + RegistryHolder 单测（T022，data-model.md §4 / §11 规则 8）。
 *
 * <p>纯逻辑单测（surefire，无真实后端）。覆盖：
 * <ul>
 *   <li>{@link BackendRegistry}：不可变快照（{@code version} + {@code byName}），{@code get}/{@code names}，
 *       对入参 Map 做<b>防御性拷贝</b>（构造后篡改原 Map 不影响 registry）。</li>
 *   <li>{@link RegistryHolder}：{@code AtomicReference} 持有，{@code getAndSet} 原子替换且返回旧快照
 *       （热重载整体替换、in-flight 持旧 Entry 可完成，data-model.md §3 不变量）。</li>
 * </ul>
 *
 * <p><b>零桩约束</b>：{@code NOOP_CLIENT} 为<b>测试缝</b>（占位 client 字段，单测从不调用其 {@code callTool}，
 * 抛 {@link UnsupportedOperationException} 明示「非 arthas 响应替身」）；真实多目标路由隔离（S-CALL-1）由
 * {@code ToolsCallRoutingContractTest}（T019，真实多后端）承担，非本单测职责。
 */
class BackendRegistryTest {

    /** 测试缝：占位 client，注册表单测从不调用其响应路径（非 arthas 替身）。 */
    private static final BackendClient NOOP_CLIENT = new BackendClient() {
        @Override
        public void initialize() {
            // 占位
        }

        @Override
        public McpSchema.CallToolResult callTool(String name, Map<String, Object> arguments) {
            throw new UnsupportedOperationException("注册表单测不调用 client（测试缝，非 arthas 响应替身）");
        }

        @Override
        public boolean isInitialized() {
            return false;
        }

        @Override
        public void close() {
            // 占位
        }
    };

    private static BackendConfig config(String name) {
        return new BackendConfig(
                name,
                "http://host:8563/mcp",
                Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000,
                30000,
                5);
    }

    private static BackendEntry entry(String name) {
        return new BackendEntry(config(name), NOOP_CLIENT, CircuitBreaker.create(() -> 0L));
    }

    // ---------------- BackendRegistry ----------------

    @Test
    void emptyRegistryHasVersionZeroAndNoEntries() {
        BackendRegistry r = BackendRegistry.empty();
        assertThat(r.version()).isZero();
        assertThat(r.size()).isZero();
        assertThat(r.names()).isEmpty();
        assertThat(r.get("any")).isEmpty();
    }

    @Test
    void registryHoldsEntriesByNameWithVersion() {
        BackendEntry order = entry("order-service");
        BackendEntry payment = entry("payment");
        BackendRegistry r = new BackendRegistry(7L, Map.of("order-service", order, "payment", payment));

        assertThat(r.version()).isEqualTo(7L);
        assertThat(r.size()).isEqualTo(2);
        assertThat(r.names()).containsExactlyInAnyOrder("order-service", "payment");
        assertThat(r.get("order-service")).contains(order);
        assertThat(r.get("payment")).contains(payment);
        assertThat(r.get("missing")).isEmpty();
    }

    @Test
    void registryDefensivelyCopiesBackingMap() {
        // 不可变快照不变量：构造后篡改原 Map 不得泄漏进 registry（data-model.md §4 不可变）
        Map<String, BackendEntry> mutable = new HashMap<>();
        mutable.put("order-service", entry("order-service"));
        BackendRegistry r = new BackendRegistry(1L, mutable);

        mutable.put("rogue", entry("rogue")); // 构造后向原 Map 塞入 rogue
        mutable.remove("order-service");      // 构造后从原 Map 删 order-service

        assertThat(r.size()).as("防御性拷贝：原 Map 篡改不影响 registry").isEqualTo(1);
        assertThat(r.get("order-service")).as("原 Map 删除不反映到 registry").isPresent();
        assertThat(r.get("rogue")).as("原 Map 新增不泄漏进 registry").isEmpty();
    }

    // ---------------- RegistryHolder ----------------

    @Test
    void holderDefaultsToEmptyRegistry() {
        RegistryHolder h = new RegistryHolder();
        assertThat(h.current().version()).isZero();
        assertThat(h.current().size()).isZero();
    }

    @Test
    void holderInitiallyHoldsProvidedRegistry() {
        BackendRegistry r = new BackendRegistry(1L, Map.of("order-service", entry("order-service")));
        RegistryHolder h = new RegistryHolder(r);
        assertThat(h.current()).isSameAs(r);
        assertThat(h.get("order-service")).isPresent();
    }

    @Test
    void getAndSetAtomicallySwapsAndReturnsPrevious() {
        // 热重载整体替换：getAndSet 返回旧快照（供异步优雅下线），current() 反映新快照
        BackendEntry orderV1 = entry("order-service");
        BackendRegistry v1 = new BackendRegistry(1L, Map.of("order-service", orderV1));
        BackendRegistry v2 = new BackendRegistry(
                2L, Map.of("order-service", entry("order-service"), "payment", entry("payment")));
        RegistryHolder h = new RegistryHolder(v1);

        BackendRegistry previous = h.getAndSet(v2);
        assertThat(previous).as("getAndSet 返回旧 registry").isSameAs(v1);
        assertThat(h.current()).as("current 反映新 registry").isSameAs(v2);
        assertThat(h.current().version()).isEqualTo(2L);
        assertThat(h.current().size()).isEqualTo(2);
        assertThat(h.get("payment")).as("便捷 get 委派新 registry").isPresent();
    }

    @Test
    void getAndSetRejectsNullToFailFast() {
        RegistryHolder h = new RegistryHolder();
        assertThatThrownBy(() -> h.getAndSet(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("registry");
    }
}
```


---

## com/arthas/gateway/backend/BackendResolverTest.java

**文件**：`src/test/java/com/arthas/gateway/backend/BackendResolverTest.java`

```java
package com.arthas.gateway.backend;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 005 BackendResolver 接口契约（T007）：接口可返 mcpUrl（K8S 模式）/ empty（静态模式）。
 * 具体 K8S vs 静态判定逻辑在 K8sBackendResolverTest（T013）测。
 */
class BackendResolverTest {

    @Test
    void resolverCanReturnMcpUrl() {
        BackendConfig config = mock(BackendConfig.class);
        BackendResolver resolver = c -> Optional.of("http://resolved:30000");
        assertThat(resolver.resolveMcpUrl(config)).contains("http://resolved:30000");
    }

    @Test
    void resolverCanReturnEmptyForStaticMode() {
        BackendConfig config = mock(BackendConfig.class);
        BackendResolver resolver = c -> Optional.empty();
        assertThat(resolver.resolveMcpUrl(config)).isEmpty();
    }
}
```

---

## com/arthas/gateway/backend/CircuitBreakerConcurrencyTest.java

**文件**：`src/test/java/com/arthas/gateway/backend/CircuitBreakerConcurrencyTest.java`

```java
package com.arthas.gateway.backend;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T003 {@link CircuitBreaker} 线程安全单测（002 整改 P1-1/FR-003）。
 *
 * <p>验证 synchronized 修复后:并发 {@code recordFailure} 不丢失更新（达阈值精确 OPEN）、
 * HALF_OPEN 仅放 1 探测（无状态撕裂）。用<b>真实线程池 + CountDownLatch 屏障</b>触发真实并发
 * （非 arthas 桩；熔断器是纯逻辑领域对象）。
 */
class CircuitBreakerConcurrencyTest {

    @Test
    void concurrentRecordFailuresOpensAtThreshold_noLostUpdate() throws Exception {
        AtomicLong clock = new AtomicLong(0L);
        CircuitBreaker breaker = CircuitBreaker.create(clock::get);

        int threads = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch fire = new CountDownLatch(1);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        fire.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    breaker.recordFailure();
                    return null;
                });
            }
            ready.await();
            fire.countDown(); // 同时开闸，最大化并发竞争
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(breaker.state())
                .as("100 次并发失败后必 OPEN（阈值3，无丢失更新导致该断不断）")
                .isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void halfOpenAllowsExactlyOneProbe_underConcurrentAllowRequest() throws Exception {
        AtomicLong clock = new AtomicLong(0L);
        CircuitBreaker breaker = CircuitBreaker.create(clock::get);
        // 推到 OPEN（阈值 3）
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure();
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        clock.set(2_000_000_000L); // 推进超过 base 退避(1s)，下次 allowRequest 应转 HALF_OPEN 放探测

        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch fire = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger(0);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        fire.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    if (breaker.allowRequest()) {
                        allowed.incrementAndGet();
                    }
                    return null;
                });
            }
            ready.await();
            fire.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(allowed.get())
                .as("HALF_OPEN 仅放 1 个探测（synchronized 保证首个转 HALF_OPEN 后其余被拒，无半开放行多探测）")
                .isEqualTo(1);
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    }
}
```


---

## com/arthas/gateway/backend/CircuitBreakerTest.java

**文件**：`src/test/java/com/arthas/gateway/backend/CircuitBreakerTest.java`

```java
package com.arthas.gateway.backend;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CircuitBreaker 状态机测试（T045，data-model.md §8）。
 *
 * <p>纯逻辑单测（surefire，无需真实后端）。用可推进的假时钟（纳秒）驱动，不依赖真实时间。
 * 覆盖：CLOSED→OPEN（连续 3 次基础设施失败）→HALF_OPEN（退避后）→CLOSED（探测成功）/ OPEN（探测失败，退避升级）；
 * 退避 base 1s×2、cap 30s；HALF_OPEN 仅放 1 个探测。
 *
 * <p><b>失败计入与否的裁决不在本类</b>——业务错误（isError=true/INVALID_PARAMS）不计入熔断，
 * 由 BackendClient（T024/T046，Wave C）决定是否调用 {@code recordFailure}。本类仅提供 success/failure 记录与状态。
 */
class CircuitBreakerTest {

    /** 可推进的假时钟（纳秒），避免依赖真实时间。 */
    private static final class FakeClock implements LongSupplier {
        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long getAsLong() {
            return nanos.get();
        }

        void advance(Duration d) {
            nanos.addAndGet(d.toNanos());
        }
    }

    private static CircuitBreaker breaker(FakeClock clock) {
        return CircuitBreaker.create(clock);
    }

    private static void failN(CircuitBreaker b, int n) {
        for (int i = 0; i < n; i++) {
            b.recordFailure();
        }
    }

    @Test
    void initiallyClosedAndAllowsRequests() {
        CircuitBreaker b = breaker(new FakeClock());
        assertThat(b.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(b.allowRequest()).isTrue();
    }

    @Test
    void staysClosedBelowFailureThreshold() {
        CircuitBreaker b = breaker(new FakeClock());
        failN(b, 2); // < 阈值 3
        assertThat(b.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(b.allowRequest()).isTrue();
    }

    @Test
    void opensAfterConsecutiveFailureThreshold() {
        CircuitBreaker b = breaker(new FakeClock());
        failN(b, 3);
        assertThat(b.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(b.allowRequest()).as("OPEN 立即阻断，不等 30s").isFalse();
    }

    @Test
    void failureStreakResetsOnSuccess() {
        CircuitBreaker b = breaker(new FakeClock());
        failN(b, 2);
        b.recordSuccess(); // 成功打断连续失败计数
        failN(b, 2);
        assertThat(b.state()).as("成功打断连续计数，未达阈值").isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(b.allowRequest()).isTrue();
    }

    @Test
    void openBlocksUntilBaseBackoffElapsesThenGrantsProbe() {
        FakeClock clock = new FakeClock();
        CircuitBreaker b = breaker(clock);
        failN(b, 3);
        assertThat(b.state()).isEqualTo(CircuitBreaker.State.OPEN);

        clock.advance(Duration.ofMillis(999)); // base 退避 1s，差 1ms
        assertThat(b.allowRequest()).as("未满 1s 退避，阻断").isFalse();
        assertThat(b.state()).isEqualTo(CircuitBreaker.State.OPEN);

        clock.advance(Duration.ofMillis(1)); // 满 1s
        assertThat(b.allowRequest()).as("满退避，放行 1 个探测").isTrue();
        assertThat(b.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    }

    @Test
    void halfOpenProbeSuccessClosesCircuit() {
        FakeClock clock = new FakeClock();
        CircuitBreaker b = breaker(clock);
        failN(b, 3);
        clock.advance(Duration.ofSeconds(1));
        assertThat(b.allowRequest()).isTrue(); // HALF_OPEN
        b.recordSuccess();
        assertThat(b.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(b.allowRequest()).isTrue();
    }

    @Test
    void halfOpenProbeFailureReopensWithEscalatedBackoff() {
        FakeClock clock = new FakeClock();
        CircuitBreaker b = breaker(clock);
        failN(b, 3);                  // 退避 1s
        clock.advance(Duration.ofSeconds(1));
        b.allowRequest();             // HALF_OPEN
        b.recordFailure();            // 探测失败 → OPEN，退避升级 2s
        assertThat(b.state()).isEqualTo(CircuitBreaker.State.OPEN);
        clock.advance(Duration.ofMillis(1999));
        assertThat(b.allowRequest()).as("升级后 2s 退避未满，阻断").isFalse();
        clock.advance(Duration.ofMillis(1));
        assertThat(b.allowRequest()).as("满 2s 退避，放行").isTrue();
    }

    @Test
    void halfOpenBlocksSecondRequestDuringProbe() {
        FakeClock clock = new FakeClock();
        CircuitBreaker b = breaker(clock);
        failN(b, 3);
        clock.advance(Duration.ofSeconds(1));
        assertThat(b.allowRequest()).as("首个探测放行").isTrue();
        assertThat(b.allowRequest()).as("探测未决前，第二个请求阻断（HALF_OPEN 仅放 1 个）").isFalse();
    }

    @Test
    void backoffEscalatesAndCapsAt30s() {
        FakeClock clock = new FakeClock();
        CircuitBreaker b = breaker(clock);
        // 退避序列：1,2,4,8,16,32→cap 30,30
        long[] expectedMs = {1000, 2000, 4000, 8000, 16000, 30000, 30000};
        for (long ms : expectedMs) {
            if (b.state() == CircuitBreaker.State.CLOSED) {
                failN(b, 3); // 首轮靠失败开路；后续轮探测失败已置 OPEN
            }
            clock.advance(Duration.ofMillis(ms - 1));
            assertThat(b.allowRequest()).as("退避 %dms 未满应阻断", ms).isFalse();
            clock.advance(Duration.ofMillis(1));
            assertThat(b.allowRequest()).as("退避 %dms 满应放探测", ms).isTrue();
            b.recordFailure(); // 升级
        }
    }

    @Test
    void closingResetsBackoffToBase() {
        FakeClock clock = new FakeClock();
        CircuitBreaker b = breaker(clock);
        failN(b, 3);                  // 退避 1s
        clock.advance(Duration.ofSeconds(1));
        b.allowRequest();             // HALF_OPEN
        b.recordFailure();            // 升级 2s
        clock.advance(Duration.ofSeconds(2));
        b.allowRequest();             // HALF_OPEN
        b.recordSuccess();            // CLOSED，退避计数重置

        failN(b, 3);                  // 再次开路，应为基础 1s（非升级值）
        clock.advance(Duration.ofMillis(999));
        assertThat(b.allowRequest()).as("恢复后重置为基础 1s 退避").isFalse();
        clock.advance(Duration.ofMillis(1));
        assertThat(b.allowRequest()).isTrue();
    }
}
```


---

## com/arthas/gateway/backend/DynamicBackendStoreTest.java

**文件**：`src/test/java/com/arthas/gateway/backend/DynamicBackendStoreTest.java`

```java
package com.arthas.gateway.backend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T015 {@code DynamicBackendStore} 测试（surefire 波次 A，纯逻辑，无 K8S）。
 *
 * <p>断言 contracts/dynamic-registration-invariants.md §5（动态注册层）：
 * <ul>
 *   <li>D-REG-1：{@code register(DYNAMIC cfg)} 后 store 含该 target（{@code onChange} 触发一次）。</li>
 *   <li>D-REG-2：{@code register} 与静态种子同名 → 抛 {@link BackendConfigException}（I-3 拒绝，保护静态）。</li>
 *   <li>D-REG-3：同名同 URL 二次 {@code register} → 幂等（无异常、target 仍在、无重复变更回调）。</li>
 *   <li>D-REG-4：同名<b>异</b> URL {@code register} → 抛 {@link BackendConfigException}（I-3）。</li>
 *   <li>D-SOURCE-1（动态注册强制 DYNAMIC）：{@code register} 非 DYNAMIC cfg → 抛。</li>
 *   <li>D-UNREG-1：{@code unregister(动态)} 后 store 不含、{@code onChange} 触发。</li>
 *   <li>D-UNREG-2：{@code unregister} 静态名（store 不持有）→ 无操作、无变更回调。</li>
 *   <li>D-UNREG-3：{@code unregister} 不存在 → 幂等无操作。</li>
 * </ul>
 *
 * <p>注：D-REG-1 的"RegistryHolder.current() 含该 target"由 {@code RegistryComposerTest}（T017）覆盖——
 * 那里 wiring store+composer+holder 验证完整 register→compose→swap 链；本测试聚焦 store 自身不变量。
 */
class DynamicBackendStoreTest {

    private final Set<String> staticSeeds = new HashSet<>(Set.of("static-seed"));
    private int changeCount;
    private DynamicBackendStore store;

    @BeforeEach
    void setUp() {
        changeCount = 0;
        store = new DynamicBackendStore(() -> Set.copyOf(staticSeeds), () -> changeCount++);
    }

    private static BackendConfig dyn(String name, String url) {
        return new BackendConfig(name, url, Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5, Source.DYNAMIC);
    }

    private static BackendConfig stat(String name, String url) {
        return new BackendConfig(name, url, Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5, Source.STATIC);
    }

    // ===== D-REG-1：register(DYNAMIC) 后 store 含该 target；onChange 触发一次 =====

    @Test
    void registerDynamicTargetAddsAndNotifies() {
        assertThat(store.list()).isEmpty();
        store.register(dyn("srv-pod", "http://10.0.0.5:31234"));
        assertThat(store.get("srv-pod")).map(BackendConfig::name).contains("srv-pod");
        assertThat(store.list()).hasSize(1);
        assertThat(changeCount).as("register 触发一次 compose 通知").isEqualTo(1);
    }

    // ===== D-SOURCE-1（动态注册强制 DYNAMIC）：register 非 DYNAMIC → 抛 =====

    @Test
    void registerRejectsNonDynamicSource() {
        assertThatThrownBy(() -> store.register(stat("srv-pod", "http://10.0.0.5:31234")))
                .isInstanceOf(BackendConfigException.class)
                .hasMessageContaining("DYNAMIC");
        assertThat(store.list()).as("拒绝的 cfg 不入 store").isEmpty();
        assertThat(changeCount).as("拒绝时不触发变更通知").isZero();
    }

    // ===== D-REG-2：与静态种子同名 → 拒绝（I-3 保护静态） =====

    @Test
    void registerCollidingWithStaticSeedNameRejected() {
        assertThatThrownBy(() -> store.register(dyn("static-seed", "http://10.0.0.5:31234")))
                .isInstanceOf(BackendConfigException.class)
                .hasMessageContaining("static-seed");
        assertThat(store.get("static-seed")).isEmpty();
        assertThat(changeCount).isZero();
    }

    // ===== D-REG-3：同名同 URL 二次 register → 幂等（无异常、仍在、无重复回调） =====

    @Test
    void registerSameNameSameUrlIsIdempotent() {
        store.register(dyn("srv-pod", "http://10.0.0.5:31234"));
        int afterFirst = changeCount;
        // 二次注册（同 name 同 url）—— 不抛、target 仍在、不再重复触发变更回调
        store.register(dyn("srv-pod", "http://10.0.0.5:31234"));
        assertThat(store.list()).hasSize(1);
        assertThat(store.get("srv-pod")).isPresent();
        assertThat(changeCount).as("幂等再注册不重复触发 compose").isEqualTo(afterFirst);
    }

    // ===== D-REG-4：同名异 URL → 拒绝（I-3） =====

    @Test
    void registerSameNameDifferentUrlRejected() {
        store.register(dyn("srv-pod", "http://10.0.0.5:31234"));
        int before = changeCount;
        assertThatThrownBy(() -> store.register(dyn("srv-pod", "http://10.0.0.6:31235")))
                .isInstanceOf(BackendConfigException.class);
        assertThat(store.get("srv-pod")).map(BackendConfig::url).contains("http://10.0.0.5:31234");
        assertThat(changeCount).as("拒绝异 URL 时不触发变更通知").isEqualTo(before);
    }

    // ===== D-UNREG-1：unregister(动态) 后 store 不含、onChange 触发 =====

    @Test
    void unregisterDynamicTargetRemovesAndNotifies() {
        store.register(dyn("srv-pod", "http://10.0.0.5:31234"));
        int before = changeCount;
        store.unregister("srv-pod");
        assertThat(store.get("srv-pod")).isEqualTo(Optional.empty());
        assertThat(changeCount).as("unregister 触发一次 compose 通知").isEqualTo(before + 1);
    }

    // ===== D-UNREG-2：unregister 静态名（store 不持有）→ 无操作、不触发回调 =====

    @Test
    void unregisterStaticSeedNameIsNoOp() {
        store.register(dyn("srv-pod", "http://10.0.0.5:31234"));
        int before = changeCount;
        // static-seed 不在动态 store（静态只经热重载）→ 无操作
        store.unregister("static-seed");
        assertThat(store.list()).as("动态 target 不受影响").hasSize(1);
        assertThat(changeCount).as("无实际变更不触发回调").isEqualTo(before);
    }

    // ===== D-UNREG-3：unregister 不存在 → 幂等无操作 =====

    @Test
    void unregisterNonExistentIsIdempotent() {
        int before = changeCount;
        store.unregister("ghost");
        assertThat(store.list()).isEmpty();
        assertThat(changeCount).isEqualTo(before);
    }
}
```


---

## com/arthas/gateway/backend/RegistryComposerTest.java

**文件**：`src/test/java/com/arthas/gateway/backend/RegistryComposerTest.java`

```java
package com.arthas.gateway.backend;

import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T017 {@code RegistryComposer.compose} 纯逻辑测试（surefire 波次 A，无 K8S）。
 *
 * <p>断言 contracts/dynamic-registration-invariants.md §3/§5：
 * <ul>
 *   <li><b>D-COEXIST-1（I-2 关键）</b>：静态热重载移除某静态 target，但动态 target 仍在 effective
 *       （热重载不误删动态 target）。</li>
 *   <li><b>D-REG-1</b>：compose(static ∪ dynamic) 后 effective 含动态 target。</li>
 *   <li><b>D-ATOMIC-1（I-1）</b>：unchanged target 的 {@link BackendEntry} 实例跨 compose 不变
 *       （in-flight 调用持有的引用稳定，不串台）。</li>
 *   <li><b>I-6 复用减少重连</b>：同 name 同 config 的 Entry 复用旧实例（保连接池）。</li>
 *   <li><b>D-VERSION-1（I-7）</b>：无实际变更的 compose → {@code changed=false}、registry 维持原实例
 *       （调用方据此跳过 getAndSet，version 去重）；有变更 → version 单调递增。</li>
 *   <li><b>I-3 守护</b>：动态与静态同名（异 source）经 store 拒绝在先，compose 收到的 static/dynamic
 *       名字不相交；本测试用不相交名验证合并正确。</li>
 * </ul>
 *
 * <p>{@code compose} 为纯函数（输入 previous effective + static registry + dynamic cfgs → effective + toRetire）；
 * 「静态热重载移除 A、动态 D 仍在」即以 staticReg 不含 A、dynamic 含 D 调用 compose 验证。
 */
class RegistryComposerTest {

    private final BackendEntryFactory factory = new BackendEntryFactory();
    private final RegistryComposer composer = new RegistryComposer(factory);

    private static BackendConfig stat(String name, String url) {
        return new BackendConfig(name, url, Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5, Source.STATIC);
    }

    private static BackendConfig dyn(String name, String url) {
        return new BackendConfig(name, url, Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5, Source.DYNAMIC);
    }

    /** 由若干 cfg 经 factory 构造 Entry，组装 registry（version 仅为标识，compose 用自身计数器）。 */
    private BackendRegistry registry(long version, BackendConfig... cfgs) {
        Map<String, BackendEntry> byName = new LinkedHashMap<>();
        for (BackendConfig c : cfgs) {
            byName.put(c.name(), factory.create(c));
        }
        return new BackendRegistry(version, byName);
    }

    private static Collection<BackendConfig> dynList(BackendConfig... cfgs) {
        return List.of(cfgs);
    }

    // ===== D-REG-1：compose(static ∪ dynamic) 后 effective 含动态 target =====

    @Test
    void composeMergesStaticAndDynamic() {
        BackendRegistry previous = registry(1L); // 空
        BackendRegistry staticReg = registry(10L, stat("order", "http://1.1.1.1:8563"));
        RegistryComposer.ComposeResult r = composer.compose(previous, staticReg,
                dynList(dyn("srv-pod", "http://10.0.0.5:31234")));

        assertThat(r.changed()).isTrue();
        assertThat(r.registry().names()).containsExactlyInAnyOrder("order", "srv-pod");
        assertThat(r.toRetire()).isEmpty();
    }

    // ===== D-COEXIST-1（I-2 关键）：静态移除 A、动态 D 仍在 effective =====

    @Test
    void staticReloadRemovingTargetKeepsDynamicTarget() {
        // previous effective = 静态 A + 动态 D
        BackendRegistry previous = registry(1L, stat("alpha", "http://1.1.1.1:8563"),
                dyn("srv-pod", "http://10.0.0.5:31234"));
        // 静态热重载移除 alpha（staticReg 不含 alpha），动态 D 不受 YAML 影响（仍由 store 提供）
        BackendRegistry staticReg = registry(11L); // 空 static
        RegistryComposer.ComposeResult r = composer.compose(previous, staticReg,
                dynList(dyn("srv-pod", "http://10.0.0.5:31234")));

        assertThat(r.registry().names()).as("动态 D 仍在 effective（热重载不误删）").containsExactly("srv-pod");
        assertThat(r.toRetire()).as("被移除的静态 alpha 进下线").hasSize(1);
        assertThat(r.toRetire().get(0).config().name()).isEqualTo("alpha");
    }

    // ===== D-ATOMIC-1（I-1）/ I-6：unchanged target 的 Entry 实例跨 compose 不变（复用旧实例） =====

    @Test
    void unchangedEntriesAreReusedByInstancePreservingConnections() {
        BackendConfig staticCfg = stat("order", "http://1.1.1.1:8563");
        BackendConfig dynCfg = dyn("srv-pod", "http://10.0.0.5:31234");
        BackendEntry staticEntry = factory.create(staticCfg);
        BackendEntry dynEntry = factory.create(dynCfg);
        Map<String, BackendEntry> prevByName = new LinkedHashMap<>();
        prevByName.put("order", staticEntry);
        prevByName.put("srv-pod", dynEntry);
        BackendRegistry previous = new BackendRegistry(1L, prevByName);

        BackendRegistry staticReg = new BackendRegistry(11L, Map.of("order", staticEntry));
        RegistryComposer.ComposeResult r = composer.compose(previous, staticReg, dynList(dynCfg));

        assertThat(r.registry().get("order")).as("静态 Entry 复用旧实例（保连接）").containsSame(staticEntry);
        assertThat(r.registry().get("srv-pod")).as("动态 Entry 复用旧实例（保连接）").containsSame(dynEntry);
        assertThat(r.toRetire()).isEmpty();
    }

    // ===== D-VERSION-1（I-7）：无变更 → changed=false、registry 维持原实例、跳过 swap =====

    @Test
    void noChangeComposeSkipsSwapAndKeepsPreviousInstance() {
        BackendConfig staticCfg = stat("order", "http://1.1.1.1:8563");
        BackendConfig dynCfg = dyn("srv-pod", "http://10.0.0.5:31234");
        BackendEntry staticEntry = factory.create(staticCfg);
        BackendRegistry staticReg = new BackendRegistry(11L, Map.of("order", staticEntry));
        // 首次 compose 建立含 order + srv-pod 的 effective
        BackendRegistry effective = composer.compose(registry(1L), staticReg, dynList(dynCfg)).registry();

        // 再次 compose：static 与 dynamic 均未变 → 应复用、changed=false、registry 维持原实例
        RegistryComposer.ComposeResult r = composer.compose(effective, staticReg, dynList(dynCfg));
        assertThat(r.changed()).as("无变更 → changed=false（version 去重）").isFalse();
        assertThat(r.registry()).as("无变更 → 维持原 effective 实例（调用方跳过 getAndSet）").isSameAs(effective);
        assertThat(r.toRetire()).isEmpty();
    }

    // ===== I-7：有变更 → version 单调递增 =====

    @Test
    void changedComposesProduceMonotonicallyIncreasingVersions() {
        BackendRegistry staticReg = registry(11L, stat("order", "http://1.1.1.1:8563"));
        long v1 = composer.compose(registry(1L), staticReg, dynList()).registry().version();
        long v2 = composer.compose(registry(1L), staticReg,
                dynList(dyn("srv-pod", "http://10.0.0.5:31234"))).registry().version();
        long v3 = composer.compose(registry(1L), staticReg,
                dynList(dyn("srv-pod", "http://10.0.0.5:31234"),
                        dyn("srv-pod2", "http://10.0.0.5:31235"))).registry().version();
        assertThat(v2).as("每次变更 version 递增").isGreaterThan(v1);
        assertThat(v3).isGreaterThan(v2);
    }

    // ===== I-3 守护：静态/动态同名已由 store 拒绝在先，compose 收到不相交名 → 合并无冲突 =====

    @Test
    void disjointStaticAndDynamicNamesMergeWithoutConflict() {
        BackendRegistry staticReg = registry(11L, stat("order", "http://1.1.1.1:8563"));
        RegistryComposer.ComposeResult r = composer.compose(registry(1L), staticReg,
                dynList(dyn("srv-pod", "http://10.0.0.5:31234")));
        assertThat(r.registry().size()).isEqualTo(2);
    }
}
```


---

## com/arthas/gateway/backend/SourceParsingTest.java

**文件**：`src/test/java/com/arthas/gateway/backend/SourceParsingTest.java`

```java
package com.arthas.gateway.backend;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T011 {@code source} 来源标记解析测试（surefire 波次 A，纯逻辑，无 K8S）。
 *
 * <p>断言 D-SOURCE-1（见 contracts/dynamic-registration-invariants.md §5 / data-model §2/§3）：
 * <ul>
 *   <li>YAML 缺省 {@code source} → {@link Source#STATIC}（向后兼容，001 既有种子零改动可用）。</li>
 *   <li>显式 {@code source: STATIC} → STATIC。</li>
 *   <li>显式 {@code source: DYNAMIC} → DYNAMIC（loader 须能解析，纵使常态下动态 target 不经 YAML）。</li>
 *   <li>7 参构造（向后兼容）→ STATIC。</li>
 *   <li>{@code source} 不参与 equals（data-model §2：不参与复用判定核心）——同其余字段、异 source 仍 equal。</li>
 * </ul>
 *
 * <p>TDD：先于实现编写（red：{@link Source} 枚举 / {@code source()} 访问器尚不存在）。
 * 「动态注册路径强制 DYNAMIC」由 {@code DynamicBackendStoreTest}（T015）覆盖。
 */
class SourceParsingTest {

    private static InputStream yaml(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    private static BackendConfig first(BackendConfigLoader.LoadedBackends loaded) {
        return loaded.backends().get(0);
    }

    // ===== D-SOURCE-1：YAML 缺省 source → STATIC =====

    @Test
    void yamlOmittingSourceDefaultsToStatic() {
        BackendConfig cfg = first(new BackendConfigLoader().load(yaml("""
                version: 1
                backends:
                  - name: order
                    url: http://127.0.0.1:8563
                    protocol: STREAMABLE
                    auth: { mode: NONE }
                """)));
        assertThat(cfg.source()).as("缺省 source → STATIC（向后兼容）").isEqualTo(Source.STATIC);
    }

    @Test
    void yamlExplicitStaticParsesToStatic() {
        BackendConfig cfg = first(new BackendConfigLoader().load(yaml("""
                version: 1
                backends:
                  - name: order
                    url: http://127.0.0.1:8563
                    protocol: STREAMABLE
                    auth: { mode: NONE }
                    source: STATIC
                """)));
        assertThat(cfg.source()).isEqualTo(Source.STATIC);
    }

    @Test
    void yamlExplicitDynamicParsesToDynamic() {
        BackendConfig cfg = first(new BackendConfigLoader().load(yaml("""
                version: 1
                backends:
                  - name: dyn
                    url: http://10.0.0.5:31234
                    protocol: STREAMABLE
                    auth: { mode: NONE }
                    source: DYNAMIC
                """)));
        assertThat(cfg.source()).as("loader 须能解析显式 DYNAMIC").isEqualTo(Source.DYNAMIC);
    }

    @Test
    void yamlInvalidSourceValueRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        new BackendConfigLoader().load(yaml("""
                                version: 1
                                backends:
                                  - name: order
                                    url: http://127.0.0.1:8563
                                    protocol: STREAMABLE
                                    auth: { mode: NONE }
                                    source: BANANA
                                """)))
                .isInstanceOf(BackendConfigException.class);
    }

    // ===== 向后兼容：7 参构造 → STATIC =====

    @Test
    void sevenArgConstructorDefaultsToStatic() {
        BackendConfig cfg = new BackendConfig(
                "order", "http://127.0.0.1:8563", Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5);
        assertThat(cfg.source()).as("既有 7 参调用点零改动 → STATIC").isEqualTo(Source.STATIC);
    }

    @Test
    void eightArgConstructorHonorsExplicitSource() {
        BackendConfig cfg = new BackendConfig(
                "order", "http://127.0.0.1:8563", Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5, Source.DYNAMIC);
        assertThat(cfg.source()).isEqualTo(Source.DYNAMIC);
    }

    // ===== source 不参与 equals（data-model §2） =====

    @Test
    void sourceDoesNotParticipateInEquals() {
        BackendConfig staticCfg = new BackendConfig(
                "order", "http://127.0.0.1:8563", Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5, Source.STATIC);
        BackendConfig dynamicCfg = new BackendConfig(
                "order", "http://127.0.0.1:8563", Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5, Source.DYNAMIC);
        assertThat(dynamicCfg)
                .as("同 name/url/auth/超时/并发、异 source → 仍 equal（source 不参与复用判定）")
                .isEqualTo(staticCfg)
                .hasSameHashCodeAs(staticCfg);
    }
}
```


---

## com/arthas/gateway/contract/client/BackendClientContractIT.java

**文件**：`src/test/java/com/arthas/gateway/contract/client/BackendClientContractIT.java`

```java
package com.arthas.gateway.contract.client;

import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendClient;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.HttpBackendClient;
import com.arthas.gateway.backend.Protocol;
import com.arthas.gateway.testfixtures.ArthasMcpBackend;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T018 客户端契约测试（网关→arthas 后端，C-INIT/C-CALL/C-RESULT）。
 *
 * <p>用真实 {@link ArthasMcpBackend}（T009 夹具）驱动 {@link HttpBackendClient}，验证网关作为 MCP 客户端
 * 连真实 arthas 的行为承诺（{@code backend-client-contract.md} §3/§4）：<b>零桩</b>。
 *
 * <p><b>命名 *IT（failsafe）</b>：本测试连真实 arthas（attach + 真实诊断），按 pom surefire/failsafe
 * 分离约定（surefire=纯逻辑/状态机单测，非真实后端；failsafe=*IT 真实 arthas）走集成测试阶段。
 * tasks.md T018 原命名 *Test，此处调整为 *IT 以对齐工程约定。
 *
 * <p>断言（间接验证，证据驱动）：
 * <ul>
 *   <li>C-INIT：{@code initialize} 成功（Accept/Authorization 头正确——否则 arthas 拒绝握手）、
 *       {@code isInitialized} 翻转、二次 {@code initialize} 幂等不抛。</li>
 *   <li>C-CALL：{@code callTool("jvm", {})} 返回真实 JVM 诊断（转发链路通）。</li>
 *   <li>C-RESULT：结果 {@code content} 非空、{@code isError!=true}、含真实进程级数据（原样，非桩）。</li>
 * </ul>
 * target 剥离（C-CALL-1）属 {@code DiagnosticRequest}/路由层职责，由服务端路由契约测试覆盖，不在此。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BackendClientContractIT {

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void initializeHandshakeAndCallToolAgainstRealArthas() throws Exception {
        try (ArthasMcpBackend backend = ArthasMcpBackend.start("order")) {
            BackendConfig config = noneAuth("order", backend.baseUrl());
            try (BackendClient client = new HttpBackendClient(config)) {
                // C-INIT：握手前 isInitialized=false
                assertThat(client.isInitialized()).isFalse();
                client.initialize();
                assertThat(client.isInitialized()).isTrue();
                // 幂等：二次 initialize 不抛（C-INIT 已握手则空操作）
                client.initialize();

                // C-CALL / C-RESULT：同步 tools/call 转发，真实诊断原样返回
                McpSchema.CallToolResult result = client.callTool("jvm", Map.of());
                assertThat(result).isNotNull();
                assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
                assertThat(result.content()).isNotNull().isNotEmpty();

                String text = extractText(result);
                assertThat(text).as("jvm 工具应返回真实 JVM 诊断").isNotBlank();
                // arthas jvm 返回 JSON 含 jvmInfo/RUNTIME 等真实进程级结构（非桩硬证据）
                assertThat(text).containsAnyOf("jvmInfo", "RUNTIME", "resultCount", "MACHINE-NAME", "SPEC-NAME");
            }
        }
    }

    /** NONE 认证后端配置（测试夹具：url 指向 ArthasMcpBackend 动态端口）。 */
    private static BackendConfig noneAuth(String name, String url) {
        return new BackendConfig(
                name, url, Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5);
    }

    private static String extractText(McpSchema.CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent tc && tc.text() != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(tc.text());
            }
        }
        return sb.toString();
    }
}
```


---

## com/arthas/gateway/contract/client/FaultIsolationContractIT.java

**文件**：`src/test/java/com/arthas/gateway/contract/client/FaultIsolationContractIT.java`

```java
package com.arthas.gateway.contract.client;

import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.BackendEntry;
import com.arthas.gateway.backend.BackendEntryFactory;
import com.arthas.gateway.backend.BackendRegistry;
import com.arthas.gateway.backend.Protocol;
import com.arthas.gateway.backend.RegistryHolder;
import com.arthas.gateway.testfixtures.ArthasMcpBackend;
import com.arthas.gateway.testfixtures.McpClientHarness;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T043 故障隔离与限流契约测试（failsafe，真实故障条件 + 真实 arthas + 真实网关，零桩，backend-client-contract.md §5/§8）。
 *
 * <p>覆盖可真实驱动的契约点：
 * <ul>
 *   <li><b>C-CB-1</b>：{@code dead} 后端指向<b>关闭端口</b>（真实不可达，连接拒绝）→ 连续 3 次同步调用各计失败 →
 *       熔断 OPEN → 第 4 次 {@code guardCircuit} <b>立即</b>返 S-ERR-5（不发连接、不等 30s，{@code retryAfterMs&gt;0}）。</li>
 *   <li><b>C-CB-2</b>：{@code ognl 非法表达式} 连打 <b>5 次（&gt; 阈值 3）</b>→ 后端业务级响应（isError / 正常带错误文本
 *       / McpError，均属"后端已响应"）<b>不计</b>熔断——之后 {@code list-targets} 仍 {@code healthy=true}，后续 {@code jvm} 成功。
 *       判别证据：infra 失败 3 次即 OPEN（见 C-CB-1），业务响应连 5 次仍 CLOSED。</li>
 *   <li><b>C-LIMIT-1</b>：对同一 target 提交 5 个 async watch（numberOfExecutions=999 不触发，持槽 working）→
 *       第 6 个 {@code tryAcquireSlot} 失败 → 前置返 INVALID_PARAMS + {@code reason:concurrency_limit}（不越界打后端）。</li>
 *   <li><b>C-ISO-1</b>：order 提交一个 pending async watch（后台轮询占资源），同时 payment 同步 {@code jvm}
 *       <b>即时</b>返回（独立连接池/线程，order 的占用不拖慢 payment）。</li>
 * </ul>
 *
 * <p><b>显式延后（真实条件不可得，非桩替代）</b>：
 * <ul>
 *   <li><b>C-AUTH-1</b>（401+WWW-Authenticate）：需真实带认证 arthas 后端 + 错误 token 触发真实 401。
 *       当前 {@link ArthasMcpBackend} 仅 NONE 认证；认证夹具未构建。延后至认证后端夹具就绪。</li>
 *   <li><b>C-STATELESS-1</b>（无 Mcp-Session-Id 纯 JSON）：arthas MCP 为 Streamable <b>有状态</b>后端
 *       （每次 initialize 返 session-id），项目范围内无 STATELESS 后端，不适用、无法真实驱动。</li>
 * </ul>
 * 两者不计入熔断/隔离核心逻辑，由 {@code CircuitBreakerTest}（单测）+ {@code FailedTargetErrorIT}（SC-003/隔离）旁证。
 *
 * <p>槽位卫生：C-LIMIT-1/C-ISO-1 提交的 async watch 持 target 槽位，每个测试末尾 {@code task-cancel} 释放，
 * 避免跨测试槽位饱和。{@code @TestMethodOrder} 固定顺序。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class FaultIsolationContractIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TARGET_CLASS = "com.arthas.gateway.testfixtures.OrderService";
    private static final String TARGET_METHOD = "hotMethod";
    private static final String[] JVM_DIAGNOSTIC_MARKERS =
            {"jvmInfo", "RUNTIME", "resultCount", "MACHINE-NAME", "SPEC-NAME"};
    /** 关闭端口（discard/9 通常未监听）→ 真实连接拒绝，非桩。 */
    private static final String DEAD_URL = "http://127.0.0.1:9";

    @LocalServerPort
    private int port;

    @Autowired
    private RegistryHolder holder;

    @Autowired
    private BackendEntryFactory factory;

    private ArthasMcpBackend order;
    private ArthasMcpBackend payment;

    private String gatewayUrl() {
        return "http://localhost:" + port + "/mcp";
    }

    @BeforeAll
    void startBackendsAndRegister() throws Exception {
        order = ArthasMcpBackend.start("order");
        payment = ArthasMcpBackend.start("payment");
        Map<String, BackendEntry> byName = new LinkedHashMap<>();
        byName.put("order", factory.create(noneAuth("order", order.baseUrl())));
        byName.put("payment", factory.create(noneAuth("payment", payment.baseUrl())));
        byName.put("dead", factory.create(noneAuth("dead", DEAD_URL))); // 关闭端口，构造不连
        holder.getAndSet(new BackendRegistry(1L, byName));
    }

    @AfterAll
    void stopBackends() {
        if (order != null) {
            order.close();
        }
        if (payment != null) {
            payment.close();
        }
    }

    // ===== C-CB-1：连续 3 次连接失败 → 熔断 OPEN → 第 4 次立即返明确错误 =====

    @Test
    @Order(1)
    void c_cb_1_threeFailuresOpenCircuit_thenImmediateRejection() throws Exception {
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();
            // 前 3 次：真实连接拒绝 → 各 recordFailure（CLOSED 累计）
            for (int i = 1; i <= 3; i++) {
                assertThatThrownBy(() -> h.callTool("jvm", Map.of("target", "dead")))
                        .as("第 " + i + " 次连接失败 → S-ERR-5 backend_unreachable")
                        .isInstanceOf(McpError.class)
                        .satisfies(t -> assertThat(((McpError) t).getJsonRpcError().code()).isEqualTo(-32602));
            }
            // 第 4 次：熔断 OPEN → guardCircuit 立即拒（不发连接）
            long start = System.nanoTime();
            assertThatThrownBy(() -> h.callTool("jvm", Map.of("target", "dead")))
                    .as("OPEN 后立即拒").isInstanceOf(McpError.class)
                    .satisfies(t -> {
                        McpError err = (McpError) t;
                        Map<?, ?> data = (Map<?, ?>) err.getJsonRpcError().data();
                        assertThat(data.get("reason")).isEqualTo("backend_unreachable");
                        assertThat(((Number) data.get("retryAfterMs")).longValue())
                                .as("OPEN → retryAfterMs>0（退避未满）").isPositive();
                    });
            long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertThat(elapsedMs).as("立即拒（无 30s 等待）").isLessThan(2_000L);
        }
    }

    // ===== C-CB-2：后端业务级响应不计熔断（ognl 非法表达式连打 5 次 > 阈值 3，熔断仍 CLOSED） =====

    @Test
    @Order(2)
    void c_cb_2_businessErrorDoesNotTripBreaker() throws Exception {
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();
            // 触发后端业务级响应：ognl 非法表达式 → arthas 真实返（isError 或正常带错误文本，均"后端已响应"，非基础设施故障）。
            // 连打 5 次（> 熔断阈值 3）：若误计入失败，第 3 次即应 OPEN；此处验证 5 次业务响应后熔断仍 CLOSED。
            for (int i = 1; i <= 5; i++) {
                invokeBusinessOutcome(h, "ognl",
                        Map.of("target", "order", "expression", "@@@not valid ognl@@@"));
            }
            // 熔断未开：list-targets order healthy=true，且后续 jvm 成功
            assertThat(healthy(h, "order")).as("业务错误不计熔断 → 连 5 次后 healthy 仍 true").isTrue();
            CallToolResult jvm = h.callTool("jvm", Map.of("target", "order"));
            assertThat(jvm.isError()).as("后续 jvm 仍成功（熔断未开）").isNotEqualTo(Boolean.TRUE);
            assertThat(extractText(jvm)).containsAnyOf(JVM_DIAGNOSTIC_MARKERS);
        }
    }

    // ===== C-LIMIT-1：并发超 5 → 第 6 个前置限流 INVALID_PARAMS =====

    @Test
    @Order(3)
    void c_limit_1_sixthConcurrentTaskRejected() throws Exception {
        try (McpClientHarness h = new McpClientHarness(gatewayClientWithLongTimeout())) {
            h.initialize();
            List<String> taskIds = new ArrayList<>();
            // 5 个 watch（numberOfExecutions=999 不触发 → 持槽 working）
            for (int i = 0; i < 5; i++) {
                String taskId = submitWatch(h, "order");
                taskIds.add(taskId);
            }
            // 第 6 个 → tryAcquireSlot 失败 → INVALID_PARAMS + reason:concurrency_limit
            assertThatThrownBy(() -> submitWatch(h, "order"))
                    .as("第 6 个并发任务被前置限流").isInstanceOf(McpError.class)
                    .satisfies(t -> {
                        McpError err = (McpError) t;
                        assertThat(err.getJsonRpcError().code()).isEqualTo(-32602);
                        Map<?, ?> data = (Map<?, ?>) err.getJsonRpcError().data();
                        assertThat(data.get("reason")).isEqualTo("concurrency_limit");
                        assertThat(((Number) data.get("maxConcurrentTasks")).intValue()).isEqualTo(5);
                    });
            // 槽位卫生：取消 5 个持槽任务，释放 order 槽位供后续测试
            cancelAll(h, taskIds);
        }
    }

    // ===== C-ISO-1：target A 后端占用不拖慢 target B =====

    @Test
    @Order(4)
    void c_iso_1_targetAOccupancyDoesNotSlowTargetB() throws Exception {
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();
            // order 提交 pending async watch（后台轮询占资源）
            String orderTask = submitWatch(h, "order");

            // 同时 payment 同步 jvm → 即时返回（独立连接池/线程，不受 order 占用影响）
            long start = System.nanoTime();
            CallToolResult paymentJvm = h.callTool("jvm", Map.of("target", "payment"));
            long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

            assertThat(paymentJvm.isError()).as("payment jvm 成功").isNotEqualTo(Boolean.TRUE);
            assertThat(extractText(paymentJvm)).containsAnyOf(JVM_DIAGNOSTIC_MARKERS);
            assertThat(elapsedMs).as("payment 不被 order 占用拖慢（&lt;10s）").isLessThan(10_000L);

            cancelAll(h, List.of(orderTask));
        }
    }

    // ===== 辅助 =====

    /** 提交一个不触发的 async watch（numberOfExecutions=999），返 taskId。 */
    private static String submitWatch(McpClientHarness h, String target) throws java.io.IOException {
        CallToolResult acc = h.callTool("watch", Map.of(
                "target", target,
                "classPattern", TARGET_CLASS,
                "methodPattern", TARGET_METHOD,
                "numberOfExecutions", 999,
                "timeout", 30));
        JsonNode node = JSON.readTree(extractText(acc));
        String taskId = node.path("taskId").asText();
        assertThat(taskId).as("watch 立即返 taskId").matches("t-[0-9a-f]{6,}");
        return taskId;
    }

    private static void cancelAll(McpClientHarness h, List<String> taskIds) {
        for (String id : taskIds) {
            try {
                h.callTool("arthas-gateway.task-cancel", Map.of("taskId", id));
            } catch (RuntimeException ignored) {
                // 个别取消失败不影响断言（已终态等）
            }
        }
        try {
            Thread.sleep(500); // 让后台中断释放槽位
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 发起一次后端业务级调用，接受 isError=true/false 或 McpError（均为"后端已响应"，非基础设施故障）。 */
    private static void invokeBusinessOutcome(McpClientHarness h, String tool, Map<String, Object> args) {
        try {
            h.callTool(tool, args); // isError 与否均视为业务响应（成功或业务错误）
        } catch (McpError e) {
            // 后端 JSON-RPC error（如 INVALID_PARAMS）也是业务错误，不计熔断
        }
    }

    private static boolean healthy(McpClientHarness h, String target) throws java.io.IOException {
        CallToolResult r = h.callTool("arthas-gateway.list-targets", Map.of());
        for (JsonNode t : JSON.readTree(extractText(r)).path("targets")) {
            if (target.equals(t.path("name").asText())) {
                return t.path("healthy").asBoolean();
            }
        }
        return false;
    }

    private static String extractText(CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        for (Content c : result.content()) {
            if (c instanceof TextContent tc && tc.text() != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(tc.text());
            }
        }
        return sb.toString();
    }

    /** C-LIMIT-1 用默认超时座（submit 不阻塞，OK）；此方法留作扩展锚点。 */
    private String gatewayClientWithLongTimeout() {
        return gatewayUrl();
    }

    private static BackendConfig noneAuth(String name, String url) {
        return new BackendConfig(
                name, url, Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5);
    }
}
```


---

## com/arthas/gateway/contract/server/GatewayToolsContractTest.java

**文件**：`src/test/java/com/arthas/gateway/contract/server/GatewayToolsContractTest.java`

```java
package com.arthas.gateway.contract.server;

import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.BackendEntry;
import com.arthas.gateway.backend.BackendEntryFactory;
import com.arthas.gateway.backend.BackendRegistry;
import com.arthas.gateway.backend.Protocol;
import com.arthas.gateway.backend.RegistryHolder;
import com.arthas.gateway.handler.GatewayToolHandlers;
import com.arthas.gateway.handler.ToolsCallRouter;
import com.arthas.gateway.task.AsyncTaskExecutor;
import com.arthas.gateway.task.GatewayTask;
import com.arthas.gateway.task.TaskError;
import com.arthas.gateway.task.TaskStore;
import com.arthas.gateway.task.TaskState;
import com.arthas.gateway.tool.ExposedTool;
import com.arthas.gateway.tool.RoutingMode;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T029 网关自有工具契约测试（surefire，纯逻辑，零 MCP 传输 / 零真实后端）。
 *
 * <p>断言 gateway-tools-contract.md §6 的纯逻辑断言点（响应整形 + handler 分派）：
 * G-ASYNC-1（立即响应整形）、G-LT-1（list-targets 返回注册表）、G-TG-1/2/3（task-get 各状态 + isError 原样 +
 * 未知→INVALID_PARAMS）、G-TL-1（task-list 全量 + 过滤）、G-TC-1/2（task-cancel working→cancelled / 终态幂等）。
 *
 * <p><b>测试边界</b>（遵循宪法「真实环境、禁止桩」）：
 * <ul>
 *   <li>本测的是网关<b>自有</b>逻辑（响应整形 + handler 分派），非 arthas 交互。</li>
 *   <li>任务状态由 {@link GatewayTask} 自身转换方法（markCompleted/markFailed/markCancelled）置入——
 *       这是网关状态机（T031 已测），用于构造 handler 测试夹具；其内嵌的 CallToolResult 为<b>映射测试数据</b>
 *       （测「handler 是否把 result 正确放入 completed 响应」），<b>非</b> arthas 成功响应桩。</li>
 *   <li>真实 arthas 结果保真度（真实诊断→completed.result）由端到端 {@code AsyncTaskContractIT}（真实 arthas）覆盖；
 *       G-ASYNC-1 的「route 不阻塞」由 {@code AsyncTaskExecutorTest}（submit 非阻塞）+ 上述 IT 覆盖。</li>
 * </ul>
 */
class GatewayToolsContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant T0 = Instant.parse("2026-06-20T10:00:00Z");

    private TaskStore store;
    private AsyncTaskExecutor executor;
    private RegistryHolder registry;
    private GatewayToolHandlers handlers;
    private ToolsCallRouter router;

    @BeforeEach
    void setUp() {
        store = new TaskStore(Duration.ofHours(1), () -> T0);
        executor = new AsyncTaskExecutor(store, Duration.ofMinutes(11));
        BackendEntryFactory factory = new BackendEntryFactory();
        BackendEntry entry = factory.create(noneAuth("order", "http://127.0.0.1:1")); // closed port，构造不连
        registry = new RegistryHolder();
        registry.getAndSet(new BackendRegistry(7L, Map.of("order", entry)));
        handlers = new GatewayToolHandlers(registry, executor);
        router = new ToolsCallRouter(registry, executor, handlers);
    }

    @AfterEach
    void tearDown() {
        executor.close();
        store.close();
    }

    // ===== G-ASYNC-1：立即响应整形（固定 working，不读 task 状态避免与后台竞态） =====

    @Test
    void g_async_1_immediateResponseShape() throws Exception {
        GatewayTask task = working("t-7f3a9c", "watch", "order");

        CallToolResult resp = ToolsCallRouter.asyncAcceptedResponse(task);

        JsonNode node = json(resp);
        assertThat(node.path("taskId").asText()).isEqualTo("t-7f3a9c");
        assertThat(node.path("status").asText()).isEqualTo("working");
        assertThat(node.path("_meta").path("toolName").asText()).isEqualTo("watch");
        assertThat(node.path("_meta").path("target").asText()).isEqualTo("order");
    }

    // ===== G-LT-1：list-targets 返回注册表 =====

    @Test
    void g_lt_1_listTargetsReturnsRegistry() throws Exception {
        CallToolResult resp = handlers.handle(listTargetsTool(), new CallToolRequest("arthas-gateway.list-targets", Map.of()));

        JsonNode node = json(resp);
        assertThat(node.path("version").asLong()).isEqualTo(7L);
        assertThat(node.path("targets").isArray()).isTrue();
        JsonNode t = node.path("targets").get(0);
        assertThat(t.path("name").asText()).isEqualTo("order");
        assertThat(t.path("protocol").asText()).isEqualTo("STREAMABLE");
        assertThat(t.path("state").asText()).isEqualTo("ACTIVE");
    }

    // ===== G-TG-1：task-get 各状态分支 =====

    @Test
    void g_tg_1_taskGetByStatus() throws Exception {
        seed(working("t-1", "watch", "order"));
        seed(completed("t-2", "trace", "order", false));
        seed(failed("t-3", "stack", "order"));
        seed(cancelled("t-4", "tt", "order"));

        assertThat(taskGetStatus("t-1")).isEqualTo("working");
        assertThat(taskGetStatus("t-2")).isEqualTo("completed");
        assertThat(taskGetStatus("t-3")).isEqualTo("failed");
        assertThat(taskGetStatus("t-4")).isEqualTo("cancelled");

        JsonNode failed = json(taskGet("t-3"));
        assertThat(failed.path("error").path("reason").asText()).isEqualTo(TaskError.REASON_BACKEND_TIMEOUT);
        assertThat(failed.path("error").path("message").asText()).isNotBlank();
    }

    // ===== G-TG-2：后端 isError=true 在 completed.result 原样保留（不转 failed） =====

    @Test
    void g_tg_2_isErrorPreservedInCompletedResult() throws Exception {
        seed(completed("t-err", "watch", "order", true)); // isError=true 业务错误

        JsonNode node = json(taskGet("t-err"));

        assertThat(node.path("status").asText())
                .as("isError=true 是业务响应 → completed（非 failed）").isEqualTo("completed");
        assertThat(node.path("result").path("isError").asBoolean())
                .as("result.isError 原样保留为 true").isTrue();
    }

    // ===== G-TG-3：未知 taskId → INVALID_PARAMS =====

    @Test
    void g_tg_3_unknownTaskIdReturnsInvalidParams() {
        assertThatThrownBy(() -> taskGet("t-ghost"))
                .isInstanceOf(McpError.class)
                .satisfies(t -> assertThat(((McpError) t).getJsonRpcError().code()).isEqualTo(-32602));
    }

    // ===== G-TL-1：task-list 全量 + status 过滤 =====

    @Test
    void g_tl_1_taskListAllAndStatusFilter() throws Exception {
        seed(working("t-w", "watch", "order"));
        seed(completed("t-c", "trace", "order", false));
        seed(failed("t-f", "stack", "order"));

        JsonNode all = json(taskList(null));
        assertThat(all.path("tasks")).hasSize(3);

        JsonNode working = json(taskList("working"));
        assertThat(working.path("tasks")).hasSize(1);
        assertThat(working.path("tasks").get(0).path("taskId").asText()).isEqualTo("t-w");

        JsonNode failed = json(taskList("failed"));
        assertThat(failed.path("tasks")).hasSize(1);
        assertThat(failed.path("tasks").get(0).path("status").asText()).isEqualTo("failed");
    }

    // ===== G-TC-1：cancel working → cancelled =====

    @Test
    void g_tc_1_cancelWorkingTransitionsToCancelled() throws Exception {
        seed(working("t-w", "watch", "order"));

        JsonNode node = json(taskCancel("t-w"));

        assertThat(node.path("status").asText()).isEqualTo("cancelled");
        assertThat(store.get("t-w").orElseThrow().status()).isEqualTo(TaskState.CANCELLED);
    }

    // ===== G-TC-2：cancel 终态任务幂等（返当前状态，不报错） =====

    @Test
    void g_tc_2_cancelTerminalIsIdempotent() throws Exception {
        seed(cancelled("t-x", "tt", "order")); // 已终态

        JsonNode node = json(taskCancel("t-x"));

        assertThat(node.path("status").asText()).as("返当前状态 cancelled").isEqualTo("cancelled");
    }

    // ===== 夹具与辅助 =====

    private static GatewayTask working(String id, String tool, String target) {
        return new GatewayTask(id, tool, target, T0, () -> T0);
    }

    private static GatewayTask completed(String id, String tool, String target, boolean error) {
        GatewayTask t = working(id, tool, target);
        t.markCompleted(result("诊断输出：" + id, error));
        return t;
    }

    private static GatewayTask failed(String id, String tool, String target) {
        GatewayTask t = working(id, tool, target);
        t.markFailed(new TaskError(TaskError.REASON_BACKEND_TIMEOUT, "后端 11min 未响应"));
        return t;
    }

    private static GatewayTask cancelled(String id, String tool, String target) {
        GatewayTask t = working(id, tool, target);
        t.markCancelled();
        return t;
    }

    private void seed(GatewayTask task) {
        store.put(task);
    }

    private static CallToolResult result(String text, boolean error) {
        return new CallToolResult(List.of(new TextContent(text)), error, null, null);
    }

    private CallToolResult taskGet(String taskId) {
        return handlers.handle(taskGetTool(), new CallToolRequest("arthas-gateway.task-get", Map.of("taskId", taskId)));
    }

    private String taskGetStatus(String taskId) throws Exception {
        return json(taskGet(taskId)).path("status").asText();
    }

    private CallToolResult taskList(String status) {
        Map<String, Object> args = status != null ? Map.of("status", status) : Map.of();
        return handlers.handle(taskListTool(), new CallToolRequest("arthas-gateway.task-list", args));
    }

    private CallToolResult taskCancel(String taskId) {
        return handlers.handle(taskCancelTool(), new CallToolRequest("arthas-gateway.task-cancel", Map.of("taskId", taskId)));
    }

    private static JsonNode json(CallToolResult result) throws Exception {
        TextContent tc = (TextContent) result.content().get(0);
        return MAPPER.readTree(tc.text());
    }

    private static ExposedTool listTargetsTool() {
        return tool("arthas-gateway.list-targets");
    }

    private static ExposedTool taskGetTool() {
        return tool("arthas-gateway.task-get");
    }

    private static ExposedTool taskListTool() {
        return tool("arthas-gateway.task-list");
    }

    private static ExposedTool taskCancelTool() {
        return tool("arthas-gateway.task-cancel");
    }

    private static ExposedTool tool(String name) {
        return new ExposedTool(name, "test", Map.of("type", "object"), null, RoutingMode.GATEWAY_LOCAL);
    }

    private static BackendConfig noneAuth(String name, String url) {
        return new BackendConfig(
                name, url, Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5);
    }
}
```


---

## com/arthas/gateway/contract/server/InitializeAndToolsListContractTest.java

**文件**：`src/test/java/com/arthas/gateway/contract/server/InitializeAndToolsListContractTest.java`

```java
package com.arthas.gateway.contract.server;

import com.arthas.gateway.testfixtures.McpClientHarness;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 服务端契约测试：initialize 握手 + tools/list（contracts/server-contract.md §2-4、§7 断言点 S-INIT/S-TL）。
 *
 * <p>由官方 SDK client（{@link McpClientHarness}，走标准 MCP 协议、非裸 curl）驱动，断言网关作为
 * 标准 MCP 服务端对 Claude Code 的行为承诺。
 *
 * <p>本测试<b>不需要真实 arthas 后端</b>——initialize 与 tools/list 均为网关本地行为（协议骨架 + 静态注册表），
 * 故归 surefire（单元），不走 failsafe。tools/call 的真实后端路由由 US1 契约/集成测试覆盖。
 *
 * <p>TDD：先于服务端骨架编写，预期全红（工具数为 0 / 协议未装配）。
 *
 * @see McpClientHarness
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InitializeAndToolsListContractTest {

    /** SDK 2.0.0 锁定的协议版本接受列表（S-INIT-1）。 */
    private static final Set<String> ACCEPTED_PROTOCOL_VERSIONS =
            Set.of("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25");

    /** 4 个网关自有工具名前缀（区分 arthas 工具与网关/K8S 工具）。 */
    private static final String GATEWAY_TOOL_PREFIX = "arthas-gateway.";

    /** 3 个 K8S 编排工具名前缀（003 特性：k8s.list-pods/k8s.list-services/k8s.ensure-arthas-mcp，无 target 参数）。 */
    private static final String K8S_TOOL_PREFIX = "k8s.";

    @LocalServerPort
    private int port;

    private String baseUrl() {
        return "http://localhost:" + port + "/mcp";
    }

    // ===== S-INIT =====

    /** S-INIT-1：协议版本在列表内原样回显（客户端请求其最高版本，服务端回显列表内值）。 */
    @Test
    void s_init_1_protocolVersionEchoedFromAcceptList() {
        try (McpClientHarness h = new McpClientHarness(baseUrl())) {
            McpSchema.InitializeResult init = h.initialize();
            assertThat(init.protocolVersion())
                    .as("回显的协议版本须在 SDK 锁定的接受列表内")
                    .isIn(ACCEPTED_PROTOCOL_VERSIONS);
        }
    }

    /** S-INIT-2：serverInfo 非空且名字为网关；capabilities.tools 存在；不声明 prompts/resources。 */
    @Test
    void s_init_2_serverInfoAndCapabilities() {
        try (McpClientHarness h = new McpClientHarness(baseUrl())) {
            McpSchema.InitializeResult init = h.initialize();
            assertThat(init.serverInfo()).as("serverInfo 非空").isNotNull();
            assertThat(init.serverInfo().name())
                    .as("serverInfo.name 为 arthas-mcp-gateway")
                    .isEqualTo("arthas-mcp-gateway");
            assertThat(init.serverInfo().version())
                    .as("serverInfo.version 非空")
                    .isNotBlank();

            McpSchema.ServerCapabilities caps = init.capabilities();
            assertThat(caps).as("capabilities 非空").isNotNull();
            assertThat(caps.tools()).as("capabilities.tools 存在").isNotNull();
            assertThat(caps.tools().listChanged())
                    .as("tools.listChanged == false（工具集静态，不广播变更）")
                    .isEqualTo(false);
            assertThat(caps.prompts()).as("不声明 prompts（arthas 恒空）").isNull();
            assertThat(caps.resources()).as("不声明 resources（arthas 恒空）").isNull();
        }
    }

    /** S-INIT-3：notifications/initialized 后网关不报错；客户端进入已初始化态。 */
    @Test
    void s_init_3_afterInitializedNoError() {
        try (McpClientHarness h = new McpClientHarness(baseUrl())) {
            assertThatCode(h::initialize)
                    .as("initialize 握手（含 initialized 通知）不抛异常")
                    .doesNotThrowAnyException();
            assertThat(h.client().isInitialized())
                    .as("握手后客户端进入已初始化态")
                    .isTrue();
        }
    }

    // ===== S-TL =====

    /** S-TL-1：tools/list 工具数 == 38（31 arthas + 4 网关自有 + 3 K8S 编排）。 */
    @Test
    void s_tl_1_toolsCountIs38() {
        try (McpClientHarness h = new McpClientHarness(baseUrl())) {
            h.initialize();
            List<Tool> tools = h.listTools().tools();
            assertThat(tools).as("工具总数 == 38（31 arthas + 4 网关自有 + 3 K8S 编排）").hasSize(38);
        }
    }

    /** S-TL-2：每个 arthas 工具 inputSchema.properties 含 target 且 ∈ required；additionalProperties==false。 */
    @Test
    void s_tl_2_everyArthasToolHasTargetInRequiredAndAdditionalPropertiesFalse() {
        try (McpClientHarness h = new McpClientHarness(baseUrl())) {
            h.initialize();
            List<Tool> arthasTools = arthasTools(h.listTools().tools());
            assertThat(arthasTools).as("31 个 arthas 工具").hasSize(31);

            for (Tool tool : arthasTools) {
                Map<String, Object> schema = tool.inputSchema();
                Map<String, Object> properties = asMap(schema.get("properties"));
                assertThat(properties)
                    .as("%s: properties 含 target", tool.name())
                    .containsKey("target");
                assertThat(requiredOf(schema))
                    .as("%s: required 含 target", tool.name())
                    .contains("target");
                assertThat(schema.get("additionalProperties"))
                    .as("%s: additionalProperties == false", tool.name())
                    .isEqualTo(false);
            }
        }
    }

    /**
     * S-TL-3：除 target 外，inputSchema 逐字等于 arthas 原始 schema（剥离 target 后与能力清单 baseline 比对）。
     *
     * <p>验证整条链路无损：arthas-tools.json → StaticToolRegistry（注入 target）→ SDK Tool → MCP 线 → 客户端反序列化。
     */
    @Test
    void s_tl_3_inputSchemaVerbatimExceptTarget() {
        Map<String, Map<String, Object>> baseline = loadArthasBaseline();
        try (McpClientHarness h = new McpClientHarness(baseUrl())) {
            h.initialize();
            for (Tool tool : arthasTools(h.listTools().tools())) {
                Map<String, Object> stripped = stripTarget(tool.inputSchema());
                assertThat(toSortedMap(stripped))
                    .as("%s: 剥离 target 后逐字等于 arthas baseline", tool.name())
                    .isEqualTo(toSortedMap(baseline.get(tool.name()).get("inputSchema")));
            }
        }
    }

    /**
     * S-TL-4（A1 调整）：taskSupport 仅内部路由用，<b>不</b>在协议发射——
     * 断言线上 38 个工具的 inputSchema 与 Tool 均<b>不含</b> taskSupport / execution 字段。
     *
     * <p>原 S-TL-4（execution.taskSupport 符合预期）的线上断言改在 StaticToolRegistryTest（注册表/路由层）覆盖；
     * 此处仅校验协议边界不泄漏内部字段（用户决定 A1，见 memory sdk2-vs-spec-divergences）。
     */
    @Test
    void s_tl_4_noExecutionTaskSupportLeakedOnWire() {
        try (McpClientHarness h = new McpClientHarness(baseUrl())) {
            h.initialize();
            for (Tool tool : h.listTools().tools()) {
                assertThat(tool.inputSchema())
                    .as("%s: inputSchema 不含 taskSupport（仅内部路由用）", tool.name())
                    .doesNotContainKey("taskSupport");
                assertThat(tool.inputSchema())
                    .as("%s: inputSchema 不含 execution（SDK 2.0.0 无此 draft 扩展）", tool.name())
                    .doesNotContainKey("execution");
            }
        }
    }

    /** S-TL-5：tools/list 的 nextCursor == null（38 工具静态，不分页）。 */
    @Test
    void s_tl_5_nextCursorIsNull() {
        try (McpClientHarness h = new McpClientHarness(baseUrl())) {
            h.initialize();
            assertThat(h.listTools().nextCursor())
                    .as("nextCursor == null（不分页）")
                    .isNull();
        }
    }

    // ===== 辅助 =====

    /**
     * 从线上工具中筛出 31 个 arthas 工具（排除 arthas-gateway.* 网关自有工具与 k8s.* K8S 编排工具）。
     *
     * <p>K8S 编排工具（003 特性）与网关自有工具均<b>不含</b> target 参数（非 arthas 诊断工具），
     * 须从 S-TL-2/S-TL-3 的 target 逐字比对中剔除，否则会误判 K8S 工具"缺 target"为违约。
     */
    private static List<Tool> arthasTools(List<Tool> all) {
        return all.stream()
                .filter(t -> !t.name().startsWith(GATEWAY_TOOL_PREFIX))
                .filter(t -> !t.name().startsWith(K8S_TOOL_PREFIX))
                .toList();
    }

    /** 从 inputSchema 取 required（List&lt;String&gt;）。 */
    @SuppressWarnings("unchecked")
    private static List<String> requiredOf(Map<String, Object> schema) {
        Object raw = schema.get("required");
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                out.add((String) o);
            }
            return out;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        return raw instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /**
     * 剥离 target：从 properties 删 target 键、从 required 删 target 元素，其余逐字保留。
     */
    private static Map<String, Object> stripTarget(Map<String, Object> schema) {
        Map<String, Object> copy = deepMutable(schema);
        Map<String, Object> properties = asMap(copy.get("properties"));
        Map<String, Object> propsCopy = new LinkedHashMap<>(properties);
        propsCopy.remove("target");
        copy.put("properties", propsCopy);

        List<String> required = new ArrayList<>(requiredOf(copy));
        required.remove("target");
        copy.put("required", required);
        return copy;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepMutable(Map<String, Object> src) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (src == null) {
            return out;
        }
        src.forEach((k, v) -> out.put(k, switch (v) {
            case Map<?, ?> m -> deepMutable((Map<String, Object>) m);
            case List<?> l -> new ArrayList<>(l);
            default -> v;
        }));
        return out;
    }

    /** 转为按键名排序的 TreeMap，消除 Jackson/SDK 在 Map 键序上的非确定性，做逐字内容比对。 */
    private static Object toSortedMap(Object node) {
        if (node instanceof Map<?, ?> m) {
            Map<String, Object> sorted = new TreeMap<>();
            m.forEach((k, v) -> sorted.put((String) k, toSortedMap(v)));
            return sorted;
        }
        if (node instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object e : l) {
                out.add(toSortedMap(e));
            }
            return out;
        }
        return node;
    }

    /**
     * 独立加载 arthas-tools.json 作为逐字比对 baseline（不经过 StaticToolRegistry，避免循环依赖断言）。
     *
     * @return 工具名 → 该工具原始 spec（含 inputSchema，未注入 target）
     */
    private static Map<String, Map<String, Object>> loadArthasBaseline() {
        try (InputStream in = InitializeAndToolsListContractTest.class
                .getClassLoader().getResourceAsStream("arthas-tools.json")) {
            if (in == null) {
                throw new IllegalStateException("未找到 classpath 资源 arthas-tools.json");
            }
            Map<String, Object> root = new ObjectMapper()
                    .readValue(in, new TypeReference<Map<String, Object>>() {});
            List<Map<String, Object>> specs = asList(root.get("tools"));
            Map<String, Map<String, Object>> byName = new LinkedHashMap<>();
            for (Map<String, Object> spec : specs) {
                byName.put((String) spec.get("name"), spec);
            }
            return byName;
        } catch (Exception e) {
            throw new IllegalStateException("加载 arthas-tools.json baseline 失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object raw) {
        if (raw instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : list) {
                out.add((Map<String, Object>) o);
            }
            return out;
        }
        throw new IllegalStateException("arthas-tools.json 的 tools 字段须为数组");
    }
}
```


---

## com/arthas/gateway/contract/server/ListTargetsContractTest.java

**文件**：`src/test/java/com/arthas/gateway/contract/server/ListTargetsContractTest.java`

```java
package com.arthas.gateway.contract.server;

import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.BackendEntry;
import com.arthas.gateway.backend.BackendEntryFactory;
import com.arthas.gateway.backend.BackendRegistry;
import com.arthas.gateway.backend.Protocol;
import com.arthas.gateway.backend.RegistryHolder;
import com.arthas.gateway.handler.GatewayToolHandlers;
import com.arthas.gateway.task.AsyncTaskExecutor;
import com.arthas.gateway.task.TaskStore;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T038 {@code list-targets} 契约测试（surefire，handler 级纯逻辑，零 MCP 传输 / 零真实后端）。
 *
 * <p>补 {@code GatewayToolsContractTest.g_lt_1}（单 target）未覆盖的多 target + healthy 派生 + 无参契约：
 * <ul>
 *   <li><b>G-LT-1 多 target</b>：注册表含多个 target → {@code list-targets} 全量返回（name/state/protocol + version）。</li>
 *   <li><b>G-LT-1 healthy 派生</b>：{@code healthy = state==ACTIVE && breaker.state()!=OPEN}。注入一个熔断 OPEN 的 target
 *       （3 次 {@code recordFailure} 触发阈值）→ {@code healthy=false}，但<b>仍列出</b>（便于诊断，gateway-tools-contract.md §1）。
 *       熔断由 {@link com.arthas.gateway.backend.CircuitBreaker} 自身 API 驱动（网关自有逻辑，非 arthas 桩）。</li>
 *   <li><b>G-LT-2 无需 target 参数</b>：以空参 {@code Map.of()} 调用即成功，响应不依赖/回显 target。</li>
 * </ul>
 *
 * <p>「热重载后内容更新」由 {@code HotReloadIT}（真实文件监听）端到端覆盖；本测聚焦多 target 响应整形 + healthy 派生逻辑。
 */
class ListTargetsContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TaskStore store;
    private AsyncTaskExecutor executor;
    private RegistryHolder registry;
    private GatewayToolHandlers handlers;

    @BeforeEach
    void setUp() {
        BackendEntryFactory factory = new BackendEntryFactory();
        BackendEntry order = factory.create(noneAuth("order", "http://127.0.0.1:1"));     // ACTIVE + CLOSED
        BackendEntry payment = factory.create(noneAuth("payment", "http://127.0.0.1:2")); // ACTIVE + CLOSED
        BackendEntry inventory = factory.create(noneAuth("inventory", "http://127.0.0.1:3"));
        // 连续 3 次基础设施失败 → 熔断 OPEN（DEFAULT_FAILURE_THRESHOLD=3）
        inventory.breaker().recordFailure();
        inventory.breaker().recordFailure();
        inventory.breaker().recordFailure();

        Map<String, BackendEntry> byName = new LinkedHashMap<>();
        byName.put("order", order);
        byName.put("payment", payment);
        byName.put("inventory", inventory);

        store = new TaskStore(Duration.ofHours(1), java.time.Instant::now);
        executor = new AsyncTaskExecutor(store, Duration.ofMinutes(11));
        registry = new RegistryHolder();
        registry.getAndSet(new BackendRegistry(42L, byName));
        handlers = new GatewayToolHandlers(registry, executor);
    }

    @AfterEach
    void tearDown() {
        executor.close();
        store.close();
    }

    // ===== G-LT-1：多 target 全量返回 + version =====

    @Test
    void g_lt_1_multiTarget_returnsAllWithStateProtocolAndVersion() throws Exception {
        JsonNode node = json(listTargets());

        assertThat(node.path("version").asLong()).as("回显注册表 version").isEqualTo(42L);
        assertThat(node.path("targets").isArray()).isTrue();
        assertThat(node.path("targets")).as("多 target 全量列出").hasSize(3);

        // order / payment：ACTIVE + 熔断 CLOSED → healthy=true
        JsonNode order = target(node, "order");
        assertThat(order.path("state").asText()).isEqualTo("ACTIVE");
        assertThat(order.path("protocol").asText()).isEqualTo("STREAMABLE");
        assertThat(order.path("healthy").asBoolean()).as("ACTIVE+CLOSED → healthy").isTrue();

        JsonNode payment = target(node, "payment");
        assertThat(payment.path("healthy").asBoolean()).isTrue();
    }

    // ===== G-LT-1：healthy=false（熔断 OPEN）仍列出 =====

    @Test
    void g_lt_1_openBreaker_marksUnhealthyButStillListed() throws Exception {
        JsonNode node = json(listTargets());

        JsonNode inventory = target(node, "inventory");
        assertThat(inventory).as("熔断 target 仍列出（便于诊断）").isNotNull();
        assertThat(inventory.path("healthy").asBoolean())
                .as("breaker OPEN → healthy=false")
                .isFalse();
    }

    // ===== G-LT-2：无需 target 参数 =====

    @Test
    void g_lt_2_noTargetParameterRequired() throws Exception {
        // 空参调用成功 + 响应不依赖/回显 target（list-targets 描述「可用目标」，自身不消费 target）
        JsonNode node = json(listTargets());

        assertThat(node.has("target")).as("响应无 target 字段").isFalse();
        assertThat(node.path("targets").isArray()).isTrue();
    }

    // ===== 辅助 =====

    private CallToolResult listTargets() {
        return handlers.handle(
                new com.arthas.gateway.tool.ExposedTool(
                        "arthas-gateway.list-targets", "test", Map.of("type", "object"), null,
                        com.arthas.gateway.tool.RoutingMode.GATEWAY_LOCAL),
                new CallToolRequest("arthas-gateway.list-targets", Map.of()));
    }

    private static JsonNode json(CallToolResult result) throws Exception {
        TextContent tc = (TextContent) result.content().get(0);
        return MAPPER.readTree(tc.text());
    }

    private static JsonNode target(JsonNode root, String name) {
        for (JsonNode t : root.path("targets")) {
            if (name.equals(t.path("name").asText())) {
                return t;
            }
        }
        return null;
    }

    private static BackendConfig noneAuth(String name, String url) {
        return new BackendConfig(
                name, url, Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5);
    }
}
```


---

## com/arthas/gateway/contract/server/ToolsCallRoutingContractIT.java

**文件**：`src/test/java/com/arthas/gateway/contract/server/ToolsCallRoutingContractIT.java`

```java
package com.arthas.gateway.contract.server;

import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.BackendEntry;
import com.arthas.gateway.backend.BackendEntryFactory;
import com.arthas.gateway.backend.BackendRegistry;
import com.arthas.gateway.backend.Protocol;
import com.arthas.gateway.backend.RegistryHolder;
import com.arthas.gateway.testfixtures.ArthasMcpBackend;
import com.arthas.gateway.testfixtures.McpClientHarness;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T019 工具调用路由契约测试（服务端，S-CALL / S-ERR-2，真实 arthas + 真实网关，零桩）。
 *
 * <p>用真实 {@link ArthasMcpBackend}（T009 夹具）拉起 arthas MCP 后端，经 {@link RegistryHolder#getAndSet}
 * 将真实后端注入网关装配的注册表（覆盖 {@code backends.yaml} 的示例占位），再由官方 SDK client
 * （{@link McpClientHarness}）连<b>网关</b> {@code /mcp} 端点驱动 {@code tools/call}——验证整条路由链路：
 * MCP 协议 → 网关 handler → {@code ToolsCallRouter} → {@code BackendClient} → 真实 arthas。
 *
 * <p><b>动态注册表注入</b>：{@code @SpringBootTest} context 启动时装配 RegistryHolder 指向 {@code backends.yaml}
 * 示例 url（虚构，{@code HttpBackendClient} 构造不连）；{@code @BeforeAll} 启动真实后端后 {@code getAndSet}
 * 覆盖为指向真实 {@code baseUrl} 的 {@link BackendEntry}。{@code HttpBackendClient.initialize} 首次路由时才握手。
 *
 * <p>断言：
 * <ul>
 *   <li>S-CALL：{@code jvm target=order} 经网关路由到真实 arthas，返回真实 JVM 诊断（透传无损）。</li>
 *   <li>S-CALL-2（间接）：jvm 成功证明剥离工作——网关注入的 {@code target} 未透传给后端
 *       （arthas jvm schema 无 target；若未剥离，arthas 因 additionalProperties:false 拒绝）。target 剥离的
 *       强保证在 {@code DiagnosticRequestTest}（单元）。</li>
 *   <li>S-ERR-2：{@code target=ghost}（不在册）→ INVALID_PARAMS(-32602) + {@code data.available} 含真实在册 target。</li>
 * </ul>
 *
 * <p>{@code @TestInstance(PER_CLASS)} 使 {@code @BeforeAll/@AfterAll} 可为非静态实例方法，从而访问
 * {@code @Autowired} 注入的 RegistryHolder（Spring 在 beforeAll 前完成字段注入）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ToolsCallRoutingContractIT {

    /** arthas jvm 返回 JSON 含的真实进程级结构标记（与 BackendClientContractIT 一致，非桩硬证据）。 */
    private static final String[] JVM_DIAGNOSTIC_MARKERS =
            {"jvmInfo", "RUNTIME", "resultCount", "MACHINE-NAME", "SPEC-NAME"};

    @LocalServerPort
    private int port;

    @Autowired
    private RegistryHolder holder;

    @Autowired
    private BackendEntryFactory factory;

    private ArthasMcpBackend backend;

    private String gatewayUrl() {
        return "http://localhost:" + port + "/mcp";
    }

    @BeforeAll
    void startRealArthasAndRegister() throws Exception {
        backend = ArthasMcpBackend.start("order");
        // 用真实后端覆盖装配的示例注册表（指向 backends.yaml 虚构 url）
        BackendEntry entry = factory.create(noneAuth("order", backend.baseUrl()));
        holder.getAndSet(new BackendRegistry(1L, Map.of("order", entry)));
    }

    @AfterAll
    void stopBackend() {
        if (backend != null) {
            backend.close();
        }
    }

    // ===== S-CALL / S-CALL-2 =====

    @Test
    void s_call_jvmRealDiagnosticRoutesThroughGateway() {
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();
            CallToolResult result = h.callTool("jvm", Map.of("target", "order"));

            assertThat(result).as("jvm 经网关路由返回非空结果").isNotNull();
            assertThat(result.isError())
                    .as("jvm 诊断成功（isError 非 true）")
                    .isNotEqualTo(Boolean.TRUE);
            assertThat(result.content()).as("content 非空").isNotEmpty();
            assertThat(extractText(result))
                    .as("网关透传真实 JVM 诊断（含进程级结构标记）")
                    .containsAnyOf(JVM_DIAGNOSTIC_MARKERS);
        }
    }

    // ===== S-ERR-2：target 不在册（端到端） =====

    @Test
    void s_err_2_unknownTargetReturnsInvalidParamsWithAvailable() {
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();
            assertThatThrownBy(() -> h.callTool("jvm", Map.of("target", "ghost")))
                    .as("不在册的 target 经网关返 INVALID_PARAMS")
                    .isInstanceOf(McpError.class)
                    .satisfies(t -> {
                        McpError err = (McpError) t;
                        assertThat(err.getJsonRpcError().code()).isEqualTo(-32602);
                        Map<?, ?> data = (Map<?, ?>) err.getJsonRpcError().data();
                        assertThat(data).as("data 含 available").isNotNull();
                        assertThat(data.containsKey("available"))
                                .as("data.available 字段存在")
                                .isTrue();
                        assertThat(data.get("available").toString())
                                .as("available 含真实在册 target")
                                .contains("order");
                    });
        }
    }

    // ===== S-ERR-3：未知工具（端到端，SDK 协议层） =====

    @Test
    void s_err_3_unknownToolReturnsProtocolError() {
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();
            // 未知工具名：SDK 注册表无此工具 → 协议层 error（不到网关 handler）
            assertThatThrownBy(() -> h.callTool("nonexistent-tool-xyz", Map.of("target", "order")))
                    .as("未知工具名 → 协议层错误（不到网关 handler）")
                    .isInstanceOf(McpError.class)
                    .satisfies(t -> {
                        int code = ((McpError) t).getJsonRpcError().code();
                        assertThat(code)
                                .as("未知工具返 INVALID_PARAMS(-32602) 或 METHOD_NOT_FOUND(-32601)")
                                .isIn(-32602, -32601);
                    });
        }
    }

    /** NONE 认证后端配置（指向真实 ArthasMcpBackend 动态端口）。 */
    private static BackendConfig noneAuth(String name, String url) {
        return new BackendConfig(
                name, url, Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5);
    }

    private static String extractText(CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        for (Content content : result.content()) {
            if (content instanceof TextContent tc && tc.text() != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(tc.text());
            }
        }
        return sb.toString();
    }
}
```


---

## com/arthas/gateway/domain/DomainEnumsTest.java

**文件**：`src/test/java/com/arthas/gateway/domain/DomainEnumsTest.java`

```java
package com.arthas.gateway.domain;

import com.arthas.gateway.backend.BackendState;
import com.arthas.gateway.backend.Protocol;
import com.arthas.gateway.tool.RoutingMode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 领域枚举契约测试（T011）。
 * <p>
 * 锁定三个领域叶子枚举的取值集合（宪法原则二：证据驱动——取值源自 data-model.md §2/§3/§9）：
 * <ul>
 *   <li>{@link Protocol}：后端协议（STREAMABLE / STATELESS）</li>
 *   <li>{@link RoutingMode}：工具路由模式（同步直发 / 流式聚合 / 异步任务 / 网关本地）</li>
 *   <li>{@link BackendState}：后端运行态（在册 / 退役中）</li>
 * </ul>
 * 用名字字符串断言而非枚举常量自身，避免「用枚举列举自己」的循环，构成真正的契约钉子。
 */
class DomainEnumsTest {

    @Test
    void protocol_exposesExactlyStreamableAndStateless() {
        assertThat(Arrays.stream(Protocol.values()).map(Enum::name))
                .containsExactlyInAnyOrder("STREAMABLE", "STATELESS");
    }

    @Test
    void routingMode_exposesExactlyFourStrategies() {
        // SYNC_DIRECT(26 即时) / STREAM_AGGREGATE(dashboard) / ASYNC_TASK(5 optional) / GATEWAY_LOCAL(4 自有工具)
        assertThat(Arrays.stream(RoutingMode.values()).map(Enum::name))
                .containsExactlyInAnyOrder(
                        "SYNC_DIRECT", "STREAM_AGGREGATE", "ASYNC_TASK", "GATEWAY_LOCAL");
    }

    @Test
    void backendState_exposesExactlyActiveAndRetired() {
        // ACTIVE=在册；RETIRED=热重载移除中（in-flight 调用可完成，见 data-model.md §3）
        assertThat(Arrays.stream(BackendState.values()).map(Enum::name))
                .containsExactlyInAnyOrder("ACTIVE", "RETIRED");
    }
}
```


---

## com/arthas/gateway/handler/DiagnosticRequestNullArgTest.java

**文件**：`src/test/java/com/arthas/gateway/handler/DiagnosticRequestNullArgTest.java`

```java
package com.arthas.gateway.handler;

import com.arthas.gateway.tool.ExposedTool;
import com.arthas.gateway.tool.RoutingMode;
import com.arthas.gateway.tool.TaskSupport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T014 {@link DiagnosticRequest} null 值参数容忍单测(002 整改 · P2-2,US4)。
 *
 * <p>验证后端可选参数在某些客户端(MCP SDK 反序列化)下会以 <b>null value</b> 显式出现(如 {@code {"target":"x","timeout":null}})。
 * 修复前 {@code Map.copyOf} 对 null value 抛 NPE 导致整次解析失败(误伤合法调用);修复后须<b>容忍 null value</b>,
 * 原样保留(剥离 target)、保持不可变 + 稳定序(LinkedHashMap)。
 */
class DiagnosticRequestNullArgTest {

    @Test
    void nullValueInArgumentsIsPreservedTargetStrippedImmutable() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("target", "order");
        arguments.put("timeout", null); // 可选参数显式 null
        arguments.put("express", "@params[0]");

        DiagnosticRequest dr = DiagnosticRequest.parse(syncTool(), new CallToolRequest("jvm", arguments));

        assertThat(dr.target()).isEqualTo("order");
        // target 已剥离,永不进后端参数(S-CALL-2)
        assertThat(dr.backendArgs()).doesNotContainKey("target");
        // null value 原样保留(容忍,不抛 NPE)
        assertThat(dr.backendArgs()).containsEntry("timeout", null);
        assertThat(dr.backendArgs()).containsEntry("express", "@params[0]");
        // 稳定序保留
        assertThat(dr.backendArgs().keySet()).containsExactly("timeout", "express");
        // 不可变(防御拷贝)
        assertThatThrownBy(() -> dr.backendArgs().put("extra", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static ExposedTool syncTool() {
        return new ExposedTool(
                "jvm",
                "JVM 诊断",
                Map.of(
                        "type", "object",
                        "properties", new LinkedHashMap<>(),
                        "required", List.of(),
                        "additionalProperties", false),
                TaskSupport.FORBIDDEN,
                RoutingMode.SYNC_DIRECT);
    }
}
```


---

## com/arthas/gateway/handler/DiagnosticRequestTest.java

**文件**：`src/test/java/com/arthas/gateway/handler/DiagnosticRequestTest.java`

```java
package com.arthas.gateway.handler;

import com.arthas.gateway.tool.ExposedTool;
import com.arthas.gateway.tool.RoutingMode;
import com.arthas.gateway.tool.TaskSupport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T026 {@link DiagnosticRequest} 纯逻辑单测（surefire，无 arthas）。
 *
 * <p>覆盖 server-contract §5.1 的「解析」职责：toolName 命中（由 SDK 保证，本类不验）、
 * {@code target} 取出并剥离、{@code backendArgs} 原样保留（target 永不进后端参数，S-CALL-2）、
 * {@code routingMode} 判定、target 缺失/空/类型非法 → INVALID_PARAMS(-32602)（S-ERR-1）。
 *
 * <p>「target 不在册 → INVALID_PARAMS + data.available」（S-ERR-2）依赖注册表，属
 * {@link ToolsCallRouter} 职责，由路由测试覆盖，不在此。
 */
class DiagnosticRequestTest {

    /** 构造一个 routingMode 可配的 arthas 工具（inputSchema 占位，本类不验 schema）。 */
    private static ExposedTool tool(RoutingMode mode) {
        return new ExposedTool(
                "test-tool",
                "测试工具",
                Map.of(
                        "type", "object",
                        "properties", new LinkedHashMap<>(),
                        "required", List.of(),
                        "additionalProperties", false),
                TaskSupport.FORBIDDEN,
                mode);
    }

    // ===== 正常解析 =====

    @Test
    void parseExtractsTargetAndPreservesBackendArgsVerbatim() {
        ExposedTool tool = tool(RoutingMode.SYNC_DIRECT);
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("target", "order-service");
        arguments.put("classPattern", "com.example.OrderService");
        arguments.put("express", "@params[0]");

        DiagnosticRequest dr = DiagnosticRequest.parse(tool, new CallToolRequest("test-tool", arguments));

        assertThat(dr.target()).isEqualTo("order-service");
        assertThat(dr.routingMode()).isEqualTo(RoutingMode.SYNC_DIRECT);
        // backendArgs 原样保留除 target 外的全部参数
        assertThat(dr.backendArgs())
                .containsEntry("classPattern", "com.example.OrderService")
                .containsEntry("express", "@params[0]");
    }

    @Test
    void parseStripsTargetSoItNeverReachesBackend() {
        ExposedTool tool = tool(RoutingMode.SYNC_DIRECT);

        DiagnosticRequest dr = DiagnosticRequest.parse(
                tool, new CallToolRequest("test-tool", Map.of("target", "order", "classPattern", "X")));

        assertThat(dr.backendArgs())
                .as("target 永不进后端参数（剥离验证，S-CALL-2）")
                .doesNotContainKey("target");
    }

    @Test
    void parsePreservesRoutingModeForEachMode() {
        for (RoutingMode mode : RoutingMode.values()) {
            ExposedTool tool = tool(mode);
            DiagnosticRequest dr = DiagnosticRequest.parse(
                    tool, new CallToolRequest("test-tool", Map.of("target", "t")));
            assertThat(dr.routingMode())
                    .as("routingMode 取自 ExposedTool（%s）", mode)
                    .isEqualTo(mode);
        }
    }

    @Test
    void parseBackendArgsIsDefensiveCopy() {
        ExposedTool tool = tool(RoutingMode.SYNC_DIRECT);
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("target", "t");
        arguments.put("classPattern", "X");

        DiagnosticRequest dr = DiagnosticRequest.parse(tool, new CallToolRequest("test-tool", arguments));

        // 事后篡改原 Map，不得泄漏到 backendArgs（不可变快照语义）
        arguments.put("classPattern", "TAMPERED");
        arguments.put("extra", "leak");
        assertThat(dr.backendArgs())
                .as("backendArgs 为防御性拷贝，原 arguments 篡改不泄漏")
                .containsEntry("classPattern", "X")
                .doesNotContainKey("extra");
    }

    // ===== target 校验 → INVALID_PARAMS(-32602) =====

    @Test
    void parseNullArgumentsThrowsInvalidParams() {
        ExposedTool tool = tool(RoutingMode.SYNC_DIRECT);
        assertThatThrownBy(() -> DiagnosticRequest.parse(tool, new CallToolRequest("test-tool", null)))
                .isInstanceOf(McpError.class)
                .hasFieldOrPropertyWithValue("jsonRpcError.code", -32602);
    }

    @Test
    void parseMissingTargetThrowsInvalidParams() {
        ExposedTool tool = tool(RoutingMode.SYNC_DIRECT);
        assertThatThrownBy(() -> DiagnosticRequest.parse(tool, new CallToolRequest("test-tool", Map.of())))
                .isInstanceOf(McpError.class)
                .hasFieldOrPropertyWithValue("jsonRpcError.code", -32602);
    }

    @Test
    void parseBlankTargetThrowsInvalidParams() {
        ExposedTool tool = tool(RoutingMode.SYNC_DIRECT);
        assertThatThrownBy(() -> DiagnosticRequest.parse(
                tool, new CallToolRequest("test-tool", Map.of("target", "   "))))
                .isInstanceOf(McpError.class)
                .hasFieldOrPropertyWithValue("jsonRpcError.code", -32602);
    }

    @Test
    void parseNonStringTargetThrowsInvalidParams() {
        ExposedTool tool = tool(RoutingMode.SYNC_DIRECT);
        assertThatThrownBy(() -> DiagnosticRequest.parse(
                tool, new CallToolRequest("test-tool", Map.of("target", 123))))
                .isInstanceOf(McpError.class)
                .hasFieldOrPropertyWithValue("jsonRpcError.code", -32602);
    }

    @Test
    void invalidParamsErrorCarriesDescriptiveMessage() {
        ExposedTool tool = tool(RoutingMode.SYNC_DIRECT);
        assertThatThrownBy(() -> DiagnosticRequest.parse(tool, new CallToolRequest("test-tool", Map.of())))
                .isInstanceOf(McpError.class)
                .satisfies(e -> {
                    McpError err = (McpError) e;
                    assertThat(err.getJsonRpcError().message())
                            .as("INVALID_PARAMS 附可读消息（含 target 字段名）")
                            .contains("target");
                });
    }
}
```


---

## com/arthas/gateway/handler/HealthSingleSourceTest.java

**文件**：`src/test/java/com/arthas/gateway/handler/HealthSingleSourceTest.java`

```java
package com.arthas.gateway.handler;

import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.BackendEntry;
import com.arthas.gateway.backend.BackendEntryFactory;
import com.arthas.gateway.backend.BackendRegistry;
import com.arthas.gateway.backend.Protocol;
import com.arthas.gateway.backend.RegistryHolder;
import com.arthas.gateway.obs.BackendRegistryHealthIndicator;
import com.arthas.gateway.task.AsyncTaskExecutor;
import com.arthas.gateway.task.TaskStore;
import com.arthas.gateway.tool.ExposedTool;
import com.arthas.gateway.tool.RoutingMode;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T023 健康判定单一事实源测试(002 整改 · P3-2/FR-012,US5)。
 *
 * <p>钉住「三处健康判定一致」契约:{@code BackendEntry.isHealthy()}(单一事实源,
 * T027 实现委托目标)、{@code list-targets} 的 {@code healthy} 字段、{@code HealthIndicator} 的
 * {@code healthy} detail——三者对同一后端状态(熔断 CLOSED/OPEN、ACTIVE/RETIRED)须返回一致结果。
 *
 * <p>修复前三处各自内联 {@code state==ACTIVE && breaker.state()!=OPEN},任一处独立漂移将导致
 * list-targets 与 actuator/health 对同一后端报不同健康(运维困惑)。T027 收口到 {@code isHealthy()} 后,
 * 本测持续守护该不变量。熔断 OPEN 由真实 {@code recordFailure×3} 驱动(非桩),与既有
 * {@code BackendRegistryHealthIndicatorTest}/{@code ListTargetsContractTest} 一致。
 *
 * <p>本测为<b>钉契约型</b>(pinning):在 T027 委托前后均应 GREEN(委托是无行为变更的重构)。
 */
class HealthSingleSourceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TaskStore store;
    private AsyncTaskExecutor executor;
    private RegistryHolder registry;
    private GatewayToolHandlers handlers;
    private BackendRegistryHealthIndicator indicator;

    private BackendEntry healthy;
    private BackendEntry openBreaker;
    private BackendEntry retired;

    @BeforeEach
    void setUp() {
        BackendEntryFactory factory = new BackendEntryFactory();
        healthy = factory.create(cfg("healthy"));       // ACTIVE + CLOSED → healthy
        openBreaker = factory.create(cfg("open"));      // ACTIVE + OPEN(真实 recordFailure×3)→ unhealthy
        openBreaker.breaker().recordFailure();
        openBreaker.breaker().recordFailure();
        openBreaker.breaker().recordFailure();
        retired = factory.create(cfg("retired"));       // RETIRED + CLOSED → unhealthy
        retired.markRetired();

        Map<String, BackendEntry> byName = new LinkedHashMap<>();
        byName.put("healthy", healthy);
        byName.put("open", openBreaker);
        byName.put("retired", retired);

        store = new TaskStore(Duration.ofHours(1), java.time.Instant::now);
        executor = new AsyncTaskExecutor(store, Duration.ofMinutes(11));
        registry = new RegistryHolder();
        registry.getAndSet(new BackendRegistry(1L, byName));
        handlers = new GatewayToolHandlers(registry, executor);
        indicator = new BackendRegistryHealthIndicator(registry);
    }

    @AfterEach
    void tearDown() {
        executor.close();
        store.close();
    }

    @Test
    void allThreeSourcesAgreePerBackendState() throws Exception {
        JsonNode targets = json(listTargets()).path("targets");
        Map<?, ?> healthBackends = (Map<?, ?>) indicator.health().getDetails().get("backends");

        for (String name : new String[]{"healthy", "open", "retired"}) {
            BackendEntry entry = switch (name) {
                case "healthy" -> healthy;
                case "open" -> openBreaker;
                default -> retired;
            };
            boolean fromEntry = entry.isHealthy();                       // 单一事实源(T027 委托目标)
            boolean fromListTargets = target(targets, name).path("healthy").asBoolean();
            boolean fromHealthIndicator = (boolean) ((Map<?, ?>) healthBackends.get(name)).get("healthy");

            assertThat(fromListTargets).as("%s: list-targets 与 isHealthy 一致", name).isEqualTo(fromEntry);
            assertThat(fromHealthIndicator).as("%s: HealthIndicator 与 isHealthy 一致", name).isEqualTo(fromEntry);
        }
    }

    @Test
    void openBreakerAndRetiredAreUnhealthyEverywhere() {
        // 三处均判 OPEN/RETIRED 为 unhealthy(便于诊断,不被悄悄当健康)
        assertThat(healthy.isHealthy()).isTrue();
        assertThat(openBreaker.isHealthy()).isFalse();
        assertThat(retired.isHealthy()).isFalse();
    }

    private CallToolResult listTargets() {
        return handlers.handle(
                new ExposedTool("arthas-gateway.list-targets", "test", Map.of("type", "object"), null, RoutingMode.GATEWAY_LOCAL),
                new CallToolRequest("arthas-gateway.list-targets", Map.of()));
    }

    private static JsonNode json(CallToolResult result) throws Exception {
        TextContent tc = (TextContent) result.content().get(0);
        return MAPPER.readTree(tc.text());
    }

    private static JsonNode target(JsonNode root, String name) {
        for (JsonNode t : root) {
            if (name.equals(t.path("name").asText())) {
                return t;
            }
        }
        throw new AssertionError("未找到 target：" + name);
    }

    private static BackendConfig cfg(String name) {
        return new BackendConfig(name, "http://127.0.0.1:9", Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null), 5000, 30000, 5);
    }
}
```


---

## com/arthas/gateway/handler/McpJsonTest.java

**文件**：`src/test/java/com/arthas/gateway/handler/McpJsonTest.java`

```java
package com.arthas.gateway.handler;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T025 {@link McpJson} 单例/封装单测(002 整改 · P3-3/FR-013,US5)。
 *
 * <p>验证全局共享 {@code ObjectMapper} 单例 + {@code json(Object)} 把对象序列化为
 * {@code CallToolResult}(单 {@link TextContent} JSON、{@code isError=false})——
 * 供 list-targets、task 系列自有工具、asyncAcceptedResponse 与错误体整形共用,
 * 替代散在三处的 {@code new ObjectMapper()}。
 */
class McpJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void mapperSingletonIsAvailable() {
        // 全局单例非空(供 StaticToolRegistry.readValue 等复用)
        assertThat(McpJson.MAPPER).isNotNull();
        assertThat(McpJson.MAPPER).isSameAs(McpJson.MAPPER); // 静态 final,恒等
    }

    @Test
    void jsonSerializesObjectToTextContent() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", "t-1");
        body.put("status", "working");

        CallToolResult result = McpJson.json(body);

        assertThat(result.isError()).as("非错误响应").isFalse();
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0)).isInstanceOf(TextContent.class);

        TextContent tc = (TextContent) result.content().get(0);
        JsonNode node = MAPPER.readTree(tc.text());
        assertThat(node.path("taskId").asText()).isEqualTo("t-1");
        assertThat(node.path("status").asText()).isEqualTo("working");
    }

    @Test
    void jsonPreservesNestedStructure() throws Exception {
        // 嵌套 _meta(异步接受响应用)正确序列化——证明与修复前 writeValueAsString 行为一致
        Map<String, Object> body = Map.of("taskId", "t-2", "_meta",
                Map.of("toolName", "watch", "target", "order"));

        JsonNode node = MAPPER.readTree(((TextContent) McpJson.json(body).content().get(0)).text());
        assertThat(node.path("_meta").path("toolName").asText()).isEqualTo("watch");
        assertThat(node.path("_meta").path("target").asText()).isEqualTo("order");
    }
}
```


---

## com/arthas/gateway/handler/ToolsCallRouterStatelessTest.java

**文件**：`src/test/java/com/arthas/gateway/handler/ToolsCallRouterStatelessTest.java`

```java
package com.arthas.gateway.handler;

import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.BackendEntry;
import com.arthas.gateway.backend.BackendRegistry;
import com.arthas.gateway.backend.CircuitBreaker;
import com.arthas.gateway.backend.Protocol;
import com.arthas.gateway.backend.RegistryHolder;
import com.arthas.gateway.task.AsyncTaskExecutor;
import com.arthas.gateway.task.TaskStore;
import com.arthas.gateway.testfixtures.FakeBackendClient;
import com.arthas.gateway.tool.ExposedTool;
import com.arthas.gateway.tool.RoutingMode;
import com.arthas.gateway.tool.TaskSupport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T012 {@link ToolsCallRouter} STATELESS 异步前置拒绝单测(002 整改 · P1-2,US3)。
 *
 * <p>验证后端协议契约(原理四双侧契约):对 <b>STATELESS</b> 后端的<b>异步</b>类工具调用(watch/trace/stack/tt/monitor)
 * 在 {@code BackendEntry.admit} 前置返 {@code StatelessAsyncException} → 路由器翻译为
 * {@code INVALID_PARAMS}({@code reason=stateless_unsupported_async}),<b>不</b>进 {@code asyncExecutor.submit}
 * (不提交后台、不耗兜底超时);对同一 STATELESS 后端的<b>同步</b>类工具调用(jvm 等)<b>正常</b>(不抛 STATELESS)。
 *
 * <p><b>真实性</b>:注册表内置 1 个真实 {@link BackendEntry}(STATELESS 协议)+ 受控 {@link FakeBackendClient}
 * (同步成功路径返回标记结果,非 arthas 成功桩)。STATELESS 拒绝是<b>网关自身协议判定</b>(读 config.protocol),
 * 不依赖后端真实响应;异步拒绝后断言 {@code store} 无任务(提交未发生)。
 */
class ToolsCallRouterStatelessTest {

    private static final String STATELESS_TARGET = "stateless-be";

    private TaskStore store;
    private AsyncTaskExecutor executor;
    private ToolsCallRouter router;
    private FakeBackendClient statelessClient;

    @BeforeEach
    void setUp() {
        store = new TaskStore(Duration.ofHours(1), java.time.Instant::now);
        executor = new AsyncTaskExecutor(store, Duration.ofMinutes(11));
        statelessClient = new FakeBackendClient();
        statelessClient.setCallToolResult(
                new CallToolResult(List.of(new TextContent("sync-ok")), false, null, null));

        BackendConfig statelessCfg = new BackendConfig(STATELESS_TARGET, "http://localhost:8563",
                Protocol.STATELESS,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 60000, 3);
        BackendEntry statelessEntry = new BackendEntry(statelessCfg, statelessClient,
                CircuitBreaker.create(System::nanoTime));
        BackendRegistry registry = new BackendRegistry(1L, Map.of(STATELESS_TARGET, statelessEntry));

        router = new ToolsCallRouter(new RegistryHolder(registry), executor,
                new GatewayToolHandlers(new RegistryHolder(registry), executor));
    }

    @AfterEach
    void tearDown() {
        executor.close();
        store.close();
    }

    /** 异步工具(watch)对 STATELESS 后端 → 立即 INVALID_PARAMS + reason=stateless_unsupported_async,不进 submit。 */
    @Test
    void statelessAsyncCallRejectedBeforeSubmit() {
        assertThatThrownBy(() -> router.route(asyncTool(),
                new CallToolRequest("watch", Map.of("target", STATELESS_TARGET))))
                .isInstanceOf(McpError.class)
                .satisfies(t -> {
                    McpError err = (McpError) t;
                    assertThat(err.getJsonRpcError().code())
                            .as("STATELESS 异步 → INVALID_PARAMS").isEqualTo(McpErrorCodes.INVALID_PARAMS);
                    assertThat(err.getJsonRpcError().data()).isNotNull();
                    Map<?, ?> data = (Map<?, ?>) err.getJsonRpcError().data();
                    assertThat(data.get("reason"))
                            .as("reason=stateless_unsupported_async")
                            .isEqualTo("stateless_unsupported_async");
                    assertThat(data.get("target")).isEqualTo(STATELESS_TARGET);
                    List<String> available = ((List<?>) data.get("available")).stream()
                            .map(Object::toString).toList();
                    assertThat(available)
                            .as("available 含该 STATELESS target(同步仍可用)")
                            .contains(STATELESS_TARGET);
                });
        // 关键:异步拒绝在 admit 前置,asyncExecutor.submit 未被调用 → store 无任务、无槽占用
        assertThat(store.list())
                .as("STATELESS 异步拒绝不提交后台任务(store 为空)").isEmpty();
    }

    /** 同步工具(jvm)对 STATELESS 后端 → 正常(不经 admit 的 STATELESS 检查),返回后端结果。 */
    @Test
    void statelessSyncCallStillWorks() {
        CallToolResult result = router.route(syncTool(),
                new CallToolRequest("jvm", Map.of("target", STATELESS_TARGET)));

        assertThat(result).isNotNull();
        assertThat(result.isError()).as("同步调用正常,非错误").isFalse();
        assertThat(((TextContent) result.content().get(0)).text()).isEqualTo("sync-ok");
    }

    private static ExposedTool asyncTool() {
        return new ExposedTool(
                "watch",
                "异步观测",
                Map.of(
                        "type", "object",
                        "properties", new LinkedHashMap<>(),
                        "required", List.of(),
                        "additionalProperties", false),
                TaskSupport.OPTIONAL,
                RoutingMode.ASYNC_TASK);
    }

    private static ExposedTool syncTool() {
        return new ExposedTool(
                "jvm",
                "JVM 诊断",
                Map.of(
                        "type", "object",
                        "properties", new LinkedHashMap<>(),
                        "required", List.of(),
                        "additionalProperties", false),
                TaskSupport.FORBIDDEN,
                RoutingMode.SYNC_DIRECT);
    }
}
```


---

## com/arthas/gateway/handler/ToolsCallRouterTest.java

**文件**：`src/test/java/com/arthas/gateway/handler/ToolsCallRouterTest.java`

```java
package com.arthas.gateway.handler;

import com.arthas.gateway.backend.BackendRegistry;
import com.arthas.gateway.backend.RegistryHolder;
import com.arthas.gateway.task.AsyncTaskExecutor;
import com.arthas.gateway.task.TaskStore;
import com.arthas.gateway.tool.ExposedTool;
import com.arthas.gateway.tool.RoutingMode;
import com.arthas.gateway.tool.TaskSupport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T027/T028 {@link ToolsCallRouter} 纯逻辑单测（surefire，无 arthas）。
 *
 * <p>覆盖路由层的<b>错误传播前置路径</b>（不触及后端转发）：
 * <ul>
 *   <li>S-ERR-1：target 缺失 → INVALID_PARAMS(-32602)（{@link DiagnosticRequest} 抛，路由器透传）。</li>
 *   <li>S-ERR-2：target 不在册 → INVALID_PARAMS(-32602) + {@code data.available}（当前可用 target 列表）。</li>
 * </ul>
 *
 * <p>用空注册表（真实 {@link RegistryHolder}，非桩）——前置校验在路由到后端之前完成，无需真实 BackendClient。
 * 真实转发（S-CALL：jvm 真实诊断透传、S-CALL-2 剥离、S-ERR-4 后端 isError 透传）由
 * {@code ToolsCallRoutingContractIT}（failsafe，真实 arthas）覆盖。
 */
class ToolsCallRouterTest {

    private TaskStore store;
    private AsyncTaskExecutor executor;
    private ToolsCallRouter router;

    @BeforeEach
    void setUp() {
        store = new TaskStore(Duration.ofHours(1), java.time.Instant::now);
        executor = new AsyncTaskExecutor(store, Duration.ofMinutes(11));
        // 空注册表路由器（target 永不在册 → S-ERR-2）
        router = new ToolsCallRouter(
                new RegistryHolder(BackendRegistry.empty()),
                executor,
                new GatewayToolHandlers(new RegistryHolder(BackendRegistry.empty()), executor));
    }

    @AfterEach
    void tearDown() {
        executor.close();
        store.close();
    }

    private static ExposedTool syncTool() {
        return new ExposedTool(
                "jvm",
                "JVM 诊断",
                Map.of(
                        "type", "object",
                        "properties", new LinkedHashMap<>(),
                        "required", List.of(),
                        "additionalProperties", false),
                TaskSupport.FORBIDDEN,
                RoutingMode.SYNC_DIRECT);
    }

    // ===== S-ERR-1：target 缺失/空 → INVALID_PARAMS =====

    @Test
    void s_err_1_missingTargetThrowsInvalidParams() {
        assertThatThrownBy(() -> router.route(syncTool(), new CallToolRequest("jvm", Map.of())))
                .isInstanceOf(McpError.class)
                .hasFieldOrPropertyWithValue("jsonRpcError.code", -32602);
    }

    @Test
    void s_err_1_nullArgumentsThrowsInvalidParams() {
        assertThatThrownBy(() -> router.route(syncTool(), new CallToolRequest("jvm", null)))
                .isInstanceOf(McpError.class)
                .hasFieldOrPropertyWithValue("jsonRpcError.code", -32602);
    }

    // ===== S-ERR-2：target 不在册 → INVALID_PARAMS + data.available =====

    @Test
    void s_err_2_unknownTargetThrowsInvalidParamsWithAvailable() {
        assertThatThrownBy(() -> router.route(
                syncTool(), new CallToolRequest("jvm", Map.of("target", "ghost"))))
                .isInstanceOf(McpError.class)
                .satisfies(t -> {
                    McpError err = (McpError) t;
                    assertThat(err.getJsonRpcError().code()).isEqualTo(-32602);
                    // data.available 附当前可用 target 列表
                    assertThat(err.getJsonRpcError().data()).isNotNull();
                    Map<?, ?> data = (Map<?, ?>) err.getJsonRpcError().data();
                    assertThat(data.containsKey("available"))
                            .as("data 含 available 字段")
                            .isTrue();
                    assertThat((List<?>) data.get("available"))
                            .as("available 为当前注册表逻辑名快照（空表 → 空列表）")
                            .isEmpty();
                    // 消息指出未知的 target，便于调用方定位
                    assertThat(err.getJsonRpcError().message()).contains("ghost");
                });
    }
}
```


---

## com/arthas/gateway/integration/ArthasMcpBackendIT.java

**文件**：`src/test/java/com/arthas/gateway/integration/ArthasMcpBackendIT.java`

```java
package com.arthas.gateway.integration;

import com.arthas.gateway.testfixtures.ArthasMcpBackend;
import com.arthas.gateway.testfixtures.McpClientHarness;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T009 真实 arthas MCP 后端夹具首测（宪法原则七 TDD、§9 互操作性裁决）。
 *
 * <p>验证 {@link ArthasMcpBackend} 能经 {@code java -jar tools/arthas-boot-4.3.0.jar} attach 到
 * 真实 {@code DemoBusinessApp} 子进程，并暴露可被官方 SDK client（{@link McpClientHarness}）
 * {@code initialize}+{@code listTools}+{@code callTool} 的 MCP 端点。<b>零桩</b>：arthas、业务服务、
 * 诊断数据全真实（驱动分层：夹具协议契约用官方 SDK client，非 curl 裸打）。
 *
 * <p>本测试<b>裁决设计文档 §9 残留风险</b>：
 * <ul>
 *   <li>arthas 4.3.0（SDK 0.17.0）↔ 网关 SDK 2.0.0 协议互操作性（initialize 握手 + 2025-11-25 回显）；</li>
 *   <li>Windows 原生 Java Attach API（reference IT 因 bash/as.sh 跳 Windows，arthas-boot.jar 移除此障碍）；</li>
 *   <li>MCP 端点路径：根 URL {@code http://127.0.0.1:<port>}（reference 实证）vs {@code /mcp}（契约假设）。</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ArthasMcpBackendIT {

    /** 核心诊断工具名（跨平台稳定可用；不强制 31 全集，因 arthas 版本/平台差异可能微调工具集）。 */
    private static final Set<String> CORE_TOOLS = Set.of(
            "jvm", "thread", "memory", "dashboard", "watch", "trace", "stack", "tt", "sc", "jad");

    /**
     * attach 成功 + 端点暴露：initialize 协商 2025-11-25 + listTools 返回核心 arthas 工具。
     *
     * <p>这是 §9 互操作性的硬裁决点——若 arthas 4.3.0 端点路径或握手与 SDK 2.0.0 不兼容，本测试失败。
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void attachesArthasAndExposesMcpEndpoint() throws Exception {
        try (ArthasMcpBackend backend = ArthasMcpBackend.start("order")) {
            assertThat(backend.name()).isEqualTo("order");
            assertThat(backend.baseUrl()).startsWith("http://127.0.0.1:");
            assertThat(backend.mcpPort()).isPositive();
            assertThat(backend.appPort()).isPositive();

            try (McpClientHarness harness = new McpClientHarness(backend.baseUrl())) {
                McpSchema.InitializeResult init = harness.initialize();
                assertThat(init.protocolVersion()).isEqualTo("2025-11-25");

                Set<String> toolNames = harness.listTools().tools().stream()
                        .map(McpSchema.Tool::name)
                        .collect(Collectors.toSet());
                assertThat(toolNames).containsAll(CORE_TOOLS);
            }
        }
    }

    /**
     * 真实诊断非桩：{@code jvm} 工具返回真实目标 JVM 进程级诊断（证明 attach 到真实进程、数据非桩）。
     *
     * <p>{@code DemoBusinessApp} 后台守护线程持续触发 {@code hotMethod}，arthas 注入其 JVM 后，
     * {@code jvm} 返回该进程的运行时/线程/类加载等真实数据。
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void realJvmDiagnosticReturnsLiveProcessData() throws Exception {
        try (ArthasMcpBackend backend = ArthasMcpBackend.start("diag")) {
            try (McpClientHarness harness = new McpClientHarness(backend.baseUrl())) {
                harness.initialize();
                McpSchema.CallToolResult result = harness.callTool("jvm", Map.of());

                assertThat(result).isNotNull();
                assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
                assertThat(result.content()).isNotNull().isNotEmpty();

                String text = extractText(result);
                assertThat(text).as("jvm 工具应返回真实 JVM 诊断文本").isNotBlank();
                // arthas jvm 返回 JSON，含 jvmInfo/RUNTIME/resultCount 等真实 JVM 诊断结构
                // （实证：MACHINE-NAME=真实机器名、RUNTIME/SPEC-NAME 等进程级数据——非桩硬证据）。
                // 字段为全大写（arthas 输出约定），故关键词对齐实测。
                assertThat(text).containsAnyOf("jvmInfo", "RUNTIME", "resultCount", "MACHINE-NAME", "SPEC-NAME");
            }
        }
    }

    private static String extractText(McpSchema.CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent tc && tc.text() != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(tc.text());
            }
        }
        return sb.toString();
    }
}
```


---

## com/arthas/gateway/integration/AsyncTaskContractIT.java

**文件**：`src/test/java/com/arthas/gateway/integration/AsyncTaskContractIT.java`

```java
package com.arthas.gateway.integration;

import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.BackendEntry;
import com.arthas.gateway.backend.BackendEntryFactory;
import com.arthas.gateway.backend.BackendRegistry;
import com.arthas.gateway.backend.Protocol;
import com.arthas.gateway.backend.RegistryHolder;
import com.arthas.gateway.testfixtures.ArthasMcpBackend;
import com.arthas.gateway.testfixtures.McpClientHarness;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T035 端到端异步任务契约测试（failsafe，真实 arthas + 真实业务服务 + 真实网关，零桩）。
 *
 * <p>经官方 SDK client 连网关 {@code /mcp}，驱动 watch（ASYNC_TASK optional 工具）走方案 C 全链路：
 * 网关 handler → {@code ToolsCallRouter} → {@code AsyncTaskExecutor} 后台虚拟线程 → 真实 arthas 后端
 * （Flow B：tools/call 不带 task，后端自动轮询路①）。
 *
 * <p>真实完成路径：watch 提交后<b>触发业务方法</b>（GET {@code /api/order} → {@code OrderService.hotMethod}），
 * arthas watch 命中（numberOfExecutions=1）→ 后端 Flow B 同步返回真实诊断 → 网关 task 转 COMPLETED。
 *
 * <p>断言（gateway-tools-contract.md §6）：
 * <ul>
 *   <li>G-ASYNC-1：watch 立即返 {taskId, status:working}（SDK client 不阻塞）。</li>
 *   <li>G-TG-1：poll task-get → COMPLETED，含<b>真实</b> watch 诊断输出（命中 hotMethod 的硬证据）。</li>
 *   <li>G-TL-1：task-list 含该任务。</li>
 *   <li>G-TC-1：task-cancel 一个 WORKING watch（未触发，numberOfExecutions=200）→ cancelled。</li>
 * </ul>
 *
 * <p>纯逻辑分支（completed/failed/cancelled 响应整形、isError 原样、未知 taskId）由
 * {@code GatewayToolsContractTest}（T029，surefire）覆盖；本 IT 聚焦<b>真实 arthas 端到端保真</b>。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AsyncTaskContractIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TARGET_CLASS = "com.arthas.gateway.testfixtures.OrderService";
    private static final String TARGET_METHOD = "hotMethod";

    @LocalServerPort
    private int port;

    @Autowired
    private RegistryHolder holder;

    @Autowired
    private BackendEntryFactory factory;

    private ArthasMcpBackend backend;

    private String gatewayUrl() {
        return "http://localhost:" + port + "/mcp";
    }

    @BeforeAll
    void startRealArthasAndRegister() throws Exception {
        backend = ArthasMcpBackend.start("order");
        BackendEntry entry = factory.create(noneAuth("order", backend.baseUrl()));
        holder.getAndSet(new BackendRegistry(1L, Map.of("order", entry)));
    }

    @AfterAll
    void stopBackend() {
        if (backend != null) {
            backend.close();
        }
    }

    // ===== G-ASYNC-1 + G-TG-1（completed，真实 watch 诊断） =====

    @Test
    void async_watch_completes_with_real_result() throws Exception {
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();

            // G-ASYNC-1：立即返 taskId + working（SDK client 不阻塞 30s+）
            long start = System.nanoTime();
            CallToolResult accepted = h.callTool("watch", Map.of(
                    "target", "order",
                    "classPattern", TARGET_CLASS,
                    "methodPattern", TARGET_METHOD,
                    "numberOfExecutions", 1,
                    "timeout", 30));
            long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

            JsonNode acc = JSON.readTree(text(accepted));
            String taskId = acc.path("taskId").asText();
            assertThat(taskId).as("watch 立即返回 taskId").matches("t-[0-9a-f]{6,}");
            assertThat(acc.path("status").asText()).isEqualTo("working");
            assertThat(elapsedMs).as("G-ASYNC-1：不阻塞等后端").isLessThan(5000);

            // 触发业务方法 → arthas watch 命中 → 后端 Flow B 返回真实诊断
            triggerOrder(backend.appPort());
            String status = pollTaskStatus(h, taskId, Duration.ofSeconds(30));

            assertThat(status).as("watch 完成（真实 arthas 诊断）").isEqualTo("completed");
            CallToolResult getResult = h.callTool("arthas-gateway.task-get", Map.of("taskId", taskId));
            JsonNode completed = JSON.readTree(text(getResult));
            assertThat(completed.path("result").path("isError").asBoolean())
                    .as("watch 成功（非业务错误）").isFalse();
            assertThat(extractResultText(completed))
                    .as("结果含命中方法的真实诊断（hotMethod）")
                    .containsAnyOf("hotMethod", "OrderService", "watch");
        }
    }

    // ===== G-TL-1：task-list 含任务 =====

    @Test
    void task_list_contains_submitted_task() throws Exception {
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();
            CallToolResult accepted = h.callTool("watch", Map.of(
                    "target", "order",
                    "classPattern", TARGET_CLASS,
                    "methodPattern", TARGET_METHOD,
                    "numberOfExecutions", 200, // 不触发 → 长期 working，供 list 观察
                    "timeout", 30));
            String taskId = JSON.readTree(text(accepted)).path("taskId").asText();

            CallToolResult listResult = h.callTool("arthas-gateway.task-list", Map.of());
            JsonNode list = JSON.readTree(text(listResult));

            assertThat(list.path("tasks").isArray()).isTrue();
            assertThat(extractTaskIds(list))
                    .as("task-list 含刚提交的任务")
                    .contains(taskId);
        }
    }

    // ===== G-TC-1：cancel working → cancelled =====

    @Test
    void cancel_working_task_transitions_to_cancelled() throws Exception {
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();
            CallToolResult accepted = h.callTool("watch", Map.of(
                    "target", "order",
                    "classPattern", TARGET_CLASS,
                    "methodPattern", TARGET_METHOD,
                    "numberOfExecutions", 200, // 不触发 → working
                    "timeout", 30));
            String taskId = JSON.readTree(text(accepted)).path("taskId").asText();

            CallToolResult cancelResult = h.callTool("arthas-gateway.task-cancel", Map.of("taskId", taskId));
            JsonNode cancel = JSON.readTree(text(cancelResult));
            assertThat(cancel.path("status").asText())
                    .as("cancel working → cancelled").isEqualTo("cancelled");

            // 落库状态确为 CANCELLED（G-TC-1）
            CallToolResult getResult = h.callTool("arthas-gateway.task-get", Map.of("taskId", taskId));
            assertThat(JSON.readTree(text(getResult)).path("status").asText()).isEqualTo("cancelled");
        }
    }

    // ===== 辅助 =====

    /** GET /api/order 触发 OrderService.hotMethod（watch 命中源）。 */
    private static void triggerOrder(int appPort) throws IOException {
        HttpURLConnection conn = (HttpURLConnection)
                URI.create("http://127.0.0.1:" + appPort + "/api/order?orderId=1").toURL().openConnection();
        try {
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            assertThat(code).as("触发 /api/order 成功（HTTP 2xx）").isBetween(200, 299);
        } finally {
            conn.disconnect();
        }
    }

    /** 轮询 task-get 直到终态或超时；返回 status（小写）。 */
    private static String pollTaskStatus(McpClientHarness h, String taskId, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            CallToolResult r = h.callTool("arthas-gateway.task-get", Map.of("taskId", taskId));
            String status = JSON.readTree(text(r)).path("status").asText();
            if (!"working".equals(status)) {
                return status;
            }
            Thread.sleep(500);
        }
        return "working"; // 超时仍在 working
    }

    private static String text(CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        for (var c : result.content()) {
            if (c instanceof TextContent tc && tc.text() != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(tc.text());
            }
        }
        return sb.toString();
    }

    private static String extractResultText(JsonNode taskGetNode) {
        StringBuilder sb = new StringBuilder();
        JsonNode content = taskGetNode.path("result").path("content");
        if (content.isArray()) {
            for (JsonNode item : content) {
                String t = item.path("text").asText("");
                if (!t.isEmpty()) {
                    sb.append(t).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static java.util.List<String> extractTaskIds(JsonNode listNode) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (JsonNode t : listNode.path("tasks")) {
            ids.add(t.path("taskId").asText());
        }
        return ids;
    }

    private static BackendConfig noneAuth(String name, String url) {
        return new BackendConfig(
                name, url, Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5);
    }
}
```


---

## com/arthas/gateway/integration/AsyncTaskTimeoutIT.java

**文件**：`src/test/java/com/arthas/gateway/integration/AsyncTaskTimeoutIT.java`

```java
package com.arthas.gateway.integration;

import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.BackendEntry;
import com.arthas.gateway.backend.BackendEntryFactory;
import com.arthas.gateway.backend.BackendRegistry;
import com.arthas.gateway.backend.Protocol;
import com.arthas.gateway.backend.RegistryHolder;
import com.arthas.gateway.testfixtures.ArthasMcpBackend;
import com.arthas.gateway.testfixtures.McpClientHarness;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T030 异步后台超时契约测试（failsafe，G-ASYNC-2，真实 arthas + 真实业务服务 + 真实网关，零桩）。
 *
 * <p><b>真实慢后端条件</b>：后台 hot-loop 每 ~50ms 调一次 {@code OrderService.hotMethod}（arthas watch 事件源）。
 * 提交 watch（{@code numberOfExecutions=100}）→ arthas 需采集 ~100 次执行才返回（hot-loop ~20/s → ~5s），
 * <b>确定性</b>超过本测试注入的<b>短兜底</b> {@code arthas-gateway.task.backend-timeout=3s}
 * （覆盖默认 11min；G-ASYNC-2 用「短兜底」让真实超时在秒级可测，而非等 11min）。
 * 后台 worker {@code get(3s)} 超时 → {@code worker.cancel(true)} 中断后端 HTTP 调用 → task 标
 * {@code failed} + {@code error.reason="backend_timeout"}（{@code TaskError.REASON_BACKEND_TIMEOUT}）。
 *
 * <p><b>为何不用 per-request {@code slowMs} / {@code numberOfExecutions=1}</b>：hot-loop 连续触发快调用，
 * watch({@code numberOfExecutions=1}) 会立刻命中某个快调用而秒完成，无法形成稳定的「超时」条件；
 * 高 {@code numberOfExecutions} 让 arthas 真实采集中被兜底打断，是<b>确定性</b>的真实慢后端。
 *
 * <p>无任何桩——失败原因是真实的 {@link java.util.concurrent.TimeoutException}（后台兜底），
 * 非模拟。SDK client 自身 30s 请求超时不触发（3s 兜底先于它）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "arthas-gateway.task.backend-timeout=3s")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AsyncTaskTimeoutIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TARGET_CLASS = "com.arthas.gateway.testfixtures.OrderService";
    private static final String TARGET_METHOD = "hotMethod";

    @LocalServerPort
    private int port;

    @Autowired
    private RegistryHolder holder;

    @Autowired
    private BackendEntryFactory factory;

    private ArthasMcpBackend backend;

    private String gatewayUrl() {
        return "http://localhost:" + port + "/mcp";
    }

    @BeforeAll
    void startRealArthasAndRegister() throws Exception {
        backend = ArthasMcpBackend.start("order");
        BackendEntry entry = factory.create(noneAuth("order", backend.baseUrl()));
        holder.getAndSet(new BackendRegistry(1L, Map.of("order", entry)));
    }

    @AfterAll
    void stopBackend() {
        if (backend != null) {
            backend.close();
        }
    }

    // ===== G-ASYNC-2：后台超短兜底 → task FAILED（backend_timeout） =====

    @Test
    void backend_timeout_marks_task_failed() throws Exception {
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();

            // 立即接受（G-ASYNC-1 形状复用）
            CallToolResult accepted = h.callTool("watch", Map.of(
                    "target", "order",
                    "classPattern", TARGET_CLASS,
                    "methodPattern", TARGET_METHOD,
                    "numberOfExecutions", 100, // ~5s 采满，> 3s 兜底（hot-loop ~20/s）
                    "timeout", 30));
            JsonNode acc = JSON.readTree(text(accepted));
            String taskId = acc.path("taskId").asText();
            assertThat(taskId).matches("t-[0-9a-f]{6,}");
            assertThat(acc.path("status").asText()).isEqualTo("working");

            // 轮询至终态：预期 3s 兜底触发 → failed + backend_timeout
            JsonNode terminal = pollUntilTerminal(h, taskId, Duration.ofSeconds(20));
            assertThat(terminal.path("status").asText())
                    .as("G-ASYNC-2：后台超短兜底 → failed").isEqualTo("failed");
            assertThat(terminal.path("error").path("reason").asText())
                    .as("失败原因：backend_timeout（真实 TimeoutException，非桩）")
                    .isEqualTo("backend_timeout");
            assertThat(terminal.path("error").path("message").asText())
                    .as("错误详情非空").isNotBlank();
        }
    }

    // ===== 辅助 =====

    /** 轮询 task-get 直到非 working 终态；返回该终态的 task-get JSON 节点。 */
    private static JsonNode pollUntilTerminal(McpClientHarness h, String taskId, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            CallToolResult r = h.callTool("arthas-gateway.task-get", Map.of("taskId", taskId));
            JsonNode node = JSON.readTree(text(r));
            if (!"working".equals(node.path("status").asText())) {
                return node;
            }
            Thread.sleep(300);
        }
        throw new AssertionError("任务在 " + timeout + " 内未达终态（仍 working），taskId=" + taskId);
    }

    private static String text(CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        for (var c : result.content()) {
            if (c instanceof TextContent tc && tc.text() != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(tc.text());
            }
        }
        return sb.toString();
    }

    private static BackendConfig noneAuth(String name, String url) {
        return new BackendConfig(
                name, url, Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5);
    }
}
```


---

## com/arthas/gateway/integration/FailedTargetErrorIT.java

**文件**：`src/test/java/com/arthas/gateway/integration/FailedTargetErrorIT.java`

```java
package com.arthas.gateway.integration;

import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.BackendEntry;
import com.arthas.gateway.backend.BackendEntryFactory;
import com.arthas.gateway.backend.BackendRegistry;
import com.arthas.gateway.backend.Protocol;
import com.arthas.gateway.backend.RegistryHolder;
import com.arthas.gateway.testfixtures.ArthasMcpBackend;
import com.arthas.gateway.testfixtures.McpClientHarness;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T044 失效 target 错误时效测试（failsafe，真实 arthas + 真实网关，零桩，SC-003 / S-ERR-5）。
 *
 * <p>真实故障条件：起 2 个真实 arthas 后端（order + payment）并注册，<b>停掉 order 的 JVM</b>（真实不可达，
 * 非桩），对其 target 调用 → 30s 内返结构化错误（连接拒绝/断连即时或受 callTimeout 30s 兜底）；
 * 同时 payment 仍可正常诊断（故障隔离，C-ISO-1 / 宪法原则三「局部故障韧性」）。
 *
 * <p>断言：
 * <ul>
 *   <li><b>S-ERR-5 / SC-003</b>：停掉 order 后 {@code jvm target=order} → 30s 内 INVALID_PARAMS(-32602) +
 *       {@code data{target:"order", reason:"backend_unreachable", available:["order","payment"], retryAfterMs}}。</li>
 *   <li><b>故障隔离</b>：order 失效<b>不</b>影响 payment——{@code jvm target=payment} 仍返真实 JVM 诊断。</li>
 * </ul>
 *
 * <p>熔断 OPEN（连续 3 次→立即拒绝）由 {@code FaultIsolationContractIT}（C-CB-1）覆盖；本测聚焦
 * 单次失效 target 的 30s 明确错误 + 跨 target 隔离。breaker 在首次失败后仍 CLOSED（retryAfterMs=0）。
 *
 * <p>{@code @TestInstance(PER_CLASS)} 使 {@code @BeforeAll/@AfterAll} 为非静态实例方法，访问注入的 RegistryHolder。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
class FailedTargetErrorIT {

    /** arthas jvm 真实进程级诊断标记（非桩硬证据）。 */
    private static final String[] JVM_DIAGNOSTIC_MARKERS =
            {"jvmInfo", "RUNTIME", "resultCount", "MACHINE-NAME", "SPEC-NAME"};

    @LocalServerPort
    private int port;

    @Autowired
    private RegistryHolder holder;

    @Autowired
    private BackendEntryFactory factory;

    private ArthasMcpBackend order;
    private ArthasMcpBackend payment;

    private String gatewayUrl() {
        return "http://localhost:" + port + "/mcp";
    }

    @BeforeAll
    void startTwoBackendsAndRegister() throws Exception {
        order = ArthasMcpBackend.start("order");
        payment = ArthasMcpBackend.start("payment");
        Map<String, BackendEntry> byName = new LinkedHashMap<>();
        byName.put("order", factory.create(noneAuth("order", order.baseUrl())));
        byName.put("payment", factory.create(noneAuth("payment", payment.baseUrl())));
        holder.getAndSet(new BackendRegistry(1L, byName));
    }

    @AfterAll
    void stopBackends() {
        if (order != null) {
            order.close();
        }
        if (payment != null) {
            payment.close();
        }
    }

    // ===== 基线：两 target 均健康可诊断（建立 session，验证夹具就绪） =====

    @Test
    @Order(1)
    void baseline_bothTargetsHealthy() {
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();
            assertThat(extractText(h.callTool("jvm", Map.of("target", "order"))))
                    .as("order 基线可诊断").containsAnyOf(JVM_DIAGNOSTIC_MARKERS);
            assertThat(extractText(h.callTool("jvm", Map.of("target", "payment"))))
                    .as("payment 基线可诊断").containsAnyOf(JVM_DIAGNOSTIC_MARKERS);
        }
    }

    // ===== S-ERR-5 / SC-003：停掉 order → 30s 内明确错误；payment 不受影响 =====

    @Test
    @Order(2)
    void stoppedTarget_returnsStructuredErrorWithin30s_andIsolationHolds() {
        // 真实故障条件：停掉 order 的 JVM（端口关闭 → 连接拒绝/断连，非桩）
        order.close();

        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();
            long start = System.nanoTime();

            assertThatThrownBy(() -> h.callTool("jvm", Map.of("target", "order")))
                    .as("失效 target 返结构化错误").isInstanceOf(McpError.class)
                    .satisfies(t -> {
                        McpError err = (McpError) t;
                        assertThat(err.getJsonRpcError().code())
                                .as("INVALID_PARAMS(-32602)").isEqualTo(-32602);
                        Map<?, ?> data = (Map<?, ?>) err.getJsonRpcError().data();
                        assertThat(data).as("data 非空").isNotNull();
                        assertThat(data.get("target")).isEqualTo("order");
                        assertThat(data.get("reason")).isEqualTo("backend_unreachable");
                        assertThat(data.containsKey("retryAfterMs"))
                                .as("data 含 retryAfterMs").isTrue();
                        assertThat(data.get("available").toString())
                                .as("available 列出全部在册 target").contains("order", "payment");
                    });

            long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertThat(elapsedMs).as("SC-003：30s 内返回明确错误").isLessThan(30_000L);

            // 故障隔离：order 失效不影响 payment（独立连接池 + 独立熔断，宪法原则三）
            CallToolResult paymentResult = h.callTool("jvm", Map.of("target", "payment"));
            assertThat(paymentResult.isError()).as("payment 仍成功").isNotEqualTo(Boolean.TRUE);
            assertThat(extractText(paymentResult))
                    .as("payment 真实诊断不受 order 故障影响").containsAnyOf(JVM_DIAGNOSTIC_MARKERS);
        }
    }

    // ===== 辅助 =====

    private static String extractText(CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        for (Content c : result.content()) {
            if (c instanceof TextContent tc && tc.text() != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(tc.text());
            }
        }
        return sb.toString();
    }

    private static BackendConfig noneAuth(String name, String url) {
        return new BackendConfig(
                name, url, Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5);
    }
}
```


---

## com/arthas/gateway/integration/HotReloadIT.java

**文件**：`src/test/java/com/arthas/gateway/integration/HotReloadIT.java`

```java
package com.arthas.gateway.integration;

import com.arthas.gateway.testfixtures.ArthasMcpBackend;
import com.arthas.gateway.testfixtures.McpClientHarness;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T037 热重载端到端契约测试（failsafe，真实 arthas + 真实文件监听 + 真实网关，零桩，SC-002）。
 *
 * <p>覆写 {@code arthas-gateway.backends-file} 指向 {@code target/hotreload-it/backends.yaml}（专用子目录，
 * 远离 {@code target/} 根的构建噪声），驱动真实 {@link com.arthas.gateway.backend.BackendConfigWatcher}
 * （WatchService + 500ms 防抖）→ {@link com.arthas.gateway.backend.BackendRegistryReloader}（diff/复用）
 * → {@link com.arthas.gateway.backend.RegistryHolder#getAndSet} 原子替换 → 优雅下线。
 *
 * <p><b>生命周期</b>：static {@code @BeforeAll}（先于 Spring context 加载）启动真实 arthas 后端 + 写入
 * version 1 空表，使网关以空注册表启动、watcher 监听该文件。static {@code @AfterAll} 关停 arthas。
 *
 * <p>断言（gateway-tools-contract.md §7 热重载、data-model.md §11 规则 7/8）：
 * <ul>
 *   <li><b>新增</b>：写入 version 2（含真实 order 后端）→ 30s 内 list-targets 出现 order 且<b>可诊断</b>
 *       （jvm target=order 返真实 JVM 诊断，非桩）。</li>
 *   <li><b>校验失败保留旧表</b>（§11 规则 7）：写入 version 3（缺 auth 块，非法）→ watcher catch
 *       {@code BackendConfigException} → list-targets 仍含 order（不半替换）。</li>
 *   <li><b>移除</b>：写入 version 4（空表）→ 30s 内 list-targets order 消失，且 jvm target=order 返
 *       INVALID_PARAMS(-32602) + {@code data.available} 不含 order。</li>
 * </ul>
 *
 * <p>version 去重（§11 规则 8：相同 version 忽略）由 {@code BackendRegistryReloaderTest}（单测）覆盖；
 * 并发路由不变量由既有路由 IT 覆盖。本 IT 聚焦<b>真实文件监听端到端保真</b>。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "arthas-gateway.backends-file=target/hotreload-it/backends.yaml")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HotReloadIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 专用配置子目录（远离 target/ 根的构建噪声，避免 watcher 收到无关事件）。 */
    private static final Path CONFIG_DIR = Path.of("target/hotreload-it");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("backends.yaml");

    /** arthas jvm 返回 JSON 含的真实进程级结构标记（与 ToolsCallRoutingContractIT 一致，非桩硬证据）。 */
    private static final String[] JVM_DIAGNOSTIC_MARKERS =
            {"jvmInfo", "RUNTIME", "resultCount", "MACHINE-NAME", "SPEC-NAME"};

    private static ArthasMcpBackend backend;
    private static String orderUrl;

    @LocalServerPort
    private int port;

    private String gatewayUrl() {
        return "http://localhost:" + port + "/mcp";
    }

    @BeforeAll
    static void startRealArthasAndSeedEmptyConfig() throws Exception {
        backend = ArthasMcpBackend.start("order");
        orderUrl = backend.baseUrl();
        Files.createDirectories(CONFIG_DIR);
        writeBackends(1L, List.of()); // 空表 → 网关空注册表启动
    }

    @AfterAll
    static void stopBackend() {
        if (backend != null) {
            backend.close();
        }
    }

    // ===== 新增：version 2（真实 order）→ 30s 内出现且可诊断 =====

    @Test
    @Order(1)
    void added_backend_appears_and_is_diagnosable() throws Exception {
        writeBackends(2L, List.of(backendYaml("order", orderUrl)));
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();
            awaitTargetPresence(h, "order", Duration.ofSeconds(30));
            assertThat(targetNames(h)).as("热重载后 list-targets 含 order").contains("order");

            // 可诊断：jvm target=order 经新加后端路由到真实 arthas（非桩）
            CallToolResult result = h.callTool("jvm", Map.of("target", "order"));
            assertThat(result.isError()).as("jvm 诊断成功").isNotEqualTo(Boolean.TRUE);
            assertThat(extractText(result))
                    .as("含真实 JVM 进程级诊断标记")
                    .containsAnyOf(JVM_DIAGNOSTIC_MARKERS);
        }
    }

    // ===== 校验失败保留旧表（§11 规则 7）：version 3 缺 auth 块 → 仍含 order =====

    @Test
    @Order(2)
    void invalid_config_keeps_old_registry() throws Exception {
        Files.writeString(CONFIG_FILE, invalidYamlMissingAuth(3L), StandardCharsets.UTF_8);
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();
            // 等 watcher 防抖(500ms)+poll+重载失败 → 保留旧表
            Thread.sleep(3000);
            assertThat(targetNames(h))
                    .as("非法配置：保留旧表（order 仍在）")
                    .contains("order");
        }
    }

    // ===== 移除：version 4（空表）→ 30s 内消失且调用返明确错误 =====

    @Test
    @Order(3)
    void removed_backend_disappears_and_calls_fail() throws Exception {
        writeBackends(4L, List.of());
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();
            awaitTargetAbsence(h, "order", Duration.ofSeconds(30));
            assertThat(targetNames(h)).as("热重载后 order 已移除").doesNotContain("order");

            // 调用移除的 target → 明确错误（INVALID_PARAMS + available 不含 order）
            assertThatThrownBy(() -> h.callTool("jvm", Map.of("target", "order")))
                    .as("移除的 target 调用返明确错误")
                    .isInstanceOf(McpError.class)
                    .satisfies(t -> {
                        McpError err = (McpError) t;
                        assertThat(err.getJsonRpcError().code())
                                .as("INVALID_PARAMS(-32602)").isEqualTo(-32602);
                        Map<?, ?> data = (Map<?, ?>) err.getJsonRpcError().data();
                        assertThat(data).as("data 含 available").isNotNull();
                        assertThat(data.get("available").toString())
                                .as("available 不含已移除的 order")
                                .doesNotContain("order");
                    });
        }
    }

    // ===== 辅助：YAML 生成 =====

    private static void writeBackends(long version, List<String> backendBlocks) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("version: ").append(version).append('\n');
        if (backendBlocks.isEmpty()) {
            sb.append("backends: []\n"); // 空表：行内 flow，免缩进歧义
        } else {
            sb.append("backends:\n");
            for (String b : backendBlocks) {
                sb.append(b);
            }
        }
        Files.writeString(CONFIG_FILE, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String backendYaml(String name, String url) {
        return "  - name: " + name + "\n"
                + "    url: " + url + "\n"
                + "    protocol: STREAMABLE\n"
                + "    auth:\n"
                + "      mode: NONE\n";
    }

    /** 非法配置：缺 auth 块（解析层抛 BackendConfigException）。 */
    private static String invalidYamlMissingAuth(long version) {
        return "version: " + version + "\n"
                + "backends:\n"
                + "  - name: order\n"
                + "    url: " + orderUrl + "\n"
                + "    protocol: STREAMABLE\n";
    }

    // ===== 辅助：list-targets 解析与轮询 =====

    private List<String> targetNames(McpClientHarness h) throws java.io.IOException {
        CallToolResult r = h.callTool("arthas-gateway.list-targets", Map.of());
        JsonNode root = JSON.readTree(text(r));
        List<String> names = new ArrayList<>();
        for (JsonNode t : root.path("targets")) {
            names.add(t.path("name").asText());
        }
        return names;
    }

    private void awaitTargetPresence(McpClientHarness h, String name, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (targetNames(h).contains(name)) {
                return;
            }
            Thread.sleep(500);
        }
    }

    private void awaitTargetAbsence(McpClientHarness h, String name, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!targetNames(h).contains(name)) {
                return;
            }
            Thread.sleep(500);
        }
    }

    private static String text(CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        for (Content c : result.content()) {
            if (c instanceof TextContent tc && tc.text() != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(tc.text());
            }
        }
        return sb.toString();
    }

    private static String extractText(CallToolResult result) {
        return text(result);
    }
}
```


---

## com/arthas/gateway/integration/ResultConsistencyIT.java

**文件**：`src/test/java/com/arthas/gateway/integration/ResultConsistencyIT.java`

```java
package com.arthas.gateway.integration;

import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.BackendEntry;
import com.arthas.gateway.backend.BackendEntryFactory;
import com.arthas.gateway.backend.BackendRegistry;
import com.arthas.gateway.backend.Protocol;
import com.arthas.gateway.backend.RegistryHolder;
import com.arthas.gateway.testfixtures.ArthasMcpBackend;
import com.arthas.gateway.testfixtures.McpClientHarness;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T020 结果一致性测试（SC-005 A/B，网关 vs 直连 arthas，零桩）。
 *
 * <p>同一真实 arthas 后端，两侧各调一次 {@code jvm}：
 * <ul>
 *   <li><b>A（经网关）</b>：SDK client 连网关 {@code /mcp}，{@code jvm target=order}（网关剥离 target 后转发）。</li>
 *   <li><b>B（直连）</b>：SDK client 直连 arthas 后端根 URL，{@code jvm} 无参（金标准一侧）。</li>
 * </ul>
 * 断言两侧<b>结构一致</b>：{@code isError} 相同、都返回真实 JVM 诊断（含进程级结构标记）。
 *
 * <p><b>不要求文本逐字相同</b>：jvm 诊断含动态值（运行时时间戳、线程数等），每次调用不同；一致性
 * 指「网关透传不破坏结果结构」（宪法原则二：透明无损聚合），而非快照相等。
 *
 * <p>动态注册表注入同 {@code ToolsCallRoutingContractIT}（@SpringBootTest + getAndSet 覆盖示例占位）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResultConsistencyIT {

    private static final String[] JVM_DIAGNOSTIC_MARKERS =
            {"jvmInfo", "RUNTIME", "resultCount", "MACHINE-NAME", "SPEC-NAME"};

    @LocalServerPort
    private int port;

    @Autowired
    private RegistryHolder holder;

    @Autowired
    private BackendEntryFactory factory;

    private ArthasMcpBackend backend;

    private String gatewayUrl() {
        return "http://localhost:" + port + "/mcp";
    }

    @BeforeAll
    void startRealArthasAndRegister() throws Exception {
        backend = ArthasMcpBackend.start("order");
        BackendEntry entry = factory.create(noneAuth("order", backend.baseUrl()));
        holder.getAndSet(new BackendRegistry(1L, Map.of("order", entry)));
    }

    @AfterAll
    void stopBackend() {
        if (backend != null) {
            backend.close();
        }
    }

    @Test
    void sc_005_gatewayResultConsistentWithDirectArthas() {
        // A：经网关（target 剥离后转发到同一后端）
        CallToolResult viaGateway;
        try (McpClientHarness gw = new McpClientHarness(gatewayUrl())) {
            gw.initialize();
            viaGateway = gw.callTool("jvm", Map.of("target", "order"));
        }

        // B：直连 arthas 后端（金标准）
        CallToolResult direct;
        try (McpClientHarness dir = new McpClientHarness(backend.baseUrl())) {
            dir.initialize();
            direct = dir.callTool("jvm", Map.of());
        }

        // 一致性：isError 相同（网关不吞/不改写错误标记）
        assertThat(viaGateway.isError())
                .as("经网关与直连的 isError 一致（透传不改写）")
                .isEqualTo(direct.isError());

        // 两侧都返回真实 JVM 诊断（结构标记存在 → 真实进程级数据，非桩）
        assertThat(extractText(viaGateway))
                .as("经网关的 jvm 诊断含真实进程级结构")
                .containsAnyOf(JVM_DIAGNOSTIC_MARKERS);
        assertThat(extractText(direct))
                .as("直连的 jvm 诊断含真实进程级结构")
                .containsAnyOf(JVM_DIAGNOSTIC_MARKERS);
    }

    private static BackendConfig noneAuth(String name, String url) {
        return new BackendConfig(
                name, url, Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5);
    }

    private static String extractText(CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        for (Content content : result.content()) {
            if (content instanceof TextContent tc && tc.text() != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(tc.text());
            }
        }
        return sb.toString();
    }
}
```


---

## com/arthas/gateway/obs/BackendRegistryHealthIndicatorTest.java

**文件**：`src/test/java/com/arthas/gateway/obs/BackendRegistryHealthIndicatorTest.java`

```java
package com.arthas.gateway.obs;

import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.BackendEntry;
import com.arthas.gateway.backend.BackendEntryFactory;
import com.arthas.gateway.backend.BackendRegistry;
import com.arthas.gateway.backend.BackendState;
import com.arthas.gateway.backend.Protocol;
import com.arthas.gateway.backend.RegistryHolder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T050 后端注册表健康指标单测（surefire，纯逻辑，零桩零 arthas）。
 *
 * <p>{@link BackendRegistryHealthIndicator} 将注册表 + 熔断状态映射到 {@code /actuator/health} 的 details，
 * 运维无需读源码即可见各后端健康（宪法原则五；T050「唯一允许 Actuator 端点直接验证」）。
 *
 * <p><b>状态语义</b>（与 {@code list-targets} 一致、非桩驱动）：
 * <ul>
 *   <li>网关进程 status 恒为 <b>UP</b>——故障隔离：单后端熔断是正常韧性表现，<b>不</b>拉低网关 status。</li>
 *   <li>单后端 {@code healthy = state==ACTIVE && breaker 未 OPEN}（真实 {@code recordFailure×3} 驱动 OPEN，非桩）。</li>
 *   <li>{@code details.backends[name] = {state, healthy, protocol, breaker}}；{@code details.summary = {total, healthy, unhealthy}}。</li>
 * </ul>
 */
class BackendRegistryHealthIndicatorTest {

    private final BackendEntryFactory factory = new BackendEntryFactory();

    @Test
    void health_up_withPerBackendDetails_andTrippedBreakerMarkedUnhealthy() {
        BackendEntry order = factory.create(cfg("order"));
        BackendEntry payment = factory.create(cfg("payment"));
        // order 连续 3 次基础设施失败 → 真实驱动熔断 OPEN（驱动真实状态机，非桩）
        order.breaker().recordFailure();
        order.breaker().recordFailure();
        order.breaker().recordFailure();

        Map<String, BackendEntry> byName = new LinkedHashMap<>();
        byName.put("order", order);
        byName.put("payment", payment);
        BackendRegistry reg = new BackendRegistry(1L, byName);
        BackendRegistryHealthIndicator indicator = new BackendRegistryHealthIndicator(new RegistryHolder(reg));

        Health health = indicator.health();

        // 网关进程健康（故障隔离：单后端 sick ≠ 网关 DOWN）
        assertThat(health.getStatus()).as("网关 status 恒 UP").isEqualTo(Status.UP);

        @SuppressWarnings("unchecked")
        Map<String, Object> backends = (Map<String, Object>) health.getDetails().get("backends");
        assertThat(backends).containsOnlyKeys("order", "payment");

        @SuppressWarnings("unchecked")
        Map<String, Object> orderDetail = (Map<String, Object>) backends.get("order");
        assertThat(orderDetail).containsEntry("healthy", false);
        assertThat(orderDetail).containsEntry("breaker", "OPEN");
        assertThat(orderDetail).containsEntry("state", BackendState.ACTIVE.name());

        @SuppressWarnings("unchecked")
        Map<String, Object> paymentDetail = (Map<String, Object>) backends.get("payment");
        assertThat(paymentDetail).containsEntry("healthy", true);
        assertThat(paymentDetail).containsEntry("breaker", "CLOSED");

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) health.getDetails().get("summary");
        assertThat(summary)
                .containsEntry("total", 2)
                .containsEntry("healthy", 1)
                .containsEntry("unhealthy", 1);
    }

    @Test
    void retiredBackendMarkedUnhealthy() {
        BackendEntry order = factory.create(cfg("order"));
        order.markRetired(); // 热重载移除中：healthy=false（即使 breaker 仍 CLOSED）

        BackendRegistry reg = new BackendRegistry(2L, Map.of("order", order));
        Health health = new BackendRegistryHealthIndicator(new RegistryHolder(reg)).health();

        @SuppressWarnings("unchecked")
        Map<String, Object> orderDetail = (Map<String, Object>)
                ((Map<String, Object>) health.getDetails().get("backends")).get("order");
        assertThat(orderDetail).containsEntry("healthy", false);
        assertThat(orderDetail).containsEntry("state", BackendState.RETIRED.name());
    }

    @Test
    void health_up_emptyRegistry_whenNoBackends() {
        Health health = new BackendRegistryHealthIndicator(new RegistryHolder()).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        @SuppressWarnings("unchecked")
        Map<String, Object> backends = (Map<String, Object>) health.getDetails().get("backends");
        assertThat(backends).isEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) health.getDetails().get("summary");
        assertThat(summary)
                .containsEntry("total", 0)
                .containsEntry("healthy", 0)
                .containsEntry("unhealthy", 0);
    }

    private static BackendConfig cfg(String name) {
        return new BackendConfig(name, "http://127.0.0.1:9", Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null), 5000, 30000, 5);
    }
}
```


---

## com/arthas/gateway/orchestration/ArthasLauncherSpiTest.java

**文件**：`src/test/java/com/arthas/gateway/orchestration/ArthasLauncherSpiTest.java`

```java
package com.arthas.gateway.orchestration;

import com.arthas.gateway.backend.DynamicBackendStore;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 005 US3 ArthasLauncher SPI 委托单测（T023，INV-LAUNCHER-1/4）。
 *
 * <p>验证 {@link ArthasProvisioner} 委托 {@link ArthasLauncher}（locatePid + startArthas，<b>非硬编码</b>，
 * INV-LAUNCHER-1）+ {@code LaunchException} 映射为 failed 记录（携带 error reason/phase，INV-LAUNCHER-4）。
 * {@code @Primary} 覆盖（INV-LAUNCHER-3）与真实链路由 {@code CustomLauncherContractIT}（T024）覆盖。
 *
 * <p>用 {@link org.mockito.Mockito#spy} 覆盖 {@code installArthas}（跳过真实 fabric8 upload）+ {@code probeHealthy}
 *（返 true 跳过真实 MCP 握手），聚焦 launcher 委托。
 */
class ArthasLauncherSpiTest {

    /** 构造 spy ArthasProvisioner（mock exposer/store/client，注入指定 launcher）。 */
    private ArthasProvisioner provisioner(ArthasLauncher launcher) {
        NodePortExposer exposer = mock(NodePortExposer.class);
        when(exposer.expose(any(), any(), any(), anyInt())).thenReturn(
                new NodePortExposer.ExposeResult("arthas-mcp-svc", 30000,
                        "http://1.2.3.4:30000", "arthas-mcp-svc/30000"));
        DynamicBackendStore store = mock(DynamicBackendStore.class);
        when(store.get(any())).thenReturn(Optional.empty()); // 无幂等复用 → 走供给
        OrchestrationRecordStore rs = mock(OrchestrationRecordStore.class);
        KubernetesClient client = mock(KubernetesClient.class);
        return spy(new ArthasProvisioner(client, exposer, store, rs, "0.0.0.0",
                "tools/arthas-boot.jar", 8563, "4.3.0", "pwd", Duration.ofMillis(50), launcher));
    }

    /** INV-LAUNCHER-1：ArthasProvisioner.ensure 委托 launcher.locatePid + startArthas（pid 传递）。 */
    @Test
    void provisionerDelegatesLocatePidAndStartArthasToLauncher() {
        ArthasLauncher launcher = mock(ArthasLauncher.class);
        when(launcher.locatePid(any())).thenReturn(12345L);
        ArthasProvisioner p = provisioner(launcher);
        doNothing().when(p).installArthas(any(), any()); // 跳过真实 upload
        doReturn(true).when(p).probeHealthy(any(), any()); // 跳过真实握手

        OrchestrationRecord rec = p.ensure("debian", "demo-business", "default", Instant.EPOCH);

        verify(launcher).locatePid(any()); // 委托定位
        verify(launcher).startArthas(any(), eq(12345L)); // 委托启动 + pid 透传
        assertThat(rec.status()).as("全子步成功 → ready").isEqualTo(OrchestrationRecord.Status.READY);
    }

    /** INV-LAUNCHER-4：launcher.locatePid 抛 LaunchException → failed 记录（携带 error reason/phase）。 */
    @Test
    void launcherLaunchExceptionMappedToFailedRecord() {
        ArthasLauncher launcher = mock(ArthasLauncher.class);
        when(launcher.locatePid(any())).thenThrow(new ArthasLauncher.LaunchException(
                new OrchestrationRecord.Error("locate_jvm", "no_jvm", "测试注入故障")));
        ArthasProvisioner p = provisioner(launcher);

        OrchestrationRecord rec = p.ensure("debian", "demo-business", "default", Instant.EPOCH);

        assertThat(rec.status()).as("LaunchException → failed（不注册）").isEqualTo(OrchestrationRecord.Status.FAILED);
        assertThat(rec.error().reason()).isEqualTo("no_jvm");
        assertThat(rec.error().phase()).isEqualTo("locate_jvm");
    }

    /** INV-LAUNCHER-4：launcher.startArthas 抛 LaunchException → failed（attach_failed@start_arthas）。 */
    @Test
    void startArthasLaunchExceptionMappedToFailedRecord() {
        ArthasLauncher launcher = mock(ArthasLauncher.class);
        when(launcher.locatePid(any())).thenReturn(12345L);
        doThrow(new ArthasLauncher.LaunchException(
                new OrchestrationRecord.Error("start_arthas", "attach_failed", "测试注入启动故障")))
                .when(launcher).startArthas(any(), anyLong());
        ArthasProvisioner p = provisioner(launcher);
        doNothing().when(p).installArthas(any(), any());

        OrchestrationRecord rec = p.ensure("debian", "demo-business", "default", Instant.EPOCH);

        assertThat(rec.status()).isEqualTo(OrchestrationRecord.Status.FAILED);
        assertThat(rec.error().reason()).isEqualTo("attach_failed");
        assertThat(rec.error().phase()).isEqualTo("start_arthas");
    }
}
```

---

## com/arthas/gateway/orchestration/ArthasLauncherTest.java

**文件**：`src/test/java/com/arthas/gateway/orchestration/ArthasLauncherTest.java`

```java
package com.arthas.gateway.orchestration;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 005 ArthasLauncher SPI 接口契约（T003）：LaunchContext 字段全集 + LaunchException 携带 Error。
 */
class ArthasLauncherTest {

    @Test
    void launchContextCarriesAllFields() {
        K8sExec exec = mock(K8sExec.class);
        ArthasLauncher.LaunchContext ctx = new ArthasLauncher.LaunchContext(
                "default", "demo-business", exec,
                8563, "0.0.0.0", "4.3.0", "secret",
                "/tmp/arthas-boot.jar",
                Duration.ofSeconds(300), Duration.ofSeconds(10));
        assertThat(ctx.namespace()).isEqualTo("default");
        assertThat(ctx.pod()).isEqualTo("demo-business");
        assertThat(ctx.exec()).isSameAs(exec);
        assertThat(ctx.mcpPort()).isEqualTo(8563);
        assertThat(ctx.targetIp()).isEqualTo("0.0.0.0");
        assertThat(ctx.arthasVersion()).isEqualTo("4.3.0");
        assertThat(ctx.arthasPassword()).isEqualTo("secret");
        assertThat(ctx.arthasBootJar()).isEqualTo("/tmp/arthas-boot.jar");
        assertThat(ctx.attachTimeout()).isEqualTo(Duration.ofSeconds(300));
        assertThat(ctx.locateTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void launchExceptionCarriesErrorAndMessage() {
        OrchestrationRecord.Error err = new OrchestrationRecord.Error("start_arthas", "attach_failed", "exit=1");
        ArthasLauncher.LaunchException ex = new ArthasLauncher.LaunchException(err);
        assertThat(ex.error()).isEqualTo(err);
        assertThat(ex.getMessage()).contains("attach_failed", "start_arthas", "exit=1");
    }
}
```

---

## com/arthas/gateway/orchestration/ArthasProvisionerIT.java

**文件**：`src/test/java/com/arthas/gateway/orchestration/ArthasProvisionerIT.java`

```java
package com.arthas.gateway.orchestration;

import com.arthas.gateway.auth.BackendAuthCustomizer;
import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.DynamicBackendStore;
import com.arthas.gateway.backend.Source;
import com.arthas.gateway.config.GatewayProperties;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T023 ArthasProvisioner 供给器 IT（波次 B，契约 §3，K-ENS-1/2/4/5/7/8 + K-ATOMIC-1，真实 k3s + 真实 arthas 注入，零桩）。
 *
 * <p>直接对真实 {@link ArthasProvisioner}（装配真实 {@link KubernetesClient}/{@link NodePortExposer}/
 * {@link DynamicBackendStore} bean）发起 {@code ensure}，断言<b>真实供给</b>行为：
 * <ul>
 *   <li>K-ENS-8：{@code target = {server}-{pod}}（确定性派生）。</li>
 *   <li>K-ENS-1：对含 JVM 的真实 demo-business pod → status=READY + 可达 mcpUrl + 进注册表（source=DYNAMIC）。</li>
 *   <li>K-ENS-2：对 ready target 重复 ensure → status=REUSED（零副作用）。</li>
 *   <li>K-ENS-4：对无 JVM 的 busybox pod → FAILED + reason=no_jvm + stage=locate_jvm，且未注册。</li>
 *   <li>K-ENS-5：对无 shell 的 pause pod → FAILED + reason=no_shell（真实故障，非桩）。</li>
 *   <li>K-ENS-7：arthas 绑 loopback（--target-ip 127.0.0.1）→ NodePort 不可达 → reason=health_check_timeout（验证 0.0.0.0 要求，R4）。</li>
 *   <li>K-ATOMIC-1：任一子步失败 → 注册表不含该 target（不半注册）。</li>
 * </ul>
 *
 * <p><b>真实故障夹具</b>（非桩）：{@code @BeforeAll} 用真实 fabric8 client 起 busybox（有 shell 无 java→no_jvm）、
 * pause（FROM scratch 无 shell→no_shell）两个 pod；{@code @AfterAll} 清理。K-ENS-6（k8s_forbidden 需受限 kubeconfig）
 * 与 K-ENS-9（name_conflict）由波次 A 纯逻辑测试覆盖（契约 §5 注），本 IT 不重复。
 *
 * <p><b>启用门禁</b>：kubeconfig 不可读 → {@link Assumptions#assumeTrue} 跳过（CI 无 k3s）。
 *
 * <p><b>顺序</b>：K-ENS-1 先跑（首跑下载 arthas lib 到 pod ~/.arthas/lib/4.3.0，~3min；后续 ensure 复用缓存即快）。
 */
@SpringBootTest
@TestPropertySource(properties = "arthas-gateway.k8s.kubeconfig=test-env/k8s/kubeconfig/k3s-admin.yaml")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ArthasProvisionerIT {

    private static final String NAMESPACE = "default";
    private static final String DEMO_POD = "demo-business";
    private static final String NO_JVM_POD = "apit-nojvm";
    private static final String NO_SHELL_POD = "apit-noshell";
    private static final String BUSYBOX_IMAGE = "rancher/mirrored-library-busybox:1.37.0";
    private static final String PAUSE_IMAGE = "rancher/mirrored-pause:3.6";
    private static final String SERVER = "debian";

    @Autowired
    private KubernetesClient client;
    @Autowired
    private NodePortExposer exposer;
    @Autowired
    private DynamicBackendStore dynamicStore;
    @Autowired
    private OrchestrationRecordStore recordStore;
    @Autowired
    private GatewayProperties props;

    /** 生产参数 Provisioner（0.0.0.0、8563、默认健康超时）—— K-ENS-1/2 用。 */
    private ArthasProvisioner provisioner() {
        GatewayProperties.K8s k = props.getK8s();
        return new ArthasProvisioner(client, exposer, dynamicStore, recordStore,
                k.getTargetIp(), k.getArthasBootJar(), k.getMcpPort(), k.getArthasVersion(),
                k.getArthasPassword());
    }

    /** loopback Provisioner（127.0.0.1、独立端口 8564、短健康超时 12s 快速失败）—— K-ENS-7 用。 */
    private ArthasProvisioner loopbackProvisioner() {
        GatewayProperties.K8s k = props.getK8s();
        return new ArthasProvisioner(client, exposer, dynamicStore, recordStore,
                "127.0.0.1", k.getArthasBootJar(), 8564, k.getArthasVersion(), k.getArthasPassword(),
                Duration.ofSeconds(12));
    }

    @BeforeAll
    void requireRealClusterAndFixtures() {
        Assumptions.assumeTrue(Files.isReadable(Path.of("test-env/k8s/kubeconfig/k3s-admin.yaml")),
                "跳过：未找到可读 kubeconfig，需真实 k3s 测试床");
        // 真实故障夹具：busybox（有 shell 无 java → no_jvm）
        createPod(NO_JVM_POD, BUSYBOX_IMAGE, new String[]{"sleep", "600"});
        // 真实故障夹具：pause（FROM scratch 无 shell → no_shell）
        createPod(NO_SHELL_POD, PAUSE_IMAGE, null);
        waitReady(NO_JVM_POD);
        waitReady(NO_SHELL_POD);
    }

    @AfterAll
    void cleanupFixturesAndTargets() {
        // 清理动态 target（避免污染后续 IT 的注册表）
        dynamicStore.unregister(ArthasProvisioner.deriveLogicalName(SERVER, DEMO_POD));
        dynamicStore.unregister(ArthasProvisioner.deriveLogicalName(SERVER + "-loopback", DEMO_POD));
        // 清理故障夹具 pod（幂等）
        deletePod(NO_JVM_POD);
        deletePod(NO_SHELL_POD);
        // 清理可能残留的 NodePort Service（失败用例的副作用）
        deleteServiceQuiet(NAMESPACE, "arthas-mcp-" + sanitize(ArthasProvisioner.deriveLogicalName(SERVER, DEMO_POD)));
        deleteServiceQuiet(NAMESPACE, "arthas-mcp-" + sanitize(ArthasProvisioner.deriveLogicalName(SERVER + "-loopback", DEMO_POD)));
    }

    // ===== K-ENS-8 + K-ENS-1：确定性命名 + 真实供给成功 =====

    @Test
    @Order(1)
    void k_ens_8_and_1_ensureRealJvmPodReadyAndRegistered() {
        Instant now = Clock.systemUTC().instant();
        OrchestrationRecord rec = provisioner().ensure(SERVER, DEMO_POD, NAMESPACE, now);

        // K-ENS-8：target = {server}-{pod}
        assertThat(rec.logicalName())
                .as("K-ENS-8：target 确定性派生 = {server}-{pod}")
                .isEqualTo(ArthasProvisioner.deriveLogicalName(SERVER, DEMO_POD));
        // K-ENS-1：status=READY + 可达 mcpUrl + 进注册表 source=DYNAMIC
        assertThat(rec.status()).as("K-ENS-1 失败详情：%s", rec.error()).isEqualTo(OrchestrationRecord.Status.READY);
        assertThat(rec.mcpUrl()).as("READY 记录含 mcpUrl").isNotBlank();
        assertThat(isMcpReachable(rec.mcpUrl(), props.getK8s().getArthasPassword()))
                .as("K-ENS-1：mcpUrl 真实可达（arthas MCP 握手成功）").isTrue();

        Optional<com.arthas.gateway.backend.BackendConfig> reg = dynamicStore.get(rec.logicalName());
        assertThat(reg).as("K-ENS-1：target 进动态注册表").isPresent();
        assertThat(reg.get().source()).isEqualTo(Source.DYNAMIC);
        assertThat(reg.get().url()).isEqualTo(rec.mcpUrl());
    }

    // ===== K-ENS-2：重复 ensure → reused（零副作用） =====

    @Test
    @Order(2)
    void k_ens_2_repeatEnsureReused() {
        // 先确保 ready（若 K-ENS-1 已注册则直接复用；保证本测试独立可跑）
        provisioner().ensure(SERVER, DEMO_POD, NAMESPACE, Clock.systemUTC().instant());

        OrchestrationRecord first = provisioner().ensure(SERVER, DEMO_POD, NAMESPACE, Clock.systemUTC().instant());
        assertThat(first.status())
                .as("第二次 ensure：reused（已 ready）")
                .isIn(OrchestrationRecord.Status.READY, OrchestrationRecord.Status.REUSED);
        // 第三次必 reused
        OrchestrationRecord third = provisioner().ensure(SERVER, DEMO_POD, NAMESPACE, Clock.systemUTC().instant());
        assertThat(third.status()).as("第三次 ensure：reused").isEqualTo(OrchestrationRecord.Status.REUSED);
    }

    // ===== K-ENS-4：无 JVM pod → no_jvm，未注册 =====

    @Test
    @Order(3)
    void k_ens_4_noJvmPodFailsNoJvmNotRegistered() {
        String logical = ArthasProvisioner.deriveLogicalName(SERVER, NO_JVM_POD);
        OrchestrationRecord rec = provisioner().ensure(SERVER, NO_JVM_POD, NAMESPACE, Clock.systemUTC().instant());

        assertThat(rec.status()).as("K-ENS-4：无 JVM pod → FAILED").isEqualTo(OrchestrationRecord.Status.FAILED);
        assertThat(rec.error().reason()).as("reason=no_jvm").isEqualTo("no_jvm");
        assertThat(rec.error().phase()).as("stage=locate_jvm").isEqualTo("locate_jvm");
        assertThat(dynamicStore.get(logical))
                .as("K-ATOMIC-1：no_jvm 失败 → 未注册").isEmpty();
    }

    // ===== K-ENS-5：无 shell pod → no_shell，未注册 =====

    @Test
    @Order(4)
    void k_ens_5_noShellPodFailsNoShellNotRegistered() {
        String logical = ArthasProvisioner.deriveLogicalName(SERVER, NO_SHELL_POD);
        OrchestrationRecord rec = provisioner().ensure(SERVER, NO_SHELL_POD, NAMESPACE, Clock.systemUTC().instant());

        assertThat(rec.status()).as("K-ENS-5：无 shell pod → FAILED").isEqualTo(OrchestrationRecord.Status.FAILED);
        assertThat(rec.error().reason()).as("reason=no_shell").isEqualTo("no_shell");
        assertThat(dynamicStore.get(logical))
                .as("K-ATOMIC-1：no_shell 失败 → 未注册").isEmpty();
    }

    // ===== K-ENS-7：arthas 绑 loopback → health_check_timeout，未注册 =====

    @Test
    @Order(5)
    void k_ens_7_loopbackBindHealthCheckTimeoutNotRegistered() {
        // 独立 logicalName（避免与 0.0.0.0:8563 实例抢端口/抢 Service）
        String serverLb = SERVER + "-loopback";
        String logical = ArthasProvisioner.deriveLogicalName(serverLb, DEMO_POD);
        OrchestrationRecord rec = loopbackProvisioner().ensure(serverLb, DEMO_POD, NAMESPACE, Clock.systemUTC().instant());

        assertThat(rec.status())
                .as("K-ENS-7：arthas 绑 loopback → FAILED（NodePort 不可达，健康检查超时）")
                .isEqualTo(OrchestrationRecord.Status.FAILED);
        assertThat(rec.error().reason()).as("reason=health_check_timeout").isEqualTo("health_check_timeout");
        assertThat(dynamicStore.get(logical))
                .as("K-ATOMIC-1：health_check 失败 → 未注册").isEmpty();
    }

    // ===== 真实 MCP 可达性探活（arthas MCP 根 URL，带 Bearer 鉴权——0.0.0.0 外部访问强制鉴权） =====

    private static boolean isMcpReachable(String mcpUrl, String arthasPassword) {
        // 0.0.0.0 经 NodePort 外部访问须 Bearer（arthas 4.3.0 强制鉴权），裸 transport 会 401。
        McpSyncClient c = McpClient.sync(
                HttpClientStreamableHttpTransport.builder(mcpUrl)
                        .httpRequestCustomizer(new BackendAuthCustomizer(
                                new BackendConfig.Auth(AuthMode.BEARER, arthasPassword, null, null)))
                        .build())
                .requestTimeout(Duration.ofSeconds(5))
                .build();
        try {
            c.initialize();
            return true;
        } catch (RuntimeException e) {
            return false;
        } finally {
            c.close();
        }
    }

    // ===== fabric8 pod/service 夹具管理（真实集群操作） =====

    private void createPod(String name, String image, String[] command) {
        // 幂等：先删后建。pod spec 多数字段不可变，对已存在 pod 做 createOrReplace 会被 K8S 以 Invalid 拒绝；
        // 先 delete 并等其真正消失，再 create，保证 @BeforeAll 可重跑（残留夹具不再阻塞）。
        client.pods().inNamespace(NAMESPACE).withName(name).delete();
        waitGone(name);
        Pod pod = (command != null
                ? new PodBuilder()
                .withNewMetadata().withName(name).withNamespace(NAMESPACE).endMetadata()
                .withNewSpec().addNewContainer().withName(name).withImage(image)
                .withCommand(command).endContainer().withRestartPolicy("Never").endSpec().build()
                : new PodBuilder()
                .withNewMetadata().withName(name).withNamespace(NAMESPACE).endMetadata()
                .withNewSpec().addNewContainer().withName(name).withImage(image)
                .endContainer().withRestartPolicy("Never").endSpec().build());
        client.pods().inNamespace(NAMESPACE).resource(pod).create();
    }

    /** 等待 pod 真正消失（delete 异步终止，须确认其 gone 再 create，否则命中 terminating pod）。 */
    private void waitGone(String name) {
        for (int i = 0; i < 30; i++) {
            if (client.pods().inNamespace(NAMESPACE).withName(name).get() == null) {
                return;
            }
            sleep(1000);
        }
    }

    private void deletePod(String name) {
        try {
            client.pods().inNamespace(NAMESPACE).withName(name).delete();
        } catch (RuntimeException ignored) {
            // 幂等清理
        }
    }

    private void waitReady(String name) {
        for (int i = 0; i < 30; i++) {
            Pod p = client.pods().inNamespace(NAMESPACE).withName(name).get();
            if (p != null && p.getStatus() != null
                    && p.getStatus().getPhase() != null && p.getStatus().getPhase().equals("Running")) {
                return;
            }
            sleep(1000);
        }
    }

    private void deleteServiceQuiet(String namespace, String serviceName) {
        try {
            exposer.deleteService(namespace, serviceName);
        } catch (RuntimeException ignored) {
            // 幂等
        }
    }

    /** 复用 NodePortExposer 的 sanitize（service 名派生须一致，否则清理删不到）。 */
    private static String sanitize(String logicalName) {
        // 与 NodePortExposer.sanitizeServiceName 同算法：小写、非法→-、截断
        String lower = logicalName.toLowerCase();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lower.length() && sb.length() < 253; i++) {
            char c = lower.charAt(i);
            sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.' ? c : '-');
        }
        return sb.toString().replaceAll("^[^a-z0-9]+", "").replaceAll("[^a-z0-9]+$", "");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```


---

## com/arthas/gateway/orchestration/CustomLauncherContractIT.java

**文件**：`src/test/java/com/arthas/gateway/orchestration/CustomLauncherContractIT.java`

```java
package com.arthas.gateway.orchestration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 005 US3 自定义 ArthasLauncher 契约 IT（T024，INV-LAUNCHER-3，真实 Spring 装配 + @Primary 覆盖）。
 *
 * <p>验证 SPI 替换机制：用户 {@code @Primary} 自定义 {@link ArthasLauncher} 覆盖 {@code DefaultArthasLauncher}
 * （{@code @ConditionalOnMissingBean} 让位），ArthasProvisioner 注入自定义实现。
 *
 * <p>用 {@link TestArthasLauncher}（真实实现 fixture，T022）经 {@code @TestConfiguration} 显式装配为 @Primary bean
 * （<b>不</b>用 @Component，避免污染其他 IT 的 Default 装配）。
 *
 * <p><b>启用门禁</b>：kubeconfig 不可读 → 跳过（CI 无 k3s）。@Primary 覆盖是 Spring 装配行为，不依赖集群可达性。
 */
@SpringBootTest
@Import(CustomLauncherContractIT.TestLauncherConfig.class)
@TestPropertySource(properties = "arthas-gateway.k8s.kubeconfig=test-env/k8s/kubeconfig/k3s-admin.yaml")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomLauncherContractIT {

    @Autowired(required = false)
    private ArthasLauncher launcher;

    @BeforeAll
    void requireK8s() {
        Assumptions.assumeTrue(Files.isReadable(Path.of("test-env/k8s/kubeconfig/k3s-admin.yaml")),
                "跳过：未找到可读 kubeconfig（K8S 编排未装配，DefaultArthasLauncher/自定义 launcher 均不装配）");
        Assumptions.assumeTrue(launcher != null, "跳过：无 ArthasLauncher bean");
    }

    /** INV-LAUNCHER-3：@Primary 自定义 launcher 覆盖 DefaultArthasLauncher。 */
    @Test
    void customPrimaryLauncherReplacesDefault() {
        assertThat(launcher)
                .as("INV-LAUNCHER-3：@Primary TestArthasLauncher 覆盖 DefaultArthasLauncher（@ConditionalOnMissingBean 让位）")
                .isInstanceOf(TestArthasLauncher.class);
    }

    /** 用户自定义实现零代码侵入即可定制 javaPath/启动命令（SPI 口子，005 第三点需求）。 */
    @Test
    void customLauncherIsRealImplNotMock() {
        assertThat(launcher).as("真实实现 fixture，非 mock（INV-LAUNCHER-5）").isInstanceOf(TestArthasLauncher.class);
        // 探针字段可观测（证明是真实记录的实现，非桩）
        assertThat(((TestArthasLauncher) launcher).lastPid()).as("初始未调 startArthas").isEqualTo(-1L);
    }

    /** 显式装配 TestArthasLauncher 为 @Primary ArthasLauncher（覆盖 DefaultArthasLauncher）。 */
    @TestConfiguration
    static class TestLauncherConfig {
        @Bean
        @Primary
        ArthasLauncher testArthasLauncher() {
            return new TestArthasLauncher();
        }
    }
}
```

---

## com/arthas/gateway/orchestration/DefaultArthasLauncherTest.java

**文件**：`src/test/java/com/arthas/gateway/orchestration/DefaultArthasLauncherTest.java`

```java
package com.arthas.gateway.orchestration;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 005 DefaultArthasLauncher（T005，INV-LAUNCHER-2 兼容现状）：
 * locatePid = jps -q | head -1、startArthas = java -jar 标准参数；故障分类 no_jvm/no_shell/attach_failed。
 */
class DefaultArthasLauncherTest {

    private ArthasLauncher.LaunchContext ctx(K8sExec exec) {
        return new ArthasLauncher.LaunchContext("default", "demo", exec,
                8563, "0.0.0.0", "4.3.0", "pwd",
                "/tmp/arthas-boot.jar", Duration.ofSeconds(300), Duration.ofSeconds(10));
    }

    @Test
    void locatePidParsesJpsOutput() {
        K8sExec exec = mock(K8sExec.class);
        when(exec.exec(eq("default"), eq("demo"), any(), eq("sh"), eq("-c"), any()))
                .thenReturn(new K8sExec.ExecResult(0, "12345\n", ""));
        assertThat(new DefaultArthasLauncher().locatePid(ctx(exec))).isEqualTo(12345L);
    }

    @Test
    void locatePidNoJvmThrowsNoJvm() {
        K8sExec exec = mock(K8sExec.class);
        when(exec.exec(eq("default"), eq("demo"), any(), eq("sh"), eq("-c"), any()))
                .thenReturn(new K8sExec.ExecResult(0, "", ""));
        assertThatThrownBy(() -> new DefaultArthasLauncher().locatePid(ctx(exec)))
                .isInstanceOf(ArthasLauncher.LaunchException.class)
                .hasFieldOrPropertyWithValue("error.reason", "no_jvm");
    }

    @Test
    void locatePidNonZeroExitThrowsNoShell() {
        K8sExec exec = mock(K8sExec.class);
        when(exec.exec(eq("default"), eq("demo"), any(), eq("sh"), eq("-c"), any()))
                .thenReturn(new K8sExec.ExecResult(127, "", "jps: not found"));
        assertThatThrownBy(() -> new DefaultArthasLauncher().locatePid(ctx(exec)))
                .isInstanceOf(ArthasLauncher.LaunchException.class)
                .hasFieldOrPropertyWithValue("error.reason", "no_shell")
                .hasFieldOrPropertyWithValue("error.phase", "locate_jvm");
    }

    @Test
    void startArthasInvokesJavaJarCommand() {
        K8sExec exec = mock(K8sExec.class);
        when(exec.exec(eq("default"), eq("demo"), any(), eq("java"), eq("-jar"), any(), eq("12345"),
                eq("--attach-only"), eq("--http-port"), eq("8563"), eq("--target-ip"), eq("0.0.0.0"),
                eq("--telnet-port"), eq("0"), eq("--use-version"), eq("4.3.0"), eq("--password"), eq("pwd")))
                .thenReturn(new K8sExec.ExecResult(0, "", ""));
        new DefaultArthasLauncher().startArthas(ctx(exec), 12345L);
        verify(exec, times(1)).exec(eq("default"), eq("demo"), any(), eq("java"), eq("-jar"), any(), eq("12345"),
                eq("--attach-only"), eq("--http-port"), eq("8563"), eq("--target-ip"), eq("0.0.0.0"),
                eq("--telnet-port"), eq("0"), eq("--use-version"), eq("4.3.0"), eq("--password"), eq("pwd"));
    }

    @Test
    void startArthasNonZeroExitThrowsAttachFailed() {
        K8sExec exec = mock(K8sExec.class);
        when(exec.exec(any(), any(), any(), eq("java"), eq("-jar"), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new K8sExec.ExecResult(1, "", "err"));
        assertThatThrownBy(() -> new DefaultArthasLauncher().startArthas(ctx(exec), 12345L))
                .isInstanceOf(ArthasLauncher.LaunchException.class)
                .hasFieldOrPropertyWithValue("error.reason", "attach_failed")
                .hasFieldOrPropertyWithValue("error.phase", "start_arthas");
    }
}
```

---

## com/arthas/gateway/orchestration/K8sBackendResolverContractIT.java

**文件**：`src/test/java/com/arthas/gateway/orchestration/K8sBackendResolverContractIT.java`

```java
package com.arthas.gateway.orchestration;

import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.BackendResolver;
import com.arthas.gateway.backend.DynamicBackendStore;
import com.arthas.gateway.backend.Protocol;
import com.arthas.gateway.backend.Source;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 005 US2 K8sBackendResolver 懒 resolve 契约 IT（T015，INV-K8SHOST-2/5，真实 k3s + Spring 装配 + 真实 ensure，零桩）。
 *
 * <p>对真实装配的 {@link BackendResolver}（{@code K8sOrchestrationConfig} 按 {@code k8s-hosts} 建 provisioner）
 * 发起 {@code resolveMcpUrl}，断言<b>真实 K8S</b> 行为：K8S 模式 → 真实 ensure（注入 arthas + NodePort 暴露 +
 * 健康检查）出 mcpUrl；二次 → 缓存命中；静态模式 → empty 旁路。
 *
 * <h3>覆盖断言</h3>
 * <ul>
 *   <li>K8S 模式 backend（k8sHost=debian + pod=demo-business）→ resolve 出可达 mcpUrl（真实 ensure）。</li>
 *   <li>二次 resolve 同 logicalName → mcpUrl 不变（缓存命中，INV-K8SHOST-2，不重复 ensure）。</li>
 *   <li>静态模式 backend → {@code Optional.empty()} 旁路（INV-K8SHOST-5）。</li>
 * </ul>
 *
 * <p><b>启用门禁</b>：kubeconfig 不可读 / demo-business pod 不存在 / 无 BackendResolver bean（CI 无 k3s）→ 跳过。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "arthas-gateway.k8s.kubeconfig=test-env/k8s/kubeconfig/k3s-admin.yaml",
        "arthas-gateway.k8s-hosts[0].name=debian",
        "arthas-gateway.k8s-hosts[0].kubeconfig=test-env/k8s/kubeconfig/k3s-admin.yaml",
        "arthas-gateway.k8s-hosts[0].namespace=default"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class K8sBackendResolverContractIT {

    private static final String NS = "default";
    private static final String POD = "demo-business";
    private static final String HOST = "debian";
    private static final String LOGICAL = "debian-demo-business";

    @Autowired(required = false)
    private BackendResolver resolver;
    @Autowired
    private DynamicBackendStore dynamicStore;

    @BeforeAll
    void requireClusterAndResolver() {
        Assumptions.assumeTrue(Files.isReadable(Path.of("test-env/k8s/kubeconfig/k3s-admin.yaml")),
                "跳过：未找到可读 kubeconfig，需真实 k3s 测试床");
        Assumptions.assumeTrue(resolver != null, "跳过：无 BackendResolver bean（K8S 编排未装配）");
    }

    @AfterAll
    void cleanup() {
        // 清理 ensure 可能的动态注册（幂等；避免污染后续 IT 注册表）
        try {
            dynamicStore.unregister(LOGICAL);
        } catch (RuntimeException ignored) {
            // 幂等清理
        }
    }

    /** K8S 模式 → 真实 ensure 出可达 mcpUrl。 */
    @Test
    void k8sModeBackendResolvesMcpUrlViaRealEnsure() {
        BackendConfig k8s = k8sConfig(HOST, POD);
        Optional<String> url = resolver.resolveMcpUrl(k8s);

        assertThat(url).as("K8S 模式 resolve 出 mcpUrl（真实 ensure）").isPresent();
        assertThat(url.get()).as("mcpUrl 为 http 形态").startsWith("http://");
    }

    /** 二次 resolve 同 logicalName → mcpUrl 不变（缓存命中，INV-K8SHOST-2）。 */
    @Test
    void secondResolveHitsCache() {
        BackendConfig k8s = k8sConfig(HOST, POD);
        String first = resolver.resolveMcpUrl(k8s).orElseThrow();
        String second = resolver.resolveMcpUrl(k8s).orElseThrow();

        assertThat(second).as("二次 resolve 缓存命中，mcpUrl 不变").isEqualTo(first);
    }

    /** 静态模式 → Optional.empty() 旁路（INV-K8SHOST-5）。 */
    @Test
    void staticModeBypassesResolve() {
        BackendConfig stat = new BackendConfig("static-one", "http://127.0.0.1:8563", Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5);
        assertThat(resolver.resolveMcpUrl(stat)).as("静态模式旁路返 empty").isEmpty();
    }

    private static BackendConfig k8sConfig(String host, String pod) {
        return new BackendConfig(LOGICAL, null, Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5, host, pod, Source.STATIC);
    }
}
```

---

## com/arthas/gateway/orchestration/K8sBackendResolverTest.java

**文件**：`src/test/java/com/arthas/gateway/orchestration/K8sBackendResolverTest.java`

```java
package com.arthas.gateway.orchestration;

import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.Protocol;
import com.arthas.gateway.backend.Source;
import com.arthas.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 005 US2 K8sBackendResolver 懒 resolve 单测（T013，INV-K8SHOST-2/3/5）。
 *
 * <p>mock {@link ArthasProvisioner}（ensure 真实 K8S 行为由 {@code K8sBackendResolverContractIT} 覆盖），
 * 验证<b>决策逻辑</b>：K8S 模式 ensure + 缓存、静态模式旁路、未知 host 报错。
 */
class K8sBackendResolverTest {

    private static final Instant NOW = Instant.parse("2026-07-10T00:00:00Z");

    /** K8S 模式 config（k8sHost + pod）。 */
    private static BackendConfig k8sConfig(String host, String pod) {
        return new BackendConfig("k", null, Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5, host, pod, Source.STATIC);
    }

    /** 静态模式 config（url）。 */
    private static BackendConfig staticConfig() {
        return new BackendConfig("s", "http://127.0.0.1:8563", Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5);
    }

    /** ready 终态 record（含 mcpUrl）。 */
    private static OrchestrationRecord ready(String logical, String mcpUrl) {
        return OrchestrationRecord.ensuring(logical, "debian", "demo-business", "default", NOW)
                .ready(mcpUrl, "svc/30050", NOW);
    }

    private static GatewayProperties.K8sHost k8sHost(String name, String namespace) {
        GatewayProperties.K8sHost h = new GatewayProperties.K8sHost();
        h.setName(name);
        h.setNamespace(namespace);
        h.setKubeconfig("/tmp/kc.yaml");
        return h;
    }

    private static K8sBackendResolver resolver(Map<String, ArthasProvisioner> p,
                                                Map<String, GatewayProperties.K8sHost> h) {
        return new K8sBackendResolver(p, h, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** K8S 模式 → 调 ensure 返 mcpUrl（INV-K8SHOST-2 首次）。 */
    @Test
    void k8sModeResolvesMcpUrlViaEnsure() {
        ArthasProvisioner p = mock(ArthasProvisioner.class);
        when(p.ensure(any(), any(), any(), any()))
                .thenReturn(ready("debian-demo-business", "http://1.2.3.4:30050"));

        K8sBackendResolver r = resolver(Map.of("debian", p), Map.of("debian", k8sHost("debian", "default")));

        assertThat(r.resolveMcpUrl(k8sConfig("debian", "demo-business")))
                .as("K8S 模式 resolve 出 ensure 的 mcpUrl").contains("http://1.2.3.4:30050");
    }

    /** 同 logicalName 二次 → 缓存命中，ensure 仅调一次（INV-K8SHOST-2 幂等缓存）。 */
    @Test
    void k8sModeCachesByLogicalNameSecondResolveSkipsEnsure() {
        ArthasProvisioner p = mock(ArthasProvisioner.class);
        when(p.ensure(any(), any(), any(), any()))
                .thenReturn(ready("debian-demo-business", "http://1.2.3.4:30050"));

        K8sBackendResolver r = resolver(Map.of("debian", p), Map.of("debian", k8sHost("debian", "default")));
        r.resolveMcpUrl(k8sConfig("debian", "demo-business"));
        r.resolveMcpUrl(k8sConfig("debian", "demo-business")); // 二次

        verify(p, times(1)).ensure(any(), any(), any(), any());
    }

    /** 静态模式 → Optional.empty() 旁路，不触 provisioner（INV-K8SHOST-5）。 */
    @Test
    void staticModeBypassesResolve() {
        ArthasProvisioner p = mock(ArthasProvisioner.class);
        K8sBackendResolver r = resolver(Map.of("debian", p), Map.of("debian", k8sHost("debian", "default")));

        assertThat(r.resolveMcpUrl(staticConfig())).as("静态模式旁路返 empty").isEmpty();
        verifyNoInteractions(p);
    }

    /** host 未在 k8s-hosts 配置 → unknown_k8s_host（INV-K8SHOST-3）。 */
    @Test
    void unknownHostThrowsUnknownK8sHost() {
        ArthasProvisioner p = mock(ArthasProvisioner.class);
        K8sBackendResolver r = resolver(Map.of("debian", p), Map.of("debian", k8sHost("debian", "default")));

        assertThatThrownBy(() -> r.resolveMcpUrl(k8sConfig("ghost-cluster", "demo-business")))
                .isInstanceOf(K8sBackendResolver.K8sResolveException.class)
                .hasMessageContaining("unknown_k8s_host");
    }

    /** ensure 终态非 ready/reused（failed）→ ensure_failed 异常。 */
    @Test
    void ensureFailedThrowsEnsureFailed() {
        ArthasProvisioner p = mock(ArthasProvisioner.class);
        OrchestrationRecord failed = OrchestrationRecord.ensuring("debian-demo-business", "debian", "demo-business", "default", NOW)
                .failed(new OrchestrationRecord.Error("locate_jvm", "no_jvm", "无 JVM"), NOW);
        when(p.ensure(any(), any(), any(), any())).thenReturn(failed);

        K8sBackendResolver r = resolver(Map.of("debian", p), Map.of("debian", k8sHost("debian", "default")));

        assertThatThrownBy(() -> r.resolveMcpUrl(k8sConfig("debian", "demo-business")))
                .isInstanceOf(K8sBackendResolver.K8sResolveException.class)
                .hasMessageContaining("ensure_failed");
    }
}
```

---

## com/arthas/gateway/orchestration/K8sEnsureContractIT.java

**文件**：`src/test/java/com/arthas/gateway/orchestration/K8sEnsureContractIT.java`

```java
package com.arthas.gateway.orchestration;

import com.arthas.gateway.backend.DynamicBackendStore;
import com.arthas.gateway.config.GatewayProperties;
import com.arthas.gateway.testfixtures.McpClientHarness;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T027 K8S 纳管端到端契约 IT（波次 B，契约 §5，K-ENS-3 / K-COEXIST-2 / SC-003，真实 k3s + 真实 arthas + 真实业务 pod，零桩）。
 *
 * <p>经<b>官方 MCP SDK client</b>（{@link McpClientHarness}，确定性断言）连网关 {@code /mcp}，驱动
 * 「{@code k8s.ensure-arthas-mcp}（编排工具）→ 既有 watch/jvm（诊断工具）」全链路，验证动态纳管 target
 * 的真实端到端行为（契约 §4 时序）。
 *
 * <h3>覆盖断言</h3>
 * <ul>
 *   <li><b>K-ENS-3</b>：经网关 MCP ensure → 用返回 target 调 watch → 经网关捕获<b>该 pod JVM</b> 的真实诊断
 *      （{@code OrderService.hotMethod}，由 demo-business hotLoop 自驱动命中；原样透传，宪法原则二）。</li>
 *   <li><b>K-COEXIST-2</b>：动态 target 不可达（pod 删除）→ {@code list-targets} 标 unhealthy（熔断 OPEN）、
 *       对其诊断返明确错误；<b>其他 target 不受影响</b>（复用 001 熔断隔离，故障局部韧性）。</li>
 *   <li><b>SC-003</b>：pod 删除 → 30 秒内隔离（熔断 OPEN）+ 明确错误（{@code backend_unreachable} +
 *       {@code available} 列表），不影响其他 target。</li>
 * </ul>
 *
 * <p><b>真实性（零桩）</b>：两个 target 均经真实 ensure 注入真实 arthas + 真实 NodePort 暴露；
 * 故障用<b>真实</b>条件——删除 {@code demo-business-2} pod（NodePort 无后端 → 不可达，连接拒绝），
 * 非 WireMock/Mock 模拟失败。
 *
 * <p><b>夹具</b>：{@code demo-business}（测试床固定 pod，<b>不删</b>）→ target1；
 * {@code demo-business-2}（临时第二 JVM pod，@BeforeAll 起、@AfterAll 清理）→ target2，作 SC-003 删除目标。
 * 两个 JVM pod 独立熔断/连接池，验证隔离（契约 §5 K-COEXIST-2 注：K-COEXIST-1 热重载已由波次 A
 * {@code RegistryComposerTest} 覆盖，本 IT 聚焦 K-COEXIST-2/SC-003 运行时隔离）。
 *
 * <p><b>启用门禁</b>：kubeconfig 不可读 → {@link Assumptions#assumeTrue} 跳过（CI 无 k3s）。
 *
 * <p><b>顺序</b>：K-ENS-3 先（ensure target1 + watch）；K-COEXIST-2 后（依赖 target1 已就绪 + target2 已预热）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "arthas-gateway.k8s.kubeconfig=test-env/k8s/kubeconfig/k3s-admin.yaml")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class K8sEnsureContractIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NAMESPACE = "default";
    private static final String SERVER = "debian";
    private static final String DEMO_POD = "demo-business";
    /** 临时第二 JVM pod（SC-003 删除目标；测试床固定 demo-business 不动）。 */
    private static final String DEMO_POD_2 = "demo-business-2";
    private static final String DEMO_IMAGE = "arthas-gateway/demo-business:local";
    private static final String TARGET1 = "debian-demo-business";
    private static final String TARGET2 = "debian-demo-business-2";
    /** demo-business 内运行的热方法类/方法（{@code DemoBusinessApp} 自带，hotLoop 每 ~50ms 自驱动）。 */
    private static final String TARGET_CLASS = "com.arthas.gateway.testfixtures.OrderService";
    private static final String TARGET_METHOD = "hotMethod";
    /** arthas jvm 真实进程级诊断标记（非桩硬证据）。 */
    private static final String[] JVM_MARKERS = {"jvmInfo", "RUNTIME", "resultCount", "MACHINE-NAME", "SPEC-NAME"};

    @LocalServerPort
    private int port;
    @Autowired
    private KubernetesClient client;
    @Autowired
    private ArthasProvisioner provisioner;
    @Autowired
    private DynamicBackendStore dynamicStore;
    @Autowired
    private GatewayProperties props;

    private String gatewayUrl() {
        return "http://localhost:" + port + "/mcp";
    }

    @BeforeAll
    void requireClusterAndProvisionSecondPod() {
        Assumptions.assumeTrue(Files.isReadable(Path.of("test-env/k8s/kubeconfig/k3s-admin.yaml")),
                "跳过：未找到可读 kubeconfig，需真实 k3s 测试床");
        // 起临时第二 JVM pod（同镜像，含 shell+java+JVM）；SC-003 删除目标，不删测试床固定 demo-business
        createDemoPod(DEMO_POD_2);
        waitRunning(DEMO_POD_2);
        // 预热 target2：首次 ensure 会 attach + 下载 arthas lib 到 pod（~3min，一次性下放夹具准备，避免拖慢测试本身）
        OrchestrationRecord rec = provisioner.ensure(SERVER, DEMO_POD_2, NAMESPACE, Clock.systemUTC().instant());
        Assumptions.assumeTrue(
                rec.status() == OrchestrationRecord.Status.READY
                        || rec.status() == OrchestrationRecord.Status.REUSED,
                "跳过：demo-business-2 预热 ensure 未就绪 status=" + rec.status()
                        + (rec.error() != null ? " reason=" + rec.error().reason() : ""));
    }

    @AfterAll
    void cleanup() {
        // 清理动态注册（避免污染后续 IT 的注册表）
        dynamicStore.unregister(TARGET1);
        dynamicStore.unregister(TARGET2);
        // 清理可能残留的 NodePort Service（幂等）
        deleteServiceQuiet("arthas-mcp-" + sanitize(TARGET1));
        deleteServiceQuiet("arthas-mcp-" + sanitize(TARGET2));
        // demo-business-2 pod 已在 K-COEXIST-2 测试中删除（SC-003 故障注入）；demo-business 为测试床固定 pod，不动
    }

    // ===== K-ENS-3：经网关 MCP ensure → 用返回 target 调 watch 捕获该 pod JVM 真实诊断 =====

    @Test
    @Order(1)
    void k_ens_3_ensureViaMcpThenWatchRealDiagnosis() throws Exception {
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();
            // 经网关 MCP 端点 ensure（编排工具；复用既有 attach 或重新供给）
            String target = ensureViaMcp(h, SERVER, DEMO_POD);
            assertThat(target).as("target 确定性派生 {server}-{pod}（K-ENS-8）").isEqualTo(TARGET1);

            // watch 该 target → 真实诊断（OrderService.hotMethod 由 hotLoop 自驱动，numberOfExecutions=1 命中）
            String taskId = submitWatch(h, target, 1);
            String status = pollTaskStatus(h, taskId, Duration.ofSeconds(30));
            assertThat(status).as("K-ENS-3：watch 完成（真实 arthas 诊断）").isEqualTo("completed");

            JsonNode done = JSON.readTree(text(h.callTool("arthas-gateway.task-get", Map.of("taskId", taskId))));
            assertThat(done.path("result").path("isError").asBoolean())
                    .as("watch 成功（非业务错误）").isFalse();
            assertThat(extractResultText(done))
                    .as("结果含命中该 pod JVM 方法的真实诊断（hotMethod/OrderService）")
                    .containsAnyOf("hotMethod", "OrderService", "watch");
        }
    }

    // ===== K-COEXIST-2 + SC-003：pod 删除 → 失效 target 隔离 + 明确错误 + 其他 target 不受影响 =====

    @Test
    @Order(2)
    void k_coexist_2_podDeletionIsolatesDeadTargetOthersUnaffected() throws Exception {
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();
            // 基线：两 target 均 healthy（target1 由 K-ENS-3 ensure，target2 由 @BeforeAll 预热）
            assertThat(healthy(h, TARGET1)).as("基线 target1 healthy").isTrue();
            assertThat(healthy(h, TARGET2)).as("基线 target2 healthy").isTrue();

            // 真实故障注入：删除 demo-business-2 pod（NodePort 无后端 → 不可达，连接拒绝；非桩）
            client.pods().inNamespace(NAMESPACE).withName(DEMO_POD_2).delete();
            waitGone(DEMO_POD_2);
            sleep(3000); // 等 K8S endpoint 收敛（kube-proxy 移除失效 endpoint）

            // 累积 3 次基础设施失败 → 熔断 OPEN（被动 call-driven 隔离，复用 001 熔断语义；breakers 各 target 独立）
            for (int i = 1; i <= 3; i++) {
                final int round = i;
                assertThatThrownBy(() -> h.callTool("jvm", Map.of("target", TARGET2)))
                        .as("第 " + round + " 次：pod 删除后 target2 不可达 → backend_unreachable")
                        .isInstanceOf(McpError.class);
            }

            // K-COEXIST-2：list-targets 标 target2 unhealthy（熔断 OPEN），target1 仍 healthy（隔离）
            assertThat(healthy(h, TARGET2))
                    .as("K-COEXIST-2：pod 删除 → target2 隔离标 unhealthy（熔断 OPEN）").isFalse();
            assertThat(healthy(h, TARGET1))
                    .as("K-COEXIST-2：其他 target 不受影响（healthy）").isTrue();

            // SC-003：对失效 target2 诊断 → 30s 内明确错误（熔断 OPEN 立即拒）+ 结构化 data
            long start = System.nanoTime();
            assertThatThrownBy(() -> h.callTool("jvm", Map.of("target", TARGET2)))
                    .as("SC-003：失效 target 返结构化错误").isInstanceOf(McpError.class)
                    .satisfies(t -> {
                        McpError err = (McpError) t;
                        assertThat(err.getJsonRpcError().code())
                                .as("INVALID_PARAMS(-32602)").isEqualTo(-32602);
                        Map<?, ?> data = (Map<?, ?>) err.getJsonRpcError().data();
                        assertThat(data.get("target")).isEqualTo(TARGET2);
                        assertThat(data.get("reason")).isEqualTo("backend_unreachable");
                        assertThat(data.get("available").toString())
                                .as("available 列出全部在册 target（含失效的 target2）").contains(TARGET1, TARGET2);
                    });
            long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
            assertThat(elapsedMs).as("SC-003：30s 内返回明确错误").isLessThan(30_000L);

            // 隔离验证：target1 仍可诊断（独立熔断 + 连接池，不受 target2 pod 删除影响）
            CallToolResult t1Jvm = h.callTool("jvm", Map.of("target", TARGET1));
            assertThat(t1Jvm.isError()).as("target1 诊断仍成功").isNotEqualTo(Boolean.TRUE);
            assertThat(extractText(t1Jvm))
                    .as("target1 真实 JVM 诊断不受 target2 故障影响").containsAnyOf(JVM_MARKERS);
        }
    }

    // ===== 辅助：经网关 MCP ensure（编排工具） =====

    private static String ensureViaMcp(McpClientHarness h, String server, String pod) throws Exception {
        CallToolResult r = h.callTool("k8s.ensure-arthas-mcp", Map.of("server", server, "pod", pod));
        JsonNode node = JSON.readTree(text(r));
        assertThat(node.path("status").asText())
                .as("ensure 经网关 MCP 返 ready/reused").isIn("ready", "reused");
        return node.path("target").asText();
    }

    // ===== 辅助：watch 异步任务（提交 + 轮询） =====

    private static String submitWatch(McpClientHarness h, String target, int numberOfExecutions) throws Exception {
        CallToolResult acc = h.callTool("watch", Map.of(
                "target", target,
                "classPattern", TARGET_CLASS,
                "methodPattern", TARGET_METHOD,
                "numberOfExecutions", numberOfExecutions,
                "timeout", 30));
        String taskId = JSON.readTree(text(acc)).path("taskId").asText();
        assertThat(taskId).as("watch 立即返 taskId").matches("t-[0-9a-f]{6,}");
        return taskId;
    }

    /** 轮询 task-get 直到终态或超时；返回 status（小写）。 */
    private static String pollTaskStatus(McpClientHarness h, String taskId, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String status = JSON.readTree(text(h.callTool("arthas-gateway.task-get", Map.of("taskId", taskId))))
                    .path("status").asText();
            if (!"working".equals(status)) {
                return status;
            }
            Thread.sleep(500);
        }
        return "working";
    }

    // ===== 辅助：list-targets 健康查询 =====

    private static boolean healthy(McpClientHarness h, String target) throws Exception {
        CallToolResult r = h.callTool("arthas-gateway.list-targets", Map.of());
        for (JsonNode t : JSON.readTree(text(r)).path("targets")) {
            if (target.equals(t.path("name").asText())) {
                return t.path("healthy").asBoolean();
            }
        }
        return false;
    }

    // ===== 辅助：结果文本提取 =====

    private static String text(CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        for (Content c : result.content()) {
            if (c instanceof TextContent tc && tc.text() != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(tc.text());
            }
        }
        return sb.toString();
    }

    private static String extractText(CallToolResult result) {
        return text(result);
    }

    /** 从 task-get completed 节点提取 result.content 文本（K-ENS-3 真实诊断断言）。 */
    private static String extractResultText(JsonNode taskGetNode) {
        StringBuilder sb = new StringBuilder();
        JsonNode content = taskGetNode.path("result").path("content");
        if (content.isArray()) {
            for (JsonNode item : content) {
                String t = item.path("text").asText("");
                if (!t.isEmpty()) {
                    sb.append(t).append('\n');
                }
            }
        }
        return sb.toString();
    }

    // ===== 辅助：fabric8 pod/service 夹具管理（真实集群操作） =====

    /** 起一个与 demo-business 同镜像的临时 JVM pod（幂等：先删后建）。 */
    private void createDemoPod(String name) {
        client.pods().inNamespace(NAMESPACE).withName(name).delete();
        waitGone(name);
        Pod pod = new PodBuilder()
                .withNewMetadata().withName(name).withNamespace(NAMESPACE)
                .addToLabels("app", name).endMetadata()
                .withNewSpec()
                .addNewContainer().withName("app").withImage(DEMO_IMAGE).withImagePullPolicy("Never")
                .addNewPort().withContainerPort(8081).endPort()
                .endContainer()
                .withRestartPolicy("Always")
                .endSpec()
                .build();
        client.pods().inNamespace(NAMESPACE).resource(pod).create();
    }

    /** 等 pod Running 且 JVM 就绪（DemoBusinessApp main 启动，jps 可见）。 */
    private void waitRunning(String name) {
        for (int i = 0; i < 90; i++) {
            Pod p = client.pods().inNamespace(NAMESPACE).withName(name).get();
            if (p != null && p.getStatus() != null && "Running".equals(p.getStatus().getPhase())) {
                sleep(2000); // 等 DemoBusinessApp JVM 就绪
                return;
            }
            sleep(1000);
        }
        throw new IllegalStateException("pod 未就绪（Running 超时）：" + name);
    }

    /** 等 pod 真正消失（delete 异步终止）。 */
    private void waitGone(String name) {
        for (int i = 0; i < 60; i++) {
            if (client.pods().inNamespace(NAMESPACE).withName(name).get() == null) {
                return;
            }
            sleep(1000);
        }
    }

    private void deleteServiceQuiet(String serviceName) {
        try {
            client.services().inNamespace(NAMESPACE).withName(serviceName).delete();
        } catch (RuntimeException ignored) {
            // 幂等清理
        }
    }

    /** 复用 NodePortExposer 的 sanitize（service 名派生须一致，否则清理删不到）。 */
    private static String sanitize(String logicalName) {
        String lower = logicalName.toLowerCase();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lower.length() && sb.length() < 253; i++) {
            char c = lower.charAt(i);
            sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.' ? c : '-');
        }
        return sb.toString().replaceAll("^[^a-z0-9]+", "").replaceAll("[^a-z0-9]+$", "");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```


---

## com/arthas/gateway/orchestration/K8sExternalGatewaySmokeTest.java

**文件**：`src/test/java/com/arthas/gateway/orchestration/K8sExternalGatewaySmokeTest.java`

```java
package com.arthas.gateway.orchestration;

import com.arthas.gateway.testfixtures.McpClientHarness;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T028 SC-001 端到端冒烟（波次 C，确定性补证）：对<b>运行中的 uber-jar 网关</b>（非 @SpringBootTest 嵌入式）
 * 经<b>官方 MCP SDK client</b> 驱动「ensure → watch → 轮询 task-get → 真实诊断」全链路。
 *
 * <p>背景：T028 的「可用性冒烟」主路径走真实 Claude Code（claude -p）。本类为<b>模型无关的确定性补证</b>——
 * 在 claude -p 受环境约束（配额 / 模型配置）时，仍能用 SDK client 确证 SC-001 闭环：动态纳管 target 的
 * watch 能捕获<b>该 pod JVM</b> 的真实诊断。与 {@link K8sEnsureContractIT}（嵌入式网关）互补——本类针对
 * <b>独立运行的真实 uber-jar 进程</b>（验证 spring-boot 重打包后的运行时完整性，含 fabric8 commons-compress 依赖）。
 *
 * <p><b>启用门禁</b>：外置网关 {@code http://127.0.0.1:8761/mcp} 健康端点不可达 → {@link org.junit.jupiter.api.Assumptions#assumeTrue}
 * 跳过（CI 无常驻网关）。本类<b>非</b> @SpringBootTest，不启动嵌入式上下文。
 */
class K8sExternalGatewaySmokeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String GATEWAY_URL = "http://127.0.0.1:8761/mcp";
    private static final String HEALTH_URL = "http://127.0.0.1:8761/actuator/health";
    private static final String SERVER = "debian";
    private static final String POD = "demo-business";
    private static final String TARGET = "debian-demo-business";
    private static final String TARGET_CLASS = "com.arthas.gateway.testfixtures.OrderService";
    private static final String TARGET_METHOD = "hotMethod";

    @Test
    void sc001_ensureWatchPollRealDiagnosisAgainstRunningGateway() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(isGatewayUp(),
                "跳过：外置网关 " + HEALTH_URL + " 不可达（需常驻 uber-jar 网关进程）");

        try (McpClientHarness h = new McpClientHarness(GATEWAY_URL)) {
            h.initialize();

            // 1. ensure：已纳管则 reused（零副作用），否则供给 ready
            CallToolResult ensure = h.callTool("k8s.ensure-arthas-mcp",
                    Map.of("server", SERVER, "pod", POD));
            String ensureText = text(ensure);
            assertThat(ensureText).as("ensure 返回 ready/reused").containsAnyOf("ready", "reused");
            assertThat(ensureText).as("ensure target 确定性派生").contains(TARGET);

            // 2. watch 该 target → 真实诊断（hotMethod 由 hotLoop 自驱动命中）
            CallToolResult watch = h.callTool("watch", Map.of(
                    "target", TARGET,
                    "classPattern", TARGET_CLASS,
                    "methodPattern", TARGET_METHOD,
                    "numberOfExecutions", 1,
                    "timeout", 30));
            String taskId = JSON.readTree(text(watch)).path("taskId").asText();
            assertThat(taskId).as("watch 立即返 taskId").matches("t-[0-9a-f]{6,}");

            // 3. 轮询 task-get 直到终态（completed）→ 提取真实诊断
            String status = "working";
            JsonNode done = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
            while (System.nanoTime() < deadline) {
                CallToolResult tg = h.callTool("arthas-gateway.task-get", Map.of("taskId", taskId));
                JsonNode node = JSON.readTree(text(tg));
                status = node.path("status").asText();
                if (!"working".equals(status)) {
                    done = node;
                    break;
                }
                Thread.sleep(2000);
            }
            assertThat(status).as("watch 完成（真实 arthas 诊断）").isEqualTo("completed");
            assertThat(done.path("result").path("isError").asBoolean())
                    .as("watch 成功（非业务错误）").isFalse();
            assertThat(extractResultText(done))
                    .as("结果含命中该 pod JVM 方法的真实诊断")
                    .containsAnyOf("hotMethod", "OrderService", "watch");
        }
    }

    private static boolean isGatewayUp() {
        try (HttpClient c = HttpClient.newHttpClient()) {
            HttpResponse<Void> r = c.send(HttpRequest.newBuilder(URI.create(HEALTH_URL))
                            .timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return r.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static String text(CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        for (Content c : result.content()) {
            if (c instanceof TextContent tc && tc.text() != null) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(tc.text());
            }
        }
        return sb.toString();
    }

    private static String extractResultText(JsonNode taskGetNode) {
        StringBuilder sb = new StringBuilder();
        JsonNode content = taskGetNode.path("result").path("content");
        if (content.isArray()) {
            for (JsonNode item : content) {
                String t = item.path("text").asText("");
                if (!t.isEmpty()) {
                    sb.append(t).append('\n');
                }
            }
        }
        return sb.toString();
    }
}
```


---

## com/arthas/gateway/orchestration/K8sListToolsContractIT.java

**文件**：`src/test/java/com/arthas/gateway/orchestration/K8sListToolsContractIT.java`

```java
package com.arthas.gateway.orchestration;

import com.arthas.gateway.handler.McpJson;
import com.arthas.gateway.testfixtures.McpClientHarness;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T020 K8S 编排列举工具契约 IT（波次 B，契约 §1/§2，K-LP-1/K-LS-1，真实 k3s + 真实业务 pod，零桩）。
 *
 * <p>经官方 MCP SDK client（{@link McpClientHarness}）连<b>网关</b> {@code /mcp} 端点驱动
 * {@code k8s.list-pods} / {@code k8s.list-services}，断言返回<b>真实集群</b>清单：
 * <ul>
 *   <li>K-LP-1：default 命名空间清单含 {@code demo-business}（hasJvm=true、hasShell=true）；
 *       namespace 过滤生效（kube-system 不含 demo-business）。</li>
 *   <li>K-LS-1：default 命名空间 service 清单非空。</li>
 *   <li>附带：{@code tools/list}=38（35 既有 + 3 编排），编排工具恒声明（回归守护 T029 前置证据）。</li>
 * </ul>
 *
 * <p><b>启用门禁</b>：仅当 {@code arthas-gateway.k8s.kubeconfig} 指向的 kubeconfig 可读时跑（真实集群）；
 * CI 无 k3s → {@link Assumptions#assumeTrue} 跳过（不报失败）。{@code @SpringBootTest} 启动时
 * {@link K8sEnabledCondition} 命中 → 编排 bean 装配 → {@code tools/list}=38。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "arthas-gateway.k8s.kubeconfig=test-env/k8s/kubeconfig/k3s-admin.yaml")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class K8sListToolsContractIT {

    /** 38 = 35 既有 arthas/网关工具 + 3 K8S 编排工具（list-pods/list-services/ensure-arthas-mcp）。 */
    private static final int EXPECTED_TOOL_COUNT = 38;

    @LocalServerPort
    private int port;

    @BeforeAll
    void requireRealCluster() {
        // 真实环境门禁：kubeconfig 不可读 → Assume 跳过（CI 无 k3s），不算失败
        Assumptions.assumeTrue(Files.isReadable(Path.of("test-env/k8s/kubeconfig/k3s-admin.yaml")),
                "跳过：未找到可读 kubeconfig（test-env/k8s/kubeconfig/k3s-admin.yaml），需真实 k3s 测试床");
    }

    private String gatewayUrl() {
        return "http://localhost:" + port + "/mcp";
    }

    // ===== tools/list = 38（编排工具恒声明） =====

    @Test
    void k_tools_list_includesThreeOrchestrationTools() {
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();
            List<Tool> tools = h.listTools().tools();
            assertThat(tools).as("tools/list 含 38 工具（35+3 编排）").hasSize(EXPECTED_TOOL_COUNT);
            assertThat(tools.stream().map(Tool::name))
                    .as("含 3 个编排工具")
                    .contains(K8sToolRegistry.LIST_PODS, K8sToolRegistry.LIST_SERVICES,
                            K8sToolRegistry.ENSURE_ARTHAS_MCP);
        }
    }

    // ===== K-LP-1：k8s.list-pods 返回真实 pod 清单 + namespace 过滤 =====

    @Test
    @SuppressWarnings("unchecked")
    void k_lp_1_listPodsReturnsRealClusterPodsWithJvmShellMarkers() {
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();

            // default 命名空间：须含 demo-business，且 hasJvm/hasShell=true（真实 JVM pod 硬证据）
            Map<String, Object> defaultResult = json(h.callTool(K8sToolRegistry.LIST_PODS, Map.of()));
            List<Map<String, Object>> defaultPods = (List<Map<String, Object>>) defaultResult.get("pods");
            assertThat(defaultPods).as("default pod 清单非空").isNotEmpty();
            Map<String, Object> demo = defaultPods.stream()
                    .filter(p -> "demo-business".equals(p.get("name")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("default 清单未含 demo-business pod"));
            assertThat(demo.get("hasJvm")).as("demo-business hasJvm=true（真实运行 JVM）").isEqualTo(true);
            assertThat(demo.get("hasShell")).as("demo-business hasShell=true").isEqualTo(true);
            assertThat(defaultResult.get("namespace")).isEqualTo("default");

            // namespace 过滤：kube-system 不含 demo-business
            Map<String, Object> ksResult = json(h.callTool(K8sToolRegistry.LIST_PODS,
                    Map.of("namespace", "kube-system")));
            List<Map<String, Object>> ksPods = (List<Map<String, Object>>) ksResult.get("pods");
            assertThat(ksPods.stream().map(p -> p.get("name")))
                    .as("kube-system 命名空间过滤生效（不含 demo-business）")
                    .doesNotContain("demo-business");
        }
    }

    // ===== K-LS-1：k8s.list-services 返回真实 service 清单 =====

    @Test
    @SuppressWarnings("unchecked")
    void k_ls_1_listServicesReturnsRealClusterServices() {
        try (McpClientHarness h = new McpClientHarness(gatewayUrl())) {
            h.initialize();
            Map<String, Object> result = json(h.callTool(K8sToolRegistry.LIST_SERVICES, Map.of()));
            List<Map<String, Object>> services = (List<Map<String, Object>>) result.get("services");
            assertThat(services).as("service 清单非空（k3s 自带 kube-dns 等）").isNotEmpty();
            assertThat(result.get("namespace")).isEqualTo("default");
            // 每条 service 含契约字段
            for (Map<String, Object> svc : services) {
                assertThat(svc).containsKeys("name", "namespace", "type", "clusterIp", "ports");
            }
        }
    }

    /** 解析 callTool 结果 TextContent 为 JSON Map。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(CallToolResult result) {
        assertThat(result).as("list-* 返回非空结果").isNotNull();
        assertThat(result.isError()).as("list-* 非 error").isNotEqualTo(Boolean.TRUE);
        StringBuilder sb = new StringBuilder();
        for (Content c : result.content()) {
            if (c instanceof TextContent tc && tc.text() != null) {
                sb.append(tc.text());
            }
        }
        assertThat(sb.length()).as("list-* 返回 TextContent 非空").isGreaterThan(0);
        return (Map<String, Object>) McpJson.MAPPER.readValue(sb.toString(), Map.class);
    }
}
```


---

## com/arthas/gateway/orchestration/NodePortExposerContractIT.java

**文件**：`src/test/java/com/arthas/gateway/orchestration/NodePortExposerContractIT.java`

```java
package com.arthas.gateway.orchestration;

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 005 US1 NodePortExposer Service 复用契约 IT（T010，K-ENS-10/11/12，真实 k3s + 真实 demo-business pod，零桩）。
 *
 * <p>直接对真实 {@link KubernetesClient} 装配的 {@link NodePortExposer} 发起 {@code expose}，断言<b>真实 K8S</b>
 * 行为（labelSelector 查询、PATCH type、NodePort 分配），覆盖单测（{@link NodePortExposerTest}）无法触及的
 * 服务端 nodePort 分配与 type 变更副作用。
 *
 * <h3>覆盖断言</h3>
 * <ul>
 *   <li><b>K-ENS-10 复用</b>：预打 {@code arthas-mcp-gateway/target} label 的现有 NodePort Service → ensure 复用
 *       （不新建独立 Service，result.serviceName = 业务 Service 名）。</li>
 *   <li><b>K-ENS-11</b>：预打 label 的 ClusterIP Service → patch type=NodePort + 加端口，K8S 分配 nodePort。
 *       复用业务 Service 名（非 arthas-mcp-）。</li>
 *   <li><b>K-ENS-12 幂等</b>：二次 ensure → nodePort 不变 + 端口数量不重复增长。</li>
 *   <li><b>K-ENS-10 回退</b>：无带 label Service → 回退新建独立 Service（arthas-mcp- 前缀，003 现状）。</li>
 * </ul>
 *
 * <p><b>启用门禁</b>：kubeconfig 不可读 / demo-business pod 不存在 → {@link Assumptions#assumeTrue} 跳过（CI 无 k3s）。
 *
 * <p><b>隔离</b>：每测试用独立 logicalName + 业务 Service（@AfterEach 清理创建的 Service，不删测试床 demo-business pod）。
 */
@SpringBootTest
@TestPropertySource(properties = "arthas-gateway.k8s.kubeconfig=test-env/k8s/kubeconfig/k3s-admin.yaml")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NodePortExposerContractIT {

    private static final String NS = "default";
    private static final String POD = "demo-business";

    @Autowired
    private KubernetesClient client;

    /** 本测试创建的 Service 名（@AfterEach 幂等清理）。 */
    private final Set<String> createdServices = new HashSet<>();

    @BeforeAll
    void requireClusterAndPod() {
        Assumptions.assumeTrue(Files.isReadable(Path.of("test-env/k8s/kubeconfig/k3s-admin.yaml")),
                "跳过：未找到可读 kubeconfig，需真实 k3s 测试床");
        Assumptions.assumeTrue(client.pods().inNamespace(NS).withName(POD).get() != null,
                "跳过：测试床 pod " + POD + " 不存在（NodePortExposer.labelPod 需真实 pod）");
    }

    @AfterEach
    void cleanupServices() {
        for (String name : createdServices) {
            deleteServiceQuiet(name);
        }
        createdServices.clear();
    }

    /** K-ENS-10：预打 label 的现有 NodePort Service → ensure 复用（不新建独立 Service）。 */
    @Test
    void k_ens_10_reuseLabeledNodePortService() {
        String logical = "contractit-reuse";
        String svcName = createLabeledService("biz-contractit-reuse", "NodePort", 8563, 30050, logical);

        NodePortExposer exposer = new NodePortExposer(client);
        NodePortExposer.ExposeResult r = exposer.expose(NS, POD, logical, 8563);

        assertThat(r.serviceName()).as("K-ENS-10：复用业务 Service（非 arthas-mcp- 新建）").isEqualTo(svcName);
        assertThat(r.nodePort()).as("K-ENS-10：复用既有 nodePort").isEqualTo(30050);
    }

    /** K-ENS-11：预打 label 的 ClusterIP Service → patch type=NodePort + 加端口，K8S 分配 nodePort。 */
    @Test
    void k_ens_11_clusterIpServicePatchedToNodePort(TestInfo info) {
        String logical = "contractit-cip";
        String svcName = createLabeledService("biz-contractit-cip", "ClusterIP", 8563, null, logical);

        NodePortExposer exposer = new NodePortExposer(client);
        NodePortExposer.ExposeResult r = exposer.expose(NS, POD, logical, 8563);

        assertThat(r.serviceName()).as("K-ENS-11：复用业务 Service（patch 而非新建）").isEqualTo(svcName);
        assertThat(r.nodePort()).as("K-ENS-11：K8S 分配的 nodePort 在默认范围内").isBetween(30000, 32767);
        Service after = client.services().inNamespace(NS).withName(svcName).get();
        assertThat(after.getSpec().getType()).as("K-ENS-11：Service type 改为 NodePort").isEqualTo("NodePort");
    }

    /** K-ENS-12：二次 ensure → nodePort 不变 + 端口数量不重复增长（幂等）。 */
    @Test
    void k_ens_12_idempotentReuse() {
        String logical = "contractit-idem";
        String svcName = createLabeledService("biz-contractit-idem", "NodePort", 8563, 30052, logical);

        NodePortExposer exposer = new NodePortExposer(client);
        NodePortExposer.ExposeResult first = exposer.expose(NS, POD, logical, 8563);
        NodePortExposer.ExposeResult second = exposer.expose(NS, POD, logical, 8563);

        assertThat(second.nodePort()).as("K-ENS-12：二次 ensure nodePort 不变")
                .isEqualTo(first.nodePort()).isEqualTo(30052);
        Service after = client.services().inNamespace(NS).withName(svcName).get();
        assertThat(after.getSpec().getPorts()).as("K-ENS-12：端口数量不重复增长").hasSize(1);
        assertThat(after.getSpec().getPorts().stream().map(ServicePort::getNodePort).findFirst().orElse(null))
                .as("K-ENS-12：端口仍含既有 nodePort").isEqualTo(30052);
    }

    /** K-ENS-10 回退：无带 label Service → 回退新建独立 Service（arthas-mcp- 前缀，003 现状）。 */
    @Test
    void k_ens_10_fallbackNewWhenNoLabel() {
        String logical = "contractit-fallback"; // 不预创建带 label 的业务 Service
        String expected = "arthas-mcp-" + NodePortExposer.sanitizeLabelValue(logical);
        createdServices.add(expected);

        NodePortExposer exposer = new NodePortExposer(client);
        NodePortExposer.ExposeResult r = exposer.expose(NS, POD, logical, 8563);

        assertThat(r.serviceName()).as("K-ENS-10 回退：新建独立 Service（arthas-mcp- 前缀）")
                .startsWith("arthas-mcp-");
        assertThat(r.nodePort()).as("K-ENS-10 回退：K8S 分配 nodePort").isBetween(30000, 32767);
    }

    // ===== fabric8 Service 夹具管理（真实集群操作） =====

    /** 预创建带 label 的业务 Service（createOrReplace 幂等），登记待清理。 */
    private String createLabeledService(String name, String type, int port, Integer nodePort, String logical) {
        createdServices.add(name);
        String label = NodePortExposer.sanitizeLabelValue(logical);
        Service svc = new ServiceBuilder()
                .withNewMetadata().withName(name).withNamespace(NS)
                .addToLabels(NodePortExposer.TARGET_LABEL_KEY, label).endMetadata()
                .withNewSpec().withType(type)
                .addToSelector("app", "demo-business")
                .addNewPort().withPort(port).withNewTargetPort(port).withNodePort(nodePort).endPort()
                .endSpec()
                .build();
        client.services().inNamespace(NS).resource(svc).createOrReplace();
        return name;
    }

    private void deleteServiceQuiet(String name) {
        try {
            client.services().inNamespace(NS).withName(name).delete();
        } catch (RuntimeException ignored) {
            // 幂等清理
        }
    }
}
```

---

## com/arthas/gateway/orchestration/NodePortExposerTest.java

**文件**：`src/test/java/com/arthas/gateway/orchestration/NodePortExposerTest.java`

```java
package com.arthas.gateway.orchestration;

import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.NodeList;
import io.fabric8.kubernetes.api.model.NodeListBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.api.model.ServiceListBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 005 US1 NodePortExposer Service 复用单测（T009，K-ENS-10/11/12）。
 *
 * <p>用 fabric8 官方 {@link KubernetesMockServer}（请求-响应模式，模拟真实 K8S API server）验证 NodePortExposer
 * 的<b>决策逻辑</b>（findLabeledService 命中走 patch / 未命中回退新建 / 幂等复用）。NodePortExposer 直接持
 * {@link KubernetesClient} 调 fluent 链，与 fabric8 紧耦合——Mockito 深桩对 fabric8 复杂泛型链不可靠，
 * 故用 fabric8 官方 mock server（labelSelector 查询、PUT、POST 真实 HTTP 模拟）。真实 k3s 端到端（含
 * NodePort 分配）由 {@code NodePortExposerContractIT}（T010）覆盖。
 *
 * <h3>覆盖断言</h3>
 * <ul>
 *   <li><b>K-ENS-10 复用</b>：带 {@code arthas-mcp-gateway/target} label 的现有 NodePort Service → ensure 复用
 *       （result.serviceName = 业务 Service 名，非 {@code arthas-mcp-} 前缀，不新建独立 Service）。</li>
 *   <li><b>K-ENS-11</b>：带 label 的 ClusterIP Service → patch（PUT type=NodePort + 加端口），复用业务 Service 名。</li>
 *   <li><b>K-ENS-12 幂等</b>：已含同 targetPort → 复用既有 nodePort（二次 ensure ports 不变）。</li>
 *   <li><b>K-ENS-10 回退</b>：无带 label Service → 回退新建独立 Service（{@code arthas-mcp-} 前缀，003 现状）。</li>
 * </ul>
 */
@EnableKubernetesMockClient(crud = false)
class NodePortExposerTest {

    private static final String NS = "default";
    private static final String POD = "demo-business";
    private static final String LOGICAL = "debian-demo-business";
    private static final String LABEL = NodePortExposer.sanitizeLabelValue(LOGICAL);
    private static final int MCP_PORT = 8563;
    private static final String NODE_IP = "192.168.31.92";

    // fabric8 注解注入（PER_METHOD：每测试独立 mock server）
    KubernetesMockServer mockServer;
    KubernetesClient client;

    /** K-ENS-10：带 label 的现有 NodePort Service → ensure 复用（不新建独立 Service）。 */
    @Test
    void k_ens_10_reuseExistingLabeledNodePortService() {
        Service business = service("business-svc", LABEL, "NodePort", MCP_PORT, 32001);
        registerCommon();
        registerLabeledList(business);

        NodePortExposer exposer = new NodePortExposer(client);
        NodePortExposer.ExposeResult r = exposer.expose(NS, POD, LOGICAL, MCP_PORT);

        assertThat(r.serviceName()).as("K-ENS-10：复用业务 Service 名（非 arthas-mcp- 前缀）")
                .isEqualTo("business-svc");
        assertThat(r.nodePort()).as("K-ENS-10：复用既有 nodePort").isEqualTo(32001);
        assertThat(r.mcpUrl()).isEqualTo("http://" + NODE_IP + ":32001");
    }

    /** K-ENS-11：带 label 的 ClusterIP Service → patch（PUT type=NodePort + 加端口），复用业务 Service。 */
    @Test
    void k_ens_11_clusterIpServicePatchedToNodePort() {
        Service clusterIp = service("business-svc", LABEL, "ClusterIP", MCP_PORT, null);
        Service patched = service("business-svc", LABEL, "NodePort", MCP_PORT, 32005);
        registerCommon();
        registerLabeledList(clusterIp);
        // patchServiceAddNodePort update：fabric8 update() 在 svc 无 resourceVersion 时先 GET 拿版本再 PUT。
        mockServer.expect().get().withPath("/api/v1/namespaces/default/services/business-svc")
                .andReturn(200, clusterIp).always();
        // PUT（patch type=NodePort + 加端口）→ 返回 patch 后 Service（K8S 分配 nodePort=32005）
        mockServer.expect().put().withPath("/api/v1/namespaces/default/services/business-svc")
                .andReturn(200, patched).always();

        NodePortExposer exposer = new NodePortExposer(client);
        NodePortExposer.ExposeResult r = exposer.expose(NS, POD, LOGICAL, MCP_PORT);

        assertThat(r.serviceName()).as("K-ENS-11：复用业务 Service 名（patch 而非新建）")
                .isEqualTo("business-svc");
        assertThat(r.nodePort()).as("K-ENS-11：patch 后分配的 nodePort").isEqualTo(32005);
    }

    /** K-ENS-12：已含同 targetPort 的 NodePort Service → 复用既有 nodePort（二次 ensure ports 不变）。 */
    @Test
    void k_ens_12_idempotentReuseSameNodePort() {
        Service business = service("business-svc", LABEL, "NodePort", MCP_PORT, 32001);
        registerCommon();
        registerLabeledList(business);

        NodePortExposer exposer = new NodePortExposer(client);
        NodePortExposer.ExposeResult first = exposer.expose(NS, POD, LOGICAL, MCP_PORT);
        NodePortExposer.ExposeResult second = exposer.expose(NS, POD, LOGICAL, MCP_PORT);

        assertThat(second.serviceName()).as("K-ENS-12：复用业务 Service（非新建 arthas-mcp-）")
                .isEqualTo("business-svc").isEqualTo(first.serviceName());
        assertThat(second.nodePort()).as("K-ENS-12：二次 ensure nodePort 不变")
                .isEqualTo(32001).isEqualTo(first.nodePort());
    }

    /** K-ENS-10 回退：无带 label 的 Service → 回退新建独立 Service（arthas-mcp- 前缀，003 现状）。 */
    @Test
    void k_ens_10_fallbackNewServiceWhenNoLabel() {
        Service created = service("arthas-mcp-debian-demo-business", LABEL, "NodePort", MCP_PORT, 32010);
        registerCommon();
        registerLabeledList(); // 空列表 → 触发回退
        // ensureNodePortService（回退）：POST create → created；GET withName → created
        mockServer.expect().post().withPath("/api/v1/namespaces/default/services")
                .andReturn(201, created).always();
        mockServer.expect().get().withPath("/api/v1/namespaces/default/services/arthas-mcp-debian-demo-business")
                .andReturn(200, created).always();

        NodePortExposer exposer = new NodePortExposer(client);
        NodePortExposer.ExposeResult r = exposer.expose(NS, POD, LOGICAL, MCP_PORT);

        assertThat(r.serviceName()).as("K-ENS-10 回退：新建独立 Service（arthas-mcp- 前缀）")
                .startsWith("arthas-mcp-").contains("debian-demo-business");
        assertThat(r.nodePort()).isEqualTo(32010);
    }

    /** pod 不存在 → IllegalStateException（既有行为，003 兼容）。 */
    @Test
    void exposeThrowsWhenPodMissing() {
        mockServer.expect().get().withPath("/api/v1/namespaces/default/pods/demo-business")
                .andReturn(404, null).always();

        NodePortExposer exposer = new NodePortExposer(client);

        assertThatThrownBy(() -> exposer.expose(NS, POD, LOGICAL, MCP_PORT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(POD);
    }

    // ===== mock server 端点注册辅助 =====

    /** 注册公共端点：pod get/update（labelPod）、nodes list（resolveNodeIp）。 */
    private void registerCommon() {
        mockServer.expect().get().withPath("/api/v1/namespaces/default/pods/demo-business")
                .andReturn(200, podWithoutLabel()).always();
        // labelPod update（pod 无 label → 打 label；PUT 返回 pod）
        mockServer.expect().put().withPath("/api/v1/namespaces/default/pods/demo-business")
                .andReturn(200, podWithoutLabel()).always();
        mockServer.expect().get().withPath("/api/v1/nodes")
                .andReturn(200, new NodeListBuilder().withItems(nodeWithInternalIp(NODE_IP)).build()).always();
    }

    /**
     * 注册 labelSelector list 端点（findLabeledService 查询）。
     *
     * <p>fabric8 mock server 用精确匹配（path + query）。fabric8 client 发的 labelSelector 请求 path 为
     * {@code /services?labelSelector=arthas-mcp-gateway%2Ftarget%3D<value>}（{@code /}→{@code %2F}，
     * {@code =}→{@code %3D}），故 withPath 须含完整 encoded query。
     *
     * @param services 命中的带 label Service 列表（空 → findLabeledService 返空，触发回退）
     */
    private void registerLabeledList(Service... services) {
        ServiceList list = new ServiceListBuilder().withItems(services).build();
        String path = "/api/v1/namespaces/default/services?labelSelector="
                + "arthas-mcp-gateway%2Ftarget%3D" + LABEL;
        mockServer.expect().get().withPath(path).andReturn(200, list).always();
    }

    // ===== fabric8 model 构造辅助 =====

    private static Service service(String name, String label, String type, int port, Integer nodePort) {
        return new ServiceBuilder()
                .withNewMetadata().withName(name).withNamespace(NS)
                .addToLabels(NodePortExposer.TARGET_LABEL_KEY, label).endMetadata()
                .withNewSpec().withType(type)
                .addNewPort().withPort(port).withNewTargetPort(port)
                .withNodePort(nodePort).endPort()
                .endSpec()
                .build();
    }

    private static Pod podWithoutLabel() {
        return new PodBuilder()
                .withNewMetadata().withName(POD).withNamespace(NS).endMetadata()
                .withNewSpec().endSpec()
                .build();
    }

    private static Node nodeWithInternalIp(String ip) {
        return new NodeBuilder()
                .withNewMetadata().withName("node1").endMetadata()
                .withNewStatus()
                .addNewAddress().withType("InternalIP").withAddress(ip).endAddress()
                .endStatus()
                .build();
    }
}
```

---

## com/arthas/gateway/orchestration/OrchestrationRecordTest.java

**文件**：`src/test/java/com/arthas/gateway/orchestration/OrchestrationRecordTest.java`

```java
package com.arthas.gateway.orchestration;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T013 {@code OrchestrationRecord}/{@code OrchestrationRecordStore} 状态机与存储测试
 * （surefire 波次 A，纯逻辑，无 K8S）。
 *
 * <p>断言 data-model §8/§9：
 * <ul>
 *   <li>字段集合：logicalName/server/pod/namespace/mcpUrl/serviceRef/status/error/createdAt/completedAt。</li>
 *   <li>状态机：{@code ensuring}（非终态）→ {@code ready}/{@code reused}/{@code failed}（终态）。</li>
 *   <li>{@code createdAt} 为<b>传入</b>瞬时量（非进程内取时，与 001 {@code GatewayTask.createdAt} 一致，便于确定性测试）。</li>
 *   <li>{@code failed} 保留已暴露的 mcpUrl/serviceRef 副作用字段（§4.1：可清理副作用记录供运维追溯）。</li>
 *   <li>Store：按 logicalName 覆盖最新状态、get/list 语义。</li>
 * </ul>
 *
 * <p>TDD：先于实现编写（red：{@code OrchestrationRecord}/{@code OrchestrationRecordStore} 尚不存在）。
 */
class OrchestrationRecordTest {

    private static final Instant T0 = Instant.parse("2026-06-23T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-06-23T00:00:05Z");

    // ===== 状态机：ensuring（非终态） =====

    @Test
    void ensuringRecordCarriesPassedCreatedAtAndIsNonTerminal() {
        OrchestrationRecord r = OrchestrationRecord.ensuring("srv-pod", "srv", "pod", "default", T0);
        assertThat(r.logicalName()).isEqualTo("srv-pod");
        assertThat(r.server()).isEqualTo("srv");
        assertThat(r.pod()).isEqualTo("pod");
        assertThat(r.namespace()).isEqualTo("default");
        assertThat(r.status()).isEqualTo(OrchestrationRecord.Status.ENSURING);
        assertThat(r.createdAt()).as("createdAt 须为传入瞬时量（非进程取时）").isEqualTo(T0);
        assertThat(r.completedAt()).isNull();
        assertThat(r.mcpUrl()).isNull();
        assertThat(r.serviceRef()).isNull();
        assertThat(r.error()).isNull();
        assertThat(r.status().isTerminal()).as("ENSURING 非终态").isFalse();
    }

    // ===== ensuring → ready（新供给完成） =====

    @Test
    void transitionToReadyIsTerminalWithExposedUrlAndService() {
        OrchestrationRecord ready = OrchestrationRecord.ensuring("srv-pod", "srv", "pod", "default", T0)
                .ready("http://192.168.31.92:31234", "arthas-mcp-srv-pod/31234", T1);
        assertThat(ready.status()).isEqualTo(OrchestrationRecord.Status.READY);
        assertThat(ready.status().isTerminal()).isTrue();
        assertThat(ready.mcpUrl()).isEqualTo("http://192.168.31.92:31234");
        assertThat(ready.serviceRef()).isEqualTo("arthas-mcp-srv-pod/31234");
        assertThat(ready.completedAt()).isEqualTo(T1);
        assertThat(ready.error()).isNull();
        assertThat(ready.createdAt()).isEqualTo(T0); // 传入瞬时量贯穿转换
    }

    // ===== ensuring → reused（幂等复用命中） =====

    @Test
    void transitionToReusedIsTerminal() {
        OrchestrationRecord reused = OrchestrationRecord.ensuring("srv-pod", "srv", "pod", "default", T0)
                .reused("http://192.168.31.92:31234", "arthas-mcp-srv-pod/31234", T1);
        assertThat(reused.status()).isEqualTo(OrchestrationRecord.Status.REUSED);
        assertThat(reused.status().isTerminal()).isTrue();
        assertThat(reused.completedAt()).isEqualTo(T1);
    }

    // ===== ensuring → failed（终态；保留已暴露副作用供追溯；§4.1） =====

    @Test
    void transitionToFailedIsTerminalAndPreservesSideEffectsForTraceability() {
        OrchestrationRecord.Error err = new OrchestrationRecord.Error(
                "health-check", "unreachable", "arthas MCP 端口 30s 未就绪");
        // 假设暴露 Service 成功但健康检查失败：mcpUrl/serviceRef 应保留进 failed 记录（§4.1 可清理副作用）
        OrchestrationRecord failed = OrchestrationRecord.ensuring("srv-pod", "srv", "pod", "default", T0)
                .withExposed("http://192.168.31.92:31234", "arthas-mcp-srv-pod/31234")
                .failed(err, T1);
        assertThat(failed.status()).isEqualTo(OrchestrationRecord.Status.FAILED);
        assertThat(failed.status().isTerminal()).isTrue();
        assertThat(failed.error()).isEqualTo(err);
        assertThat(failed.error().phase()).isEqualTo("health-check");
        assertThat(failed.mcpUrl()).as("已暴露 mcpUrl 保留供运维追溯清理").isEqualTo("http://192.168.31.92:31234");
        assertThat(failed.serviceRef()).isEqualTo("arthas-mcp-srv-pod/31234");
        assertThat(failed.completedAt()).isEqualTo(T1);
    }

    // ===== Store：按 logicalName 覆盖、get/list =====

    @Test
    void storeOverwritesByLogicalNameAndSupportsGetList() {
        OrchestrationRecordStore store = new OrchestrationRecordStore();
        assertThat(store.get("x")).isEqualTo(Optional.empty());
        assertThat(store.list()).isEmpty();

        store.record(OrchestrationRecord.ensuring("a-pod", "a", "pod", "default", T0));
        store.record(OrchestrationRecord.ensuring("b-pod", "b", "pod", "default", T0));
        assertThat(store.list()).hasSize(2);

        // 同 logicalName 覆盖最新状态（ensuring → ready）
        store.record(OrchestrationRecord.ensuring("a-pod", "a", "pod", "default", T0)
                .ready("http://1.2.3.4:30000", "svc-a/30000", T1));
        assertThat(store.list()).as("覆盖而非新增").hasSize(2);
        Optional<OrchestrationRecord> a = store.get("a-pod");
        assertThat(a).isPresent();
        assertThat(a.get().status()).isEqualTo(OrchestrationRecord.Status.READY);
    }
}
```


---

## com/arthas/gateway/task/AsyncTaskExecutorShutdownRaceTest.java

**文件**：`src/test/java/com/arthas/gateway/task/AsyncTaskExecutorShutdownRaceTest.java`

```java
package com.arthas.gateway.task;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T006 {@link AsyncTaskExecutor} 关闭竞态单测（002 整改 · P0-1/P0-2，US1）。
 *
 * <p>验证 {@code onTerminal} 在<b>所有终态路径</b>恰好调用一次，且外层提交被拒时<b>不留僵尸 WORKING</b>：
 * <ul>
 *   <li>(a) 外层 {@code pool.submit} 被拒（池已关）→ store 无残留 + onTerminal 一次（释放槽/背压，P0-2）。</li>
 *   <li>(b) 内层 {@code pool.submit(work)} 被拒 → 任务标 FAILED（无僵尸，P0-1）+ onTerminal 一次。</li>
 *   <li>(c) 正常完成 → onTerminal 一次。</li>
 *   <li>(d) cancel → onTerminal 一次。</li>
 * </ul>
 *
 * <p><b>真实性</b>：用<b>真实 {@code ExecutorService}</b>——(a) 用默认虚拟线程池 {@code shutdownNow} 后 submit 触发<b>真实
 * {@code RejectedExecutionException}</b>；(b) 用受控 {@code AbstractExecutorService}（真实线程执行 + 第 2 次 execute 真实抛
 * RejectedExecutionException），非 arthas 桩、非 mock。{@code onTerminal} 仅计数（真实回调，非桩语义）。
 */
class AsyncTaskExecutorShutdownRaceTest {

    private AsyncTaskExecutor executor;

    @AfterEach
    void closeExecutor() {
        if (executor != null) {
            executor.close();
        }
    }

    private static final Supplier<Instant> CLOCK = Instant::now;
    private static final Callable<CallToolResult> OK_WORK =
            () -> new CallToolResult(List.of(new TextContent("ok")), false, null, null);

    /** (a) 外层提交被拒（池已关）→ store 无僵尸 + onTerminal 恰好一次。 */
    @Test
    void outerSubmitRejected_leavesNoZombieAndInvokesOnTerminalOnce() {
        TaskStore store = new TaskStore(Duration.ofHours(1), CLOCK);
        executor = new AsyncTaskExecutor(store, Duration.ofMinutes(11), CLOCK, () -> "t-outer");
        executor.close(); // 关闭真实池 → 后续 submit 触发真实 RejectedExecutionException

        AtomicInteger onTerminal = new AtomicInteger();
        assertThatThrownBy(() -> executor.submit("watch", "order", OK_WORK, onTerminal::incrementAndGet))
                .isInstanceOf(RejectedExecutionException.class);

        assertThat(store.list()).as("外层拒绝后 store 无残留 WORKING（P0-1 无僵尸）").isEmpty();
        assertThat(store.get("t-outer")).as("僵尸任务已 remove").isEmpty();
        assertThat(onTerminal.get()).as("onTerminal 恰好一次（释放槽/背压，P0-2）").isEqualTo(1);
    }

    /** (b) 内层 work 提交被拒 → 任务标 FAILED（无僵尸）+ onTerminal 一次。 */
    @Test
    void innerSubmitRejected_marksFailedAndInvokesOnTerminalOnce() throws Exception {
        TaskStore store = new TaskStore(Duration.ofHours(1), CLOCK);
        AtomicInteger execCount = new AtomicInteger();
        // 受控真实执行器：第 1 次 execute 跑 supervisor，第 2 次（内层 work）真实抛 RejectedExecutionException
        ExecutorService innerRejectingPool = new AbstractExecutorService() {
            @Override
            public void execute(Runnable command) {
                if (execCount.incrementAndGet() == 2) {
                    throw new RejectedExecutionException("inner work submit rejected (test)");
                }
                Thread.ofVirtual().start(command); // 真实执行 supervisor
            }

            @Override
            public void shutdown() {
                // no-op
            }

            @Override
            public List<Runnable> shutdownNow() {
                return List.of();
            }

            @Override
            public boolean isShutdown() {
                return false;
            }

            @Override
            public boolean isTerminated() {
                return false;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return true;
            }
        };
        executor = new AsyncTaskExecutor(store, Duration.ofMinutes(11), CLOCK, () -> "t-inner", innerRejectingPool);

        AtomicInteger onTerminal = new AtomicInteger();
        GatewayTask task = executor.submit("watch", "order", OK_WORK, onTerminal::incrementAndGet);

        awaitOnTerminal(onTerminal, 1);
        assertThat(task.status()).as("内层拒绝 → markFailed（P0-1 无僵尸）").isEqualTo(TaskState.FAILED);
        assertThat(task.error()).isNotNull();
        assertThat(task.error().reason()).isEqualTo(TaskError.REASON_BACKEND_UNREACHABLE);
        assertThat(onTerminal.get()).as("onTerminal 恰好一次（释放槽，P0-2）").isEqualTo(1);
    }

    /** (c) 正常完成 → onTerminal 恰好一次。 */
    @Test
    void normalCompletionInvokesOnTerminalOnce() throws Exception {
        TaskStore store = new TaskStore(Duration.ofHours(1), CLOCK);
        executor = new AsyncTaskExecutor(store, Duration.ofMinutes(11), CLOCK, () -> "t-ok");

        AtomicInteger onTerminal = new AtomicInteger();
        GatewayTask task = executor.submit("watch", "order", OK_WORK, onTerminal::incrementAndGet);

        awaitOnTerminal(onTerminal, 1);
        assertThat(task.status()).isEqualTo(TaskState.COMPLETED);
        assertThat(onTerminal.get()).as("正常完成 onTerminal 一次").isEqualTo(1);
    }

    /** (d) cancel → onTerminal 恰好一次。 */
    @Test
    void cancelInvokesOnTerminalOnce() throws Exception {
        TaskStore store = new TaskStore(Duration.ofHours(1), CLOCK);
        executor = new AsyncTaskExecutor(store, Duration.ofMinutes(11), CLOCK, () -> "t-cancel");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch block = new CountDownLatch(1);

        AtomicInteger onTerminal = new AtomicInteger();
        GatewayTask task = executor.submit("watch", "order", blockingWork(entered, block), onTerminal::incrementAndGet);
        entered.await();

        assertThat(executor.cancel(task)).isTrue();
        block.countDown();
        awaitOnTerminal(onTerminal, 1);

        assertThat(task.status()).isEqualTo(TaskState.CANCELLED);
        assertThat(onTerminal.get()).as("cancel 后 onTerminal 一次（释放槽）").isEqualTo(1);
    }

    /** 阻塞 callable：进入后阻塞 latch（时序占位，非 arthas 成功桩）。 */
    private static Callable<CallToolResult> blockingWork(CountDownLatch entered, CountDownLatch block) {
        return () -> {
            entered.countDown();
            try {
                block.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("test-only，被 cancel 中断");
        };
    }

    /**
     * 轮询等待 onTerminal 达到期望次数（最多 5s）。
     *
     * <p>注意：onTerminal 在 orchestrate 的 finally 执行，<b>晚于</b> task 终态标记（markCompleted/Failed/Cancelled
     * 在 catch/try 中先设）。故须直接 await onTerminal 计数，而非仅等 task.isTerminal（避免终态已置但 finally 未到的竞态）。
     */
    private static void awaitOnTerminal(AtomicInteger onTerminal, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (onTerminal.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        if (onTerminal.get() < expected) {
            throw new AssertionError("onTerminal 未在 5s 内达到 " + expected + "，当前 " + onTerminal.get());
        }
    }
}
```


---

## com/arthas/gateway/task/AsyncTaskExecutorTest.java

**文件**：`src/test/java/com/arthas/gateway/task/AsyncTaskExecutorTest.java`

```java
package com.arthas.gateway.task;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T033 {@link AsyncTaskExecutor} 纯逻辑单测（surefire）。
 *
 * <p><b>测试边界</b>（遵循宪法「真实环境、禁止桩」）：仅测执行器的<b>编排契约</b>——submit 立即返 WORKING +
 * 入 TaskStore + taskId 唯一（G-ASYNC-1）、cancel 转 CANCELLED（G-TC-1）、终态幂等（G-TC-2）。
 * 后台对后端的<b>真实完成/失败/isError 透传</b>（真实结果→COMPLETED、真实超时→FAILED）由
 * {@code AsyncTaskTimeoutIT}（T030）与端到端 {@code AsyncTaskContractIT}（T035）以<b>真实 arthas</b> 覆盖。
 *
 * <p><b>阻塞 callable 不是 arthas 桩</b>：测试用 {@code latch.await()} 阻塞的后台 callable 仅为<b>时序占位</b>
 * （不返回任何 arthas 成功响应、不模拟后端结果），用于证明「submit 不等后端、任务停在 WORKING」；
 * 释放后抛异常（不产出任何成功结果）。arthas 真实响应保真度由上述 IT 保证。
 *
 * <p>{@link GatewayTask} 的状态机本身（markCompleted/markFailed 终态不可逆、isError 原样保留）已在
 * {@code GatewayTaskTest}（T031）覆盖，此处不重复。
 */
class AsyncTaskExecutorTest {

    private AsyncTaskExecutor executor;

    @AfterEach
    void closeExecutor() {
        if (executor != null) {
            executor.close();
        }
    }

    /** 阻塞 callable：进入后阻塞在 latch，释放后抛异常（永不产出 arthas 成功结果，非桩）。 */
    private static Callable<CallToolResult> blockingWork(CountDownLatch entered, CountDownLatch block) {
        return () -> {
            entered.countDown();
            block.await(); // 阻塞，模拟「后端在途」
            throw new IllegalStateException("test-only，永不产出成功结果");
        };
    }

    @Test
    void submitReturnsWorkingTaskImmediatelyAndStoresIt() throws Exception {
        TaskStore store = new TaskStore(Duration.ofHours(1), java.time.Instant::now);
        executor = new AsyncTaskExecutor(store, Duration.ofMinutes(11));

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch block = new CountDownLatch(1);

        long start = System.nanoTime();
        GatewayTask task = executor.submit("watch", "order-service",
                blockingWork(entered, block));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(task.status()).as("submit 立即返 WORKING（G-ASYNC-1，不等后端）")
                .isEqualTo(TaskState.WORKING);
        assertThat(elapsedMs).as("submit 不阻塞等后端完成").isLessThan(2000);
        assertThat(task.taskId()).as("taskId 形如 t-<hex>").matches("t-[0-9a-f]{6,}");
        assertThat(task.toolName()).isEqualTo("watch");
        assertThat(task.target()).isEqualTo("order-service");
        assertThat(store.get(task.taskId())).as("任务已入 TaskStore").containsSame(task);

        assertThat(entered.await(2, TimeUnit.SECONDS))
                .as("后台已进入（证明 submit 确实调度了后台）").isTrue();
        assertThat(task.status()).as("后端在途期间任务仍 WORKING").isEqualTo(TaskState.WORKING);

        block.countDown(); // 释放后台（不测完成态）
    }

    @Test
    void submitGeneratesUniqueTaskIds() {
        TaskStore store = new TaskStore(Duration.ofHours(1), java.time.Instant::now);
        executor = new AsyncTaskExecutor(store, Duration.ofMinutes(11));
        CountDownLatch block = new CountDownLatch(1);
        try {
            GatewayTask a = executor.submit("watch", "order", blockingWork(new CountDownLatch(1), block));
            GatewayTask b = executor.submit("trace", "pay", blockingWork(new CountDownLatch(1), block));

            assertThat(a.taskId()).isNotEqualTo(b.taskId());
        } finally {
            block.countDown();
        }
    }

    @Test
    void cancelMarksWorkingTaskCancelled() throws Exception {
        TaskStore store = new TaskStore(Duration.ofHours(1), java.time.Instant::now);
        executor = new AsyncTaskExecutor(store, Duration.ofMinutes(11));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch block = new CountDownLatch(1);
        GatewayTask task = executor.submit("watch", "order", blockingWork(entered, block));
        entered.await(); // 确保后台已进入阻塞

        boolean transitioned = executor.cancel(task);

        assertThat(transitioned).as("WORKING 任务 cancel 成功（G-TC-1）").isTrue();
        assertThat(task.status()).isEqualTo(TaskState.CANCELLED);
        block.countDown();
    }

    @Test
    void cancelOnTerminalTaskIsIdempotent() {
        TaskStore store = new TaskStore(Duration.ofHours(1), java.time.Instant::now);
        executor = new AsyncTaskExecutor(store, Duration.ofMinutes(11));
        CountDownLatch block = new CountDownLatch(1);
        GatewayTask task = executor.submit("watch", "order", blockingWork(new CountDownLatch(1), block));

        // 直接置终态（不经后台返回 arthas 结果，避免桩）；验证 cancel 对终态任务的幂等语义
        assertThat(task.markCancelled()).as("前置：置 CANCELLED").isTrue();

        boolean transitioned = executor.cancel(task);

        assertThat(transitioned).as("终态任务 cancel 幂等返 false（G-TC-2）").isFalse();
        assertThat(task.status()).as("状态不变").isEqualTo(TaskState.CANCELLED);
        block.countDown();
    }

    @Test
    void taskGetOnUnknownReturnsEmptyViaStore() {
        // task-get 未知 taskId 的底层语义：TaskStore.get → empty（执行器持有 store 句柄供 handler 用）
        TaskStore store = new TaskStore(Duration.ofHours(1), java.time.Instant::now);
        executor = new AsyncTaskExecutor(store, Duration.ofMinutes(11));

        assertThat(executor.store().get("t-ghost")).isEmpty();
    }
}
```


---

## com/arthas/gateway/task/GatewayTaskTest.java

**文件**：`src/test/java/com/arthas/gateway/task/GatewayTaskTest.java`

```java
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
```


---

## com/arthas/gateway/task/GlobalBackpressureTest.java

**文件**：`src/test/java/com/arthas/gateway/task/GlobalBackpressureTest.java`

```java
package com.arthas.gateway.task;

import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.BackendEntry;
import com.arthas.gateway.backend.BackendRegistry;
import com.arthas.gateway.backend.CircuitBreaker;
import com.arthas.gateway.backend.Protocol;
import com.arthas.gateway.backend.RegistryHolder;
import com.arthas.gateway.handler.GatewayToolHandlers;
import com.arthas.gateway.handler.McpErrorCodes;
import com.arthas.gateway.handler.ToolsCallRouter;
import com.arthas.gateway.testfixtures.FakeBackendClient;
import com.arthas.gateway.tool.ExposedTool;
import com.arthas.gateway.tool.RoutingMode;
import com.arthas.gateway.tool.TaskSupport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T016 全局背压单测(002 整改 · P2-4/FR-010,US4)。
 *
 * <p>验证跨 target 累计在途异步任务受全局上限闸门:{@code AsyncTaskExecutor} 持 {@code globalInflight} 计数,
 * {@code submit} 前 acquire、终态 release。动态默认=后端数×5(本测试注入小 cap=2 便于触发)。
 *
 * <p><b>真实性</b>:2 个真实 {@link BackendEntry}(STREAMABLE)+ 受控 {@link FakeBackendClient}
 * (callTool 阻塞在 latch 上保持 inflight,模拟真实在途异步任务)。第 3 次跨 target 提交触发真实
 * {@link GlobalConcurrencyLimitException}(非桩)→ 路由器翻译为 {@code global_concurrency_limit}。
 */
class GlobalBackpressureTest {

    private static final String TARGET_A = "svc-a";
    private static final String TARGET_B = "svc-b";

    private final java.util.concurrent.atomic.AtomicInteger idSeq = new java.util.concurrent.atomic.AtomicInteger();

    private TaskStore store;
    private AsyncTaskExecutor executor;
    private ToolsCallRouter router;
    private CountDownLatch block;
    private FakeBackendClient clientA;
    private FakeBackendClient clientB;

    @BeforeEach
    void setUp() {
        store = new TaskStore(Duration.ofHours(1), java.time.Instant::now);
        // 全局 cap=2(注入小值便于触发越界;生产动态默认=后端数×5)
        executor = new AsyncTaskExecutor(store, Duration.ofMinutes(11), java.time.Instant::now,
                () -> "t-gb-" + idSeq.incrementAndGet(),
                java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(), () -> 2);

        block = new CountDownLatch(1);
        clientA = blockingClient();
        clientB = blockingClient();
        BackendEntry entryA = new BackendEntry(cfg(TARGET_A), clientA, CircuitBreaker.create(System::nanoTime));
        BackendEntry entryB = new BackendEntry(cfg(TARGET_B), clientB, CircuitBreaker.create(System::nanoTime));
        BackendRegistry registry = new BackendRegistry(1L, Map.of(TARGET_A, entryA, TARGET_B, entryB));

        router = new ToolsCallRouter(new RegistryHolder(registry), executor,
                new GatewayToolHandlers(new RegistryHolder(registry), executor));
    }

    @AfterEach
    void tearDown() {
        if (block.getCount() > 0) {
            block.countDown(); // 释放在途任务
        }
        executor.close();
        store.close();
    }

    /** 跨 target 累计 inflight 超 cap → 第 3 次提交返 INVALID_PARAMS(reason=global_concurrency_limit)。 */
    @Test
    void crossTargetCumulativeInflightExceedsGlobalCap() {
        // 提交 2 个跨 target 异步(各阻塞在 latch 上,保持 inflight)= cap
        router.route(asyncTool(), new CallToolRequest("watch", Map.of("target", TARGET_A)));
        router.route(asyncTool(), new CallToolRequest("watch", Map.of("target", TARGET_B)));
        assertThat(executor.currentGlobalInflight())
                .as("2 个在途任务占满全局 cap=2").isEqualTo(2);

        // 第 3 次跨 target 提交 → 全局越界 → McpError
        assertThatThrownBy(() -> router.route(asyncTool(), new CallToolRequest("watch", Map.of("target", TARGET_A))))
                .isInstanceOf(McpError.class)
                .satisfies(t -> {
                    McpError err = (McpError) t;
                    assertThat(err.getJsonRpcError().code())
                            .as("全局越界 → INVALID_PARAMS").isEqualTo(McpErrorCodes.INVALID_PARAMS);
                    Map<?, ?> data = (Map<?, ?>) err.getJsonRpcError().data();
                    assertThat(data.get("reason")).isEqualTo("global_concurrency_limit");
                    assertThat(data.get("globalMaxInflight")).isEqualTo(2);
                    assertThat(data.get("target")).isEqualTo(TARGET_A);
                });
        // 全局计数未被第 3 次占用(已回滚),仍为 2
        assertThat(executor.currentGlobalInflight()).isEqualTo(2);
        // 第 3 次未入 store(无僵尸)
        assertThat(store.list(TaskState.WORKING)).hasSize(2);
    }

    /** 未越界:cap 内的异步提交正常接受(store 出现 WORKING 任务、全局计数=1)。 */
    @Test
    void underCapAsyncSubmitsSucceed() {
        router.route(asyncTool(), new CallToolRequest("watch", Map.of("target", TARGET_A)));
        assertThat(executor.currentGlobalInflight()).as("1 个在途,未越界").isEqualTo(1);
        assertThat(store.list(TaskState.WORKING)).hasSize(1);
    }

    private static BackendConfig cfg(String name) {
        return new BackendConfig(name, "http://localhost:9001", Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null), 5000, 60000, 5);
    }

    private FakeBackendClient blockingClient() {
        FakeBackendClient c = new FakeBackendClient();
        c.setCallToolBlock(block); // 同一 latch:两个 client 的 callTool 都阻塞,保持 inflight
        return c;
    }

    private static ExposedTool asyncTool() {
        return new ExposedTool(
                "watch",
                "异步观测",
                Map.of(
                        "type", "object",
                        "properties", new LinkedHashMap<>(),
                        "required", List.of(),
                        "additionalProperties", false),
                TaskSupport.OPTIONAL,
                RoutingMode.ASYNC_TASK);
    }
}
```


---

## com/arthas/gateway/task/TaskStoreGetReadAmplificationTest.java

**文件**：`src/test/java/com/arthas/gateway/task/TaskStoreGetReadAmplificationTest.java`

```java
package com.arthas.gateway.task;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T015 {@link TaskStore#get} 读放大单测(002 整改 · P2-3/FR-009,US4)。
 *
 * <p>修复前 {@code get(taskId)} 每次调全表 {@code cleanExpired()};在大规模终态任务(如 10k)下,
 * 逐条 task-get 会产生 O(N) 读放大(轮询 N 个任务 = O(N²))。修复后 {@code get} 仅判定<b>单条</b>:
 * 存在且未过期→返回;过期→{@code remove(taskId)} 返 empty;不存在→返 empty。<b>不</b>触发全表清理
 * (全表清理仅 {@code list} + 后台 cleaner 负责)。
 *
 * <p><b>断言手段</b>:用包级探针 {@link TaskStore#containsRawForTest(String)}(不经清理的内部存储探测)
 * 证明 {@code get(其它任务)} 不会顺带移除过期的<b>兄弟</b>任务(全表清理才会;单条 O(1) 不会)。
 */
class TaskStoreGetReadAmplificationTest {

    private TaskStore store;

    @AfterEach
    void closeStore() {
        if (store != null) {
            store.close();
        }
    }

    private static CallToolResult result() {
        return new CallToolResult(List.of(new TextContent("ok")), false, null, null);
    }

    /** get(未过期任务) 不顺带移除过期的兄弟任务 → 证明未触发全表 cleanExpired(O(1) 单条判定)。 */
    @Test
    void getDoesNotScanAndEvictSiblingExpiredTasks() {
        Instant t0 = Instant.parse("2026-06-20T10:00:00Z");
        AtomicReference<Instant> clock = new AtomicReference<>(t0);
        store = new TaskStore(Duration.ofMinutes(60), clock::get);

        // expired:终态 + completedAt=t0,推进时钟使其过期
        GatewayTask expired = new GatewayTask("t-expired", "watch", "order", t0, clock::get);
        expired.markCompleted(result());
        store.put(expired);
        // working:永不 expires(completedAt=null),作为 get 的目标
        GatewayTask working = new GatewayTask("t-working", "trace", "pay", t0, clock::get);
        store.put(working);

        clock.set(t0.plus(Duration.ofMinutes(61))); // expired 现已过期

        // get(working):返回 working。若 get 全表清理,expired 会被顺带移除;若 O(1) 单条,expired 留存。
        assertThat(store.get("t-working")).containsSame(working);
        assertThat(store.containsRawForTest("t-expired"))
                .as("get(其它任务) 不触发全表清理 → 过期兄弟任务仍留存(待 list/后台 cleaner 清理)")
                .isTrue();

        // get(expired):单条过期判定 → 移除自身、返 empty(不扫全表)
        assertThat(store.get("t-expired")).as("过期任务自身:单条过期判定返 empty").isEmpty();
        assertThat(store.containsRawForTest("t-expired")).as("过期任务被定向移除").isFalse();
        // working 仍在(未被牵连移除)
        assertThat(store.containsRawForTest("t-working")).isTrue();
    }

    /** get(不存在的 taskId) 返 empty,不触发全表清理(不误伤留存任务)。 */
    @Test
    void getUnknownDoesNotScanAndEvictExpiredTasks() {
        Instant t0 = Instant.parse("2026-06-20T10:00:00Z");
        AtomicReference<Instant> clock = new AtomicReference<>(t0);
        store = new TaskStore(Duration.ofMinutes(60), clock::get);

        GatewayTask expired = new GatewayTask("t-expired", "watch", "order", t0, clock::get);
        expired.markCompleted(result());
        store.put(expired);
        clock.set(t0.plus(Duration.ofMinutes(61)));

        assertThat(store.get("t-ghost")).isEmpty();
        assertThat(store.containsRawForTest("t-expired"))
                .as("get(未知) 不触发全表清理 → 过期任务仍留存")
                .isTrue();

        // 全表清理由 list 承担(惰性):此时 list 会清掉 expired
        assertThat(store.list()).isEmpty();
    }

    /** 大规模终态任务下 get 目标任务不放大:仅目标条受影响,其余 N 条全留存(非 O(N) 清理)。 */
    @Test
    void getUnderLargeStoreOnlyTouchesTarget() {
        Instant t0 = Instant.parse("2026-06-20T10:00:00Z");
        AtomicReference<Instant> clock = new AtomicReference<>(t0);
        store = new TaskStore(Duration.ofMinutes(60), clock::get);

        // N 条过期终态任务(非目标)
        int n = 2000;
        for (int i = 0; i < n; i++) {
            GatewayTask e = new GatewayTask("t-e" + i, "watch", "order", t0, clock::get);
            e.markCompleted(result());
            store.put(e);
        }
        // 目标:working(不过期)
        GatewayTask target = new GatewayTask("t-target", "trace", "pay", t0, clock::get);
        store.put(target);
        clock.set(t0.plus(Duration.ofMinutes(61))); // 其余 N 条过期

        assertThat(store.get("t-target")).containsSame(target);
        // 关键:N 条过期兄弟任务<b>全部</b>留存(get 未全表清理)——若是 O(N) 清理,这些应已被移除
        int retained = 0;
        for (int i = 0; i < n; i++) {
            if (store.containsRawForTest("t-e" + i)) {
                retained++;
            }
        }
        assertThat(retained)
                .as("get(target) 不触发全表清理 → %d 条过期兄弟任务全部留存", n)
                .isEqualTo(n);

        store.close();
        store = null; // 避免重复 close
    }
}
```


---

## com/arthas/gateway/task/TaskStoreTest.java

**文件**：`src/test/java/com/arthas/gateway/task/TaskStoreTest.java`

```java
package com.arthas.gateway.task;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T032 {@link TaskStore} 单测（surefire，纯逻辑，注入时钟测 TTL 确定性）。
 *
 * <p>覆盖内存 Map + TTL 清理的核心不变量（data-model.md §6「TTL 清理」、gateway-tools-contract.md §3 task-list）：
 * <ul>
 *   <li>put/get/list：存取、未知→empty、全量、status 过滤。</li>
 *   <li>同 taskId 覆盖。</li>
 *   <li>TTL：终态任务（completedAt 非空）超过 ttl 被移除；WORKING 任务（completedAt=null）不受 TTL 影响。</li>
 *   <li>惰性清理：get/list 访问时自动清理过期任务；{@link TaskStore#cleanExpired()} 显式清理。</li>
 * </ul>
 */
class TaskStoreTest {

    private TaskStore store;

    @AfterEach
    void closeStore() {
        if (store != null) {
            store.close();
        }
    }

    private static CallToolResult result(String text) {
        return new CallToolResult(List.of(new TextContent(text)), false, null, null);
    }

    @Test
    void putAndGetReturnsTask() {
        store = new TaskStore(Duration.ofHours(1), Instant::now);
        GatewayTask t = new GatewayTask("t-1", "watch", "order", Instant.now(), Instant::now);
        store.put(t);

        assertThat(store.get("t-1")).containsSame(t);
    }

    @Test
    void getUnknownReturnsEmpty() {
        store = new TaskStore(Duration.ofHours(1), Instant::now);
        assertThat(store.get("ghost")).isEmpty();
    }

    @Test
    void listReturnsAllTasks() {
        store = new TaskStore(Duration.ofHours(1), Instant::now);
        GatewayTask a = new GatewayTask("t-a", "watch", "order", Instant.now(), Instant::now);
        GatewayTask b = new GatewayTask("t-b", "trace", "pay", Instant.now(), Instant::now);
        store.put(a);
        store.put(b);

        List<GatewayTask> all = store.list();
        assertThat(all).hasSize(2).containsExactlyInAnyOrder(a, b);
    }

    @Test
    void listWithStatusFilter() {
        store = new TaskStore(Duration.ofHours(1), Instant::now);
        GatewayTask working = new GatewayTask("t-w", "watch", "order", Instant.now(), Instant::now);
        GatewayTask completed = new GatewayTask("t-c", "trace", "pay", Instant.now(), Instant::now);
        completed.markCompleted(result("done"));
        store.put(working);
        store.put(completed);

        assertThat(store.list(TaskState.WORKING)).containsExactly(working);
        assertThat(store.list(TaskState.COMPLETED)).containsExactly(completed);
    }

    @Test
    void putWithSameTaskIdOverwrites() {
        store = new TaskStore(Duration.ofHours(1), Instant::now);
        GatewayTask first = new GatewayTask("t-1", "watch", "order", Instant.now(), Instant::now);
        GatewayTask second = new GatewayTask("t-1", "trace", "pay", Instant.now(), Instant::now);
        store.put(first);
        store.put(second);

        assertThat(store.get("t-1")).containsSame(second);
    }

    @Test
    void cleanExpiredEvictsTerminalTasksBeyondTtl() {
        Instant t0 = Instant.parse("2026-06-20T10:00:00Z");
        AtomicReference<Instant> clock = new AtomicReference<>(t0);
        store = new TaskStore(Duration.ofMinutes(60), clock::get);

        GatewayTask done = new GatewayTask("t-done", "watch", "order", t0, clock::get);
        done.markCompleted(result("ok")); // completedAt = t0
        store.put(done);

        assertThat(store.get("t-done")).as("未过期（now=t0）仍可见").isPresent();
        clock.set(t0.plus(Duration.ofMinutes(61))); // 超过 60min TTL

        int removed = store.cleanExpired();
        assertThat(removed).as("移除 1 条过期终态任务").isEqualTo(1);
        assertThat(store.get("t-done")).as("过期终态任务被移除").isEmpty();
    }

    @Test
    void cleanExpiredKeepsWorkingTasksRegardlessOfAge() {
        Instant t0 = Instant.parse("2026-06-20T10:00:00Z");
        AtomicReference<Instant> clock = new AtomicReference<>(t0);
        store = new TaskStore(Duration.ofMinutes(1), clock::get);

        GatewayTask working = new GatewayTask("t-w", "watch", "order", t0, clock::get);
        store.put(working);

        clock.set(t0.plus(Duration.ofDays(1))); // 远超 TTL，但 WORKING（completedAt=null）不参与 TTL
        store.cleanExpired();

        assertThat(store.get("t-w")).as("WORKING 任务不受 TTL 移除").isPresent();
    }

    @Test
    void cleanExpiredKeepsRecentTerminalTasks() {
        Instant t0 = Instant.parse("2026-06-20T10:00:00Z");
        AtomicReference<Instant> clock = new AtomicReference<>(t0);
        store = new TaskStore(Duration.ofMinutes(60), clock::get);

        GatewayTask done = new GatewayTask("t-done", "watch", "order", t0, clock::get);
        done.markCompleted(result("ok"));
        store.put(done);
        clock.set(t0.plus(Duration.ofMinutes(30))); // 仍在 TTL 窗口内

        assertThat(store.cleanExpired()).as("未超 TTL 不移除").isEqualTo(0);
        assertThat(store.get("t-done")).isPresent();
    }

    @Test
    void getTriggersLazyCleanup() {
        Instant t0 = Instant.parse("2026-06-20T10:00:00Z");
        AtomicReference<Instant> clock = new AtomicReference<>(t0);
        store = new TaskStore(Duration.ofMinutes(60), clock::get);

        GatewayTask done = new GatewayTask("t-done", "watch", "order", t0, clock::get);
        done.markCompleted(result("ok"));
        store.put(done);
        clock.set(t0.plus(Duration.ofMinutes(61)));

        assertThat(store.get("t-done")).as("get 触发惰性清理，过期任务对读不可见").isEmpty();
    }
}
```


---

## com/arthas/gateway/orchestration/TestArthasLauncher.java

**文件**：`src/test/java/com/arthas/gateway/orchestration/TestArthasLauncher.java`

```java
package com.arthas.gateway.orchestration;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 005 US3 测试用 {@link ArthasLauncher} fixture（T022，<b>真实实现非 mock</b>，INV-LAUNCHER-5）。
 *
 * <p>验证 SPI 委托机制（{@code ArthasProvisioner} 委托 launcher）与 @Primary 覆盖（用户自定义实现覆盖 Default，
 * INV-LAUNCHER-3）。<b>不</b>带 {@code @Component}——避免污染所有 SpringBootTest IT（ArthasProvisionerIT 等期望
 * DefaultArthasLauncher）；@Primary 装配由 {@code CustomLauncherContractIT}（T024）的 {@code @TestConfiguration} 显式注入。
 * T023 单测直接 new 或 mock（不经 Spring）。
 *
 * <p><b>探针字段</b>（非 mock 桩，而是真实记录被调用的证据）：
 * <ul>
 *   <li>{@link #locateCalls()}：locatePid 被调次数（验证委托）。</li>
 *   <li>{@link #lastContext()}：最近传入的 LaunchContext（验证契约字段）。</li>
 *   <li>{@link #lastPid()}：startArthas 收到的 pid（验证委托传递 pid）。</li>
 *   <li>{@link #failOnStart()}：注入真实故障（LaunchException → ProvisionException 映射，INV-LAUNCHER-4）。</li>
 * </ul>
 */
public class TestArthasLauncher implements ArthasLauncher {

    /** 固定定位的 PID（验证 ArthasProvisioner 用 launcher 返值，非硬编码）。 */
    public static final long FIXED_PID = 12345L;

    private final AtomicLong locateCalls = new AtomicLong();
    private final AtomicReference<LaunchContext> lastCtx = new AtomicReference<>();
    private final AtomicLong lastPid = new AtomicLong(-1);
    private volatile boolean failOnStart;

    /** 注入真实故障：startArthas 抛 LaunchException（attach_failed@start_arthas，INV-LAUNCHER-4）。 */
    public void failOnStart() {
        this.failOnStart = true;
    }

    public long locateCalls() {
        return locateCalls.get();
    }

    public LaunchContext lastContext() {
        return lastCtx.get();
    }

    public long lastPid() {
        return lastPid.get();
    }

    @Override
    public long locatePid(LaunchContext ctx) {
        locateCalls.incrementAndGet();
        lastCtx.set(ctx);
        return FIXED_PID;
    }

    @Override
    public void startArthas(LaunchContext ctx, long pid) {
        lastCtx.set(ctx);
        lastPid.set(pid);
        if (failOnStart) {
            throw new LaunchException(new OrchestrationRecord.Error(
                    "start_arthas", "attach_failed", "TestArthasLauncher 注入真实故障"));
        }
    }
}
```

---

## com/arthas/gateway/testfixtures/ArthasMcpBackend.java

**文件**：`src/test/java/com/arthas/gateway/testfixtures/ArthasMcpBackend.java`

```java
package com.arthas.gateway.testfixtures;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 真实 arthas MCP 后端夹具（T009，设计文档 §3/§4/§5）。
 *
 * <p>编排：启 {@link DemoBusinessApp} 子进程 → {@code java -jar tools/arthas-boot.jar} attach
 * （纯 Java Attach API，无 bash/as.sh 依赖，Windows 原生）→ 轮询 MCP 端口就绪 → 暴露 {@code baseUrl}。
 * arthas agent 注入目标 JVM 后，MCP HTTP 服务常驻<b>目标 JVM 内</b>，随其生灭（attach 进程短命，exit 0）。
 *
 * <h3>端点裁决（设计文档 §9，本夹具首测实证）</h3>
 * <p>暴露的 {@code baseUrl} 为<b>根 URL</b> {@code http://127.0.0.1:<mcpPort>}（无 {@code /mcp} 后缀）
 * ——对齐 reference {@code ArthasMcpJavaSdkIT} 实证（{@code HttpClientStreamableHttpTransport}
 * .builder("http://127.0.0.1:" + httpPort)}，tools/list+callTool 全成功）。{@code backend-client-contract.md §1}
 * 的 {@code /mcp} 描述为设计假设，以本首测实证为准。
 *
 * <h3>构件（用户约束 2026-06-20：本工程不依赖 arthas）</h3>
 * <p>arthas-boot.jar 作<b>静态工具文件</b> {@code tools/arthas-boot.jar}，经 {@code java -jar} 使用
 * （不入 pom、不构建 reference 源码）。首跑自动下载 arthas 4.3.0 运行时到 {@code ~/.arthas/lib/4.3.0}
 * （{@code --use-version 4.3.0} 锁版本，对齐设计文档 §5）。
 *
 * <h3>原子单元（设计文档 §6，单后端优先）</h3>
 * <p>一个实例 = 1 目标 JVM + 1 arthas。{@link AutoCloseable}，{@code close()} 销毁目标 JVM 子进程
 * （MCP 服务随之释放）。可按逻辑名实例化为多目标后端（集群能力后置）。
 *
 * @see DemoBusinessApp
 * @see McpClientHarness
 */
public final class ArthasMcpBackend implements AutoCloseable {

    /** 业务服务就绪轮询超时（端口监听）。 */
    private static final Duration APP_READY_TIMEOUT = Duration.ofSeconds(30);
    /** arthas attach 进程超时（含首跑下载 arthas 4.3.0 运行时，对齐 reference 90s 并放宽）。 */
    private static final Duration ATTACH_TIMEOUT = Duration.ofSeconds(180);
    /** arthas MCP 端口（注入目标 JVM 内）就绪轮询超时。 */
    private static final Duration MCP_PORT_READY_TIMEOUT = Duration.ofSeconds(30);
    /** 锁定的 arthas 版本（设计文档 §5）。 */
    private static final String ARTHAS_VERSION = "4.3.0";

    private final String logicalName;
    private final int mcpPort;
    private final int appPort;
    private final String baseUrl;
    private final Process appProcess;

    private ArthasMcpBackend(String logicalName, int mcpPort, int appPort, Process appProcess) {
        this.logicalName = logicalName;
        this.mcpPort = mcpPort;
        this.appPort = appPort;
        this.baseUrl = "http://127.0.0.1:" + mcpPort;
        this.appProcess = appProcess;
    }

    /**
     * 启动单后端夹具（NONE 认证）。
     *
     * @param logicalName 逻辑名（多目标后端标识）
     * @return 就绪的夹具（baseUrl 可连）
     * @throws IOException          子进程/端口操作失败
     * @throws InterruptedException attach/轮询被中断
     */
    public static ArthasMcpBackend start(String logicalName) throws IOException, InterruptedException {
        return start(logicalName, null);
    }

    /**
     * 启动单后端夹具。
     *
     * @param logicalName 逻辑名
     * @param token       BEARER 认证 token；{@code null} 表示 NONE。当前实现仅 NONE
     *                    （BEARER 待 arthas MCP token 配置机制核实后补，见设计文档 §8）
     * @return 就绪的夹具
     */
    public static ArthasMcpBackend start(String logicalName, String token) throws IOException, InterruptedException {
        if (logicalName == null || logicalName.isBlank()) {
            throw new IllegalArgumentException("logicalName 不可为空");
        }
        int appPort = findFreePort();
        int mcpPort = findFreePort();

        Path basedir = Path.of(System.getProperty("basedir"));
        Path appLog = basedir.resolve("target/demo-app-" + logicalName + ".log");
        Process appProcess = startDemoBusinessApp(appPort, appLog);
        try {
            waitForPortOpen("127.0.0.1", appPort, APP_READY_TIMEOUT, "DemoBusinessApp");
            long pid = appProcess.pid();

            Path arthasBootJar = resolveArthasBootJar(basedir);
            Path attachLog = basedir.resolve("target/arthas-attach-" + logicalName + ".log");
            runArthasAttach(arthasBootJar, pid, mcpPort, attachLog, ATTACH_TIMEOUT);
            waitForPortOpen("127.0.0.1", mcpPort, MCP_PORT_READY_TIMEOUT, "arthas MCP");
        } catch (RuntimeException | InterruptedException | IOException e) {
            destroyProcess(appProcess);
            throw e;
        }
        return new ArthasMcpBackend(logicalName, mcpPort, appPort, appProcess);
    }

    /** MCP 端点根 URL（{@code http://127.0.0.1:<mcpPort>}，§9 实证）。 */
    public String baseUrl() {
        return baseUrl;
    }

    /** arthas MCP HTTP 端口（注入目标 JVM 内）。 */
    public int mcpPort() {
        return mcpPort;
    }

    /** 业务服务 HTTP 端口（{@code /api/order}、{@code /actuator/health}）。 */
    public int appPort() {
        return appPort;
    }

    /** 逻辑名。 */
    public String name() {
        return logicalName;
    }

    @Override
    public void close() {
        destroyProcess(appProcess); // MCP 服务随目标 JVM 生灭
    }

    // ---------------- internals ----------------

    private static Process startDemoBusinessApp(int port, Path logFile) throws IOException {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = Path.of(System.getProperty("basedir"), "target", "test-classes").toString();
        Files.createDirectories(logFile.getParent());
        ProcessBuilder pb = new ProcessBuilder(
                javaBin, "-cp", classpath,
                "-Ddemo.slowMs=0",
                DemoBusinessApp.class.getName(),
                String.valueOf(port));
        pb.redirectErrorStream(true);
        pb.redirectOutput(logFile.toFile());
        return pb.start();
    }

    /**
     * 经 {@code java -jar tools/arthas-boot.jar} attach arthas 到目标 JVM。
     *
     * <p>命令行对齐 reference {@code runAttach} 范式（PID 位置参数 + {@code --attach-only} +
     * {@code --target-ip}/{@code --telnet-port}/{@code --http-port}），去 bash/as.sh、加
     * {@code --use-version 4.3.0} 锁版本。attach 进程短命（注入 agent 后 exit 0）。
     */
    private static void runArthasAttach(Path arthasBootJar, long pid, int mcpPort, Path logFile,
                                        Duration timeout) throws IOException, InterruptedException {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Files.createDirectories(logFile.getParent());
        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-jar", arthasBootJar.toString(),
                String.valueOf(pid), // 目标 JVM PID（位置参数）
                "--attach-only",
                "--target-ip", "127.0.0.1",
                "--telnet-port", "0", // 禁用 telnet（reference 范式），仅 MCP HTTP
                "--http-port", String.valueOf(mcpPort),
                "--use-version", ARTHAS_VERSION); // 锁 4.3.0（设计文档 §5）
        pb.redirectErrorStream(true);
        pb.redirectOutput(logFile.toFile());
        Process attach = pb.start();
        if (!attach.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
            attach.destroyForcibly();
            throw new IllegalStateException(
                    "arthas attach 超时（" + timeout + "）: pid=" + pid + ", mcpPort=" + mcpPort
                            + "，详见日志 " + logFile);
        }
        if (attach.exitValue() != 0) {
            throw new IllegalStateException(
                    "arthas attach 失败（exit=" + attach.exitValue() + "）: pid=" + pid
                            + "，详见日志 " + logFile);
        }
    }

    /** 定位静态工具文件 {@code tools/arthas-boot.jar}（工程相对路径，memory arthas-no-dependency）。 */
    private static Path resolveArthasBootJar(Path basedir) {
        Path jar = basedir.resolve("tools/arthas-boot.jar").normalize();
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException("arthas-boot.jar 未找到: " + jar
                    + "（应作为静态工具文件置于工程 tools/，见 memory arthas-no-dependency）");
        }
        return jar;
    }

    private static void waitForPortOpen(String host, int port, Duration timeout, String label)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 500);
                return;
            } catch (IOException ignored) {
                Thread.sleep(200);
            }
        }
        throw new IllegalStateException("等待 " + label + " 端口监听超时: " + host + ":" + port + "（" + timeout + "）");
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static void destroyProcess(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
```


---

## com/arthas/gateway/testfixtures/DemoBusinessApp.java

**文件**：`src/test/java/com/arthas/gateway/testfixtures/DemoBusinessApp.java`

```java
package com.arthas.gateway.testfixtures;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 真实业务服务夹具（T008，设计文档 §4）——arthas attach 的目标 JVM。
 *
 * <p><b>技术形态：纯 Java + JDK {@link HttpServer}</b>（用户裁决 2026-06-20，替代设计文档原述「Spring Boot 进程」
 * ——对齐 reference {@code TargetJvmApp} 纯 Java 范式，子进程类路径只需 {@code target/test-classes}，
 * 无 fat jar / classpath 地狱；启动快、依赖零）。诊断价值齐全：{@link OrderService#hotMethod} 调用链
 * （calc/check）+ HTTP {@code /api/order} 触发 + 可注入慢响应 + 后台守护线程持续触发（arthas watch 抓事件）。
 *
 * <h3>暴露</h3>
 * <ul>
 *   <li>{@code GET/POST /api/order?orderId=&lt;n&gt;&slowMs=&lt;ms&gt;}：触发 {@code hotMethod}，返回 JSON 结果。
 *       {@code slowMs} 为<b>单次请求</b>慢响应注入，handler save/restore 不污染后台循环（AsyncTaskTimeout 用）。</li>
 *   <li>{@code GET /actuator/health}：返回 {@code {"status":"UP"}}，T009 轮询就绪用（reference 范式）。</li>
 * </ul>
 *
 * <h3>生命周期</h3>
 * <p>可实例化（{@link #start(int, long)}/{@link #stop()}）便于夹具自测；{@link #main(String[])} 供 T009 子进程启动
 * （{@code java -cp target/test-classes com.arthas.gateway.testfixtures.DemoBusinessApp <port>}，
 * 可选 {@code -Ddemo.slowMs=<ms>} 进程级慢响应），{@code main} 阻塞常驻、注册 shutdown hook。
 */
public final class DemoBusinessApp {

    /** 子进程默认端口（main 未传参时）。 */
    private static final int DEFAULT_PORT = 8081;

    private final OrderService orderService = new OrderService();
    private final ExecutorService httpPool = Executors.newFixedThreadPool(8);
    private final AtomicBoolean running = new AtomicBoolean(false);

    private HttpServer server;
    private Thread hotLoop;

    /**
     * 启动业务服务。
     *
     * @param port   监听端口
     * @param slowMs 进程级慢响应注入（&gt;0 时设到 OrderService；HTTP 单次 slowMs 另支持）
     * @throws IOException           端口绑定失败
     * @throws IllegalStateException 重复启动
     */
    public void start(int port, long slowMs) throws IOException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("DemoBusinessApp 已启动");
        }
        if (slowMs > 0L) {
            orderService.configureSlowResponse(slowMs);
        }
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/order", new OrderHandler(orderService));
        server.createContext("/actuator/health", new HealthHandler());
        server.setExecutor(httpPool);
        server.start();
        startHotLoop();
    }

    /** 实际监听端口（传入 0 由 OS 分配时，取实际端口）。 */
    public int port() {
        return server.getAddress().getPort();
    }

    /** 共享业务服务（测试断言 hotMethod 真实调用计数用）。 */
    public OrderService orderService() {
        return orderService;
    }

    /** 启动后台守护线程：每 ~50ms 触发一次 hotMethod（arthas watch/trace/stack/tt 抓事件依赖持续调用）。 */
    private void startHotLoop() {
        hotLoop = new Thread(() -> {
            int value = 0;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    orderService.hotMethod(value++ & 0xFF);
                    Thread.sleep(50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "demo-hot-loop");
        hotLoop.setDaemon(true);
        hotLoop.start();
    }

    /** 关停 HttpServer + 线程池 + 后台循环（幂等）。 */
    public void stop() {
        if (running.getAndSet(false)) {
            if (server != null) {
                server.stop(0);
            }
            httpPool.shutdownNow();
            if (hotLoop != null) {
                hotLoop.interrupt();
            }
        }
    }

    /**
     * 子进程入口（T009 经 {@code java -cp target/test-classes} 启动）。
     *
     * @param args {@code args[0]}=端口（缺省 {@link #DEFAULT_PORT}）
     */
    public static void main(String[] args) throws Exception {
        int port = (args.length > 0) ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        long slowMs = Long.parseLong(System.getProperty("demo.slowMs", "0"));
        DemoBusinessApp app = new DemoBusinessApp();
        app.start(port, slowMs);
        Runtime.getRuntime().addShutdownHook(new Thread(app::stop, "demo-shutdown"));
        System.out.println("DemoBusinessApp started on port " + port);
        Thread.currentThread().join(); // 常驻，等待 shutdown hook 或进程 destroy
    }

    // ---------------- HTTP handlers ----------------

    /** {@code /api/order} 处理：触发 hotMethod，返回 JSON 结果，支持单次 slowMs 注入。 */
    static final class OrderHandler implements HttpHandler {
        private final OrderService service;

        OrderHandler(OrderService service) {
            this.service = service;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            if (!"GET".equalsIgnoreCase(method) && !"POST".equalsIgnoreCase(method)) {
                respondJson(exchange, 405, "{\"error\":\"method_not_allowed\"}");
                return;
            }
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            int orderId = (int) parseLong(query.get("orderId"), System.nanoTime() & 0xFF);
            long reqSlowMs = parseLong(query.get("slowMs"), 0L);
            long savedSlowMs = service.currentSlowMs();
            try {
                if (reqSlowMs > 0L) {
                    service.configureSlowResponse(reqSlowMs);
                }
                OrderResult result = service.hotMethod(orderId);
                respondJson(exchange, 200, toJson(result));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                respondJson(exchange, 503, "{\"error\":\"interrupted\"}");
            } finally {
                service.configureSlowResponse(savedSlowMs); // 恢复，避免污染后台循环
            }
        }
    }

    /** {@code /actuator/health} 处理：固定返回 UP（T009 轮询就绪）。 */
    static final class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            respondJson(exchange, 200, "{\"status\":\"UP\"}");
        }
    }

    // ---------------- helpers ----------------

    static void respondJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    static Map<String, String> parseQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return Map.of();
        }
        Map<String, String> params = new HashMap<>();
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                params.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return params;
    }

    static long parseLong(String value, long fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static String toJson(OrderResult result) {
        return "{\"orderId\":" + result.orderId()
                + ",\"price\":" + result.price()
                + ",\"valid\":" + result.valid() + "}";
    }
}
```


---

## com/arthas/gateway/testfixtures/DemoBusinessAppHttpTest.java

**文件**：`src/test/java/com/arthas/gateway/testfixtures/DemoBusinessAppHttpTest.java`

```java
package com.arthas.gateway.testfixtures;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DemoBusinessApp HTTP 启动契约集成测试（T008）。
 *
 * <p>验证夹具自身的启动可靠性（T009 端到端 arthas attach 依赖此前提）：JDK {@code HttpServer} 暴露
 * {@code /actuator/health}（T009 轮询就绪用）与 {@code /api/order}（触发 hotMethod 真实执行），后台守护线程
 * 持续触发 hotMethod（arthas watch 抓事件依赖，不靠 HTTP 时序），per-request {@code slowMs} 注入且不泄漏。
 *
 * <p>启动<b>真实</b> DemoBusinessApp（真实业务服务）+ 真实 HTTP 调用 → 真实 hotMethod 执行。arthas 真实诊断
 * （watch 捕获）由 T009 夹具 attach + T018 契约测试承担，非本测试职责（本测试不启动 arthas）。
 */
class DemoBusinessAppHttpTest {

    private DemoBusinessApp app;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
    }

    private DemoBusinessApp startApp() throws IOException {
        app = new DemoBusinessApp();
        app.start(freePort(), 0L);
        return app;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static String base(DemoBusinessApp app) {
        return "http://127.0.0.1:" + app.port();
    }

    private HttpResponse<String> get(DemoBusinessApp app, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(base(app) + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthEndpointReportsUp() throws Exception {
        DemoBusinessApp app = startApp();
        HttpResponse<String> resp = get(app, "/actuator/health");
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("UP");
    }

    @Test
    void orderEndpointInvokesHotMethodAndReturnsResult() throws Exception {
        DemoBusinessApp app = startApp();
        long before = app.orderService().hotMethodInvocations();

        HttpResponse<String> resp = get(app, "/api/order?orderId=42");

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).as("回显 orderId").contains("\"orderId\":42");
        assertThat(app.orderService().hotMethodInvocations())
                .as("HTTP 调用真实触发了 hotMethod").isGreaterThan(before);
    }

    @Test
    void backgroundLoopKeepsHotMethodFiring() throws Exception {
        DemoBusinessApp app = startApp();
        long before = app.orderService().hotMethodInvocations();

        // 后台守护线程每 ~50ms 触发 hotMethod（arthas watch/trace 抓事件依赖此持续调用）
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (app.orderService().hotMethodInvocations() >= before + 5) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(app.orderService().hotMethodInvocations())
                .as("后台循环持续触发 hotMethod").isGreaterThanOrEqualTo(before + 5);
    }

    @Test
    void perRequestSlowResponseDoesNotLeak() throws Exception {
        DemoBusinessApp app = startApp();

        long slowStart = System.nanoTime();
        get(app, "/api/order?orderId=1&slowMs=300");
        long slowElapsedMs = (System.nanoTime() - slowStart) / 1_000_000L;
        assertThat(slowElapsedMs).as("slowMs=300 使该请求变慢").isGreaterThanOrEqualTo(280L);

        long fastStart = System.nanoTime();
        get(app, "/api/order?orderId=2");
        long fastElapsedMs = (System.nanoTime() - fastStart) / 1_000_000L;
        assertThat(fastElapsedMs).as("slowMs 不泄漏到后续请求（save/restore）").isLessThan(100L);
    }
}
```


---

## com/arthas/gateway/testfixtures/FakeBackendClient.java

**文件**：`src/test/java/com/arthas/gateway/testfixtures/FakeBackendClient.java`

```java
package com.arthas.gateway.testfixtures;

import com.arthas.gateway.backend.BackendClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 受控 {@link BackendClient} test double（002 整改 T002）。
 *
 * <p><b>用途</b>:为统一拦截层/熔断/initialize CAS/取消中断的<b>纯逻辑单测</b>触发<b>真实失败条件</b>——
 * 可配置 initialize 抛异常、callTool 抛指定异常(含 {@code McpError})/阻塞(支持中断)/返回固定标记结果。
 * <b>非 arthas 成功响应桩</b>:返回的标记结果({@code "fake-result"})仅为透传断言占位,不模拟 arthas 真实诊断;
 * arthas 真实响应保真度仍由既有 IT({@code ArthasMcpBackend} + {@code DemoBusinessApp})覆盖。
 * 与 {@code AsyncTaskExecutorTest} 的 {@code blockingWork}(时序占位、不产出成功结果)同一性质。
 */
public final class FakeBackendClient implements BackendClient {

    private final AtomicInteger initializeCount = new AtomicInteger(0);
    private volatile RuntimeException initializeThrow = null;
    private volatile RuntimeException callToolThrow = null;
    private volatile CountDownLatch callToolBlock = null;
    private volatile CallToolResult callToolResult =
            new CallToolResult(List.of(new TextContent("fake-result")), false, null, null);
    private volatile boolean initialized = false;

    /** initialize 被调用次数(测 CAS 原子性:并发 invoke 应恰好 1 次)。 */
    public int initializeCount() {
        return initializeCount.get();
    }

    public void setInitializeThrow(RuntimeException e) {
        this.initializeThrow = e;
    }

    public void setCallToolResult(CallToolResult result) {
        this.callToolResult = result;
    }

    public void setCallToolThrow(RuntimeException e) {
        this.callToolThrow = e;
    }

    /** 设置后 callTool 阻塞在该 latch(支持中断,模拟"后端在途被取消")。 */
    public void setCallToolBlock(CountDownLatch latch) {
        this.callToolBlock = latch;
    }

    @Override
    public void initialize() {
        initializeCount.incrementAndGet();
        if (initializeThrow != null) {
            throw initializeThrow;
        }
        initialized = true;
    }

    @Override
    public CallToolResult callTool(String name, Map<String, Object> arguments) {
        if (callToolBlock != null) {
            try {
                callToolBlock.await();
            } catch (InterruptedException ie) {
                // 恢复中断态(供 invoke 检测跳过 recordFailure),包装为运行时异常(接口不抛 checked)
                Thread.currentThread().interrupt();
                throw new RuntimeException("fake callTool interrupted", ie);
            }
        }
        if (callToolThrow != null) {
            throw callToolThrow;
        }
        return callToolResult;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }

    @Override
    public void close() {
        // no-op
    }
}
```


---

## com/arthas/gateway/testfixtures/FakeBackendClientTest.java

**文件**：`src/test/java/com/arthas/gateway/testfixtures/FakeBackendClientTest.java`

```java
package com.arthas.gateway.testfixtures;

import com.arthas.gateway.handler.McpErrorCodes;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T002 {@link FakeBackendClient} 自验证:确认 test double 可配置行为正确(它是测试基建,自身须可信)。
 */
class FakeBackendClientTest {

    @Test
    void initializeIsCountedAndDefaultsToSuccess() {
        FakeBackendClient fake = new FakeBackendClient();
        assertThat(fake.initializeCount()).isZero();
        assertThat(fake.isInitialized()).isFalse();

        fake.initialize();
        fake.initialize();

        assertThat(fake.initializeCount()).isEqualTo(2);
        assertThat(fake.isInitialized()).isTrue();
    }

    @Test
    void initializeCanThrowConfiguredException() {
        FakeBackendClient fake = new FakeBackendClient();
        fake.setInitializeThrow(new IllegalStateException("initialize boom"));

        assertThatThrownBy(fake::initialize).isInstanceOf(IllegalStateException.class);
        assertThat(fake.initializeCount()).isEqualTo(1); // 仍计数(供 CAS 测试观测)
    }

    @Test
    void callToolReturnsConfiguredResult() {
        FakeBackendClient fake = new FakeBackendClient();
        CallToolResult businessError = new CallToolResult(
                List.of(new TextContent("isError=true 业务错误")), true, null, null);
        fake.setCallToolResult(businessError);

        CallToolResult r = fake.callTool("watch", java.util.Map.of());

        assertThat(r).isSameAs(businessError);
        assertThat(r.isError()).isTrue();
    }

    @Test
    void callToolCanThrowConfiguredRuntimeException() {
        FakeBackendClient fake = new FakeBackendClient();
        fake.setCallToolThrow(new java.io.UncheckedIOException(new java.io.IOException("conn refused")));

        assertThatThrownBy(() -> fake.callTool("jvm", java.util.Map.of()))
                .isInstanceOf(java.io.UncheckedIOException.class);
    }

    @Test
    void callToolCanThrowMcpError() {
        FakeBackendClient fake = new FakeBackendClient();
        fake.setCallToolThrow(McpError.builder(McpErrorCodes.INVALID_PARAMS)
                .message("后端业务错误").build());

        assertThatThrownBy(() -> fake.callTool("jvm", java.util.Map.of()))
                .isInstanceOf(McpError.class);
    }

    @Test
    void callToolBlockPreservesInterruptStatusWhenInterrupted() throws Exception {
        FakeBackendClient fake = new FakeBackendClient();
        CountDownLatch block = new CountDownLatch(1);
        fake.setCallToolBlock(block);
        Thread t = Thread.currentThread();

        // 另一线程中断当前线程,callTool.await 应抛并恢复中断态
        Thread interrupter = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                // ignore
            }
            t.interrupt();
        });
        interrupter.start();

        assertThatThrownBy(() -> fake.callTool("watch", java.util.Map.of()))
                .isInstanceOf(RuntimeException.class);
        assertThat(Thread.interrupted()).as("中断态已恢复(供 invoke 检测跳过 recordFailure)").isTrue();
    }
}
```


---

## com/arthas/gateway/testfixtures/McpClientHarness.java

**文件**：`src/test/java/com/arthas/gateway/testfixtures/McpClientHarness.java`

```java
package com.arthas.gateway.testfixtures;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.Duration;
import java.util.Map;

/**
 * 官方 MCP SDK client 测试驱动座（宪法原则四「双侧契约」、CLAUDE.md「驱动分层」）。
 *
 * <p>合规 MCP 客户端：走标准 Streamable HTTP 协议（{@code HttpClientStreamableHttpTransport}），
 * 非裸 curl HTTP——承担<b>结果一致性 + 协议契约</b>的确定性断言。
 *
 * <p><b>两路复用</b>（T010）：同一夹具，仅 {@code baseUrl} 不同——
 * <ul>
 *   <li>连网关：{@code http://localhost:&lt;port&gt;/mcp}（断言服务端契约 S-* / 一致性）</li>
 *   <li>直连目标 arthas 后端：后端自身 {@code /mcp}（A/B 一致性比对的金标准一侧）</li>
 * </ul>
 *
 * <p>用法：{@code try (var h = new McpClientHarness(url)) { h.initialize(); ... h.listTools(); }}
 * （AutoCloseable，自动 {@code close()} 释放连接）。
 *
 * <p>工具<b>可用性</b>（逐工具冒烟，不做一致性比对）由真实 Claude Code 驱动，不走本夹具。
 */
public final class McpClientHarness implements AutoCloseable {

    /** 客户端单次请求默认超时（覆盖 initialize/listTools/callTool）。 */
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final McpSyncClient client;

    /**
     * 以默认 client 信息构造（{@code arthas-gateway-test-client / 0.1.0}）。
     *
     * @param baseUrl MCP 端点完整 URL（如 {@code http://localhost:8761/mcp}）
     */
    public McpClientHarness(String baseUrl) {
        this(baseUrl, "arthas-gateway-test-client", "0.1.0");
    }

    /**
     * 以自定义 client 信息构造。
     *
     * @param baseUrl        MCP 端点完整 URL
     * @param clientName     clientInfo.name
     * @param clientVersion  clientInfo.version
     */
    public McpClientHarness(String baseUrl, String clientName, String clientVersion) {
        McpClientTransport transport = HttpClientStreamableHttpTransport.builder(baseUrl).build();
        this.client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation(clientName, clientVersion))
                .requestTimeout(DEFAULT_REQUEST_TIMEOUT)
                .build();
    }

    /** 暴露底层同步客户端（需要调用 SDK 未封装方法时使用）。 */
    public McpSyncClient client() {
        return client;
    }

    /**
     * 执行 initialize 握手（含 {@code notifications/initialized}）。
     *
     * @return initialize 结果（协议版本 / serverInfo / capabilities）
     */
    public McpSchema.InitializeResult initialize() {
        return client.initialize();
    }

    /** 调 {@code tools/list}，返回工具快照（{@code nextCursor} 可能为 null）。 */
    public McpSchema.ListToolsResult listTools() {
        return client.listTools();
    }

    /**
     * 调 {@code tools/call}。
     *
     * @param name      工具名
     * @param arguments 入参（含/不含 target 取决于测试场景）
     */
    public McpSchema.CallToolResult callTool(String name, Map<String, Object> arguments) {
        return client.callTool(new McpSchema.CallToolRequest(name, arguments));
    }

    /** 发 {@code ping}，连通性探活（不依赖 initialize 协商内容）。 */
    public Object ping() {
        return client.ping();
    }

    @Override
    public void close() {
        client.close();
    }
}
```


---

## com/arthas/gateway/testfixtures/OrderResult.java

**文件**：`src/test/java/com/arthas/gateway/testfixtures/OrderResult.java`

```java
package com.arthas.gateway.testfixtures;

/**
 * 订单业务结果（T008 夹具）。{@link OrderService#hotMethod} 的返回值——arthas watch 的观察对象。
 *
 * @param orderId 订单 id（入参回显）
 * @param price   计算价格（{@code calc} 产出）
 * @param valid   是否合法（{@code check} 产出）
 */
public record OrderResult(int orderId, long price, boolean valid) {
}
```


---

## com/arthas/gateway/testfixtures/OrderService.java

**文件**：`src/test/java/com/arthas/gateway/testfixtures/OrderService.java`

```java
package com.arthas.gateway.testfixtures;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 订单业务服务（T008 夹具）——arthas 诊断的真实目标对象。
 *
 * <p>{@link #hotMethod(int)} 是持续被调用的热点方法：内部依次调 {@link #calc}/{@link #check} 形成调用链
 * （便于 trace/stack 展开），返回 {@link OrderResult}（便于 watch 观察返回值）。
 * classPattern={@code com.arthas.gateway.testfixtures.OrderService}、methodPattern={@code hotMethod}。
 *
 * <p>{@link #configureSlowResponse(long)} 注入 {@code Thread.sleep} 模拟慢响应（AsyncTaskTimeout 等测试用，
 * 设计文档 §4「方法支持注入 Thread.sleep」）。默认无 sleep。{@link #hotMethodInvocations()} 暴露调用计数
 * （测试断言「业务服务真实调用产生诊断数据」用）。
 *
 * <p>纯 POJO（无 Spring 依赖），{@link DemoBusinessApp}（JDK HttpServer）与后台守护线程共用同一实例。
 */
public class OrderService {

    private final AtomicLong invocations = new AtomicLong();
    private volatile long slowMs = 0L;

    /**
     * 热点业务方法。
     *
     * @param orderId 订单 id
     * @return 订单结果
     * @throws InterruptedException 当注入慢响应被中断时
     */
    public OrderResult hotMethod(int orderId) throws InterruptedException {
        if (slowMs > 0L) {
            Thread.sleep(slowMs);
        }
        long price = calc(orderId);
        boolean valid = check(orderId);
        invocations.incrementAndGet();
        return new OrderResult(orderId, price, valid);
    }

    /** 价格计算（hotMethod 调用链节点）。 */
    long calc(int orderId) {
        return (long) orderId * 31 + 7;
    }

    /** 合法性检查（hotMethod 调用链节点）。 */
    boolean check(int orderId) {
        return orderId >= 0;
    }

    /** 累计 hotMethod 调用次数（测试断言真实调用用）。 */
    public long hotMethodInvocations() {
        return invocations.get();
    }

    /**
     * 注入慢响应（毫秒）。设 0 清除。
     *
     * @param ms 睡眠毫秒数；&lt;=0 表示不睡
     */
    public void configureSlowResponse(long ms) {
        this.slowMs = ms;
    }

    /** 当前慢响应设置（毫秒），{@code DemoBusinessApp} handler save/restore 用（避免单次注入污染后台循环）。 */
    public long currentSlowMs() {
        return slowMs;
    }
}
```


---

## com/arthas/gateway/testfixtures/OrderServiceTest.java

**文件**：`src/test/java/com/arthas/gateway/testfixtures/OrderServiceTest.java`

```java
package com.arthas.gateway.testfixtures;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OrderService 单测（T008 夹具业务逻辑，纯 Java）。
 *
 * <p>验证 {@link DemoBusinessApp} 的被诊断目标业务逻辑正确性——这是 arthas watch/trace/stack/tt 的
 * 真实诊断对象（classPattern={@code com.arthas.gateway.testfixtures.OrderService}、methodPattern={@code hotMethod}）。
 * 夹具自身逻辑正确是所有依赖它的集成测试（T018~T020/T043 等）的前提，故独立单测保证。
 *
 * <p>纯逻辑单测（surefire，无 arthas）：测 hotMethod 计算/调用计数/慢响应注入。arthas 真实诊断（watch 抓真实调用）
 * 由 T009 端到端夹具 + T018 契约测试（真实 attach + 真实 watch）承担，非本单测职责。
 */
class OrderServiceTest {

    @Test
    void hotMethodComputesPriceAndValidity() throws InterruptedException {
        OrderService service = new OrderService();
        OrderResult result = service.hotMethod(5);

        assertThat(result.orderId()).isEqualTo(5);
        assertThat(result.price()).as("price = orderId*31 + 7").isEqualTo(5L * 31 + 7);
        assertThat(result.valid()).isTrue();
    }

    @Test
    void hotMethodMarksInvalidForNegativeOrderId() throws InterruptedException {
        OrderService service = new OrderService();
        assertThat(service.hotMethod(-1).valid()).isFalse();
        assertThat(service.hotMethod(0).valid()).as("0 视为合法").isTrue();
    }

    @Test
    void hotMethodCountsEachInvocation() throws InterruptedException {
        OrderService service = new OrderService();
        assertThat(service.hotMethodInvocations()).isZero();
        service.hotMethod(1);
        service.hotMethod(2);
        service.hotMethod(3);
        assertThat(service.hotMethodInvocations()).isEqualTo(3L);
    }

    @Test
    void slowResponseInjectionDelaysHotMethod() throws InterruptedException {
        OrderService service = new OrderService();
        service.configureSlowResponse(150);

        long startMs = System.nanoTime();
        service.hotMethod(1);
        long elapsedMs = (System.nanoTime() - startMs) / 1_000_000L;

        assertThat(elapsedMs).as("注入 150ms 慢响应后耗时 >= 140ms（容忍调度误差）").isGreaterThanOrEqualTo(140L);
    }

    @Test
    void defaultHasNoSlowResponse() throws InterruptedException {
        OrderService service = new OrderService();

        long startMs = System.nanoTime();
        service.hotMethod(1);
        long elapsedMs = (System.nanoTime() - startMs) / 1_000_000L;

        assertThat(elapsedMs).as("默认无 sleep，应快速返回（< 50ms）").isLessThan(50L);
    }

    @Test
    void slowResponseCanBeCleared() throws InterruptedException {
        OrderService service = new OrderService();
        service.configureSlowResponse(120);
        service.hotMethod(1); // 慢一次
        service.configureSlowResponse(0); // 清除

        long startMs = System.nanoTime();
        service.hotMethod(2);
        long elapsedMs = (System.nanoTime() - startMs) / 1_000_000L;

        assertThat(elapsedMs).as("清除后无 sleep").isLessThan(50L);
    }
}
```


---

## com/arthas/gateway/tool/StaticToolRegistryTest.java

**文件**：`src/test/java/com/arthas/gateway/tool/StaticToolRegistryTest.java`

```java
package com.arthas.gateway.tool;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * StaticToolRegistry 契约测试（T013）。
 * <p>
 * 驱动「加载 arthas-tools.json 31 工具 → 注入 target → 追加 4 网关自有工具 → 不可变 35 快照」，
 * 覆盖 server-contract.md 的 S-TL-2/3/4/5 断言点：
 * <ul>
 *   <li>S-TL-1：工具数 == 35（31 arthas + 4 网关自有）</li>
 *   <li>S-TL-2：每个 arthas 工具 properties 含 target 且 ∈ required；additionalProperties==false</li>
 *   <li>S-TL-3：除 target 外，inputSchema 逐字等于 arthas 原始 schema</li>
 *   <li>S-TL-4：taskSupport 符合预期（dashboard forbidden；watch/trace/stack/tt/monitor optional；其余 forbidden）</li>
 *   <li>routingMode 分布：25 SYNC_DIRECT + 1 STREAM_AGGREGATE(dashboard) + 5 ASYNC_TASK + 4 GATEWAY_LOCAL</li>
 * </ul>
 */
class StaticToolRegistryTest {

    private static StaticToolRegistry registry;
    private static Map<String, Map<String, Object>> rawArthasByName; // 原始 schema 基准（无 target）

    @BeforeAll
    static void loadRegistry() throws Exception {
        registry = StaticToolRegistry.fromClasspath("arthas-tools.json");
        // 独立加载原始 JSON 作 S-TL-3 比对基准（不经 StaticToolRegistry，证明「转换仅新增 target」）
        try (var in = StaticToolRegistryTest.class.getClassLoader().getResourceAsStream("arthas-tools.json")) {
            assertThat(in).as("arthas-tools.json 须存在于 classpath").isNotNull();
            Map<String, Object> root = new ObjectMapper().readValue(in, new TypeReference<Map<String, Object>>() {});
            List<Map<String, Object>> tools = (List<Map<String, Object>>) root.get("tools");
            rawArthasByName = new LinkedHashMap<>();
            for (Map<String, Object> t : tools) {
                rawArthasByName.put((String) t.get("name"), (Map<String, Object>) t.get("inputSchema"));
            }
        }
    }

    @Test
    void exposesExactly35Tools() {
        assertThat(registry.tools()).hasSize(35);
    }

    @Test
    void exposesExactly4GatewayOwnedToolsAllGatewayLocal() {
        List<ExposedTool> gateway = registry.tools().stream()
                .filter(t -> t.routingMode() == RoutingMode.GATEWAY_LOCAL).toList();
        assertThat(gateway).extracting(ExposedTool::name).containsExactlyInAnyOrder(
                "arthas-gateway.list-targets",
                "arthas-gateway.task-get",
                "arthas-gateway.task-list",
                "arthas-gateway.task-cancel");
        gateway.forEach(t -> assertThat(t.taskSupport())
                .as("网关自有工具不暴露 taskSupport").isNull());
    }

    @Test
    void all31ArthasToolsHaveTargetInjectedIntoPropertiesAndRequired() {
        List<ExposedTool> arthas = arthasTools();
        assertThat(arthas).hasSize(31);
        for (ExposedTool t : arthas) {
            Map<String, Object> props = propertiesOf(t);
            assertThat(props).as("%s 须含 target 参数", t.name()).containsKey("target");
            assertThat(props.get("target"))
                    .as("%s 的 target 须为 string 类型", t.name())
                    .isInstanceOfSatisfying(Map.class, m ->
                            assertThat(((Map<?, ?>) m).get("type")).isEqualTo("string"));
            assertThat(requiredOf(t)).as("%s 须把 target 列入 required", t.name()).contains("target");
            assertThat(additionalPropertiesOf(t))
                    .as("%s 须保持 additionalProperties=false", t.name()).isEqualTo(false);
        }
    }

    @Test
    void arthasInputSchemaMatchesSourceVerbatimExceptTarget() {
        // S-TL-3：剥离 target 后，exposed inputSchema 逐字等于 arthas 原始 schema
        for (ExposedTool t : arthasTools()) {
            Map<String, Object> original = rawArthasByName.get(t.name());
            assertThat(original).as("基准中须有 %s", t.name()).isNotNull();

            Map<String, Object> stripped = deepCopy(t.inputSchema());
            Map<String, Object> props = (Map<String, Object>) stripped.get("properties");
            props.remove("target");
            List<String> req = (List<String>) stripped.get("required");
            stripped.put("required", req.stream().filter(r -> !"target".equals(r)).collect(Collectors.toList()));

            assertThat(stripped).as("%s 剥离 target 后须逐字等于原始 schema", t.name()).isEqualTo(original);
        }
    }

    @Test
    void targetIsRequiredAndFirstInRequiredArray() {
        // server-contract §4 示例：required 首位为 target
        ExposedTool watch = registry.find("watch").orElseThrow();
        assertThat(requiredOf(watch)).first().isEqualTo("target");
    }

    @Test
    void taskSupportMatchesExpectations() {
        // S-TL-4：5 个 optional；dashboard 与其余 25 forbidden
        Set<String> optional = Set.of("monitor", "stack", "tt", "trace", "watch");
        for (ExposedTool t : arthasTools()) {
            TaskSupport expected = optional.contains(t.name()) ? TaskSupport.OPTIONAL : TaskSupport.FORBIDDEN;
            assertThat(t.taskSupport()).as("%s 的 taskSupport", t.name()).isEqualTo(expected);
        }
    }

    @Test
    void routingModeDistributionMatchesClassificationTable() {
        // 工具传输分类表：25 SYNC_DIRECT + 1 STREAM_AGGREGATE(dashboard) + 5 ASYNC_TASK + 4 GATEWAY_LOCAL
        Map<RoutingMode, Long> dist = registry.tools().stream()
                .collect(Collectors.groupingBy(ExposedTool::routingMode, Collectors.counting()));
        assertThat(dist).containsEntry(RoutingMode.SYNC_DIRECT, 25L)
                .containsEntry(RoutingMode.STREAM_AGGREGATE, 1L)
                .containsEntry(RoutingMode.ASYNC_TASK, 5L)
                .containsEntry(RoutingMode.GATEWAY_LOCAL, 4L);
    }

    @Test
    void dashboardIsStreamAggregateAndForbidden() {
        ExposedTool dashboard = registry.find("dashboard").orElseThrow();
        assertThat(dashboard.routingMode()).isEqualTo(RoutingMode.STREAM_AGGREGATE);
        assertThat(dashboard.taskSupport()).isEqualTo(TaskSupport.FORBIDDEN);
    }

    @Test
    void preservesSpecialArthasParamNamesWithoutNormalization() {
        // 三处不统一的 ClassLoader 命名须照实透传（MCP能力清单 §0.7）
        assertThat(propertiesOf(registry.find("getstatic").orElseThrow())).containsKey("className");
        assertThat(propertiesOf(registry.find("dump").orElseThrow())).containsKey("classLoaderHashcode");
        assertThat(propertiesOf(registry.find("sc").orElseThrow())).containsKey("classLoaderStr");
        // profiler 的 include/exclude 须为数组类型
        Map<String, Object> profilerProps = propertiesOf(registry.find("profiler").orElseThrow());
        assertThat(((Map<?, ?>) profilerProps.get("include")).get("type")).isEqualTo("array");
        assertThat(((Map<?, ?>) profilerProps.get("exclude")).get("type")).isEqualTo("array");
    }

    @Test
    void snapshotIsImmutable() {
        List<ExposedTool> tools = registry.tools();
        assertThatThrownBy(() -> tools.add(new ExposedTool("x", "d", Map.of(), null, RoutingMode.GATEWAY_LOCAL)))
                .isInstanceOf(UnsupportedOperationException.class);
        // 单个工具的 inputSchema 也不可被外部修改（防御深拷贝泄漏）
        ExposedTool watch = registry.find("watch").orElseThrow();
        assertThatThrownBy(() -> propertiesOf(watch).put("evil", Map.of()))
                .as("inputSchema 须不可变").isInstanceOfAny(UnsupportedOperationException.class);
    }

    @Test
    void findReturnsEmptyForUnknownTool() {
        assertThat(registry.find("does-not-exist")).isEmpty();
    }

    // ===== 辅助 =====

    private List<ExposedTool> arthasTools() {
        return registry.tools().stream()
                .filter(t -> t.routingMode() != RoutingMode.GATEWAY_LOCAL).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> propertiesOf(ExposedTool t) {
        return (Map<String, Object>) t.inputSchema().get("properties");
    }

    @SuppressWarnings("unchecked")
    private List<String> requiredOf(ExposedTool t) {
        return (List<String>) t.inputSchema().get("required");
    }

    private Object additionalPropertiesOf(ExposedTool t) {
        return t.inputSchema().get("additionalProperties");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> src) {
        Map<String, Object> out = new LinkedHashMap<>();
        src.forEach((k, v) -> {
            if (v instanceof Map<?, ?> m) out.put(k, new LinkedHashMap<>((Map<String, Object>) m));
            else if (v instanceof List<?> l) out.put(k, new ArrayList<>((List<Object>) l));
            else out.put(k, v);
        });
        return out;
    }
}
```

