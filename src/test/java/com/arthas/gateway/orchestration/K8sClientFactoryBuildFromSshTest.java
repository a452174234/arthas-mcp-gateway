package com.arthas.gateway.orchestration;

import io.fabric8.kubernetes.client.Config;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * K8sClientFactory.buildFromSsh 单测（006 波1，T008，TDD）。
 *
 * <p>验证 SSH 取到 kubeconfig 文本后的 fabric8 Config 构造逻辑：① serverOverride 替换 masterUrl；
 * ② 无 override 保留 kubeconfig 原 server；③ insecureSkipTlsVerify=true 设 trustCerts + disableHostnameVerification。
 *
 * <p>用 {@link K8sClientFactory#buildConfig} 注入 kubeconfig 文本断言 Config 字段（隔离 SSH 与 client build：
 * SSH 真实性由 {@link SshKubeconfigFetcherTest} 的 MINA SSHD 覆盖，client build 证书校验由契约 IT 真实证书覆盖；
 * 非桩冒充，符合 CLAUDE.md 真实性约束）。契约见 INV-SSH-4。
 */
class K8sClientFactoryBuildFromSshTest {

    /** 合法结构的 kubeconfig（base64 占位证书数据，fromKubeconfig 只解析结构不 decode 证书）。 */
    private static final String KUBECONFIG = """
            apiVersion: v1
            kind: Config
            clusters:
            - cluster:
                certificate-authority-data: SGVsbG8=
                server: https://127.0.0.1:6443
              name: default
            contexts:
            - context:
                cluster: default
                user: default
              name: default
            current-context: default
            users:
            - name: default
              user:
                client-certificate-data: SGVsbG8=
                client-key-data: SGVsbG8=
            """;

    @Test
    void serverOverrideReplacesMasterUrl() {
        SshBootstrap ssh = new SshBootstrap("h", 22, "root", "pwd", null, null, "/p",
                "https://10.0.1.5:6443", false);
        Config config = K8sClientFactory.buildConfig(ssh, KUBECONFIG);
        assertThat(config.getMasterUrl()).contains("https://10.0.1.5:6443");
    }

    @Test
    void noOverrideKeepsKubeconfigServer() {
        SshBootstrap ssh = new SshBootstrap("h", 22, "root", "pwd", null, null, "/p", null, false);
        Config config = K8sClientFactory.buildConfig(ssh, KUBECONFIG);
        assertThat(config.getMasterUrl()).contains("127.0.0.1:6443");
    }

    @Test
    void insecureSkipTlsVerifySetsTrustCerts() {
        SshBootstrap ssh = new SshBootstrap("h", 22, "root", "pwd", null, null, "/p", null, true);
        Config config = K8sClientFactory.buildConfig(ssh, KUBECONFIG);
        assertThat(config.isTrustCerts()).isTrue();
        assertThat(config.isDisableHostnameVerification()).isTrue();
    }
}
