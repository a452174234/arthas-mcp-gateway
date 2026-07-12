package com.arthas.gateway.orchestration;

/**
 * SSH 引导参数（006 波1，T005）：{@code arthas-gateway.k8s-hosts[].ssh} 的运行时映射 record。
 *
 * <p>剥离 Spring 配置类（{@link com.arthas.gateway.config.GatewayProperties.K8sHost.Ssh}），传给
 * {@link SshKubeconfigFetcher}。用户只配 master IP + root + 密码/私钥 + 远端 kubeconfig 路径，网关 SSH 登入读取。
 *
 * <p><b>校验</b>（compact constructor）：{@code host}/{@code user}/{@code kubeconfigRemotePath} 非空非空白；
 * {@code password} 与 {@code privateKey} 至少一项；{@code port} 正整数。
 *
 * <p>实体见 [data-model.md §3](../../../../specs/006-k8s-host-remote-access/data-model.md)。
 *
 * @param host master/control-plane IP
 * @param port SSH 端口（22）
 * @param user SSH 用户（通常 root）
 * @param password SSH 密码（与 privateKey 二选一）
 * @param privateKey SSH 私钥（与 password 二选一）
 * @param passphrase 私钥口令（可选）
 * @param kubeconfigRemotePath 远端 kubeconfig 路径（标准 K8S /etc/kubernetes/admin.conf；k3s /etc/rancher/k3s/k3s.yaml）
 * @param serverOverride kubeconfig server 替换值（可选；server 不可达时，如 127.0.0.1/VIP）
 * @param insecureSkipTlsVerify 跳过 TLS 证书校验（默认 false；SAN 不匹配兜底，开启需知中间人风险）
 */
public record SshBootstrap(
        String host,
        int port,
        String user,
        String password,
        String privateKey,
        String passphrase,
        String kubeconfigRemotePath,
        String serverOverride,
        boolean insecureSkipTlsVerify) {

    public SshBootstrap {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host 不可为空白");
        }
        if (user == null || user.isBlank()) {
            throw new IllegalArgumentException("user 不可为空白");
        }
        if (kubeconfigRemotePath == null || kubeconfigRemotePath.isBlank()) {
            throw new IllegalArgumentException("kubeconfigRemotePath 不可为空白");
        }
        boolean hasPassword = password != null && !password.isBlank();
        boolean hasPrivateKey = privateKey != null && !privateKey.isBlank();
        if (!hasPassword && !hasPrivateKey) {
            throw new IllegalArgumentException("password 与 privateKey 至少须配置一项（SSH 认证凭证）");
        }
        if (port <= 0) {
            throw new IllegalArgumentException("port 须为正整数（默认 22）");
        }
    }
}
