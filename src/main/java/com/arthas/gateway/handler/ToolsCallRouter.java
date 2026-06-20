package com.arthas.gateway.handler;

import com.arthas.gateway.backend.BackendEntry;
import com.arthas.gateway.backend.BackendUnreachableException;
import com.arthas.gateway.backend.CircuitOpenException;
import com.arthas.gateway.backend.ConcurrencyLimitException;
import com.arthas.gateway.backend.RegistryHolder;
import com.arthas.gateway.backend.StatelessAsyncException;
import com.arthas.gateway.task.AsyncTaskExecutor;
import com.arthas.gateway.task.GatewayTask;
import com.arthas.gateway.task.GlobalConcurrencyLimitException;
import com.arthas.gateway.tool.ExposedTool;
import com.arthas.gateway.tool.RoutingMode;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
 * <h3>路由分流（002 整改：两路径变薄，熔断/槽/分类下沉 {@link BackendEntry} 统一拦截层）</h3>
 * <ul>
 *   <li><b>SYNC_DIRECT / STREAM_AGGREGATE</b>（26 即时 + dashboard）：{@code entry.execute(tool, args)}（admit→invoke→releaseSlot），
 *       结果<b>原样透传</b>（含 {@code isError=true}、后端 JSON-RPC error——后者由 invoke 内 SDK 抛 {@link McpError} 透传，S-ERR-4）。</li>
 *   <li><b>ASYNC_TASK</b>（5 optional：watch/trace/stack/tt/monitor）：{@code entry.admit(tool)}（熔断读 + 取槽；US3 加 STATELESS 校验）
 *       → {@link AsyncTaskExecutor#submit} 后台虚拟线程对后端发<b>同步</b> {@code tools/call}（闭包 {@code entry.invoke}，含 initialize CAS + 故障分类，
 *       P1-3），{@code onTerminal=entry::releaseSlot} 在任一终态/提交失败释放槽（P0-2），立即返 {@link #asyncAcceptedResponse}（G-ASYNC-1）。</li>
 *   <li><b>GATEWAY_LOCAL</b>（4 自有：list-targets/task-*）：委托 {@link GatewayToolHandlers}。</li>
 * </ul>
 *
 * <h3>错误翻译（域异常 → 结构化 {@code McpError}）</h3>
 * <p>{@code execute}/{@code admit}/{@code invoke} 抛<b>域异常</b>（不依赖 McpError/注册表），路由器翻译为结构化 {@code McpError}，
 * 字段集与修复前<b>逐字一致</b>（FR-016）：
 * <ul>
 *   <li>{@link CircuitOpenException} → {@code backend_unreachable}，data 含 {@code retryAfterMs}（来自异常）+ {@code available}。</li>
 *   <li>{@link BackendUnreachableException} → {@code backend_unreachable}，data 含 {@code retryAfterMs}（熔断器读）+ {@code available}。</li>
 *   <li>{@link ConcurrencyLimitException} → INVALID_PARAMS，data 含 {@code maxConcurrentTasks}（来自异常）。</li>
 *   <li>{@link StatelessAsyncException} → INVALID_PARAMS，data 含 {@code reason=stateless_unsupported_async}（仅异步路径）。</li>
 *   <li>{@link GlobalConcurrencyLimitException} → INVALID_PARAMS，data 含 {@code reason=global_concurrency_limit} + {@code globalMaxInflight}（跨 target 累计上限）。</li>
 *   <li>后端业务错误 {@link McpError} <b>原样</b>向上抛（不计熔断，C-CB-2；invoke 内已 recordSuccess）。</li>
 * </ul>
 */
@Component
public class ToolsCallRouter {

    private static final Logger log = LoggerFactory.getLogger(ToolsCallRouter.class);

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

    /**
     * 同步转发到 target 后端：{@code entry.execute}（统一拦截层 admit→invoke→releaseSlot），结果原样透传。
     * 域异常翻译为结构化 McpError（字段逐字保持）；后端业务错误 McpError 原样向上抛。
     */
    private CallToolResult forwardSync(ExposedTool tool, DiagnosticRequest dr) {
        BackendEntry entry = resolveTarget(dr); // 不在册 → INVALID_PARAMS + data.available（S-ERR-2）
        long start = System.nanoTime();
        try {
            CallToolResult result = entry.execute(tool.name(), dr.backendArgs());
            log.info("工具调用完成 tool={} target={} isError={} 耗时={}ms",
                    tool.name(), dr.target(), result.isError(), Duration.ofNanos(System.nanoTime() - start).toMillis());
            return result;
        } catch (CircuitOpenException e) {
            log.warn("熔断拒绝（OPEN）target={} retryAfterMs={}", dr.target(), e.retryAfterMs());
            throw backendUnreachableError(dr, e.retryAfterMs(),
                    "target 熔断中：" + dr.target() + "（连续基础设施失败，退避后探测恢复）");
        } catch (ConcurrencyLimitException e) {
            throw concurrencyLimitError(entry, dr, e.maxConcurrentTasks());
        } catch (BackendUnreachableException e) {
            // invoke 已 recordFailure（C-CB-1）；retryAfterMs 读熔断器（达阈值 OPEN 时 >0，与修复前一致）
            log.warn("基础设施故障（计入熔断）tool={} target={} 耗时={}ms {}",
                    tool.name(), dr.target(), Duration.ofNanos(System.nanoTime() - start).toMillis(), e.toString());
            throw backendUnreachableError(dr, entry.breaker().retryAfterMillis(),
                    "target 不可达：" + dr.target() + "（" + describeCause(e) + "）");
        }
        // McpError（后端业务错误）不经此 catch，原样向上抛（invoke 内已 recordSuccess，C-CB-2）
    }

    /** ASYNC_TASK：解算 target → admit 准入 → 提交后台异步任务（闭包 invoke + onTerminal 释放槽）→ 立即返 working（G-ASYNC-1）。 */
    private CallToolResult submitAsync(ExposedTool tool, DiagnosticRequest dr) {
        BackendEntry entry = resolveTarget(dr);
        try {
            entry.admit(tool.name()); // STATELESS 校验（仅异步）+ 熔断读 + 取槽；失败抛域异常
        } catch (StatelessAsyncException e) {
            log.warn("STATELESS 后端拒绝异步调用（前置，不提交后台）target={}", dr.target());
            throw statelessAsyncError(dr);
        } catch (CircuitOpenException e) {
            log.warn("熔断拒绝（OPEN）target={} retryAfterMs={}", dr.target(), e.retryAfterMs());
            throw backendUnreachableError(dr, e.retryAfterMs(),
                    "target 熔断中：" + dr.target() + "（连续基础设施失败，退避后探测恢复）");
        } catch (ConcurrencyLimitException e) {
            throw concurrencyLimitError(entry, dr, e.maxConcurrentTasks());
        }
        // entry 固定引用（热重载不串台）；slot 由 onTerminal（entry::releaseSlot）在任务终态/提交失败时释放——
        // 5 个在途 watch 持 5 槽，第 6 个 admit 取槽失败被前置限流。闭包 invoke 含 initialize + 故障分类 +
        // 熔断驱动（P1-3：异步也驱动熔断；取消中断不计，由 invoke 检测 Thread.interrupted 跳过 recordFailure）。
        GatewayTask task;
        try {
            task = asyncExecutor.submit(tool.name(), dr.target(),
                    () -> entry.invoke(tool.name(), dr.backendArgs()),
                    entry::releaseSlot);
        } catch (GlobalConcurrencyLimitException e) {
            // submit 全局背压拒绝(未创建任务/未提交后台):admit 已取的 per-target 槽须显式释放(P0-2 不漏槽),
            // 全局计数已在 submit 内回滚(无泄漏)。
            entry.releaseSlot();
            log.warn("全局并发限流（跨 target 累计）target={} globalMaxInflight={} availableSlots={}",
                    dr.target(), e.globalMaxInflight(), entry.availableSlots());
            throw globalConcurrencyLimitError(dr, e.globalMaxInflight());
        }
        log.info("异步任务已接受 tool={} target={} taskId={}", tool.name(), dr.target(), task.taskId());
        return asyncAcceptedResponse(task);
    }

    /** {@link BackendUnreachableException} cause 的可读描述（供错误消息，不含敏感信息）。 */
    private static String describeCause(BackendUnreachableException e) {
        Throwable c = e.getCause();
        if (c == null) {
            return "unknown";
        }
        return c.getClass().getSimpleName() + ": " + String.valueOf(c.getMessage());
    }

    /** 失效 target 结构化错误（S-ERR-5）：INVALID_PARAMS + data{target,reason:backend_unreachable,available,retryAfterMs}。 */
    private McpError backendUnreachableError(DiagnosticRequest dr, long retryAfterMs, String message) {
        return McpError.builder(McpErrorCodes.INVALID_PARAMS)
                .message(message)
                .data(Map.of(
                        "target", dr.target(),
                        "reason", "backend_unreachable",
                        "available", List.copyOf(registry.current().names()),
                        "retryAfterMs", retryAfterMs))
                .build();
    }

    /**
     * STATELESS 异步前置拒绝错误（P1-2/原理四双侧契约）：INVALID_PARAMS + data{target,reason:stateless_unsupported_async,available}。
     *
     * <p>无状态后端无法承载带任务语义、需轮询的异步诊断调用（watch/trace/stack/tt/monitor）——前置返明确错误，
     * 不提交后台、不耗兜底超时。同步调用不受影响（不经此校验）。
     */
    private McpError statelessAsyncError(DiagnosticRequest dr) {
        return McpError.builder(McpErrorCodes.INVALID_PARAMS)
                .message("target 协议为 STATELESS，不支持异步诊断任务：" + dr.target()
                        + "（仅同步类工具可用；异步类 watch/trace/stack/tt/monitor 需 STREAMABLE 后端）")
                .data(Map.of(
                        "target", dr.target(),
                        "reason", "stateless_unsupported_async",
                        "available", List.copyOf(registry.current().names())))
                .build();
    }

    /**
     * 全局并发限流结构化错误(P2-4/FR-010):INVALID_PARAMS + data{target,reason:global_concurrency_limit,globalMaxInflight,available}。
     *
     * <p>跨 target 累计在途异步任务超全局上限(配置或动态默认=后端数×5)——防集群触顶(虚拟线程虽廉价,后端总数有限)。
     */
    private McpError globalConcurrencyLimitError(DiagnosticRequest dr, int globalMaxInflight) {
        return McpError.builder(McpErrorCodes.INVALID_PARAMS)
                .message("全局在途异步任务已达上限（globalMaxInflight=" + globalMaxInflight
                        + "，跨 target 累计）；稍后重试或减少并发异步诊断。target=" + dr.target())
                .data(Map.of(
                        "target", dr.target(),
                        "reason", "global_concurrency_limit",
                        "globalMaxInflight", globalMaxInflight,
                        "available", List.copyOf(registry.current().names())))
                .build();
    }

    /** 并发越界结构化错误（C-LIMIT-1）：INVALID_PARAMS + data{target,reason:concurrency_limit,maxConcurrentTasks}。 */
    private McpError concurrencyLimitError(BackendEntry entry, DiagnosticRequest dr, int maxConcurrentTasks) {
        log.warn("并发限流（前置拦截，不越界打后端）target={} maxConcurrentTasks={} availableSlots={}",
                dr.target(), maxConcurrentTasks, entry.availableSlots());
        return McpError.builder(McpErrorCodes.INVALID_PARAMS)
                .message("target 并发已达上限：" + dr.target()
                        + "（maxConcurrentTasks=" + maxConcurrentTasks + "）")
                .data(Map.of(
                        "target", dr.target(),
                        "reason", "concurrency_limit",
                        "maxConcurrentTasks", maxConcurrentTasks))
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
        return McpJson.json(body); // 委托 McpJson 单例(T025/FR-013)
    }
}
