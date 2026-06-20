package com.arthas.gateway.task;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T006 {@link AsyncTaskExecutor} 关闭竞态单测（002 整改 · P0-1/P0-2，US1）。
 *
 * <p>验证 {@code onTerminal} 在<b>所有终态路径</b>恰好调用一次，且外层提交被拒时<b>不留僵尸 WORKING</b>：
 * <ul>
 *   <li>(a) 外层 {@code pool.submit} 被拒（池已关）→ store 无残留 + onTerminal 一次（释放槽/背压，P0-2）。</li>
 *   <li>(b) 内层 {@code pool.submit(work)} 被拒 → 任务标 FAILED（无僵尸，P0-1）+ onTerminal 一次。</li>
 *   <li>(c) 正常完成 → onTerminal 一次。</li>
 *   <li>(d) cancel → onTerminal 一次。</li>
 * </ul>
 *
 * <p><b>真实性</b>：用<b>真实 {@code ExecutorService}</b>——(a) 用默认虚拟线程池 {@code shutdownNow} 后 submit 触发<b>真实
 * {@code RejectedExecutionException}</b>；(b) 用受控 {@code AbstractExecutorService}（真实线程执行 + 第 2 次 execute 真实抛
 * RejectedExecutionException），非 arthas 桩、非 mock。{@code onTerminal} 仅计数（真实回调，非桩语义）。
 */
class AsyncTaskExecutorShutdownRaceTest {

    private AsyncTaskExecutor executor;

    @AfterEach
    void closeExecutor() {
        if (executor != null) {
            executor.close();
        }
    }

    private static final Supplier<Instant> CLOCK = Instant::now;
    private static final Callable<CallToolResult> OK_WORK =
            () -> new CallToolResult(List.of(new TextContent("ok")), false, null, null);

    /** (a) 外层提交被拒（池已关）→ store 无僵尸 + onTerminal 恰好一次。 */
    @Test
    void outerSubmitRejected_leavesNoZombieAndInvokesOnTerminalOnce() {
        TaskStore store = new TaskStore(Duration.ofHours(1), CLOCK);
        executor = new AsyncTaskExecutor(store, Duration.ofMinutes(11), CLOCK, () -> "t-outer");
        executor.close(); // 关闭真实池 → 后续 submit 触发真实 RejectedExecutionException

        AtomicInteger onTerminal = new AtomicInteger();
        assertThatThrownBy(() -> executor.submit("watch", "order", OK_WORK, onTerminal::incrementAndGet))
                .isInstanceOf(RejectedExecutionException.class);

        assertThat(store.list()).as("外层拒绝后 store 无残留 WORKING（P0-1 无僵尸）").isEmpty();
        assertThat(store.get("t-outer")).as("僵尸任务已 remove").isEmpty();
        assertThat(onTerminal.get()).as("onTerminal 恰好一次（释放槽/背压，P0-2）").isEqualTo(1);
    }

    /** (b) 内层 work 提交被拒 → 任务标 FAILED（无僵尸）+ onTerminal 一次。 */
    @Test
    void innerSubmitRejected_marksFailedAndInvokesOnTerminalOnce() throws Exception {
        TaskStore store = new TaskStore(Duration.ofHours(1), CLOCK);
        AtomicInteger execCount = new AtomicInteger();
        // 受控真实执行器：第 1 次 execute 跑 supervisor，第 2 次（内层 work）真实抛 RejectedExecutionException
        ExecutorService innerRejectingPool = new AbstractExecutorService() {
            @Override
            public void execute(Runnable command) {
                if (execCount.incrementAndGet() == 2) {
                    throw new RejectedExecutionException("inner work submit rejected (test)");
                }
                Thread.ofVirtual().start(command); // 真实执行 supervisor
            }

            @Override
            public void shutdown() {
                // no-op
            }

            @Override
            public List<Runnable> shutdownNow() {
                return List.of();
            }

            @Override
            public boolean isShutdown() {
                return false;
            }

            @Override
            public boolean isTerminated() {
                return false;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return true;
            }
        };
        executor = new AsyncTaskExecutor(store, Duration.ofMinutes(11), CLOCK, () -> "t-inner", innerRejectingPool);

        AtomicInteger onTerminal = new AtomicInteger();
        GatewayTask task = executor.submit("watch", "order", OK_WORK, onTerminal::incrementAndGet);

        awaitOnTerminal(onTerminal, 1);
        assertThat(task.status()).as("内层拒绝 → markFailed（P0-1 无僵尸）").isEqualTo(TaskState.FAILED);
        assertThat(task.error()).isNotNull();
        assertThat(task.error().reason()).isEqualTo(TaskError.REASON_BACKEND_UNREACHABLE);
        assertThat(onTerminal.get()).as("onTerminal 恰好一次（释放槽，P0-2）").isEqualTo(1);
    }

    /** (c) 正常完成 → onTerminal 恰好一次。 */
    @Test
    void normalCompletionInvokesOnTerminalOnce() throws Exception {
        TaskStore store = new TaskStore(Duration.ofHours(1), CLOCK);
        executor = new AsyncTaskExecutor(store, Duration.ofMinutes(11), CLOCK, () -> "t-ok");

        AtomicInteger onTerminal = new AtomicInteger();
        GatewayTask task = executor.submit("watch", "order", OK_WORK, onTerminal::incrementAndGet);

        awaitOnTerminal(onTerminal, 1);
        assertThat(task.status()).isEqualTo(TaskState.COMPLETED);
        assertThat(onTerminal.get()).as("正常完成 onTerminal 一次").isEqualTo(1);
    }

    /** (d) cancel → onTerminal 恰好一次。 */
    @Test
    void cancelInvokesOnTerminalOnce() throws Exception {
        TaskStore store = new TaskStore(Duration.ofHours(1), CLOCK);
        executor = new AsyncTaskExecutor(store, Duration.ofMinutes(11), CLOCK, () -> "t-cancel");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch block = new CountDownLatch(1);

        AtomicInteger onTerminal = new AtomicInteger();
        GatewayTask task = executor.submit("watch", "order", blockingWork(entered, block), onTerminal::incrementAndGet);
        entered.await();

        assertThat(executor.cancel(task)).isTrue();
        block.countDown();
        awaitOnTerminal(onTerminal, 1);

        assertThat(task.status()).isEqualTo(TaskState.CANCELLED);
        assertThat(onTerminal.get()).as("cancel 后 onTerminal 一次（释放槽）").isEqualTo(1);
    }

    /** 阻塞 callable：进入后阻塞 latch（时序占位，非 arthas 成功桩）。 */
    private static Callable<CallToolResult> blockingWork(CountDownLatch entered, CountDownLatch block) {
        return () -> {
            entered.countDown();
            try {
                block.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("test-only，被 cancel 中断");
        };
    }

    /**
     * 轮询等待 onTerminal 达到期望次数（最多 5s）。
     *
     * <p>注意：onTerminal 在 orchestrate 的 finally 执行，<b>晚于</b> task 终态标记（markCompleted/Failed/Cancelled
     * 在 catch/try 中先设）。故须直接 await onTerminal 计数，而非仅等 task.isTerminal（避免终态已置但 finally 未到的竞态）。
     */
    private static void awaitOnTerminal(AtomicInteger onTerminal, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (onTerminal.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        if (onTerminal.get() < expected) {
            throw new AssertionError("onTerminal 未在 5s 内达到 " + expected + "，当前 " + onTerminal.get());
        }
    }
}
