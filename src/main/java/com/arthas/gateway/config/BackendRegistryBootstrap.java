package com.arthas.gateway.config;

import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.BackendConfigLoader;
import com.arthas.gateway.backend.BackendEntry;
import com.arthas.gateway.backend.BackendEntryFactory;
import com.arthas.gateway.backend.BackendRegistry;
import com.arthas.gateway.backend.RegistryHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 后端注册表启动期装配（T027 装配）：{@code config/backends.yaml} → {@link BackendRegistry} → {@link RegistryHolder}。
 *
 * <p>补 Wave A 的纯逻辑缺口——{@link BackendConfigLoader}（解析）、{@link BackendEntry}/{@link RegistryHolder}
 * （运行对象/持有者）已就绪，但无 Spring 装配把它们串起来。本类在启动期一次性加载后端映射表，
 * 为每个 {@link BackendConfig} 经 {@link BackendEntryFactory} 构造 {@link BackendEntry}，注入 {@link RegistryHolder}
 * 供 {@link com.arthas.gateway.handler.ToolsCallRouter} 路由解算。
 *
 * <p><b>资源解析顺序</b>：先文件系统（相对工作目录，支持运行期外置配置/jar 部署），后 classpath
 * （IDE/测试资源）。两者皆无 → 空注册表（所有 target 不在册，S-ERR-2），不阻断启动。
 *
 * <p><b>热重载不在本类</b>：{@code WatchService} 增删后端（SC-002）属 US2（T037/T040），届时经
 * {@link RegistryHolder#getAndSet} 原子替换。本类仅负责启动期初值。
 *
 * <p><b>构造不连</b>：{@link BackendEntryFactory} 构造 {@code HttpBackendClient} 不发起连接，首次路由转发时
 * 才 {@code initialize()} 握手——故 Phase 2 测试（{@code InitializeAndToolsListContractTest}，不触 tools/call）
 * 不受后端可达性影响。
 */
@Configuration
public class BackendRegistryBootstrap {

    private static final Logger log = LoggerFactory.getLogger(BackendRegistryBootstrap.class);

    /**
     * 启动期加载后端映射表，构造 {@link RegistryHolder}。
     *
     * @param props   网关配置（提供 backends-file 路径）
     * @param factory 后端运行对象工厂
     */
    @Bean
    RegistryHolder registryHolder(GatewayProperties props, BackendEntryFactory factory) {
        return new RegistryHolder(load(props.getBackendsFile(), factory));
    }

    private BackendRegistry load(String file, BackendEntryFactory factory) {
        try (InputStream in = openResource(file)) {
            if (in == null) {
                log.warn("后端映射表未找到 {}：以空注册表启动（所有 target 将不在册，S-ERR-2）", file);
                return BackendRegistry.empty();
            }
            BackendConfigLoader.LoadedBackends loaded = new BackendConfigLoader().load(in);
            Map<String, BackendEntry> byName = new LinkedHashMap<>();
            for (BackendConfig cfg : loaded.backends()) {
                byName.put(cfg.name(), factory.create(cfg));
            }
            BackendRegistry registry = new BackendRegistry(loaded.version(), byName);
            log.info("已加载后端注册表 version={}，{} 个后端：{}", loaded.version(), byName.size(), byName.keySet());
            return registry;
        } catch (IOException e) {
            throw new IllegalStateException("加载后端映射表失败：" + file, e);
        }
    }

    /** 文件系统优先（外置配置），次 classpath（IDE/测试资源）。 */
    private InputStream openResource(String file) throws IOException {
        File f = new File(file);
        if (f.isFile()) {
            return new FileInputStream(f);
        }
        return getClass().getClassLoader().getResourceAsStream(file);
    }
}
