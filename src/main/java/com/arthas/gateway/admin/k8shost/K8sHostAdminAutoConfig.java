package com.arthas.gateway.admin.k8shost;

import com.arthas.gateway.config.GatewayProperties;
import com.arthas.gateway.config.K8sHostsConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * portal K8S host 管理 bean 装配（006 波3，T026，仿 {@code BackendCrudAutoConfig}）。
 *
 * <p>整体由 {@code arthas-gateway.admin.k8s-hosts.enabled} 把关（默认开，INV-PORTAL-K8S-5）。
 * 装配 {@link K8sHostSecretCipher}（密钥来自 {@code ARTHAS_GATEWAY_SECRET} env）、{@link K8sHostsYamlWriter}、
 * {@link K8sHostAdminService}、{@link K8sHostsConfig}（共享加载器，watcher 复用）。
 * {@link K8sHostAdminController} 由组件扫描自动注册。
 */
@Configuration
@ConditionalOnProperty(name = "arthas-gateway.admin.k8s-hosts.enabled", havingValue = "true", matchIfMissing = true)
public class K8sHostAdminAutoConfig {

    /** 共享 K8S Host 配置加载器（admin service + watcher 复用）。 */
    @Bean
    K8sHostsConfig k8sHostsConfig() {
        return new K8sHostsConfig();
    }

    /** SSH 凭证加密器（密钥来自 ARTHAS_GATEWAY_SECRET env；未配则 isConfigured=false，写凭证端点 400）。 */
    @Bean
    K8sHostSecretCipher k8sHostSecretCipher() {
        return new K8sHostSecretCipher(System.getenv("ARTHAS_GATEWAY_SECRET"));
    }

    @Bean
    K8sHostsYamlWriter k8sHostsYamlWriter() {
        return new K8sHostsYamlWriter();
    }

    @Bean
    K8sHostAdminService k8sHostAdminService(GatewayProperties props, K8sHostsConfig loader,
                                            K8sHostsYamlWriter writer, K8sHostSecretCipher cipher) {
        Path file = Path.of(props.getK8sHostsFile());
        return new K8sHostAdminService(file, props, loader, writer, cipher);
    }
}
