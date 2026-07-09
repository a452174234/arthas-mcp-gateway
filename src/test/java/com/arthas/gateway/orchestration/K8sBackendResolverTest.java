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
