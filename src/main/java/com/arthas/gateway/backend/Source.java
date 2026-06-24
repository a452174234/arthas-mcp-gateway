package com.arthas.gateway.backend;

/**
 * 后端来源标记（data-model.md §3，动态纳管增量）。
 *
 * <p>区分一个 {@link BackendConfig} 是源自 YAML 种子（受热重载增删）还是程序化注册
 * （受 register/unregister）。用于 {@code list-targets} 可观测性区分来源
 * （contracts/dynamic-registration-invariants.md §1）。
 *
 * <ul>
 *   <li>{@link #STATIC} —— 源自 {@code config/backends.yaml} 种子，受 {@code BackendRegistryReloader}
 *       热重载增删；YAML 缺省 {@code source} 视为 STATIC（向后兼容，001 既有零改动）。</li>
 *   <li>{@link #DYNAMIC} —— 源自程序化 {@code DynamicBackendStore.register}（由
 *       {@code ensure-arthas-mcp} 触发），不受 YAML 热重载直接影响（热重载只重读 static）。</li>
 * </ul>
 */
public enum Source {
    /** 源自 {@code config/backends.yaml} 种子，受热重载增删。 */
    STATIC,
    /** 源自程序化注册（{@code ensure-arthas-mcp} 触发），受 register/unregister。 */
    DYNAMIC
}
