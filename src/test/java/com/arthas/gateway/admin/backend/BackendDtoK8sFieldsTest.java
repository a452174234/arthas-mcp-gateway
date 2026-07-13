package com.arthas.gateway.admin.backend;

import com.arthas.gateway.admin.backend.dto.BackendDto;
import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.DynamicBackendStore;
import com.arthas.gateway.backend.Protocol;
import com.arthas.gateway.backend.RegistryHolder;
import com.arthas.gateway.backend.Source;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * BackendDto K8S 来源字段单测（006 波4，T029，TDD）。
 *
 * <p>验证 {@link BackendAdminService#toDto} 投影：K8S 模式 backend 的 DTO 带 k8sHost/pod/sourceDetail
 *（INV-DISP-3）；静态模式 K8S 字段 null + sourceDetail=static。仍无 token/password（INV-DISP-4）。
 * namespace/ensureStatus 后置填充（需 K8sHostStore/OrchestrationRecordStore，本测聚焦 cfg 投影）。
 */
class BackendDtoK8sFieldsTest {

    private final BackendAdminService service = new BackendAdminService(
            mock(RegistryHolder.class), mock(DynamicBackendStore.class),
            mock(BackendsYamlWriter.class), mock(com.arthas.gateway.backend.BackendConfigLoader.class),
            Path.of("nonexistent-backends.yaml"));

    private static BackendConfig.Auth auth() {
        return new BackendConfig.Auth(AuthMode.NONE, null, null, null);
    }

    private static BackendConfig k8sCfg(String host, String pod) {
        return new BackendConfig("k", null, Protocol.STREAMABLE, auth(),
                5000, 30000, 5, host, pod, Source.STATIC);
    }

    private static BackendConfig staticCfg() {
        return new BackendConfig("s", "http://127.0.0.1:8563", Protocol.STREAMABLE, auth(),
                5000, 30000, 5);
    }

    @Test
    void k8sModeBackendCarriesK8sSourceFields() {
        BackendDto dto = service.toDto(k8sCfg("debian", "demo-business"), "ACTIVE", true, "CLOSED");
        assertThat(dto.k8sHost()).isEqualTo("debian");
        assertThat(dto.pod()).isEqualTo("demo-business");
        assertThat(dto.sourceDetail()).isEqualTo("k8s:debian");
    }

    @Test
    void staticBackendHasNullK8sFields() {
        BackendDto dto = service.toDto(staticCfg(), "ACTIVE", true, "CLOSED");
        assertThat(dto.k8sHost()).isNull();
        assertThat(dto.pod()).isNull();
        assertThat(dto.sourceDetail()).isEqualTo("static");
    }

    @Test
    void dtoDoesNotLeakCredentials() {
        BackendDto dto = service.toDto(staticCfg(), "ACTIVE", true, "CLOSED");
        // BackendDto 无 token/password 字段（INV-DISP-4，INV-SECRET-1 不破）
        assertThat(dto.toString()).doesNotContain("token", "password");
    }
}
