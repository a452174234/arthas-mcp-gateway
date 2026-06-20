package com.arthas.gateway.task;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 异步诊断任务实体（方案 C，data-model.md §6 / gateway-tools-contract.md §5，T031）。
 *
 * <p>由 5 个 optional 工具（watch/trace/stack/tt/monitor）经 {@code AsyncTaskExecutor} 提交创建，
 * 初始 {@link TaskState#WORKING}；后台虚拟线程对后端发同步 {@code tools/call}（阻塞兜底），完成后转换终态。
 *
 * <p><b>线程安全</b>：后台写线程（markCompleted/markFailed）与 task-get/task-cancel 读线程并发，
 * 故转换方法 {@code synchronized}（原子终态检查 + 赋值）；可变字段 {@code volatile} 保证读可见性。
 *
 * <p><b>终态不可逆</b>：转换方法仅在 WORKING 时生效并返 true；已终态则返 false（不覆盖）——
 * 兼容 task-cancel 与后台迟到完成的竞争（先到者赢，后者返 false）。
 *
 * <p><b>G-TG-2</b>：后端 {@code isError=true} 是正常业务响应，经 {@link #markCompleted} 原样保留
 * （status=COMPLETED、result.isError=true），<b>不</b>转 failed——failed 仅用于基础设施故障。
 */
public final class GatewayTask {

    private final String taskId;
    private final String toolName;
    private final String target;
    private final Instant createdAt;
    private final Supplier<Instant> clock;

    private volatile TaskState status = TaskState.WORKING;
    private volatile CallToolResult result;
    private volatile TaskError error;
    private volatile Instant completedAt;

    /**
     * 构造一个 WORKING 任务。
     *
     * @param taskId    任务 id（如 {@code t-7f3a9c}）
     * @param toolName  触发工具名（watch/trace/stack/tt/monitor）
     * @param target    目标 JVM 逻辑名
     * @param createdAt 创建时间（注入便于测试确定性）
     * @param clock     终态时间源（注入便于 {@code TaskStore} TTL 测试确定性；生产传 {@code Instant::now}）
     */
    public GatewayTask(String taskId, String toolName, String target, Instant createdAt, Supplier<Instant> clock) {
        this.taskId = Objects.requireNonNull(taskId, "taskId 不可为空");
        this.toolName = Objects.requireNonNull(toolName, "toolName 不可为空");
        this.target = Objects.requireNonNull(target, "target 不可为空");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt 不可为空");
        this.clock = Objects.requireNonNull(clock, "clock 不可为空");
    }

    /** 标记成功完成：原样保留后端结果（含 isError=true，G-TG-2）。终态后返 false。 */
    public synchronized boolean markCompleted(CallToolResult result) {
        if (status != TaskState.WORKING) {
            return false;
        }
        this.result = Objects.requireNonNull(result, "result 不可为空");
        this.completedAt = clock.get();
        this.status = TaskState.COMPLETED;
        return true;
    }

    /** 标记基础设施失败（超时/不可达/熔断）。终态后返 false。 */
    public synchronized boolean markFailed(TaskError error) {
        if (status != TaskState.WORKING) {
            return false;
        }
        this.error = Objects.requireNonNull(error, "error 不可为空");
        this.completedAt = clock.get();
        this.status = TaskState.FAILED;
        return true;
    }

    /** 标记取消（task-cancel）。终态后返 false（幂等由调用方据返回值处理）。 */
    public synchronized boolean markCancelled() {
        if (status != TaskState.WORKING) {
            return false;
        }
        this.completedAt = clock.get();
        this.status = TaskState.CANCELLED;
        return true;
    }

    public String taskId() {
        return taskId;
    }

    public String toolName() {
        return toolName;
    }

    public String target() {
        return target;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public TaskState status() {
        return status;
    }

    /** 后端结果（仅 COMPLETED 时非空；含 isError 原样）。 */
    public CallToolResult result() {
        return result;
    }

    /** 失败原因（仅 FAILED 时非空）。 */
    public TaskError error() {
        return error;
    }

    /** 进入终态的时间（非 WORKING 时非空）。 */
    public Instant completedAt() {
        return completedAt;
    }

    /** 是否终态（非 WORKING）。 */
    public boolean isTerminal() {
        return status.isTerminal();
    }
}
