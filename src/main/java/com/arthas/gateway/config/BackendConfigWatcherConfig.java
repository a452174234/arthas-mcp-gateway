package com.arthas.gateway.config;

import com.arthas.gateway.backend.BackendConfigWatcher;
import com.arthas.gateway.backend.BackendEntryFactory;
import com.arthas.gateway.backend.DynamicBackendStore;
import com.arthas.gateway.backend.RegistryComposer;
import com.arthas.gateway.backend.RegistryHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 热重载监听装配（SC-002，T039/T040）：构造 {@link BackendConfigWatcher} 并启动，监听
 * {@code arthas-gateway.backends-file}（见 {@link GatewayProperties}）所在目录。
 *
 * <p>{@code destroyMethod = "close"}：容器关闭时关 WatchService + 中断监听线程。
 * 依赖 {@link RegistryHolder}（由 {@code BackendRegistryBootstrap} 启动期加载），Spring 保证 holder 先就绪。
 *
 * <p>配置文件无父目录或父目录不存在时，{@link BackendConfigWatcher#start()} 记 WARN 跳过监听
 * （启动期初值仍生效，不阻断上下文）。
 *
 * <p><b>003 动态纳管</b>：watcher 注入 {@link RegistryComposer}（合并静态∪动态）与
 * {@code ObjectProvider<DynamicBackendStore>}（懒解析，规避 watcher↔store 循环依赖，见
 * {@link DynamicRegistrationConfig}）。
 */
@Configuration
public class BackendConfigWatcherConfig {

    private static final Logger log = LoggerFactory.getLogger(BackendConfigWatcherConfig.class);

    @Bean(destroyMethod = "close")
    BackendConfigWatcher backendConfigWatcher(GatewayProperties props, BackendEntryFactory factory, RegistryHolder holder,
                                              RegistryComposer composer,
                                              ObjectProvider<DynamicBackendStore> dynamicStoreProvider) {
        Path configFile = Path.of(props.getBackendsFile());
        // 退役宽限 = backendTimeout(默认 11min,002 整改 P2-1/FR-007):保证 in-flight 异步任务(最长 11min)
        // 在退役 target 的 client 关闭前完成,避免被切断。
        BackendConfigWatcher watcher = new BackendConfigWatcher(configFile, factory, holder,
                composer, dynamicStoreProvider, props.getTask().getBackendTimeout());
        try {
            watcher.start();
        } catch (IOException e) {
            log.warn("热重载监听启动失败（{}），配置变更将不自动重载（需重启）", configFile, e);
        }
        return watcher;
    }
}
