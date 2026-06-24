package com.arthas.gateway.orchestration;

import com.arthas.gateway.backend.BackendConfigException;
import com.arthas.gateway.config.GatewayProperties;
import com.arthas.gateway.handler.McpErrorCodes;
import com.arthas.gateway.handler.McpJson;
import com.arthas.gateway.tool.ExposedTool;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 3 个 K8S 编排工具的本地处理器（003 特性，契约 §1/§2/§3，R6）。
 *
 * <p>handler <b>自带闭包</b>（在 {@code GatewayMcpServerConfig} bean 构建时绑定），<b>不经 {@code ToolsCallRouter}</b>
 * ——gateway-core 路由零 K8S 感知。分派按工具名：
 * <ul>
 *   <li>{@code k8s.list-pods}：枚举 pod（hasJvm/hasShell 标记）。</li>
 *   <li>{@code k8s.list-services}：枚举 service。</li>
 *   <li>{@code k8s.ensure-arthas-mcp}：原子幂等供给（委托 {@link ArthasProvisioner}）。</li>
 * </ul>
 *
 * <p><b>错误映射</b>（原则五显式传播）：K8S API 不可达 / RBAC 不足 → INVALID_PARAMS +
 * {@code data.reason}∈{@code k8s_unreachable/k8s_forbidden}；ensure 失败 → INVALID_PARAMS +
 * {@code data.status=failed, data.error={reason,stage,message}}（契约 §3）。
 */
public class K8sToolHandlers {

    private static final Logger log = LoggerFactory.getLogger(K8sToolHandlers.class);

    private final K8sPodExplorer explorer;
    private final ArthasProvisioner provisioner;
    private final GatewayProperties props;
    private final Clock clock;

    public K8sToolHandlers(K8sPodExplorer explorer, ArthasProvisioner provisioner, GatewayProperties props) {
        this(explorer, provisioner, props, Clock.systemUTC());
    }

    /** 测试注入时钟（ensure 记录的 createdAt/completedAt 确定性）。 */
    public K8sToolHandlers(K8sPodExplorer explorer, ArthasProvisioner provisioner, GatewayProperties props, Clock clock) {
        this.explorer = Objects.requireNonNull(explorer, "explorer 不可为空");
        this.provisioner = Objects.requireNonNull(provisioner, "provisioner 不可为空");
        this.props = Objects.requireNonNull(props, "props 不可为空");
        this.clock = Objects.requireNonNull(clock, "clock 不可为空");
    }

    /** 分派到具体处理方法（按工具名）。 */
    public CallToolResult handle(ExposedTool tool, CallToolRequest request) {
        return switch (tool.name()) {
            case K8sToolRegistry.LIST_PODS -> listPods(request);
            case K8sToolRegistry.LIST_SERVICES -> listServices(request);
            case K8sToolRegistry.ENSURE_ARTHAS_MCP -> ensureArthasMcp(request);
            default -> throw new IllegalStateException("未知 K8S 编排工具：" + tool.name());
        };
    }

    // ===== k8s.list-pods（契约 §1，K-LP-1） =====

