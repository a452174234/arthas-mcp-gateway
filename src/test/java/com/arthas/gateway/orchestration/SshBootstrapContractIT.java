package com.arthas.gateway.orchestration;

import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SSH 引导契约 IT（006 波1，T009）：真实测试床 k3s。
 *
 * <p>真实 SSH（root@{@code TEST_K3S_HOST} ed25519 key）→ 取 {@code /etc/rancher/k3s/k3s.yaml} → fabric8 client
 * 连 K8S → list-pods 含 {@code demo-business}。零桩（宪法原则七，CLAUDE.md 真实性硬约束——真实 SSH 协议 + 真实 K8S API）。
 *
 * <p>仅当环境变量 {@code TEST_K3S_HOST} 设定时运行（CI 无测试床时跳过；本地/开发期设为 {@code 192.168.31.92}）。
 * SSH 私钥默认 {@code ~/.ssh/id_ed25519}，可用 {@code -Dtest.sshKey=<path>} 覆盖。
 * 契约见 [INV-SSH-1](../../../../specs/006-k8s-host-remote-access/contracts/host-remote-access-invariants.md)。
 */
class SshBootstrapContractIT {

    @Test
    @EnabledIfEnvironmentVariable(named = "TEST_K3S_HOST", matches = ".+")
    void sshBootstrapsRealK3sCluster() {
        String host = System.getenv("TEST_K3S_HOST");
        String keyPath = System.getProperty("test.sshKey",
                System.getProperty("user.home") + "/.ssh/id_ed25519");
        SshBootstrap ssh = new SshBootstrap(host, 22, "root", null, keyPath, null,
                "/etc/rancher/k3s/k3s.yaml", "https://" + host + ":6443", false);
        try (KubernetesClient client = K8sClientFactory.buildFromSsh(ssh)) {
            PodList pods = client.pods().inNamespace("default").list();
            assertThat(pods.getItems()).extracting(p -> p.getMetadata().getName())
                    .contains("demo-business");
        }
    }
}
