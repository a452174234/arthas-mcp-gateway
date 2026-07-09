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
