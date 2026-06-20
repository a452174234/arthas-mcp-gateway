package com.arthas.gateway.backend;

/**
 * 后端运行态（data-model.md §3）。
 *
 * <ul>
 *   <li>{@link #ACTIVE}：在册可用，新调用可路由到此。</li>
 *   <li>{@link #RETIRED}：热重载移除中（US2），in-flight 调用仍可完成，新调用不再路由到此。</li>
 * </ul>
 * 一次 {@code tools/call} 全程持有固定的 {@code BackendEntry} 引用，registry 替换不影响 in-flight 调用。
 */
public enum BackendState {
    ACTIVE,
    RETIRED
}
