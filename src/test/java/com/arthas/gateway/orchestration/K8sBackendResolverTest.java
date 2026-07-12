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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * K8sBackendResolver 单测（005 T013 + 006 波2 T019 改造，INV-K8SHOST-2/3/5 + INV-HOT-1）。
 *
 * <p>mock {@link K8sHostStore}（get 返 mock {@link HostEntry}，含 mock {@link ArthasProvisioner}），
 * 验证<b>决策逻辑</b>：K8S 模式 ensure + 缓存、静态旁路、未知 host、ensure failed、host 重建清缓存。
 */
class K8sBackendResolverTest {

    private static final Instant NOW = Instant.parse("2026-07-10T00:00:00Z");

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

    /** store 含 host（get 返 mock HostEntry）。 */
    private static K8sHostStore storeWith(String hostName, ArthasProvisioner p, GatewayProperties.K8sHost meta) {
        K8sHostStore store = mock(K8sHostStore.class);
        HostEntry entry = mock(HostEntry.class);
        when(entry.provisioner()).thenReturn(p);
        when(entry.k8sHost()).thenReturn(meta);
        when(store.get(hostName)).thenReturn(entry);
        return store;
    }

    private static K8sBackendResolver resolver(K8sHostStore store) {
        return new K8sBackendResolver(store, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void k8sModeResolvesMcpUrlViaEnsure() {
        ArthasProvisioner p = mock(ArthasProvisioner.class);
        when(p.ensure(any(), any(), any(), any())).thenReturn(ready("debian-demo-business", "http://1.2.3.4:30050"));
        K8sBackendResolver r = resolver(storeWith("debian", p, k8sHost("debian", "default")));
        assertThat(r.resolveMcpUrl(k8sConfig("debian", "demo-business")))
                .as("K8S 模式 resolve 出 ensure 的 mcpUrl").contains("http://1.2.3.4:30050");
    }

    @Test
    void k8sModeCachesByLogicalNameSecondResolveSkipsEnsure() {
        ArthasProvisioner p = mock(ArthasProvisioner.class);
        when(p.ensure(any(), any(), any(), any())).thenReturn(ready("debian-demo-business", "http://1.2.3.4:30050"));
        K8sBackendResolver r = resolver(storeWith("debian", p, k8sHost("debian", "default")));
        r.resolveMcpUrl(k8sConfig("debian", "demo-business"));
        r.resolveMcpUrl(k8sConfig("debian", "demo-business"));
        verify(p, times(1)).ensure(any(), any(), any(), any());
    }

    @Test
    void staticModeBypassesResolve() {
        ArthasProvisioner p = mock(ArthasProvisioner.class);
        K8sBackendResolver r = resolver(storeWith("debian", p, k8sHost("debian", "default")));
        assertThat(r.resolveMcpUrl(staticConfig())).as("静态模式旁路返 empty").isEmpty();
        verifyNoInteractions(p);
    }

    @Test
    void unknownHostThrowsUnknownK8sHost() {
        K8sHostStore store = mock(K8sHostStore.class);
        when(store.get("ghost")).thenReturn(null);
        K8sBackendResolver r = resolver(store);
        assertThatThrownBy(() -> r.resolveMcpUrl(k8sConfig("ghost", "demo-business")))
                .isInstanceOf(K8sBackendResolver.K8sResolveException.class)
                .hasMessageContaining("unknown_k8s_host");
    }

    @Test
    void ensureFailedThrowsEnsureFailed() {
        ArthasProvisioner p = mock(ArthasProvisioner.class);
        OrchestrationRecord failed = OrchestrationRecord.ensuring("debian-demo-business", "debian", "demo-business", "default", NOW)
                .failed(new OrchestrationRecord.Error("locate_jvm", "no_jvm", "无 JVM"), NOW);
        when(p.ensure(any(), any(), any(), any())).thenReturn(failed);
        K8sBackendResolver r = resolver(storeWith("debian", p, k8sHost("debian", "default")));
        assertThatThrownBy(() -> r.resolveMcpUrl(k8sConfig("debian", "demo-business")))
                .isInstanceOf(K8sBackendResolver.K8sResolveException.class)
                .hasMessageContaining("ensure_failed");
    }

    /** host 重建清缓存（invalidateHost）→ 下次路由重 ensure（006 波2 INV-HOT-1）。 */
    @Test
    void invalidateHostClearsCacheForcingReEnsure() {
        ArthasProvisioner p = mock(ArthasProvisioner.class);
        when(p.ensure(any(), any(), any(), any())).thenReturn(ready("debian-demo-business", "http://1.2.3.4:30050"));
        K8sBackendResolver r = resolver(storeWith("debian", p, k8sHost("debian", "default")));
        r.resolveMcpUrl(k8sConfig("debian", "demo-business"));
        r.invalidateHost("debian"); // host 重建
        r.resolveMcpUrl(k8sConfig("debian", "demo-business")); // 缓存清了 → 重 ensure
        verify(p, times(2)).ensure(any(), any(), any(), any());
    }
}
