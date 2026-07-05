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
