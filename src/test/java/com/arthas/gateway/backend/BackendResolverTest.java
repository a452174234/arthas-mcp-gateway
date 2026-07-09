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
