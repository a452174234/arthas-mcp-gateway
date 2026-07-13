package com.arthas.gateway.admin.k8shost;

import com.arthas.gateway.config.GatewayProperties;
import com.arthas.gateway.config.K8sHostsConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * K8sHostAdminService 单测（006 波3，T022，TDD）。
 *
 * <p>真实写/读 {@code config/k8s-hosts.yaml}（@TempDir，非桩），验证 CRUD + 互斥校验 + 重名拒绝 +
 * DTO 脱敏（不含凭证，INV-PORTAL-K8S-2）。MVP 明文落盘（cipher 未配 → writer 明文，加密 round-trip 接入留 T023）。
 */
class K8sHostAdminServiceTest {

    @TempDir
    Path dir;
    Path file;
    GatewayProperties props = new GatewayProperties();
    K8sHostsConfig loader = new K8sHostsConfig();
    K8sHostsYamlWriter writer = new K8sHostsYamlWriter();
    K8sHostSecretCipher cipher = new K8sHostSecretCipher(null); // 未配 → 明文落盘
    K8sHostAdminService svc;

    @BeforeEach
    void setup() {
        file = dir.resolve("k8s-hosts.yaml");
        svc = new K8sHostAdminService(file, props, loader, writer, cipher);
    }

    private CreateK8sHostRequest sshReq(String name, String password) {
        return new CreateK8sHostRequest(name, "default", null,
                new CreateK8sHostRequest.Ssh("10.0.0.1", 22, "root", password, null, null,
                        "/etc/kubernetes/admin.conf", null, null));
    }

    @Test
    void listEmptyWhenNoHosts() throws IOException {
        assertThat(svc.list()).isEmpty();
    }

    @Test
    void createPersistsAndLists() throws IOException {
        K8sHostDto dto = svc.create(sshReq("a", "secret-pwd"));
        assertThat(dto.name()).isEqualTo("a");
        assertThat(dto.mode()).isEqualTo("ssh");
        assertThat(dto.sshHost()).isEqualTo("10.0.0.1");
        assertThat(svc.list()).hasSize(1);
    }

    @Test
    void createRejectsDuplicateName() throws IOException {
        svc.create(sshReq("a", "pwd"));
        assertThatThrownBy(() -> svc.create(sshReq("a", "pwd")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    void createRejectsMutualExclusionViolation() {
        // kubeconfig + ssh 都有
        assertThatThrownBy(() -> svc.create(new CreateK8sHostRequest("a", "default", "/kc",
                new CreateK8sHostRequest.Ssh("h", 22, "u", "p", null, null, "/k", null, null))))
                .isInstanceOf(IllegalArgumentException.class);
        // 都无
        assertThatThrownBy(() -> svc.create(new CreateK8sHostRequest("a", "default", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deleteRemovesHost() throws IOException {
        svc.create(sshReq("a", "pwd"));
        svc.delete("a");
        assertThat(svc.list()).isEmpty();
    }

    @Test
    void deleteUnknownThrows() {
        assertThatThrownBy(() -> svc.delete("ghost"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void dtoMasksCredentialsNoPasswordLeak() throws IOException {
        svc.create(sshReq("a", "super-secret-password"));
        K8sHostDto dto = svc.list().get(0);
        // K8sHostDto 无 password 字段 → toString 不含凭证
        assertThat(dto.toString()).doesNotContain("super-secret-password");
    }
}
