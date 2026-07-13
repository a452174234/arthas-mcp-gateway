package com.arthas.gateway.admin.k8shost;

import com.arthas.gateway.config.GatewayProperties;

/**
 * portal K8S host 展示 DTO（006 波3，T026，**脱敏**：无 password/privateKey/passphrase，INV-PORTAL-K8S-2）。
 *
 * <p>{@link #from(GatewayProperties.K8sHost)} 投影配置态 K8sHost 为展示形态（mode=ssh|kubeconfig + 连接信息）。
 */
public record K8sHostDto(String name, String namespace, String mode, String sshHost, String sshUser,
                         String kubeconfigRemotePath, String kubeconfig) {

    public static K8sHostDto from(GatewayProperties.K8sHost h) {
        if (h.getSsh() != null) {
            return new K8sHostDto(h.getName(), h.getNamespace(), "ssh",
                    h.getSsh().getHost(), h.getSsh().getUser(),
                    h.getSsh().getKubeconfigRemotePath(), null);
        }
        return new K8sHostDto(h.getName(), h.getNamespace(), "kubeconfig",
                null, null, null, h.getKubeconfig());
    }
}
