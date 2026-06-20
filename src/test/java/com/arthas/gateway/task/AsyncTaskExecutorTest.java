package com.arthas.gateway.task;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T033 {@link AsyncTaskExecutor} 纯逻辑单测（surefire）。
 *
 * <p><b>测试边界</b>（遵循宪法「真实环境、禁止桩」）：仅测执行器的<b>编排契约</b>——submit 立即返 WORKING +
 * 入 TaskStore + taskId 唯一（G-ASYNC-1）、cancel 转 CANCELLED（G-TC-1）、终态幂等（G-TC-2）。
 * 后台对后端的<b>真实完成/失败/isError 透传</b>（真实结果→COMPLETED、真实超时→FAILED）由
 * {@code AsyncTaskTimeoutIT}（T030）与端到端 {@code AsyncTaskContractIT}（T035）以<b>真实 arthas</b> 覆盖。
 *
 * <p><b>阻塞 callable 不是 arthas 桩</b>：测试用 {@code latch.await()} 阻塞的后台 callable 仅为<b>时序占位</b>
 * （不返回任何 arthas 成功响应、不模拟后端结果），用于证明「submit 不等后端、任务停在 WORKING」；
 * 释放后抛异常（不产出任何成功结果）。arthas 真实响应保真度由上述 IT 保证。
 *
 * <p>{@link GatewayTask} 的状态机本身（markCompleted/markFailed 终态不可逆、isError 原样保留）已在
 * {@code GatewayTaskTest}（T031）覆盖，此处不重复。
 */
class AsyncTaskExecutorTest {

    private AsyncTaskExecutor executor;

    @AfterEach
    void closeExecutor() {
        if (executor != null) {
            executor.close();
        }
    }

    /** 阻塞 callable：进入后阻塞在 latch，释放后抛异常（永不产出 arthas 成功结果，非桩）。 */
    private static Callable<CallToolResult> blockingWork(CountDownLatch entered, CountDownLatch block) {
        return () -> {
            entered.countDown();
            block.await(); // 阻塞，模拟「后端在途」
            throw new IllegalStateException("test-only，永不产出成功结果");
        };
    }

    @Test
    void submitReturnsWorkingTaskImmediatelyAndStoresIt() throws Exception {
        TaskStore store = new TaskStore(Duration.ofHours(1), java.time.Instant::now);
        executor = new AsyncTaskExecutor(store, Duration.ofMinutes(11));

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch block = new CountDownLatch(1);

        long start = System.nanoTime();
        GatewayTask task = executor.submit("watch", "order-service",
                blockingWork(entered, block));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(task.status()).as("submit 立即返 WORKING（G-ASYNC-1，不等后端）")
                .isEqualTo(TaskState.WORKING);
        assertThat(elapsedMs).as("submit 不阻塞等后端完成").isLessThan(2000);
        assertThat(task.taskId()).as("taskId 形如 t-<hex>").matches("t-[0-9a-f]{6,}");
        assertThat(task.toolName()).isEqualTo("watch");
        assertThat(task.target()).isEqualTo("order-service");
        assertThat(store.get(task.taskId())).as("任务已入 TaskStore").containsSame(task);

        assertThat(entered.await(2, TimeUnit.SECONDS))
                .as("后台已进入（证明 submit 确实调度了后台）").isTrue();
        assertThat(task.status()).as("后端在途期间任务仍 WORKING").isEqualTo(TaskState.WORKING);

        block.countDown(); // 释放后台（不测完成态）
    }

    @Test
    void submitGeneratesUniqueTaskIds() {
        TaskStore store = new TaskStore(Duration.ofHours(1), java.time.Instant::now);
        executor = new AsyncTaskExecutor(store, Duration.ofMinutes(11));
        CountDownLatch block = new CountDownLatch(1);
        try {
            GatewayTask a = executor.submit("watch", "order", blockingWork(new CountDownLatch(1), block));
            GatewayTask b = executor.submit("trace", "pay", blockingWork(new CountDownLatch(1), block));

            assertThat(a.taskId()).isNotEqualTo(b.taskId());
        } finally {
            block.countDown();
        }
    }

    @Test
    void cancelMarksWorkingTaskCancelled() throws Exception {
        TaskStore store = new TaskStore(Duration.ofHours(1), java.time.Instant::now);
        executor = new AsyncTaskExecutor(store, Duration.ofMinutes(11));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch block = new CountDownLatch(1);
        GatewayTask task = executor.submit("watch", "order", blockingWork(entered, block));
        entered.await(); // 确保后台已进入阻塞

        boolean transitioned = executor.cancel(task);

        assertThat(transitioned).as("WORKING 任务 cancel 成功（G-TC-1）").isTrue();
        assertThat(task.status()).isEqualTo(TaskState.CANCELLED);
        block.countDown();
    }

    @Test
    void cancelOnTerminalTaskIsIdempotent() {
        TaskStore store = new TaskStore(Duration.ofHours(1), java.time.Instant::now);
        executor = new AsyncTaskExecutor(store, Duration.ofMinutes(11));
        CountDownLatch block = new CountDownLatch(1);
        GatewayTask task = executor.submit("watch", "order", blockingWork(new CountDownLatch(1), block));

        // 直接置终态（不经后台返回 arthas 结果，避免桩）；验证 cancel 对终态任务的幂等语义
        assertThat(task.markCancelled()).as("前置：置 CANCELLED").isTrue();

        boolean transitioned = executor.cancel(task);

        assertThat(transitioned).as("终态任务 cancel 幂等返 false（G-TC-2）").isFalse();
        assertThat(task.status()).as("状态不变").isEqualTo(TaskState.CANCELLED);
        block.countDown();
    }

    @Test
    void taskGetOnUnknownReturnsEmptyViaStore() {
        // task-get 未知 taskId 的底层语义：TaskStore.get → empty（执行器持有 store 句柄供 handler 用）
        TaskStore store = new TaskStore(Duration.ofHours(1), java.time.Instant::now);
        executor = new AsyncTaskExecutor(store, Duration.ofMinutes(11));

        assertThat(executor.store().get("t-ghost")).isEmpty();
    }
}
