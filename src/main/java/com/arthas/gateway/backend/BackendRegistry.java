package com.arthas.gateway.backend;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 全部后端的不可变快照（data-model.md §4）。
 *
 * <p>含 {@code version}（配置版本号，单调递增，热重载去重）与 {@code byName}（逻辑名→{@link BackendEntry}）。
 * <b>不可变</b>：构造时对入参 Map 做 {@link Map#copyOf} 防御性拷贝，构造后任何对原 Map 的篡改均不泄漏。
 *
 * <p>由 {@link RegistryHolder} 经 {@code AtomicReference} 持有，热重载时<b>整体替换</b>（非增量修改），
 * 保证一次 {@code tools/call} 全程持有固定的 {@code BackendEntry} 引用——registry 替换不影响 in-flight
 * 调用（data-model.md §3 不变量「热重载并发不串台」）。
 *
 * @param version 配置版本号
 * @param byName  逻辑名→后端运行对象（构造时拷贝为不可变 Map）
 */
public record BackendRegistry(long version, Map<String, BackendEntry> byName) {

    public BackendRegistry {
        byName = Map.copyOf(byName); // 防御性不可变拷贝（拒绝 null key/value）
    }

    /** 空注册表（version=0，无后端）：启动期未加载 / 校验失败保留旧的兜底初始态。 */
    public static BackendRegistry empty() {
        return new BackendRegistry(0L, Map.of());
    }

    /** 按逻辑名取后端（缺失返 {@link Optional#empty()}）。 */
    public Optional<BackendEntry> get(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /** 全部逻辑名（不可变视图）。 */
    public Set<String> names() {
        return byName.keySet();
    }

    /** 后端数量。 */
    public int size() {
        return byName.size();
    }
}
