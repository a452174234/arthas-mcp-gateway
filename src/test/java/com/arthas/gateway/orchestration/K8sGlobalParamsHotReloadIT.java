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
 * 全局 K8S 参数热生效契约 IT（006 波2，T015，INV-HOT-3）。
 *
 * <p>真实测试床：运行时改 {@code config/k8s-hosts.yaml} 的 {@code k8s-params} 段（arthas-password/mcp-port）→
 * {@code K8sHostsWatcher} 热重载 → {@code K8sHostStore.updateParams} 刷新 → {@code store.currentParams()} 反映新值。
 * 验证 T020 全局参数热刷新（下次 ensure 用新值，已 ensure 的 pod 不变）。
 *
 * <p>本 IT 聚焦 params 快照刷新（store.currentParams 断言），不触发 ensure（ensure 行为由 T028 覆盖）。
 * 仅当 {@code TEST_K3S_HOST} 设定时运行。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "TEST_K3S_HOST", matches = ".+")
class K8sGlobalParamsHotReloadIT {

    @Autowired
    K8sHostStore store;

    @Autowired
    GatewayProperties props;

    @Test
    void globalParamsHotReloadFromYamlK8sParamsSection() throws Exception {
        Path file = Path.of(props.getK8sHostsFile());
        String original = Files.exists(file) ? Files.readString(file) : null;
        try {
            Files.writeString(file, """
                    version: 1
                    hosts: []
                    k8s-params:
                      arthas-password: hot-reloaded-pwd
                      mcp-port: 9999
                      target-ip: 0.0.0.0
                    """);
            Thread.sleep(2500); // 等 K8sHostsWatcher 500ms 防抖 + loadParams

            assertThat(store.currentParams())
                    .as("热重载后 store 持最新 params 快照").isNotNull();
            assertThat(store.currentParams().arthasPassword())
                    .as("arthas-password 热刷新（INV-HOT-3）").isEqualTo("hot-reloaded-pwd");
            assertThat(store.currentParams().mcpPort())
                    .as("mcp-port 热刷新").isEqualTo(9999);
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
