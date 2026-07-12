package com.arthas.gateway.orchestration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SshBootstrap record 契约测试（006 波1，T004，TDD 红）。
 *
 * <p>SshBootstrap 是 {@code arthas-gateway.k8s-hosts[].ssh} 的运行时映射 record（剥离 Spring 配置类），
 * 传给 {@link SshKubeconfigFetcher}。校验：host/user/kubeconfigRemotePath 非空；password 与 privateKey 至少一项。
 * 实体见 [data-model.md §3](../../../../specs/006-k8s-host-remote-access/data-model.md)。
 */
class SshBootstrapTest {

    @Test
    void validPasswordMode() {
        SshBootstrap ssh = new SshBootstrap("10.0.1.5", 22, "root", "pwd", null, null,
                "/etc/kubernetes/admin.conf", null, false);
        assertThat(ssh.host()).isEqualTo("10.0.1.5");
        assertThat(ssh.port()).isEqualTo(22);
        assertThat(ssh.user()).isEqualTo("root");
        assertThat(ssh.password()).isEqualTo("pwd");
        assertThat(ssh.privateKey()).isNull();
        assertThat(ssh.passphrase()).isNull();
        assertThat(ssh.kubeconfigRemotePath()).isEqualTo("/etc/kubernetes/admin.conf");
        assertThat(ssh.serverOverride()).isNull();
        assertThat(ssh.insecureSkipTlsVerify()).isFalse();
    }

    @Test
    void validPrivateKeyMode() {
        SshBootstrap ssh = new SshBootstrap("10.0.1.5", 22, "root", null, "key", "pass",
                "/etc/rancher/k3s/k3s.yaml", "https://10.0.1.5:6443", true);
        assertThat(ssh.privateKey()).isEqualTo("key");
        assertThat(ssh.passphrase()).isEqualTo("pass");
        assertThat(ssh.serverOverride()).isEqualTo("https://10.0.1.5:6443");
        assertThat(ssh.insecureSkipTlsVerify()).isTrue();
    }

    @Test
    void blankHostRejected() {
        assertThatThrownBy(() -> new SshBootstrap("  ", 22, "root", "pwd", null, null, "/p", null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("host");
    }

    @Test
    void blankUserRejected() {
        assertThatThrownBy(() -> new SshBootstrap("h", 22, null, "pwd", null, null, "/p", null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("user");
    }

    @Test
    void blankKubeconfigRemotePathRejected() {
        assertThatThrownBy(() -> new SshBootstrap("h", 22, "root", "pwd", null, null, "", null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kubeconfig");
    }

    @Test
    void neitherPasswordNorPrivateKeyRejected() {
        assertThatThrownBy(() -> new SshBootstrap("h", 22, "root", null, null, null, "/p", null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("password")
                .hasMessageContaining("privateKey");
    }
}
