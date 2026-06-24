package com.arthas.gateway.backend;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 静态∪动态合并器（data-model §5，contracts §2/§3/§4.3）。
 *
 * <p>纯逻辑：把「上一次 effective registry」+「静态 registry（已含 Entry）」+「动态 cfg 列表」合并为新
 * effective registry，unchanged 的 Entry <b>复用旧实例</b>（保连接池/session，I-6），未复用旧 Entry 进
 * 下线列表。version 由内部单调计数器驱动（I-7）；无变更的 compose → {@code changed=false}、registry 维持
 * 原实例（调用方据此跳过 {@code getAndSet}，version 去重，D-VERSION-1）。
 *
 * <p><b>调用方</b>：{@code BackendConfigWatcher} 在静态热重载（reloader 产出新 static 后）与动态变更
 * （{@code DynamicBackendStore} register/unregister）两条路径上调用 {@link #compose}，再
 * {@code holder.getAndSet} 原子替换 + 异步下线未复用 Entry（I-1）。本类不触达 holder/watcher——仅算合并结果。
 *
 * <p><b>I-2 关键</b>：静态热重载传入「移除某静态 target 的新 staticReg」，但 dynamic 列表仍含动态 target →
 * 合并结果保留动态 target（热重载不误删动态）。
 */
public final class RegistryComposer {

    private final BackendEntryFactory factory;
    /** effective registry version 单调计数器（每次变更 compose 递增；无变更不递增）。 */
    private final AtomicLong versionSeq = new AtomicLong(0L);

    public RegistryComposer(BackendEntryFactory factory) {
        this.factory = Objects.requireNonNull(factory, "factory 不可为空");
    }

    /**
     * 合并上一次 effective + 静态 registry + 动态 cfg 列表为新 effective。
     *
     * @param previous 上一次 effective registry（reuse Entry 来源）
     * @param staticReg 当前静态 registry（Entry 已由 reloader diff/复用产出；含被热重载增删后的静态 target）
     * @param dynamic 当前动态 cfg 列表（来自 {@code DynamicBackendStore.list()}）
     * @return 合并结果（新 effective + 待下线旧 Entry + 是否变更）
     */
    public ComposeResult compose(BackendRegistry previous, BackendRegistry staticReg,
                                 Collection<BackendConfig> dynamic) {
        Objects.requireNonNull(previous, "previous registry 不可为空");
        Objects.requireNonNull(staticReg, "staticReg 不可为空");
        Objects.requireNonNull(dynamic, "dynamic 不可为空");

        Map<String, BackendEntry> byName = new LinkedHashMap<>();
        Set<BackendEntry> reused = Collections.newSetFromMap(new IdentityHashMap<>());

        // 静态 target：staticReg 的 Entry 优先复用 previous 中同 config 的旧实例（保连接）
        for (Map.Entry<String, BackendEntry> e : staticReg.byName().entrySet()) {
            String name = e.getKey();
            BackendEntry staticEntry = e.getValue();
            BackendEntry prev = previous.byName().get(name);
            if (prev != null && prev.config().equals(staticEntry.config())) {
                byName.put(name, prev); // 复用旧实例
                reused.add(prev);
            } else {
                byName.put(name, staticEntry); // 新静态 Entry（reloader 已创建/复用）
                reused.add(staticEntry);
            }
        }
        // 动态 target：复用 previous 中同 config 旧实例，否则 factory.create
        for (BackendConfig cfg : dynamic) {
            BackendEntry prev = previous.byName().get(cfg.name());
            if (prev != null && prev.config().equals(cfg)) {
                byName.put(cfg.name(), prev); // 复用旧实例（I-6）
                reused.add(prev);
            } else {
                BackendEntry created = factory.create(cfg);
                byName.put(cfg.name(), created);
                reused.add(created);
            }
        }
        // 未复用的旧 Entry → 下线
        List<BackendEntry> toRetire = new ArrayList<>();
        for (BackendEntry e : previous.byName().values()) {
            if (!reused.contains(e)) {
                toRetire.add(e);
            }
        }
        boolean changed = !sameEffective(previous, byName);
        BackendRegistry next = changed
                ? new BackendRegistry(versionSeq.incrementAndGet(), byName)
                : previous;
        return new ComposeResult(next, List.copyOf(toRetire), changed);
    }

    /**
     * effective 是否实际变更：名字集相同且每个 target 复用同一 Entry 实例（identity）→ 未变更。
     * 任一名字增删、或某 target 换了新 Entry 实例 → 变更。
     */
    private static boolean sameEffective(BackendRegistry previous, Map<String, BackendEntry> byName) {
        if (!previous.byName().keySet().equals(byName.keySet())) {
            return false;
        }
        for (Map.Entry<String, BackendEntry> e : byName.entrySet()) {
            if (previous.byName().get(e.getKey()) != e.getValue()) {
                return false; // 新 Entry 实例（非 identity 复用）
            }
        }
        return true;
    }

    /**
     * 合并结果。
     *
     * @param registry 新 effective registry（{@code changed=false} 时为原 previous）
     * @param toRetire 待优雅下线的旧 Entry（{@code changed=false} 时为空）
     * @param changed  是否实际变更（false → 调用方跳过 {@code getAndSet}，version 去重）
     */
    public record ComposeResult(BackendRegistry registry, List<BackendEntry> toRetire, boolean changed) {
        public ComposeResult {
            Objects.requireNonNull(registry, "registry 不可为空");
            toRetire = List.copyOf(toRetire);
        }
    }
}
