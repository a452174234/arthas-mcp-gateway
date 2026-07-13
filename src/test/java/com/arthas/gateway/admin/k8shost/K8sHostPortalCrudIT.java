package com.arthas.gateway.admin.k8shost;

import com.arthas.gateway.orchestration.K8sHostStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * portal K8S Host CRUD 契约 IT（006 波3，T023，INV-PORTAL-K8S-1）。
 *
 * <p>真实测试床：{@link K8sHostAdminService#create} 写 {@code config/k8s-hosts.yaml} → {@code K8sHostsWatcher}
 * 热重载 → {@link K8sHostStore} 含新 host + {@link K8sHostAdminService#list} 可见。验证 portal 是热重载触发源之一。
 *
 * <p>用 kubeconfig 模式 host（测试床 kubeconfig，buildFromKubeconfig 建 client）。try-finally delete + 等 watcher 回滚。
 * 仅当 {@code TEST_K3S_HOST} 设定时运行。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "TEST_K3S_HOST", matches = ".+")
class K8sHostPortalCrudIT {

    @Autowired
    K8sHostAdminService service;

    @Autowired
    K8sHostStore store;

    @Test
    void portalCreateTriggersHotReloadAndListShows() throws Exception {
        service.create(new CreateK8sHostRequest("portal-it", "default",
                "test-env/k8s/kubeconfig/k3s-admin.yaml", null));
        Thread.sleep(2500); // 等 K8sHostsWatcher 500ms 防抖 + buildFromKubeconfig
        try {
            assertThat(store.get("portal-it"))
                    .as("portal create 热重载后 store 含 portal-it（INV-PORTAL-K8S-1）")
                    .isNotNull();
            assertThat(service.list().stream().map(K8sHostDto::name))
                    .as("portal list 含 portal-it")
                    .contains("portal-it");
        } finally {
            service.delete("portal-it");
            Thread.sleep(1500); // 等 watcher 回滚
        }
    }
}
