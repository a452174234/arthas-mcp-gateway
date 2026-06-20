package com.arthas.gateway.handler;

import com.arthas.gateway.backend.BackendEntry;
import com.arthas.gateway.backend.BackendRegistry;
import com.arthas.gateway.backend.RegistryHolder;
import com.arthas.gateway.task.AsyncTaskExecutor;
import com.arthas.gateway.task.GatewayTask;
import com.arthas.gateway.task.TaskState;
import com.arthas.gateway.tool.ExposedTool;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 网关自有工具的本地处理器（gateway-tools-contract.md，T035）。
 *
 * <p>4 个 {@code arthas-gateway.*} 工具（routingMode=GATEWAY_LOCAL，不转发后端）：
 * <ul>
 *   <li>{@code list-targets}：返回当前注册表（name/state/healthy/protocol + version，G-LT-1）。</li>
 *   <li>{@code task-get}：按 status 返回任务快照（working/completed+result/failed+error/cancelled，G-TG-1/2）；
 *       未知 taskId → INVALID_PARAMS（G-TG-3）。</li>
 *   <li>{@code task-list}：全量或按 status 过滤的任务概要（G-TL-1）。</li>
 *   <li>{@code task-cancel}：取消 WORKING 任务（→ cancelled）；终态幂等返当前状态（G-TC-1/2）。</li>
 * </ul>
 *
 * <p>这些工具对 Claude Code 是普通 MCP 工具——"task"仅网关内存态（{@link AsyncTaskExecutor#store()}），
 * 无任何手写 task 协议帧（宪法「不手写帧」硬约束）。响应体均为 {@code TextContent(JSON)}。
 *
 * <p><b>completed.result 渲染</b>：后端 {@link CallToolResult} 的 content（sealed 多态接口）渲染为
 * {@code [{type, text}]} 列表 + {@code isError}（G-TG-2：isError 原样保留），避免多态 Jackson 序列化配置。
 *
 * <p><b>healthy</b>（Phase 5 细化）：{@code state==ACTIVE && breaker 未 OPEN}。熔断 OPEN / RETIRED → false。
 */
@Component
public class GatewayToolHandlers {

    private final RegistryHolder registry;
    private final AsyncTaskExecutor executor;

    public GatewayToolHandlers(RegistryHolder registry, AsyncTaskExecutor executor) {
        this.registry = Objects.requireNonNull(registry, "registry 不可为空");
        this.executor = Objects.requireNonNull(executor, "executor 不可为空");
    }

    /** 分派到具体处理方法（按工具名）。 */
    public CallToolResult handle(ExposedTool tool, CallToolRequest request) {
        return switch (tool.name()) {
            case "arthas-gateway.list-targets" -> listTargets();
            case "arthas-gateway.task-get" -> taskGet(request);
            case "arthas-gateway.task-list" -> taskList(request);
            case "arthas-gateway.task-cancel" -> taskCancel(request);
            default -> throw new IllegalStateException("未知网关自有工具：" + tool.name());
        };
    }

    // ===== list-targets（G-LT-1） =====

    private CallToolResult listTargets() {
        BackendRegistry reg = registry.current();
        List<Map<String, Object>> targets = new ArrayList<>();
        for (BackendEntry e : reg.byName().values()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("name", e.config().name());
            t.put("state", e.state().name());
            t.put("healthy", e.isHealthy()); // 健康单一事实源(T027/FR-012),委托 BackendEntry.isHealthy()
            t.put("protocol", e.config().protocol().name());
            targets.add(t);
        }
        return McpJson.json(Map.of("targets", targets, "version", reg.version()));
    }

    // ===== task-get（G-TG-1/2/3） =====

    private CallToolResult taskGet(CallToolRequest request) {
        String taskId = requireTaskId(request);
        GatewayTask task = executor.store().get(taskId)
                .orElseThrow(() -> McpError.builder(McpErrorCodes.INVALID_PARAMS)
                        .message("taskId 不存在：" + taskId + "（见 task-list）")
                        .build());
        return McpJson.json(taskGetView(task));
    }

    private static Map<String, Object> taskGetView(GatewayTask task) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", task.taskId());
        m.put("status", task.status().name().toLowerCase());
        switch (task.status()) {
            case WORKING -> {
                m.put("toolName", task.toolName());
                m.put("target", task.target());
                m.put("createdAt", task.createdAt().toString());
            }
            case COMPLETED -> {
                m.put("toolName", task.toolName());
                m.put("target", task.target());
                m.put("completedAt", task.completedAt().toString());
                m.put("result", renderResult(task.result()));
            }
            case FAILED -> m.put("error", Map.of(
                    "reason", task.error().reason(),
                    "message", task.error().message()));
            case CANCELLED -> { /* 仅 taskId + status */ }
        }
        return m;
    }

    // ===== task-list（G-TL-1） =====

    private CallToolResult taskList(CallToolRequest request) {
        TaskState filter = parseStatus(request.arguments());
        List<GatewayTask> tasks = filter != null ? executor.store().list(filter) : executor.store().list();
        List<Map<String, Object>> views = new ArrayList<>();
        for (GatewayTask t : tasks) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("taskId", t.taskId());
            v.put("status", t.status().name().toLowerCase());
            v.put("toolName", t.toolName());
            v.put("target", t.target());
            v.put("createdAt", t.createdAt().toString());
            if (t.completedAt() != null) {
                v.put("completedAt", t.completedAt().toString());
            }
            views.add(v);
        }
        return McpJson.json(Map.of("tasks", views));
    }

    // ===== task-cancel（G-TC-1/2） =====

    private CallToolResult taskCancel(CallToolRequest request) {
        String taskId = requireTaskId(request);
        GatewayTask task = executor.store().get(taskId)
                .orElseThrow(() -> McpError.builder(McpErrorCodes.INVALID_PARAMS)
                        .message("taskId 不存在：" + taskId)
                        .build());
        executor.cancel(task); // WORKING→CANCELLED；终态幂等（返 false，不改状态）
        return McpJson.json(Map.of("taskId", task.taskId(), "status", task.status().name().toLowerCase()));
    }

    // ===== 辅助 =====

    private static String requireTaskId(CallToolRequest request) {
        Object v = request.arguments() == null ? null : request.arguments().get("taskId");
        if (!(v instanceof String s) || s.isBlank()) {
            throw McpError.builder(McpErrorCodes.INVALID_PARAMS)
                    .message("taskId 缺失或为空")
                    .build();
        }
        return s;
    }

    private static TaskState parseStatus(Map<String, Object> args) {
        if (args == null) {
            return null;
        }
        Object v = args.get("status");
        if (v == null) {
            return null;
        }
        try {
            return TaskState.valueOf(String.valueOf(v).toUpperCase());
        } catch (IllegalArgumentException e) {
            throw McpError.builder(McpErrorCodes.INVALID_PARAMS)
                    .message("非法 status 过滤值：" + v)
                    .build();
        }
    }

    /** completed.result 渲染：isError 原样 + content 文本列表（G-TG-2）。 */
    private static Map<String, Object> renderResult(CallToolResult result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("isError", Boolean.TRUE.equals(result.isError()));
        List<Map<String, Object>> content = new ArrayList<>();
        for (Content c : result.content()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", c.type());
            if (c instanceof TextContent tc) {
                item.put("text", tc.text());
            }
            content.add(item);
        }
        m.put("content", content);
        return m;
    }
}
