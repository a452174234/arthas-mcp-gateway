package com.arthas.gateway.orchestration;

import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeAddress;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * NodePort 暴露器（003 特性，契约 §3，research.md R5）：为目标 pod 打唯一 label + 建（或复用）NodePort Service，
 * 产出可达 {@code mcpUrl=http://<nodeIP>:<nodePort>}（arthas MCP 根 URL，无 /mcp）。
 *
 * <p><b>幂等</b>：Service 名确定性派生自 logicalName（{@code arthas-mcp-<sanitize(logical)>}），已存在则复用其
 * NodePort，不重建（K-ENS-2 零副作用复用的前提）。pod label 同名覆盖（幂等）。
 *
 * <p><b>selector 精确命中</b>：业务 pod 通常无唯一 label，打 {@code arthas-mcp-gateway/target=<labelValue>}
 * 后 selector 精确命中该 pod（设计 §4.1）；pod 重启 IP 变化时 Service 自动重解析（持续可达新 pod）。
 *
 * <p><b>nodeIP 解析</b>：从集群节点 status.addresses 取（优先 ExternalIP，其次 InternalIP）。k3s 单节点测试床的
 * InternalIP=192.168.31.92（网关经 LAN 可达，验证"集群外运行"拓扑）。
 *
 * <p>label value 与 service 名须 K8S 合法：sanitize 把非法字符替换为 '-'、小写、截断。
 */
public final class NodePortExposer {

    private static final Logger log = LoggerFactory.getLogger(NodePortExposer.class);

    /** pod label key（selector 匹配键）。 */
    public static final String TARGET_LABEL_KEY = "arthas-mcp-gateway/target";
    /** service 名前缀。 */
    private static final String SERVICE_PREFIX = "arthas-mcp-";

    private final KubernetesClient client;

    public NodePortExposer(KubernetesClient client) {
        this.client = Objects.requireNonNull(client, "client 不可为空");
    }

    /** 暴露结果（mcpUrl + service 引用，供 Provisioner 记录与注册）。 */
    public record ExposeResult(String serviceName, Integer nodePort, String mcpUrl, String serviceRef) {
    }

    /**
     * 暴露 pod 的 mcpPort 为 NodePort。
     *
     * @param namespace   命名空间
     * @param pod         目标 pod 名
     * @param logicalName 逻辑名（target 名，派生 label value / service 名的来源）
     * @param mcpPort     pod 内 arthas MCP 监听端口（NodePort targetPort）
     * @return 暴露结果（含可达 mcpUrl）
     */
    public ExposeResult expose(String namespace, String pod, String logicalName, int mcpPort) {
        Objects.requireNonNull(namespace, "namespace 不可为空");
        Objects.requireNonNull(pod, "pod 不可为空");
        Objects.requireNonNull(logicalName, "logicalName 不可为空");
        String labelValue = sanitizeLabelValue(logicalName);

        // 1. label pod（幂等覆盖；selector 精确命中该 pod）
        labelPod(namespace, pod, labelValue);

        // 2. 优先复用带 label 的现有 Service（patch type+端口，K-ENS-10/11）；
        //    找不到回退新建独立 Service（003 现状，K-ENS-10 回退，向后兼容）
        Service labeled = findLabeledService(namespace, labelValue);
        String serviceName;
        int nodePort;
        if (labeled != null) {
            serviceName = labeled.getMetadata().getName();
            nodePort = patchServiceAddNodePort(labeled, namespace, mcpPort);
            log.info("复用带 label 的现有 Service（K-ENS-10/11）：{}/{} → port {}（patch）",
                    namespace, serviceName, nodePort);
        } else {
            serviceName = sanitizeServiceName(SERVICE_PREFIX + labelValue);
            nodePort = ensureNodePortService(namespace, serviceName, labelValue, mcpPort);
            log.info("未找到带 label 的 Service，回退新建独立 Service（K-ENS-10 回退）：{}/{}",
                    namespace, serviceName);
        }

        // 3. 解析 nodeIP → mcpUrl
        String nodeIp = resolveNodeIp();
        String mcpUrl = "http://" + nodeIp + ":" + nodePort;
        String serviceRef = serviceName + "/" + nodePort;
        log.info("NodePort 已暴露：{}/{} → {}（service={} nodePort={}）",
                namespace, pod, mcpUrl, serviceName, nodePort);
        return new ExposeResult(serviceName, nodePort, mcpUrl, serviceRef);
    }

    /**
     * 查带 {@code arthas-mcp-gateway/target=<labelValue>} label 的现有 Service（005 US1，K-ENS-10）。
     *
     * <p>运维在业务 Service 上预打该 label 即声明「由网关复用暴露 NodePort」，ensure 优先 patch 它而非新建。
     * labelSelector 查询（{@code services().inNamespace(ns).withLabel(k, v).list()}）。
     *
     * @return 命中的第一个带 label Service（多个取首）；无则 null（触发回退新建）
     */
    Service findLabeledService(String namespace, String labelValue) {
        List<Service> svcs = client.services().inNamespace(namespace)
                .withLabel(TARGET_LABEL_KEY, labelValue).list().getItems();
        return (svcs == null || svcs.isEmpty()) ? null : svcs.get(0);
    }

    /**
     * 在现有 Service 上 patch 出 NodePort（005 US1，K-ENS-11/12）。
     *
     * <ul>
     *   <li><b>K-ENS-12 幂等</b>：已是 NodePort 且含同 targetPort 端口 → 复用既有 nodePort（不重复 patch）。</li>
     *   <li><b>K-ENS-11</b>：否则 PUT 改 {@code type=NodePort}（ClusterIP→NodePort）+ 加端口，
     *       K8S 在 nodePortRange 内分配 nodePort；既有端口随之暴露到节点。</li>
     * </ul>
     *
     * @return 该 targetPort 对应的 nodePort（复用或新分配）
     */
    int patchServiceAddNodePort(Service svc, String namespace, int mcpPort) {
        String name = svc.getMetadata().getName();
        // 幂等：已有 targetPort=mcpPort 的 NodePort 端口 → 复用既有 nodePort（K-ENS-12）
        Integer existing = nodePortForTargetPort(svc, mcpPort);
        if (existing != null) {
            log.info("Service {}/{} 已含 NodePort targetPort={} → 复用 nodePort={}（K-ENS-12 幂等）",
                    namespace, name, mcpPort, existing);
            return existing;
        }
        // patch type=NodePort（K-ENS-11）+ 确保有 targetPort=mcpPort 端口：复用既有该端口（K8S 为其分配 nodePort），
        // 仅有该端口缺失时才加。多端口 Service 每个端口须有 name，故为无名端口补 name（仅 K8S 内部唯一性所需，不影响业务）。
        List<ServicePort> ports = new ArrayList<>();
        boolean hasMcpPort = false;
        int idx = 0;
        if (svc.getSpec() != null && svc.getSpec().getPorts() != null) {
            for (ServicePort p : svc.getSpec().getPorts()) {
                ServicePort copy = new ServicePortBuilder(p).build();
                if (copy.getName() == null || copy.getName().isBlank()) {
                    copy.setName("port-" + idx);
                }
                Integer tp = copy.getTargetPort() != null ? copy.getTargetPort().getIntVal() : null;
                if (tp != null && tp == mcpPort) {
                    hasMcpPort = true;
                }
                ports.add(copy);
                idx++;
            }
        }
        if (!hasMcpPort) {
            ports.add(new ServicePortBuilder().withName("arthas-mcp-" + mcpPort)
                    .withPort(mcpPort).withNewTargetPort(mcpPort).build());
        }
        Service toPatch = new ServiceBuilder(svc).editSpec()
                .withType("NodePort").withPorts(ports).endSpec().build();
        Service patched = client.services().inNamespace(namespace).resource(toPatch).update();
        Integer np = nodePortForTargetPort(patched, mcpPort);
        if (np == null) {
            throw new IllegalStateException("patch 后未取到 nodePort（service=" + namespace + "/" + name
                    + "）：K8S 未分配 nodePort");
        }
        log.info("Service {}/{} patch 为 NodePort（K-ENS-11）→ 分配 nodePort={}", namespace, name, np);
        return np;
    }

    /** 取 Service 中 targetPort=指定值的端口 nodePort（幂等复用判定，K-ENS-12）。 */
    private static Integer nodePortForTargetPort(Service svc, int targetPort) {
        if (svc == null || svc.getSpec() == null || svc.getSpec().getPorts() == null) {
            return null;
        }
        for (var p : svc.getSpec().getPorts()) {
            Integer tp = (p.getTargetPort() != null) ? p.getTargetPort().getIntVal() : null;
            if (tp != null && tp == targetPort && p.getNodePort() != null) {
                return p.getNodePort();
            }
        }
        return null;
    }

    /** 删除供给时建的 NodePort Service（清理副作用，幂等）。 */
    public boolean deleteService(String namespace, String serviceName) {
        try {
            // fabric8 7.x delete() 返回 List<StatusDetails>：非空表示实际删除了资源
            return !client.services().inNamespace(namespace).withName(serviceName).delete().isEmpty();
        } catch (RuntimeException e) {
            log.warn("删除 NodePort Service 失败（忽略，幂等清理）：{}", serviceName, e);
            return false;
        }
    }

    /** label pod（幂等；已含同值则无操作）。 */
    private void labelPod(String namespace, String pod, String labelValue) {
        Pod cur = client.pods().inNamespace(namespace).withName(pod).get();
        if (cur == null) {
            throw new IllegalStateException("pod 不存在：" + namespace + "/" + pod);
        }
        String existing = cur.getMetadata().getLabels() != null
                ? cur.getMetadata().getLabels().get(TARGET_LABEL_KEY) : null;
        if (labelValue.equals(existing)) {
            return; // 幂等：已含同值
        }
        Pod updated = new PodBuilder(cur).editMetadata()
                .addToLabels(TARGET_LABEL_KEY, labelValue)
                .endMetadata()
                .build();
        client.pods().inNamespace(namespace).resource(updated).update();
    }

    /** create-or-get NodePort Service；返回 NodePort（K8S 在范围内自动分配）。 */
    private int ensureNodePortService(String namespace, String serviceName, String labelValue, int mcpPort) {
        Service existing = client.services().inNamespace(namespace).withName(serviceName).get();
        if (existing != null) {
            Integer np = firstNodePort(existing);
            if (np != null) {
                return np; // 复用既有 NodePort（幂等）
            }
        }
        Service svc = new ServiceBuilder()
                .withNewMetadata().withName(serviceName).endMetadata()
                .withNewSpec()
                .withType("NodePort")
                .addToSelector(TARGET_LABEL_KEY, labelValue)
                .addNewPort()
                .withPort(mcpPort)
                .withTargetPort(new IntOrString(mcpPort))
                .endPort()
                .endSpec()
                .build();
        client.services().inNamespace(namespace).resource(svc).create();
        Service created = client.services().inNamespace(namespace).withName(serviceName).get();
        Integer np = created != null ? firstNodePort(created) : null;
        if (np == null) {
            throw new IllegalStateException("NodePort 分配失败（service=" + serviceName + "）：未取到 nodePort");
        }
        return np;
    }

    private static Integer firstNodePort(Service svc) {
        if (svc.getSpec() == null || svc.getSpec().getPorts() == null || svc.getSpec().getPorts().isEmpty()) {
            return null;
        }
        return svc.getSpec().getPorts().get(0).getNodePort();
    }

    /** 解析集群 nodeIP（优先 ExternalIP，其次 InternalIP）。 */
    private String resolveNodeIp() {
        List<Node> nodes = client.nodes().list().getItems();
        String internal = null;
        for (Node n : nodes) {
            List<NodeAddress> addrs = n.getStatus() != null ? n.getStatus().getAddresses() : null;
            if (addrs == null) {
                continue;
            }
            for (NodeAddress a : addrs) {
                if ("ExternalIP".equals(a.getType()) && a.getAddress() != null) {
                    return a.getAddress();
                }
                if ("InternalIP".equals(a.getType()) && a.getAddress() != null && internal == null) {
                    internal = a.getAddress();
                }
            }
        }
        if (internal != null) {
            return internal;
        }
        throw new IllegalStateException("集群无可达 nodeIP（未找到 ExternalIP/InternalIP）");
    }

    /** sanitize 为合法 label value（小写、非法字符→'-'、≤63）。 */
    static String sanitizeLabelValue(String logicalName) {
        return sanitizeK8sName(logicalName, 63);
    }

    /** sanitize 为合法 service name（DNS-subdomain：小写、非法字符→'-'、≤253）。 */
    static String sanitizeServiceName(String name) {
        return sanitizeK8sName(name, 253);
    }

    private static String sanitizeK8sName(String s, int maxLen) {
        String lower = s.toLowerCase();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lower.length() && sb.length() < maxLen; i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.') {
                sb.append(c);
            } else {
                sb.append('-');
            }
        }
        // 去除首尾非字母数字
        String out = sb.toString().replaceAll("^[^a-z0-9]+", "").replaceAll("[^a-z0-9]+$", "");
        if (out.isEmpty()) {
            out = "x";
        }
        return out;
    }
}