    private CallToolResult listPods(CallToolRequest request) {
        String namespace = namespace(request);
        List<Map<String, Object>> pods;
        try {
            pods = explorer.listPods(namespace).stream().map(p -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", p.name());
                m.put("namespace", p.namespace());
                m.put("ready", p.ready());
                m.put("hasJvm", p.hasJvm());
                m.put("hasShell", p.hasShell());
                return m;
            }).toList();
        } catch (RuntimeException e) {
            throw k8sApiError("k8s.list-pods", e);
        }
        return McpJson.json(Map.of("pods", pods, "namespace", namespace));
    }

    // ===== k8s.list-services（契约 §2，K-LS-1） =====

    private CallToolResult listServices(CallToolRequest request) {
        String namespace = namespace(request);
        List<Map<String, Object>> services;
        try {
            services = explorer.listServices(namespace).stream().map(s -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", s.name());
                m.put("namespace", s.namespace());
                m.put("type", s.type());
                m.put("clusterIp", s.clusterIp());
                m.put("ports", s.ports());
                return m;
            }).toList();
        } catch (RuntimeException e) {
            throw k8sApiError("k8s.list-services", e);
        }
        return McpJson.json(Map.of("services", services, "namespace", namespace));
    }

    // ===== k8s.ensure-arthas-mcp（契约 §3，K-ENS-*） =====

    private CallToolResult ensureArthasMcp(CallToolRequest request) {
        String server = requireString(request, "server");
        String pod = requireString(request, "pod");
        String namespace = optionalString(request, "namespace", props.getK8s().getNamespace());

        OrchestrationRecord record;
        try {
            record = provisioner.ensure(server, pod, namespace, clock.instant());
        } catch (BackendConfigException e) {
            // register 命名冲突（动态名 ∩ 静态种子 / 同名异 URL）→ name_conflict（K-ENS-9）
            log.warn("ensure 注册失败（name_conflict）：server={} pod={} {}", server, pod, e.getMessage());
            throw ensureFailedError(ArthasProvisioner.deriveLogicalName(server, pod),
                    "name_conflict", "register", e.getMessage());
        } catch (RuntimeException e) {
            // 非预期基础设施故障（如 K8S API 不可达）→ 按可达性映射
            log.error("ensure 非预期异常：server={} pod={}", server, pod, e);
            throw k8sApiError("k8s.ensure-arthas-mcp", e);
        }

        return switch (record.status()) {
            case READY, REUSED -> McpJson.json(readyView(record));
            case FAILED -> throw ensureFailedError(record.logicalName(),
                    record.error().reason(), record.error().phase(), record.error().message());
            case ENSURING -> throw new IllegalStateException("ensure 返回非终态：" + record);
        };
    }

    /** ready/reused 成功视图（契约 §3 返回结构）。 */
    private static Map<String, Object> readyView(OrchestrationRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("target", r.logicalName());
        m.put("status", r.status().name().toLowerCase());
        m.put("mcpUrl", r.mcpUrl());
        m.put("namespace", r.namespace());
        return m;
    }

    /** ensure 失败 → INVALID_PARAMS + data{target, status:failed, error{reason,stage,message}}（契约 §3）。 */
    private static McpError ensureFailedError(String target, String reason, String stage, String message) {
        return McpError.builder(McpErrorCodes.INVALID_PARAMS)
                .message("ensure-arthas-mcp 失败：target=" + target + " reason=" + reason + " stage=" + stage
                        + "（" + message + "）")
                .data(Map.of(
                        "target", target,
                        "status", "failed",
                        "error", Map.of("reason", reason, "stage", stage, "message", message)))
                .build();
    }

    /** K8S API 故障 → INVALID_PARAMS + data.reason=k8s_forbidden(403)/k8s_unreachable（契约 §1/§3）。 */
    private static McpError k8sApiError(String tool, RuntimeException e) {
        String reason;
        if (e instanceof KubernetesClientException kce && kce.getCode() == 403) {
            reason = "k8s_forbidden";
        } else {
            reason = "k8s_unreachable";
        }
        log.warn("{} K8S API 故障 reason={}：{}", tool, reason, e.toString());
        return McpError.builder(McpErrorCodes.INVALID_PARAMS)
                .message(tool + " 访问集群失败：" + reason + "（" + e.getMessage() + "）")
                .data(Map.of("reason", reason))
                .build();
    }

    // ===== 参数解析 =====

    private String namespace(CallToolRequest request) {
        return optionalString(request, "namespace", props.getK8s().getNamespace());
    }

    private static String requireString(CallToolRequest request, String key) {
        String v = optionalString(request, key, null);
        if (v == null) {
            throw McpError.builder(McpErrorCodes.INVALID_PARAMS)
                    .message("参数缺失或为空：" + key)
                    .build();
        }
        return v;
    }

    private static String optionalString(CallToolRequest request, String key, String fallback) {
        Object v = request.arguments() == null ? null : request.arguments().get(key);
        if (v instanceof String s && !s.isBlank()) {
            return s;
        }
        return fallback;
    }
}
