package com.arthas.gateway.orchestration;

import com.arthas.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * K8S Host 热重载契约 IT（006 波2，T014，INV-HOT-1）。
 *
 * <p>真实测试床：运行时改 {@code config/k8s-hosts.yaml}（加一个 kubeconfig host）→ {@code K8sHostsWatcher}
 * WatchService 检测（500ms 防抖）→ {@code K8sHostStore.applyDiff} 建新 HostEntry → store 含新 host。
 *
 * <p>用 kubeconfig 模式 host（指向测试床 kubeconfig），避免 SSH 时序；buildFromKubeconfig 建 client。
 * try-finally 恢复配置文件 + 等 watcher 回滚。
 *
 * <p>仅当 {@code TEST_K3S_HOST} 设定时运行。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "TEST_K3S_HOST", matches = ".+")
class K8sHostHotReloadIT {

    @Autowired
    K8sHostStore store;

    @Autowired
    GatewayProperties props;

    @Test
    void addKubeconfigHostHotReloadsWithoutRestart() throws Exception {
        Path file = Path.of(props.getK8sHostsFile());
        String original = Files.exists(file) ? Files.readString(file) : null;
        try {
            assertThat(store.get("hot-reload-it"))
                    .as("热重载前 store 不含 hot-reload-it").isNull();

            // 改 config/k8s-hosts.yaml 加 host（kubeconfig 测试床，buildFromKubeconfig 建 client）
            Files.writeString(file, """
                    version: 1
                    hosts:
                      - name: hot-reload-it
                        namespace: default
                        kubeconfig: test-env/k8s/kubeconfig/k3s-admin.yaml
                    """);
            Thread.sleep(2500); // 等 WatchService + 500ms 防抖 + buildFromKubeconfig

            assertThat(store.get("hot-reload-it"))
                    .as("热重载后 store 含新 host hot-reload-it（INV-HOT-1，不重启）")
                    .isNotNull();
        } finally {
            if (original != null) {
                Files.writeString(file, original);
            } else if (Files.exists(file)) {
                Files.delete(file);
            }
            Thread.sleep(1500); // 等 watcher 回滚
        }
    }
}
