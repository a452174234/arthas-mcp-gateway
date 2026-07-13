package com.arthas.gateway.admin.k8shost;

import com.arthas.gateway.config.GatewayProperties;

/**
 * portal 新建 K8S host 请求（006 波3，T026，含明文 ssh 凭证，仅写入用、不回显）。
 *
 * <p>{@code kubeconfig} 与 {@code ssh} 二选一（互斥，Service 校验，对应 INV-SSH-3）。
 */
public record CreateK8sHostRequest(String name, String namespace, String kubeconfig, Ssh ssh) {

    /** SSH 引导子参数（明文凭证，落盘时由 {@code K8sHostsYamlWriter} 加密）。 */
    public record Ssh(String host, Integer port, String user, String password, String privateKey,
                      String passphrase, String kubeconfigRemotePath, String serverOverride,
                      Boolean insecureSkipTlsVerify) {
    }

    /** 转配置态 K8sHost（供 writer 序列化）。 */
    public GatewayProperties.K8sHost toK8sHost() {
        GatewayProperties.K8sHost h = new GatewayProperties.K8sHost();
        h.setName(name);
        h.setNamespace(namespace != null && !namespace.isBlank() ? namespace : "default");
        if (kubeconfig != null && !kubeconfig.isBlank()) {
            h.setKubeconfig(kubeconfig);
        }
        if (ssh != null) {
            GatewayProperties.K8sHost.Ssh s = new GatewayProperties.K8sHost.Ssh();
            s.setHost(ssh.host());
            if (ssh.port() != null) {
                s.setPort(ssh.port());
            }
            s.setUser(ssh.user());
            s.setPassword(ssh.password());
            s.setPrivateKey(ssh.privateKey());
            s.setPassphrase(ssh.passphrase());
            s.setKubeconfigRemotePath(ssh.kubeconfigRemotePath());
            s.setServerOverride(ssh.serverOverride());
            if (ssh.insecureSkipTlsVerify() != null) {
                s.setInsecureSkipTlsVerify(ssh.insecureSkipTlsVerify());
            }
            h.setSsh(s);
        }
        return h;
    }
}
