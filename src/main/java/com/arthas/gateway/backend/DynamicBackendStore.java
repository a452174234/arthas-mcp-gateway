package com.arthas.gateway.backend;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 动态 target 内存态（data-model §4）。程序化写入动态 target 的唯一入口
 * （{@code ensure-arthas-mcp} 经此注册；{@link RegistryComposer} 经 {@link #list()} 读动态集合）。
 *
 * <p>线程安全：内部 {@link ConcurrentHashMap}。变更后通知调用方重算 effective registry
 * （{@code onChange} 回调 → {@code RegistryComposer.compose} → {@code RegistryHolder.getAndSet} 原子替换）。
 *
 * <p>冲突检测（I-3，{@code register} 内）：
 * <ul>
 *   <li>动态名 ∩ 静态种子名 → 拒绝（保护静态配置）；静态种子名由 {@code staticNames} 供应商提供。</li>
 *   <li>动态名 ∩ 既有动态名、同名<b>异</b> URL → 拒绝。</li>
 *   <li>同名<b>同</b> URL → 幂等（无副作用、不重复触发变更回调）。</li>
 * </ul>
 *
 * @param staticNames 当前静态种子名的供应商（I-3 冲突检测用；解耦 store 与静态注册表持有者）
 * @param onChange    实际变更（新增/更新/移除）时触发的回调，由调用方 wiring 为 compose+swap
 */
public class DynamicBackendStore {

    private final ConcurrentHashMap<String, BackendConfig> byName = new ConcurrentHashMap<>();
    private final Supplier<Set<String>> staticNames;
    private final Runnable onChange;

    public DynamicBackendStore(Supplier<Set<String>> staticNames, Runnable onChange) {
        this.staticNames = staticNames;
        this.onChange = onChange;
    }

    /**
     * 注册一个动态 target（contracts §4.1）。
     *
     * <ol>
     *   <li>强制 {@code source=DYNAMIC}（非 DYNAMIC 拒绝）。</li>
     *   <li>冲突检测：与静态种子同名 / 与既有动态同名异 URL → 抛 {@link BackendConfigException}。</li>
     *   <li>同名同 URL → 幂等（无变更回调）。</li>
     *   <li>写入并 {@code onChange} 触发 compose。</li>
     * </ol>
     */
    public void register(BackendConfig cfg) {
        if (cfg.source() != Source.DYNAMIC) {
            throw new BackendConfigException(
                    "动态注册须 source=DYNAMIC，实得 " + cfg.source() + "（name=" + cfg.name() + "）");
        }
        String name = cfg.name();
        if (staticNames.get().contains(name)) {
            throw new BackendConfigException("动态注册名与静态种子冲突，拒绝（保护静态）：" + name);
        }
        BackendConfig existing = byName.get(name);
        if (existing != null) {
            if (!existing.url().equals(cfg.url())) {
                throw new BackendConfigException("动态注册名与既有动态同名异 URL，拒绝：" + name
                        + "（既有 " + existing.url() + "，新 " + cfg.url() + "）");
            }
            if (existing.equals(cfg)) {
                // 同名同 URL 且核心字段一致 → 幂等：不重复触发变更回调
                return;
            }
        }
        byName.put(name, cfg);
        onChange.run();
    }

    /**
     * 移除一个动态 target（contracts §4.2）。仅 DYNAMIC 可移（store 仅持动态，故静态名不在此 → 无操作）。
     * 不存在 → 幂等无操作。实际移除时 {@code onChange} 触发 compose。
     *
     * @return 是否实际移除（false = 不存在/静态名，幂等无操作）
     */
    public boolean unregister(String name) {
        BackendConfig removed = byName.remove(name);
        if (removed == null) {
            return false;
        }
        onChange.run();
        return true;
    }

    /** 当前动态 target 的不可变快照。 */
    public List<BackendConfig> list() {
        return List.copyOf(byName.values());
    }

    /** 取单个（缺失返 empty）。 */
    public Optional<BackendConfig> get(String name) {
        return Optional.ofNullable(byName.get(name));
    }
}
