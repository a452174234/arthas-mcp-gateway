package com.arthas.gateway.config;

import java.time.Duration;

/**
 * K8S 全局参数快照（006 波2 T020，可热刷新）。
 *
 * <p>承载 ensure 行为参数（arthas 启动/版本/端口/超时/NodePort 范围）。{@link K8sHostStore} 持此快照
 *（{@code updateParams} 由 {@code K8sHostsWatcher} 加载 {@code config/k8s-hosts.yaml} 的 {@code k8s-params}
 * 段后刷新）；{@code ArthasProvisioner} 经 {@code paramsSupplier} 读 {@code currentParams()}，使全局参数热生效
 *（下次 ensure 用新值，已 ensure 的 pod 不变，INV-HOT-3）。
 *
 * @param targetIp arthas 绑定 IP（0.0.0.0）
 * @param mcpPort pod 内 arthas MCP 端口
 * @param arthasVersion arthas 版本（4.3.0）
 * @param arthasPassword arthas 鉴权密码
 * @param ensureTimeout ensure 全流程超时
 * @param nodePortRange NodePort 分配范围
 * @param arthasBootJar arthas-boot.jar 路径
 */
public record K8sParams(
        String targetIp,
        int mcpPort,
        String arthasVersion,
        String arthasPassword,
        Duration ensureTimeout,
        String nodePortRange,
        String arthasBootJar) {

    /** 从 GatewayProperties.K8s（启动期绑定，回退源）派生。 */
    public static K8sParams from(GatewayProperties.K8s k) {
        return new K8sParams(k.getTargetIp(), k.getMcpPort(), k.getArthasVersion(), k.getArthasPassword(),
                k.getEnsureTimeout(), k.getNodePortRange(), k.getArthasBootJar());
    }
}
