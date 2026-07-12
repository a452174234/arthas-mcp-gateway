package com.arthas.gateway.orchestration;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.IOUtils;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;

import java.nio.charset.StandardCharsets;

/**
 * SSH kubeconfig 读取器（006 波1，T007）：经 sshj 登 master 执行 {@code cat <path>} 取 kubeconfig 文本。
 *
 * <p>用户只配 master IP+root+密码（或私钥）+ 远端 kubeconfig 路径，本类 SSH 登入读取
 * （标准 K8S {@code /etc/kubernetes/admin.conf}；k3s {@code /etc/rancher/k3s/k3s.yaml}），免用户处理 K8S 鉴权。
 * SSH 仅一次性引导（{@code K8sHostStore} 首取缓存，不每次路由都 SSH，research.md R3）。
 *
 * <p><b>故障分类</b>（→ {@link SshBootstrapException}）：连不上→{@code ssh_unreachable}；认证失败→{@code ssh_auth_failed}；
 * 远端路径不存在（cat exitCode≠0）→{@code kubeconfig_not_found}；内容空→{@code kubeconfig_invalid}。
 *
 * <p>实体见 [data-model.md §4](../../../../specs/006-k8s-host-remote-access/data-model.md)；库选型见 research.md R1。
 */
public final class SshKubeconfigFetcher {

    /** SSH 连接超时（ms）。 */
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    /**
     * SSH 登 master 读取 kubeconfig 文本。
     *
     * @param ssh SSH 引导参数（host/user/凭证/path）
     * @return kubeconfig 文本（非空）
     * @throws SshBootstrapException 连接/认证/读取任一失败（含 reason）
     */
    public String fetchKubeconfig(SshBootstrap ssh) {
        SSHClient client = new SSHClient();
        client.setConnectTimeout(CONNECT_TIMEOUT_MS);
        // 内网信任：接受任何 host key（master 为受控内网节点；生产应配 known_hosts）
        client.addHostKeyVerifier(new PromiscuousVerifier());
        try {
            try {
                client.connect(ssh.host(), ssh.port());
            } catch (Exception e) {
                throw new SshBootstrapException(SshBootstrapException.Reason.SSH_UNREACHABLE,
                        "SSH 连接失败：" + ssh.host() + ":" + ssh.port() + "（" + safeMsg(e) + "）", e);
            }
            try {
                if (ssh.password() != null && !ssh.password().isBlank()) {
                    client.authPassword(ssh.user(), ssh.password());
                } else {
                    // privateKey：作路径传给 sshj（authPublickey 支持 keystore/key 路径）
                    client.authPublickey(ssh.user(), ssh.privateKey());
                }
            } catch (Exception e) {
                throw new SshBootstrapException(SshBootstrapException.Reason.SSH_AUTH_FAILED,
                        "SSH 认证失败：" + ssh.user() + "@" + ssh.host() + "（" + safeMsg(e) + "）", e);
            }
            if (!client.isAuthenticated()) {
                throw new SshBootstrapException(SshBootstrapException.Reason.SSH_AUTH_FAILED,
                        "SSH 认证失败（未授权）：" + ssh.user() + "@" + ssh.host(), null);
            }
            try (Session session = client.startSession()) {
                Session.Command cmd = session.exec("cat " + ssh.kubeconfigRemotePath());
                cmd.join();
                java.io.ByteArrayOutputStream bos = IOUtils.readFully(cmd.getInputStream());
                String content = bos.toString(StandardCharsets.UTF_8);
                Integer exit = cmd.getExitStatus();
                int exitCode = exit == null ? -1 : exit;
                if (exitCode != 0) {
                    throw new SshBootstrapException(SshBootstrapException.Reason.KUBECONFIG_NOT_FOUND,
                            "远端 kubeconfig 读取失败（exitCode=" + exitCode + "）：" + ssh.kubeconfigRemotePath(), null);
                }
                if (content.isBlank()) {
                    throw new SshBootstrapException(SshBootstrapException.Reason.KUBECONFIG_INVALID,
                            "远端 kubeconfig 内容为空：" + ssh.kubeconfigRemotePath(), null);
                }
                return content;
            }
        } catch (SshBootstrapException e) {
            throw e;
        } catch (Exception e) {
            throw new SshBootstrapException(SshBootstrapException.Reason.SSH_UNREACHABLE,
                    "SSH 操作失败：" + safeMsg(e), e);
        } finally {
            try {
                client.close();
            } catch (Exception ignore) {
                // 关闭失败不影响已取结果
            }
        }
    }

    private static String safeMsg(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
