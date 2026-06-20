package com.arthas.gateway.backend;

import com.arthas.gateway.backend.BackendConfigLoader.LoadedBackends;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 热重载核心逻辑（data-model.md §11 规则 7/8，T039）：旧 {@link BackendRegistry} + 新
 * {@link LoadedBackends} → 构造新 registry（unchanged 后端<b>复用</b>旧 Entry，保连接池/session）+
 * 待优雅下线的旧 Entry 列表。纯逻辑、无 WatchService / 无线程，便于单测。
 *
 * <h3>diff 规则</h3>
 * <ul>
 *   <li><b>unchanged</b>：name 在新表且 {@link BackendConfig#equals(Object)} 完全相等（含 url/auth/超时/并发）
 *       → <b>复用</b>旧 Entry（同实例，已热连接的 client/breaker/slots 原样保留，免重连）。</li>
 *   <li><b>added / changed</b>：name 不在旧表，或 config 变更（同 name 不同 url 等）→ {@link BackendEntryFactory#create}
 *       新建 Entry（连接按需在首次路由时建立）。</li>
 *   <li><b>toRetire</b>：旧表 Entry 未被复用（name 移除，或 config 变更被新 Entry 取代）→ 进入下线列表，
 *       交调用方 {@code markRetired} + 异步 {@code close}（in-flight 可完成，data-model.md §3 不变量）。</li>
 * </ul>
 *
 * <h3>version 去重</h3>
 * <p>{@code next.version == current.version} → {@link ReloadResult#changed()}=false，不重建
 * （data-model.md §2「version 单调递增，热重载去重，重复忽略」）。调用方据此跳过 getAndSet。
 *
 * <p><b>校验失败不在本类</b>：YAML 解析/校验失败由 {@link BackendConfigLoader} 抛
 * {@link BackendConfigException}，调用方（{@code BackendConfigWatcher}）catch 后<b>保留旧表</b>
 * （§11 规则 7），本类收到的 {@code next} 已是合法加载结果。
 */
public final class BackendRegistryReloader {

    private final BackendEntryFactory factory;

    public BackendRegistryReloader(BackendEntryFactory factory) {
        this.factory = java.util.Objects.requireNonNull(factory, "factory 不可为空");
    }

    /**
     * 由旧 registry 与新加载结果构造重载结果。
     *
     * @param current 当前 registry（启动期或上次重载的快照）
     * @param next    新解析的 {@code version + 后端列表}（已校验合法）
     * @return 重载结果（新 registry + 待下线 Entry + 是否变更）
     */
    public ReloadResult reload(BackendRegistry current, LoadedBackends next) {
        java.util.Objects.requireNonNull(current, "current registry 不可为空");
        java.util.Objects.requireNonNull(next, "next 不可为空");
        if (next.version() == current.version()) {
            // version 重复 → 忽略（不重建、不下线）
            return ReloadResult.unchanged(current);
        }
        Map<String, BackendEntry> byName = new LinkedHashMap<>();
        Set<BackendEntry> reused = Collections.newSetFromMap(new IdentityHashMap<>());
        for (BackendConfig cfg : next.backends()) {
            BackendEntry existing = current.byName().get(cfg.name());
            if (existing != null && existing.config().equals(cfg)) {
                byName.put(cfg.name(), existing); // unchanged → 复用旧 Entry（保连接）
                reused.add(existing);
            } else {
                byName.put(cfg.name(), factory.create(cfg)); // added / changed → 新建
            }
        }
        List<BackendEntry> toRetire = new ArrayList<>();
        for (BackendEntry e : current.byName().values()) {
            if (!reused.contains(e)) {
                toRetire.add(e); // name 移除 或 config 变更（旧 Entry 被取代）→ 下线
            }
        }
        return new ReloadResult(new BackendRegistry(next.version(), byName), List.copyOf(toRetire), true);
    }

    /**
     * 重载结果。
     *
     * @param registry 新 registry（{@code changed=false} 时为原 current）
     * @param toRetire 待优雅下线的旧 Entry（{@code changed=false} 时为空）
     * @param changed  是否实际变更（version 重复时为 false，调用方据此跳过 getAndSet）
     */
    public record ReloadResult(BackendRegistry registry, List<BackendEntry> toRetire, boolean changed) {
        public ReloadResult {
            java.util.Objects.requireNonNull(registry, "registry 不可为空");
            toRetire = List.copyOf(toRetire);
        }

        /** version 重复：不变更，registry 维持原样、无下线。 */
        static ReloadResult unchanged(BackendRegistry current) {
            return new ReloadResult(current, List.of(), false);
        }
    }
}
