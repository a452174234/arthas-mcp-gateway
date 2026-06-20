package com.arthas.gateway.handler;

import com.arthas.gateway.backend.BackendEntry;
import com.arthas.gateway.backend.RegistryHolder;
import com.arthas.gateway.task.AsyncTaskExecutor;
import com.arthas.gateway.task.GatewayTask;
import com.arthas.gateway.tool.ExposedTool;
import com.arthas.gateway.tool.RoutingMode;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 工具调用路由器（tools/call 的统一入口，传输无关：stdio/HTTP 两路共用）。
 *
 * <p><b>职责</b>（server-contract.md §5）：
 * <ol>
 *   <li>网关自有工具（GATEWAY_LOCAL）在 target 解析<b>前</b>分流到 {@link GatewayToolHandlers}（其 arguments 无 target）。</li>
 *   <li>{@link DiagnosticRequest#parse} 解析 target 并剥离 → 缺失/空/非字符串 → INVALID_PARAMS（S-ERR-1）。</li>
 *   <li>target 在册解算 → 不在册 → INVALID_PARAMS + {@code data.available}（S-ERR-2）。</li>
 *   <li>按 {@link RoutingMode} 分流。</li>
 * </ol>
 *
 * <h3>路由分流</h3>
 * <ul>
 *   <li><b>SYNC_DIRECT / STREAM_AGGREGATE</b>（26 即时 + dashboard）：{@code client.initialize()}（幂等）+
 *       {@code client.callTool(name, backendArgs)}，结果<b>原样透传</b>（含 {@code isError=true}、后端 JSON-RPC error
 *       ——后者由 SDK client 抛 {@link McpError} 透传，S-ERR-4）。</li>
 *   <li><b>ASYNC_TASK</b>（5 optional：watch/trace/stack/tt/monitor）：解算 target → {@link AsyncTaskExecutor#submit}
 *       后台虚拟线程对后端发<b>同步</b> {@code tools/call}（不带 task 字段，走后端自动轮询路①，11min 兜底），
 *       立即返 {@link #asyncAcceptedResponse}（G-ASYNC-1）。</li>
 *   <li><b>GATEWAY_LOCAL</b>（4 自有：list-targets/task-*）：委托 {@link GatewayToolHandlers}。</li>
 * </ul>
 *
 * <h3>故障隔离与限流（US3，Phase 5 T048）</h3>
 * <p>{@link #forwardSync}/{@link #submitAsync} 转发前双守卫：
 * <ul>
 *   <li><b>熔断</b>（{@link com.arthas.gateway.backend.CircuitBreaker#allowRequest}）：OPEN 未满退避 → 立即 S-ERR-5
 *       （不等 30s，SC-003）。</li>
 *   <li><b>限流</b>（{@link BackendEntry#tryAcquireSlot}）：并发 &gt; {@code maxConcurrentTasks} → INVALID_PARAMS
 *       （C-LIMIT-1，前置拦截避免越界打后端）。</li>
 * </ul>
 * 同步路径负责熔断状态机驱动：基础设施异常 → {@code recordFailure}（连续 3 次→OPEN，C-CB-1）+ S-ERR-5；
 * 正常返回或后端业务错误（isError / McpError 透传）→ {@code recordSuccess}，<b>不计</b>熔断（C-CB-2、S-ERR-4）。
 * 异步路径<b>只读守卫 + 限流</b>、不改熔断（见 {@link #submitAsync} 注释）。
 */
@Component
public class ToolsCallRouter {

    private static final Logger log = LoggerFactory.getLogger(ToolsCallRouter.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RegistryHolder registry;
    private final AsyncTaskExecutor asyncExecutor;
    private final GatewayToolHandlers gatewayHandlers;

    public ToolsCallRouter(RegistryHolder registry, AsyncTaskExecutor asyncExecutor, GatewayToolHandlers gatewayHandlers) {
        this.registry = Objects.requireNonNull(registry, "registry 不可为空");
        this.asyncExecutor = Objects.requireNonNull(asyncExecutor, "asyncExecutor 不可为空");
        this.gatewayHandlers = Objects.requireNonNull(gatewayHandlers, "gatewayHandlers 不可为空");
    }

    /**
     * 路由一次工具调用。
     *
     * @param tool    注册表中的工具（含 routingMode）
     * @param request SDK 反序列化的调用请求（name/arguments）
     * @return 调用结果（同步透传 / 异步立即返 taskId / 本地处理）
     */
    public CallToolResult route(ExposedTool tool, CallToolRequest request) {
        // 网关自有工具无 target 参数，在 DiagnosticRequest 解析前分流（避免 target 缺失误报）
        if (tool.routingMode() == RoutingMode.GATEWAY_LOCAL) {
            return gatewayHandlers.handle(tool, request);
        }
        DiagnosticRequest dr = DiagnosticRequest.parse(tool, request); // target 格式校验（S-ERR-1）
        return switch (dr.routingMode()) {
            case SYNC_DIRECT, STREAM_AGGREGATE -> forwardSync(tool, dr);
            case ASYNC_TASK -> submitAsync(tool, dr);
            default -> throw new IllegalStateException("不可达：GATEWAY_LOCAL 已在 parse 前分流，" + dr.routingMode());
        };
    }

    /** 同步转发到 target 后端，结果原样透传（含 isError / 后端 error）；结构化日志贯穿（T049）。 */
    private CallToolResult forwardSync(ExposedTool tool, DiagnosticRequest dr) {
        BackendEntry entry = resolveTarget(dr); // 不在册 → INVALID_PARAMS + data.available（S-ERR-2）
        guardCircuit(entry, dr);                 // 熔断 OPEN → S-ERR-5（限流前先挡）
        if (!entry.tryAcquireSlot()) {           // 并发越界 → INVALID_PARAMS（C-LIMIT-1，不越界打后端）
            throw concurrencyLimitError(entry, dr);
        }
        long start = System.nanoTime();
        try {
            entry.client().initialize(); // 幂等握手（首次路由时建立后端会话）
            CallToolResult result = entry.client().callTool(tool.name(), dr.backendArgs()); // 原样透传
            entry.breaker().recordSuccess(); // 成功或 isError=true 业务错误均计成功（C-CB-2：不计熔断）
            log.info("工具调用完成 tool={} target={} isError={} 耗时={}ms",
                    tool.name(), dr.target(), result.isError(), Duration.ofNanos(System.nanoTime() - start).toMillis());
            return result;
        } catch (McpError e) {
            // 后端 JSON-RPC error（INVALID_PARAMS 等）→ 原样透传（S-ERR-4），不计熔断（C-CB-2）
            entry.breaker().recordSuccess();
            log.warn("后端业务错误（不计熔断）tool={} target={} code={} msg={}",
                    tool.name(), dr.target(), e.getJsonRpcError().code(), e.getJsonRpcError().message());
            throw e;
        } catch (RuntimeException e) {
            // 基础设施故障（连接拒绝/超时/initialize 失败/SSE 中断）→ 计入熔断（C-CB-1）+ S-ERR-5
            entry.breaker().recordFailure();
            log.warn("基础设施故障（计入熔断）tool={} target={} 耗时={}ms {}",
                    tool.name(), dr.target(), Duration.ofNanos(System.nanoTime() - start).toMillis(), e.toString());
            throw backendUnreachableError(entry, dr, e);
        } finally {
            entry.releaseSlot();
        }
    }

    /** ASYNC_TASK：解算 target → 熔断/限流守卫 → 提交后台异步任务 → 立即返 working（G-ASYNC-1）。 */
    private CallToolResult submitAsync(ExposedTool tool, DiagnosticRequest dr) {
        BackendEntry entry = resolveTarget(dr);
        guardCircuit(entry, dr);                 // 熔断 OPEN → S-ERR-5（不创建任务）
        if (!entry.tryAcquireSlot()) {           // 并发越界 → INVALID_PARAMS（C-LIMIT-1）
            throw concurrencyLimitError(entry, dr);
        }
        // entry 固定引用（热重载不串台）；slot 在后台 finally 释放——5 个在途 watch 持 5 槽，第 6 个 submit 被前置限流。
        // 异步<b>不改熔断</b>：长任务失败由 executor markFailed(backend_unreachable) 上报；避免 cancel 中断误计 +
        // 单异步失败误熔断拖累同步路径（熔断仅由同步路径 recordFailure 驱动）。
        GatewayTask task;
        try {
            task = asyncExecutor.submit(tool.name(), dr.target(), () -> {
                try {
                    entry.client().initialize();
                    return entry.client().callTool(tool.name(), dr.backendArgs());
                } finally {
                    entry.releaseSlot();
                }
            });
        } catch (RuntimeException e) {
            entry.releaseSlot(); // submit 自身异常（如池已关）兜底释放，防槽泄漏
            throw e;
        }
        log.info("异步任务已接受 tool={} target={} taskId={}", tool.name(), dr.target(), task.taskId());
        return asyncAcceptedResponse(task);
    }

    /** 熔断守卫：allowRequest=false（OPEN 未满退避）→ S-ERR-5 结构化错误。 */
    private void guardCircuit(BackendEntry entry, DiagnosticRequest dr) {
        if (!entry.breaker().allowRequest()) {
            log.warn("熔断拒绝（OPEN）target={} retryAfterMs={}", dr.target(), entry.breaker().retryAfterMillis());
            throw backendUnreachableError(entry, dr,
                    "target 熔断中：" + dr.target() + "（连续基础设施失败，退避后探测恢复）");
        }
    }

    /** 失效 target 结构化错误（S-ERR-5）：INVALID_PARAMS + data{target,reason:backend_unreachable,available,retryAfterMs}。 */
    private McpError backendUnreachableError(BackendEntry entry, DiagnosticRequest dr, String message) {
        return McpError.builder(McpErrorCodes.INVALID_PARAMS)
                .message(message)
                .data(Map.of(
                        "target", dr.target(),
                        "reason", "backend_unreachable",
                        "available", List.copyOf(registry.current().names()),
                        "retryAfterMs", entry.breaker().retryAfterMillis()))
                .build();
    }

    private McpError backendUnreachableError(BackendEntry entry, DiagnosticRequest dr, Throwable cause) {
        return backendUnreachableError(entry, dr, "target 不可达：" + dr.target()
                + "（" + cause.getClass().getSimpleName() + ": " + String.valueOf(cause.getMessage()) + "）");
    }

    /** 并发越界结构化错误（C-LIMIT-1）：INVALID_PARAMS + data{target,reason:concurrency_limit,maxConcurrentTasks}。 */
    private McpError concurrencyLimitError(BackendEntry entry, DiagnosticRequest dr) {
        log.warn("并发限流（前置拦截，不越界打后端）target={} maxConcurrentTasks={} availableSlots={}",
                dr.target(), entry.config().maxConcurrentTasks(), entry.availableSlots());
        return McpError.builder(McpErrorCodes.INVALID_PARAMS)
                .message("target 并发已达上限：" + dr.target()
                        + "（maxConcurrentTasks=" + entry.config().maxConcurrentTasks() + "）")
                .data(Map.of(
                        "target", dr.target(),
                        "reason", "concurrency_limit",
                        "maxConcurrentTasks", entry.config().maxConcurrentTasks()))
                .build();
    }

    /** target 在册解算：缺失 → INVALID_PARAMS + data.available（当前可用逻辑名快照）。 */
    private BackendEntry resolveTarget(DiagnosticRequest dr) {
        Optional<BackendEntry> entry = registry.get(dr.target());
        if (entry.isEmpty()) {
            throw McpError.builder(McpErrorCodes.INVALID_PARAMS)
                    .message("target 不在册：" + dr.target() + "（见 list-targets 的可用目标）")
                    .data(Map.of("available", List.copyOf(registry.current().names())))
                    .build();
        }
        return entry.get();
    }

    /**
     * 异步任务的立即接受响应（G-ASYNC-1）：固定 {@code status:"working"}（submit 时即此态，不重读 task 状态，
     * 避免与后台瞬时失败竞态），附 {@code _meta:{toolName,target}} 供调用方关联。
     */
    public static CallToolResult asyncAcceptedResponse(GatewayTask task) {
        Map<String, Object> body = Map.of(
                "taskId", task.taskId(),
                "status", "working",
                "_meta", Map.of("toolName", task.toolName(), "target", task.target()));
        return new CallToolResult(List.of(new TextContent(JSON.writeValueAsString(body))), false, null, null);
    }
}
