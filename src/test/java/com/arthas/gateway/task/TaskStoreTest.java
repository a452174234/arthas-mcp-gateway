package com.arthas.gateway.task;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T032 {@link TaskStore} 单测（surefire，纯逻辑，注入时钟测 TTL 确定性）。
 *
 * <p>覆盖内存 Map + TTL 清理的核心不变量（data-model.md §6「TTL 清理」、gateway-tools-contract.md §3 task-list）：
 * <ul>
 *   <li>put/get/list：存取、未知→empty、全量、status 过滤。</li>
 *   <li>同 taskId 覆盖。</li>
 *   <li>TTL：终态任务（completedAt 非空）超过 ttl 被移除；WORKING 任务（completedAt=null）不受 TTL 影响。</li>
 *   <li>惰性清理：get/list 访问时自动清理过期任务；{@link TaskStore#cleanExpired()} 显式清理。</li>
 * </ul>
 */
class TaskStoreTest {

    private TaskStore store;

    @AfterEach
    void closeStore() {
        if (store != null) {
            store.close();
        }
    }

    private static CallToolResult result(String text) {
        return new CallToolResult(List.of(new TextContent(text)), false, null, null);
    }

    @Test
    void putAndGetReturnsTask() {
        store = new TaskStore(Duration.ofHours(1), Instant::now);
        GatewayTask t = new GatewayTask("t-1", "watch", "order", Instant.now(), Instant::now);
        store.put(t);

        assertThat(store.get("t-1")).containsSame(t);
    }

    @Test
    void getUnknownReturnsEmpty() {
        store = new TaskStore(Duration.ofHours(1), Instant::now);
        assertThat(store.get("ghost")).isEmpty();
    }

    @Test
    void listReturnsAllTasks() {
        store = new TaskStore(Duration.ofHours(1), Instant::now);
        GatewayTask a = new GatewayTask("t-a", "watch", "order", Instant.now(), Instant::now);
        GatewayTask b = new GatewayTask("t-b", "trace", "pay", Instant.now(), Instant::now);
        store.put(a);
        store.put(b);

        List<GatewayTask> all = store.list();
        assertThat(all).hasSize(2).containsExactlyInAnyOrder(a, b);
    }

    @Test
    void listWithStatusFilter() {
        store = new TaskStore(Duration.ofHours(1), Instant::now);
        GatewayTask working = new GatewayTask("t-w", "watch", "order", Instant.now(), Instant::now);
        GatewayTask completed = new GatewayTask("t-c", "trace", "pay", Instant.now(), Instant::now);
        completed.markCompleted(result("done"));
        store.put(working);
        store.put(completed);

        assertThat(store.list(TaskState.WORKING)).containsExactly(working);
        assertThat(store.list(TaskState.COMPLETED)).containsExactly(completed);
    }

    @Test
    void putWithSameTaskIdOverwrites() {
        store = new TaskStore(Duration.ofHours(1), Instant::now);
        GatewayTask first = new GatewayTask("t-1", "watch", "order", Instant.now(), Instant::now);
        GatewayTask second = new GatewayTask("t-1", "trace", "pay", Instant.now(), Instant::now);
        store.put(first);
        store.put(second);

        assertThat(store.get("t-1")).containsSame(second);
    }

    @Test
    void cleanExpiredEvictsTerminalTasksBeyondTtl() {
        Instant t0 = Instant.parse("2026-06-20T10:00:00Z");
        AtomicReference<Instant> clock = new AtomicReference<>(t0);
        store = new TaskStore(Duration.ofMinutes(60), clock::get);

        GatewayTask done = new GatewayTask("t-done", "watch", "order", t0, clock::get);
        done.markCompleted(result("ok")); // completedAt = t0
        store.put(done);

        assertThat(store.get("t-done")).as("未过期（now=t0）仍可见").isPresent();
        clock.set(t0.plus(Duration.ofMinutes(61))); // 超过 60min TTL

        int removed = store.cleanExpired();
        assertThat(removed).as("移除 1 条过期终态任务").isEqualTo(1);
        assertThat(store.get("t-done")).as("过期终态任务被移除").isEmpty();
    }

    @Test
    void cleanExpiredKeepsWorkingTasksRegardlessOfAge() {
        Instant t0 = Instant.parse("2026-06-20T10:00:00Z");
        AtomicReference<Instant> clock = new AtomicReference<>(t0);
        store = new TaskStore(Duration.ofMinutes(1), clock::get);

        GatewayTask working = new GatewayTask("t-w", "watch", "order", t0, clock::get);
        store.put(working);

        clock.set(t0.plus(Duration.ofDays(1))); // 远超 TTL，但 WORKING（completedAt=null）不参与 TTL
        store.cleanExpired();

        assertThat(store.get("t-w")).as("WORKING 任务不受 TTL 移除").isPresent();
    }

    @Test
    void cleanExpiredKeepsRecentTerminalTasks() {
        Instant t0 = Instant.parse("2026-06-20T10:00:00Z");
        AtomicReference<Instant> clock = new AtomicReference<>(t0);
        store = new TaskStore(Duration.ofMinutes(60), clock::get);

        GatewayTask done = new GatewayTask("t-done", "watch", "order", t0, clock::get);
        done.markCompleted(result("ok"));
        store.put(done);
        clock.set(t0.plus(Duration.ofMinutes(30))); // 仍在 TTL 窗口内

        assertThat(store.cleanExpired()).as("未超 TTL 不移除").isEqualTo(0);
        assertThat(store.get("t-done")).isPresent();
    }

    @Test
    void getTriggersLazyCleanup() {
        Instant t0 = Instant.parse("2026-06-20T10:00:00Z");
        AtomicReference<Instant> clock = new AtomicReference<>(t0);
        store = new TaskStore(Duration.ofMinutes(60), clock::get);

        GatewayTask done = new GatewayTask("t-done", "watch", "order", t0, clock::get);
        done.markCompleted(result("ok"));
        store.put(done);
        clock.set(t0.plus(Duration.ofMinutes(61)));

        assertThat(store.get("t-done")).as("get 触发惰性清理，过期任务对读不可见").isEmpty();
    }
}
