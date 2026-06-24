package com.arthas.gateway.orchestration;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 集群 pod/service 枚举器（003 特性，契约 §1/§2）：fabric8 list + 容器内 exec 探测，供
 * {@code k8s.list-pods} / {@code k8s.list-services} 工具返回真实集群清单。
 *
 * <p><b>hasJvm/hasShell 探测</b>（设计 §4：目标 pod 须 shell+java+JVM）：对每个 {@code Running} pod exec
 * 一条组合命令，判定 shell 可用性（{@code echo __SHELL_OK__}）与是否有运行中的 JVM（{@code jps -q} 出 PID）。
 * 非 Running pod 或 exec 通道故障（无 shell / RBAC 403 / API 不可达）→ hasShell/hasJvm=false（不可诊断）。
 * 探测仅作「可诊断性」标记，非保证；arthas 注入的真实性在 ensure 时再实测（契约 §1）。
 *
 * <p>K8S API 不可达 / RBAC 不足 → fabric8 抛异常，由 {@link K8sToolHandlers} 映射为
 * INVALID_PARAMS + {@code reason:k8s_unreachable/k8s_forbidden}（契约 §1）。
 */
public final class K8sPodExplorer {

    private static final Logger log = LoggerFactory.getLogger(K8sPodExplorer.class);

    /** 单 pod 探测超时（shell+jvm 组合命令，避免悬挂 pod 阻塞 list）。 */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

    /** shell 探测成功标记（与 jvm 探测合并在一条 exec）。 */
    private static final String SHELL_OK_MARKER = "__SHELL_OK__";

    private final K8sExec exec;
    private final io.fabric8.kubernetes.client.KubernetesClient client;

    public K8sPodExplorer(io.fabric8.kubernetes.client.KubernetesClient client) {
        this.client = Objects.requireNonNull(client, "client 不可为空");
        this.exec = new K8sExec(client);
    }

    /** 单 pod 视图（契约 §1 返回结构）。 */
    public record PodInfo(String name, String namespace, boolean ready, boolean hasJvm, boolean hasShell) {
    }

    /** 单 service 视图（契约 §2 返回结构）。 */
    public record ServiceInfo(String name, String namespace, String type, String clusterIp,
                              List<Map<String, Object>> ports) {
    }

    /** 枚举指定 namespace 的 pod（探测 hasJvm/hasShell）。 */
    public List<PodInfo> listPods(String namespace) {
        Objects.requireNonNull(namespace, "namespace 不可为空");
        List<Pod> pods = client.pods().inNamespace(namespace).list().getItems();
        List<PodInfo> out = new ArrayList<>(pods.size());
        for (Pod pod : pods) {
            String name = pod.getMetadata().getName();
            boolean ready = isReady(pod);
            boolean hasShell = false;
            boolean hasJvm = false;
            if (ready) {
                // 组合探测：shell 标记 + jps 出 PID（仅 Running pod 探测，避免 exec 挂在 Pending pod）
                try {
                    K8sExec.ExecResult r = exec.exec(namespace, name, PROBE_TIMEOUT, "sh", "-c",
                            "echo " + SHELL_OK_MARKER
                                    + "; command -v jps >/dev/null 2>&1 && jps -q 2>/dev/null | grep -E '^[0-9]+$' | head -1 || true");
                    String stdout = r.stdout();
                    hasShell = stdout.contains(SHELL_OK_MARKER);
                    hasJvm = hasShell && stdout.lines().anyMatch(K8sPodExplorer::isPureDigits);
                } catch (K8sExecException e) {
                    log.debug("pod 探测失败（{}）→ hasShell/hasJvm=false：{}", name, e.getMessage());
                }
            }
            out.add(new PodInfo(name, namespace, ready, hasJvm, hasShell));
        }
        return out;
    }

    /** 枚举指定 namespace 的 service。 */
    public List<ServiceInfo> listServices(String namespace) {
        Objects.requireNonNull(namespace, "namespace 不可为空");
        List<Service> services = client.services().inNamespace(namespace).list().getItems();
        List<ServiceInfo> out = new ArrayList<>(services.size());
        for (Service svc : services) {
            List<Map<String, Object>> ports = new ArrayList<>();
            List<ServicePort> sps = svc.getSpec() != null ? svc.getSpec().getPorts() : null;
            if (sps != null) {
                for (ServicePort sp : sps) {
                    Map<String, Object> p = new java.util.LinkedHashMap<>();
                    p.put("port", sp.getPort());
                    p.put("nodePort", sp.getNodePort()); // 非 NodePort 类型时为 null
                    ports.add(p);
                }
            }
            out.add(new ServiceInfo(
                    svc.getMetadata().getName(),
                    namespace,
                    svc.getSpec() != null && svc.getSpec().getType() != null ? svc.getSpec().getType() : "ClusterIP",
                    svc.getSpec() != null ? String.valueOf(svc.getSpec().getClusterIP()) : null,
                    ports));
        }
        return out;
    }

    /** pod 是否就绪（所有容器 ready）。 */
    private static boolean isReady(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null
                || pod.getStatus().getContainerStatuses().isEmpty()) {
            return false;
        }
        return pod.getStatus().getContainerStatuses().stream().allMatch(cs -> Boolean.TRUE.equals(cs.getReady()));
    }

    private static boolean isPureDigits(String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        for (int i = 0; i < line.length(); i++) {
            if (!Character.isDigit(line.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
