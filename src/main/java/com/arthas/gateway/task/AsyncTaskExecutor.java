package com.arthas.gateway.task;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 异步任务执行器（方案 C，data-model.md §6 / gateway-tools-contract.md §5，T033）。
 *
 * <p>对 5 个 optional 工具（watch/trace/stack/tt/monitor）：submit <b>立即</b>创建 WORKING 任务并返回
 * （G-ASYNC-1），后台虚拟线程对后端发<b>同步</b> {@code tools/call}（<b>不带</b> task 字段，走后端自动轮询路①，
 * 参考后端接入契约 §5.b），阻塞兜底至 {@code backendTimeout}（默认 11min，&gt; 后端 10min 上限）。
 *
 * <h3>后台终态映射</h3>
 * <ul>
 *   <li>正常返回（含 {@code isError=true} 的业务错误）→ {@link GatewayTask#markCompleted} 原样保留（G-TG-2）。</li>
 *   <li>超时（兜底超时）→ {@code markFailed(BACKEND_TIMEOUT)} 并中断后台。</li>
 *   <li>基础设施异常（连接拒绝/IO/initialize 失败等）→ {@code markFailed(BACKEND_UNREACHABLE)}。</li>
 *   <li>cancel 中断 → {@code markCancelled}（G-TC-1）。</li>
 * </ul>
 *
 * <h3>设计与可测性</h3>
 * <ul>
 *   <li><b>DIP 缝</b>：{@code submit} 接受注入的 {@link Callable}（{@code backendWork}）——生产由路由层
 *       （T034）闭包真实 {@code entry.client().callTool}；测试注入阻塞 callable 测编排契约（非 arthas 桩，
 *       见 {@code AsyncTaskExecutorTest} 类注释）。后端真实响应保真度由 IT（T030/T035）覆盖。</li>
 *   <li><b>超时实现</b>：单虚拟线程池嵌套 submit——外层 supervisor 线程 {@code worker.get(timeout)} 等待
 *       内层 worker 的后端调用；超时/取消时 {@code worker.cancel(true)} 中断后端调用。</li>
 *   <li><b>taskId 生成</b>：{@code "t-" + 6 hex}（SecureRandom），注入 {@code Supplier} 便于确定性测试。</li>
 *   <li><b>cancel 跟踪</b>：{@code taskId → supervisorFuture} 映射，cancel 时中断 supervisor。</li>
 * </ul>
 *
 * <p><b>未覆盖（Phase 5 US3）</b>：per-target 限流（{@code entry.tryAcquireSlot}）与熔断（{@code breaker}）
 * ——当前 submit 不接；Phase 5 在路由层闭包内补 acquire/breaker（C-LIMIT-1、C-CB-1）。
 */
public final class AsyncTaskExecutor {

    private final TaskStore store;
    private final Duration callTimeout;
    private final Supplier<Instant> clock;
    private final Supplier<String> taskIdGenerator;
    private final ExecutorService pool;
    private final ConcurrentHashMap<String, Future<?>> supervisorFutures = new ConcurrentHashMap<>();

    /**
     * 全参构造（测试用：注入时钟与 taskId 生成器以确定性）。
     *
     * @param store           任务存储
     * @param callTimeout     后台阻塞兜底超时（11min）
     * @param clock           时间源（任务 createdAt / completedAt）
     * @param taskIdGenerator taskId 生成器（{@code "t-<hex>"}）
     */
    public AsyncTaskExecutor(TaskStore store, Duration callTimeout,
                             Supplier<Instant> clock, Supplier<String> taskIdGenerator) {
        this.store = Objects.requireNonNull(store, "store 不可为空");
        this.callTimeout = requirePositive(callTimeout, "callTimeout");
        this.clock = Objects.requireNonNull(clock, "clock 不可为空");
        this.taskIdGenerator = Objects.requireNonNull(taskIdGenerator, "taskIdGenerator 不可为空");
        this.pool = Executors.newVirtualThreadPerTaskExecutor();
    }

    /** 生产便利构造：默认真实时钟 + 随机 taskId。 */
    public AsyncTaskExecutor(TaskStore store, Duration callTimeout) {
        this(store, callTimeout, Instant::now, defaultTaskIdGenerator());
    }

    /**
     * 提交一个异步任务：立即创建 WORKING 任务并返回，后台虚拟线程执行 {@code backendWork}。
     *
     * @param toolName   工具名（watch/trace/stack/tt/monitor）
     * @param target     目标 JVM 逻辑名
     * @param backendWork 后端同步调用（路由层闭包 {@code entry.client().callTool}）；返回结果原样存 completed
     * @return 已入存储的 WORKING 任务（taskId 已分配）
     */
    public GatewayTask submit(String toolName, String target, Callable<CallToolResult> backendWork) {
        Objects.requireNonNull(toolName, "toolName 不可为空");
        Objects.requireNonNull(target, "target 不可为空");
        Objects.requireNonNull(backendWork, "backendWork 不可为空");

        String taskId = taskIdGenerator.get();
        GatewayTask task = new GatewayTask(taskId, toolName, target, clock.get(), clock);
        store.put(task);

        Future<?> supervisor = pool.submit(() -> orchestrate(task, backendWork));
        supervisorFutures.put(taskId, supervisor);
        return task;
    }

    /** 后台编排：submit 后台调用 + 兜底超时 + 终态映射。 */
    private void orchestrate(GatewayTask task, Callable<CallToolResult> backendWork) {
        Future<CallToolResult> worker = pool.submit(backendWork);
        try {
            CallToolResult result = worker.get(callTimeout.toMillis(), TimeUnit.MILLISECONDS);
            task.markCompleted(result); // isError=true 原样保留（G-TG-2）；终态返 false（幂等）
        } catch (TimeoutException te) {
            worker.cancel(true);
            task.markFailed(new TaskError(
                    TaskError.REASON_BACKEND_TIMEOUT,
                    "后端 " + callTimeout + " 内未返回（兜底超时）"));
        } catch (ExecutionException ee) {
            worker.cancel(true);
            task.markFailed(toTaskError(ee.getCause()));
        } catch (InterruptedException ie) {
            // cancel 触发的中断：中断后台 worker，标 cancelled（若已终态则幂等返 false）
            Thread.currentThread().interrupt();
            worker.cancel(true);
            task.markCancelled();
        } finally {
            supervisorFutures.remove(task.taskId());
        }
    }

    /**
     * 取消任务：标 CANCELLED 并中断后台。仅 WORKING 时生效（G-TC-1）；终态任务返 false（G-TC-2 幂等）。
     *
     * @return true=本次 WORKING→CANCELLED；false=已终态（未转换）
     */
    public boolean cancel(GatewayTask task) {
        Objects.requireNonNull(task, "task 不可为空");
        boolean transitioned = task.markCancelled();
        if (transitioned) {
            Future<?> supervisor = supervisorFutures.get(task.taskId());
            if (supervisor != null) {
                supervisor.cancel(true); // 中断 supervisor → orchestrate 捕获 InterruptedException
            }
        }
        return transitioned;
    }

    /** 暴露 TaskStore 供 task-get/task-list handler 查询。 */
    public TaskStore store() {
        return store;
    }

    /** 关闭后台线程池（Spring 容器关闭时调用）。 */
    public void close() {
        pool.shutdownNow();
    }

    /** 异常 → TaskError：基础设施故障统一归 backend_unreachable（Phase 5 可细化熔断 reason）。 */
    private static TaskError toTaskError(Throwable cause) {
        Throwable c = cause != null ? cause : new RuntimeException("unknown");
        return new TaskError(TaskError.REASON_BACKEND_UNREACHABLE, c.getClass().getSimpleName() + ": " + c.getMessage());
    }

    private static Duration requirePositive(Duration d, String name) {
        Objects.requireNonNull(d, name + " 不可为空");
        if (d.isNegative() || d.isZero()) {
            throw new IllegalArgumentException(name + " 必须为正");
        }
        return d;
    }

    /** 默认 taskId 生成：{@code "t-" + 6 hex}（SecureRandom，约 1600 万空间，MVP 单后端足够）。 */
    private static Supplier<String> defaultTaskIdGenerator() {
        SecureRandom rng = new SecureRandom();
        HexFormat hex = HexFormat.of();
        return () -> {
            byte[] b = new byte[3];
            rng.nextBytes(b);
            return "t-" + hex.formatHex(b);
        };
    }
}
