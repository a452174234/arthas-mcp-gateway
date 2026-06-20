package com.arthas.gateway.backend;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 注册表持有者（data-model.md §4）——经 {@link AtomicReference} 持有当前 {@link BackendRegistry}，
 * 提供<b>原子替换</b>。
 *
 * <p><b>热重载整体替换</b>：{@link #getAndSet(BackendRegistry)} 原子地把 registry 换为新快照并返回旧快照，
 * 旧快照供调用方<b>异步优雅下线</b>其 Entry（标 RETIRED、in-flight 调用可完成、关 session/连接池，
 * data-model.md §11 规则 8）。新调用经 {@link #current()} 始终见最新快照。
 *
 * <p>一次 {@code tools/call} 的路由解算：先 {@link #current()} 取<b>固定</b>的 BackendRegistry 引用，
 * 再 {@link BackendRegistry#get(String)} 取固定的 {@link BackendEntry}——registry 在调用中途被替换不影响
 * in-flight 调用（data-model.md §3 不变量）。
 */
public final class RegistryHolder {

    private final AtomicReference<BackendRegistry> ref;

    /** 默认持有空注册表（启动期未加载 backends.yaml 时）。 */
    public RegistryHolder() {
        this(BackendRegistry.empty());
    }

    /** 以指定 registry 初始化（启动期加载完成后注入）。 */
    public RegistryHolder(BackendRegistry initial) {
        this.ref = new AtomicReference<>(Objects.requireNonNull(initial, "registry 不可为空"));
    }

    /** 当前注册表快照。 */
    public BackendRegistry current() {
        return ref.get();
    }

    /**
     * 原子替换注册表。
     *
     * @param next 新注册表（非空）
     * @return 旧注册表（供调用方异步优雅下线其 Entry）
     */
    public BackendRegistry getAndSet(BackendRegistry next) {
        return ref.getAndSet(Objects.requireNonNull(next, "registry 不可为空"));
    }

    /** 便捷：从当前注册表按名取后端（缺失返 {@link Optional#empty()}）。 */
    public Optional<BackendEntry> get(String name) {
        return current().get(name);
    }
}
