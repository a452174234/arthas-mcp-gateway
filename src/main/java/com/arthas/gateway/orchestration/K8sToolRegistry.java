package com.arthas.gateway.orchestration;

import com.arthas.gateway.tool.ExposedTool;
import com.arthas.gateway.tool.RoutingMode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 3 个 K8S 编排工具规格（003 特性，契约 §1/§2/§3，research.md R6）。
 *
 * <p>不可变快照：{@code k8s.list-pods} / {@code k8s.list-services} / {@code k8s.ensure-arthas-mcp}，
 * 与既有 35 工具合并进同一 MCP server（{@code tools/list}=38）。三者 {@code routingMode=GATEWAY_LOCAL}（无
 * {@code target} 参数），但 <b>handler 自带闭包、不经 {@code ToolsCallRouter}</b>（gateway-core 路由零 K8S 感知，
 * R6）—— {@code routingMode} 字段仅供 {@link ExposedTool} 完整性，不参与实际路由。
 *
 * <p>工具规格始终注册（即便 kubeconfig 缺失、CI 无 k3s）—— 故 {@code tools/list} 恒为 38（回归守护 T029）；
 * handler 执行依赖真实 kubeconfig（缺时返「K8S 编排未启用」明确错误）。
 */
public final class K8sToolRegistry {

    /** 工具全名常量（handler 分派 + 契约断言引用）。 */
    public static final String LIST_PODS = "k8s.list-pods";
    public static final String LIST_SERVICES = "k8s.list-services";
    public static final String ENSURE_ARTHAS_MCP = "k8s.ensure-arthas-mcp";

    private K8sToolRegistry() {
    }

    /** 3 个编排工具的不可变规格。 */
    public static List<ExposedTool> tools() {
        return List.of(
                new ExposedTool(
                        LIST_PODS,
                        "枚举指定 K8S 命名空间的 pod，含 hasJvm/hasShell 标记（供筛选可被 arthas 诊断的 pod）",
                        objectSchema(Map.of("namespace", stringProp("K8S 命名空间，缺省 default")), List.of()),
                        null,
                        RoutingMode.GATEWAY_LOCAL),
                new ExposedTool(
                        LIST_SERVICES,
                        "枚举指定 K8S 命名空间的 service",
                        objectSchema(Map.of("namespace", stringProp("K8S 命名空间，缺省 default")), List.of()),
                        null,
                        RoutingMode.GATEWAY_LOCAL),
                new ExposedTool(
                        ENSURE_ARTHAS_MCP,
                        "对指定 K8S pod 原子幂等完成：注入 arthas（exec attach 该 pod JVM）+ 启动绑 0.0.0.0 的 arthas MCP + NodePort 暴露 + 内部健康检查 + 动态注册进网关；返回可诊断的 target 名",
                        objectSchema(
                                Map.of(
                                        "server", stringProp("Linux 服务器名（逻辑名前缀 + 来源标识）"),
                                        "pod", stringProp("目标 pod 名（须含 shell+java+JVM）"),
                                        "namespace", stringProp("K8S 命名空间，缺省 default")),
                                List.of("server", "pod")),
                        null,
                        RoutingMode.GATEWAY_LOCAL));
    }

    /** 构造标准 inputSchema（object + properties + required + additionalProperties:false）。 */
    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>(properties));
        schema.put("required", List.copyOf(required));
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> stringProp(String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("description", description);
        return p;
    }
}
