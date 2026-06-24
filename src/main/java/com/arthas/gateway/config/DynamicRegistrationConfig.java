package com.arthas.gateway.config;

import com.arthas.gateway.backend.BackendConfigWatcher;
import com.arthas.gateway.backend.BackendEntryFactory;
import com.arthas.gateway.backend.DynamicBackendStore;
import com.arthas.gateway.backend.RegistryComposer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 动态纳管装配（003 增量，data-model §4/§5）：注册 {@link RegistryComposer}（静态∪动态合并，纯逻辑）与
 * {@link DynamicBackendStore}（动态 target 内存态）bean。
 *
 * <p><b>循环依赖规避</b>：watcher 读 {@code store.list()} 合并 dynamic，store 的 {@code onChange} 通知
 * watcher 重算 → 互相依赖。解法：watcher 经 {@code ObjectProvider<DynamicBackendStore>} 懒解析 store
 * （构造期不需 store）；store bean 以 watcher 的方法引用（{@code staticSnapshotNames}/
 * {@code recomposeForDynamicChange}）为 staticNames 供应商与 onChange 回调。装配顺序：watcher 先建
 * （store 未需）→ store 后建（watcher 已就绪，绑方法引用）。
 */
@Configuration
public class DynamicRegistrationConfig {

    /** 静态∪动态合并器（纯逻辑；effective registry version 单调计数器内嵌）。 */
    @Bean
    RegistryComposer registryComposer(BackendEntryFactory factory) {
        return new RegistryComposer(factory);
    }

    /**
     * 动态 target store：
     * <ul>
     *   <li>{@code staticNames} = {@code watcher.staticSnapshotNames()}（I-3 命名冲突检测的静态种子名来源）。</li>
     *   <li>{@code onChange} = {@code watcher.recomposeForDynamicChange()}（动态变更触发 effective 重算）。</li>
     * </ul>
     */
    @Bean
    DynamicBackendStore dynamicBackendStore(BackendConfigWatcher watcher) {
        return new DynamicBackendStore(watcher::staticSnapshotNames, watcher::recomposeForDynamicChange);
    }
}
