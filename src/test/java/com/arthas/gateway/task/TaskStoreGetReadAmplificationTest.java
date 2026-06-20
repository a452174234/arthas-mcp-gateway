package com.arthas.gateway.task;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T015 {@link TaskStore#get} 读放大单测(002 整改 · P2-3/FR-009,US4)。
 *
 * <p>修复前 {@code get(taskId)} 每次调全表 {@code cleanExpired()};在大规模终态任务(如 10k)下,
 * 逐条 task-get 会产生 O(N) 读放大(轮询 N 个任务 = O(N²))。修复后 {@code get} 仅判定<b>单条</b>:
 * 存在且未过期→返回;过期→{@code remove(taskId)} 返 empty;不存在→返 empty。<b>不</b>触发全表清理
 * (全表清理仅 {@code list} + 后台 cleaner 负责)。
 *
 * <p><b>断言手段</b>:用包级探针 {@link TaskStore#containsRawForTest(String)}(不经清理的内部存储探测)
 * 证明 {@code get(其它任务)} 不会顺带移除过期的<b>兄弟</b>任务(全表清理才会;单条 O(1) 不会)。
 */
class TaskStoreGetReadAmplificationTest {

    private TaskStore store;

    @AfterEach
    void closeStore() {
        if (store != null) {
            store.close();
        }
    }

    private static CallToolResult result() {
        return new CallToolResult(List.of(new TextContent("ok")), false, null, null);
    }

    /** get(未过期任务) 不顺带移除过期的兄弟任务 → 证明未触发全表 cleanExpired(O(1) 单条判定)。 */
    @Test
    void getDoesNotScanAndEvictSiblingExpiredTasks() {
        Instant t0 = Instant.parse("2026-06-20T10:00:00Z");
        AtomicReference<Instant> clock = new AtomicReference<>(t0);
        store = new TaskStore(Duration.ofMinutes(60), clock::get);

        // expired:终态 + completedAt=t0,推进时钟使其过期
        GatewayTask expired = new GatewayTask("t-expired", "watch", "order", t0, clock::get);
        expired.markCompleted(result());
        store.put(expired);
        // working:永不 expires(completedAt=null),作为 get 的目标
        GatewayTask working = new GatewayTask("t-working", "trace", "pay", t0, clock::get);
        store.put(working);

        clock.set(t0.plus(Duration.ofMinutes(61))); // expired 现已过期

        // get(working):返回 working。若 get 全表清理,expired 会被顺带移除;若 O(1) 单条,expired 留存。
        assertThat(store.get("t-working")).containsSame(working);
        assertThat(store.containsRawForTest("t-expired"))
                .as("get(其它任务) 不触发全表清理 → 过期兄弟任务仍留存(待 list/后台 cleaner 清理)")
                .isTrue();

        // get(expired):单条过期判定 → 移除自身、返 empty(不扫全表)
        assertThat(store.get("t-expired")).as("过期任务自身:单条过期判定返 empty").isEmpty();
        assertThat(store.containsRawForTest("t-expired")).as("过期任务被定向移除").isFalse();
        // working 仍在(未被牵连移除)
        assertThat(store.containsRawForTest("t-working")).isTrue();
    }

    /** get(不存在的 taskId) 返 empty,不触发全表清理(不误伤留存任务)。 */
    @Test
    void getUnknownDoesNotScanAndEvictExpiredTasks() {
        Instant t0 = Instant.parse("2026-06-20T10:00:00Z");
        AtomicReference<Instant> clock = new AtomicReference<>(t0);
        store = new TaskStore(Duration.ofMinutes(60), clock::get);

        GatewayTask expired = new GatewayTask("t-expired", "watch", "order", t0, clock::get);
        expired.markCompleted(result());
        store.put(expired);
        clock.set(t0.plus(Duration.ofMinutes(61)));

        assertThat(store.get("t-ghost")).isEmpty();
        assertThat(store.containsRawForTest("t-expired"))
                .as("get(未知) 不触发全表清理 → 过期任务仍留存")
                .isTrue();

        // 全表清理由 list 承担(惰性):此时 list 会清掉 expired
        assertThat(store.list()).isEmpty();
    }

    /** 大规模终态任务下 get 目标任务不放大:仅目标条受影响,其余 N 条全留存(非 O(N) 清理)。 */
    @Test
    void getUnderLargeStoreOnlyTouchesTarget() {
        Instant t0 = Instant.parse("2026-06-20T10:00:00Z");
        AtomicReference<Instant> clock = new AtomicReference<>(t0);
        store = new TaskStore(Duration.ofMinutes(60), clock::get);

        // N 条过期终态任务(非目标)
        int n = 2000;
        for (int i = 0; i < n; i++) {
            GatewayTask e = new GatewayTask("t-e" + i, "watch", "order", t0, clock::get);
            e.markCompleted(result());
            store.put(e);
        }
        // 目标:working(不过期)
        GatewayTask target = new GatewayTask("t-target", "trace", "pay", t0, clock::get);
        store.put(target);
        clock.set(t0.plus(Duration.ofMinutes(61))); // 其余 N 条过期

        assertThat(store.get("t-target")).containsSame(target);
        // 关键:N 条过期兄弟任务<b>全部</b>留存(get 未全表清理)——若是 O(N) 清理,这些应已被移除
        int retained = 0;
        for (int i = 0; i < n; i++) {
            if (store.containsRawForTest("t-e" + i)) {
                retained++;
            }
        }
        assertThat(retained)
                .as("get(target) 不触发全表清理 → %d 条过期兄弟任务全部留存", n)
                .isEqualTo(n);

        store.close();
        store = null; // 避免重复 close
    }
}
