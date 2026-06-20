package com.arthas.gateway.task;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 异步任务的内存存储 + TTL 清理（方案 C，data-model.md §6 / gateway-tools-contract.md §3，T032）。
 *
 * <p>底层 {@link ConcurrentHashMap}（{@code taskId → GatewayTask}），供 task-get/task-list/task-cancel 查询。
 *
 * <p><b>TTL 清理</b>：终态任务（{@code completedAt} 非空）在 {@code completedAt + ttl} 后移除；
 * WORKING 任务（{@code completedAt=null}）<b>不</b>受 TTL 影响（仍在跑的不应被回收）。两条清理路径：
 * <ul>
 *   <li><b>单条惰性（get）</b>：{@link #get(String)} 仅判定<b>目标单条</b>是否过期——存在且未过期→返回;
 *       过期→定向 {@code remove} 返 empty;不存在→返 empty。<b>不</b>触发全表扫描(002 整改 P2-3/FR-009:
 *       大规模终态任务下逐条 task-get 的 O(N) 读放大)。</li>
 *   <li><b>全表惰性（list）</b>：{@link #list()} 访问时调用 {@link #cleanExpired()} 全表清理,过期任务对读不可见。</li>
 *   <li><b>主动</b>：构造时启动守护虚拟线程周期性 {@link #cleanExpired()}（period ≈ ttl/4，至少 1min），
 *       兜底长时间不被访问的终态任务，限制内存占用。</li>
 * </ul>
 *
 * <p><b>线程安全</b>：{@link ConcurrentHashMap} + 基于 {@code entrySet().removeIf} 的原子移除，
 * 后台写线程（markCompleted/markFailed）与 task-get/list 读线程并发安全。
 *
 * <p><b>时钟注入</b>：{@code clock} 为 {@code Supplier<Instant>}，生产传 {@code Instant::now}，
 * 测试传可变时钟以确定性断言 TTL（与 {@link GatewayTask} 共享同一时钟源）。
 *
 * <p><b>生命周期</b>：{@link #close()} 关闭守护清理线程（Spring 容器关闭时调用）。
 */
public final class TaskStore {

    private final ConcurrentHashMap<String, GatewayTask> tasks = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Supplier<Instant> clock;
    private final ScheduledExecutorService cleaner;

    /**
     * @param ttl   终态任务保留时长（完成后可查询时长，超期移除）
     * @param clock 时间源（终态判定 + TTL 用）
     */
    public TaskStore(Duration ttl, Supplier<Instant> clock) {
        this.ttl = Objects.requireNonNull(ttl, "ttl 不可为空");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl 必须为正");
        }
        this.clock = Objects.requireNonNull(clock, "clock 不可为空");
        this.cleaner = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
        long periodSec = Math.max(60, ttl.toSeconds() / 4);
        this.cleaner.scheduleAtFixedRate(this::cleanExpired, periodSec, periodSec, TimeUnit.SECONDS);
    }

    /** 存入/覆盖任务（同 taskId 覆盖）。 */
    public void put(GatewayTask task) {
        Objects.requireNonNull(task, "task 不可为空");
        tasks.put(task.taskId(), task);
    }

    /**
     * 按 taskId 移除任务（002 整改 · 提交失败清理僵尸用，P0-1/FR-001）。
     *
     * <p>外层 {@code pool.submit} 被拒时，执行器移除刚 {@link #put} 的 WORKING 任务，
     * 避免其长期驻留为僵尸（永远不到达终态）。
     */
    public void remove(String taskId) {
        tasks.remove(taskId);
    }

    /**
     * 按 taskId 查询（<b>单条</b>过期判定，不触发全表清理，002 整改 P2-3/FR-009）。
     *
     * <p>存在且未过期→返回;过期→定向 {@code remove} 返 empty;不存在→返 empty。
     * 全表 {@link #cleanExpired()} 仅由 {@link #list()} 与后台 cleaner 负责——避免大规模终态任务下
     * 逐条 task-get 的 O(N) 读放大(轮询 N 任务 = O(N²))。
     *
     * @return 任务(存在且未过期);否则 {@link Optional#empty()}
     */
    public Optional<GatewayTask> get(String taskId) {
        GatewayTask task = tasks.get(taskId);
        if (task == null) {
            return Optional.empty();
        }
        if (isExpired(task, clock.get())) {
            tasks.remove(taskId); // 定向移除过期单条(非全表)
            return Optional.empty();
        }
        return Optional.of(task);
    }

    /**
     * 测试探针(包级可见):不经任何清理,探测内部存储是否仍含某 taskId。
     *
     * <p><b>仅测试用</b>——供读放大断言验证 {@link #get(String)} 是否触发全表清理
     * (全表清理会移除过期的兄弟任务;单条 O(1) 不会)。生产代码不应依赖。
     */
    boolean containsRawForTest(String taskId) {
        return tasks.containsKey(taskId);
    }

    /** 全部任务（触发惰性清理；快照副本，顺序不定）。 */
    public List<GatewayTask> list() {
        cleanExpired();
        return List.copyOf(tasks.values());
    }

    /** 按 status 过滤（触发惰性清理）。 */
    public List<GatewayTask> list(TaskState status) {
        Objects.requireNonNull(status, "status 不可为空");
        cleanExpired();
        return tasks.values().stream()
                .filter(t -> t.status() == status)
                .toList();
    }

    /**
     * 移除所有「终态且 {@code completedAt + ttl} 已过」的任务。
     *
     * @return 本次移除条数（便于测试断言）
     */
    public int cleanExpired() {
        Instant now = clock.get();
        int[] removed = {0};
        tasks.entrySet().removeIf(e -> {
            if (isExpired(e.getValue(), now)) {
                removed[0]++;
                return true;
            }
            return false;
        });
        return removed[0];
    }

    /** 终态（completedAt 非空）且 {@code now} 晚于 {@code completedAt + ttl} → 过期。WORKING 永不过期。 */
    private boolean isExpired(GatewayTask task, Instant now) {
        Instant completedAt = task.completedAt();
        return completedAt != null && now.isAfter(completedAt.plus(ttl));
    }

    /** 关闭守护清理线程（Spring 容器关闭时调用）。 */
    public void close() {
        cleaner.shutdownNow();
    }
}
