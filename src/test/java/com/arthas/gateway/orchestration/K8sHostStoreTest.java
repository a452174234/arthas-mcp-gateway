package com.arthas.gateway.orchestration;

import com.arthas.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * K8sHostStore 单测（006 波2，T012，TDD 红）。
 *
 * <p>验证 host 生命周期 applyDiff diff 逻辑：① 新增 host → factory.create + 存；② 删除 → entry.close + 移除 +
 * 清缓存；③ 连接配置变 → close 旧 + 重建 + 清缓存；④ 连接配置不变 → 不重建不清缓存。
 *
 * <p>用 mock {@link HostEntryFactory}（返 mock HostEntry）隔离 SSH/client build（SSH 真实性由
 * {@link SshBootstrapContractIT} 覆盖；本测聚焦 applyDiff diff 逻辑，非冒充，符合 CLAUDE.md 真实性约束）。
 */
@ExtendWith(MockitoExtension.class)
class K8sHostStoreTest {

    @Mock
    private HostEntryFactory factory;
    @Mock
    private Consumer<String> cacheInvalidator;

    private GatewayProperties.K8sHost host(String name, String kubeconfig) {
        GatewayProperties.K8sHost h = new GatewayProperties.K8sHost();
        h.setName(name);
        h.setNamespace("default");
        h.setKubeconfig(kubeconfig);
        return h;
    }

    @Test
    void addNewHostCreatesAndStores() {
        GatewayProperties.K8sHost a = host("a", "/tmp/kc-a");
        HostEntry entryA = mock(HostEntry.class);
        when(factory.create(a)).thenReturn(entryA);

        K8sHostStore store = new K8sHostStore(factory, cacheInvalidator);
        store.applyDiff(List.of(a));

        assertThat(store.get("a")).isSameAs(entryA);
    }

    @Test
    void removedHostIsClosedAndCacheInvalidated() {
        GatewayProperties.K8sHost a = host("a", "/tmp/kc-a");
        HostEntry entryA = mock(HostEntry.class);
        when(factory.create(a)).thenReturn(entryA);
        K8sHostStore store = new K8sHostStore(factory, cacheInvalidator);
        store.applyDiff(List.of(a));

        store.applyDiff(List.of()); // 删除 a

        assertThat(store.get("a")).isNull();
        verify(entryA).close();
        verify(cacheInvalidator).accept("a");
    }

    @Test
    void connectionConfigChangeTriggersRebuild() {
        GatewayProperties.K8sHost a = host("a", "/tmp/kc-a");
        GatewayProperties.K8sHost aChanged = host("a", "/tmp/kc-a-new"); // kubeconfig 路径变（连接变）
        HostEntry old = mock(HostEntry.class);
        HostEntry rebuilt = mock(HostEntry.class);
        when(factory.create(a)).thenReturn(old);
        when(factory.create(aChanged)).thenReturn(rebuilt);
        K8sHostStore store = new K8sHostStore(factory, cacheInvalidator);
        store.applyDiff(List.of(a));

        store.applyDiff(List.of(aChanged)); // 重建

        assertThat(store.get("a")).isSameAs(rebuilt);
        verify(old).close();
        verify(cacheInvalidator).accept("a");
    }

    @Test
    void unchangedHostIsNotRebuilt() {
        GatewayProperties.K8sHost a = host("a", "/tmp/kc-a");
        HostEntry entryA = mock(HostEntry.class);
        when(factory.create(a)).thenReturn(entryA);
        K8sHostStore store = new K8sHostStore(factory, cacheInvalidator);
        store.applyDiff(List.of(a));

        store.applyDiff(List.of(a)); // 同连接配置

        verify(factory, times(1)).create(a); // 不重建
        verify(cacheInvalidator, never()).accept("a"); // 不清缓存
    }

    @Test
    void namespaceOnlyChangeDoesNotRebuildConnection() {
        // namespace 变不影响 client（client 不绑 namespace）→ 不重建 client，仅更新 namespace
        GatewayProperties.K8sHost a = host("a", "/tmp/kc-a");
        GatewayProperties.K8sHost aNsChanged = host("a", "/tmp/kc-a");
        aNsChanged.setNamespace("other");
        HostEntry entryA = mock(HostEntry.class);
        when(factory.create(a)).thenReturn(entryA);
        K8sHostStore store = new K8sHostStore(factory, cacheInvalidator);
        store.applyDiff(List.of(a));

        store.applyDiff(List.of(aNsChanged));

        verify(factory, times(1)).create(a); // 不重建（连接没变）
        assertThat(store.get("a")).isSameAs(entryA);
    }
}
