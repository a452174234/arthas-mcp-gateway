package com.arthas.gateway.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * K8sHostsConfig 单测（006 波2，T013，TDD）。
 *
 * <p>验证 config/k8s-hosts.yaml 解析（kubeconfig + ssh 模式，kebab-case key）+ 文件不存在回退 application.yml 内联。
 * 契约见 INV-HOT-5。
 */
class K8sHostsConfigTest {

    private final K8sHostsConfig config = new K8sHostsConfig();
    private final GatewayProperties props = new GatewayProperties();

    @Test
    void parsesKubeconfigAndSshHosts(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("k8s-hosts.yaml");
        Files.writeString(file, """
                version: 1
                hosts:
                  - name: a
                    namespace: default
                    kubeconfig: /tmp/kc-a
                  - name: b
                    namespace: prod
                    ssh:
                      host: 10.0.0.1
                      port: 2222
                      user: root
                      password: pwd
                      kubeconfig-remote-path: /etc/kubernetes/admin.conf
                      server-override: https://10.0.0.1:6443
                      insecure-skip-tls-verify: true
                """);
        K8sHostsConfig.LoadedHosts loaded = config.loadHosts(file, props);

        assertThat(loaded.fromFallback()).isFalse();
        assertThat(loaded.hosts()).hasSize(2);
        assertThat(loaded.hosts().get(0).getName()).isEqualTo("a");
        assertThat(loaded.hosts().get(0).getKubeconfig()).isEqualTo("/tmp/kc-a");
        GatewayProperties.K8sHost.Ssh ssh = loaded.hosts().get(1).getSsh();
        assertThat(ssh.getHost()).isEqualTo("10.0.0.1");
        assertThat(ssh.getPort()).isEqualTo(2222);
        assertThat(ssh.getKubeconfigRemotePath()).isEqualTo("/etc/kubernetes/admin.conf");
        assertThat(ssh.getServerOverride()).isEqualTo("https://10.0.0.1:6443");
        assertThat(ssh.isInsecureSkipTlsVerify()).isTrue();
    }

    @Test
    void missingFileFallsBackToInlineHosts(@TempDir Path dir) throws IOException {
        GatewayProperties.K8sHost inline = new GatewayProperties.K8sHost();
        inline.setName("inline");
        inline.setKubeconfig("/tmp/inline-kc");
        props.getK8sHosts().add(inline);

        K8sHostsConfig.LoadedHosts loaded = config.loadHosts(dir.resolve("nonexistent.yaml"), props);

        assertThat(loaded.fromFallback()).isTrue();
        assertThat(loaded.hosts()).isEqualTo(props.getK8sHosts());
    }

    @Test
    void emptyHostsListIsAllowed(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("k8s-hosts.yaml");
        Files.writeString(file, "version: 1\nhosts: []\n");
        K8sHostsConfig.LoadedHosts loaded = config.loadHosts(file, props);
        assertThat(loaded.fromFallback()).isFalse();
        assertThat(loaded.hosts()).isEmpty();
    }
}
