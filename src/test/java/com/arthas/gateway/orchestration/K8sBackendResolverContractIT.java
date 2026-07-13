package com.arthas.gateway.orchestration;

import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.BackendResolver;
import com.arthas.gateway.backend.DynamicBackendStore;
import com.arthas.gateway.backend.Protocol;
import com.arthas.gateway.backend.Source;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 005 US2 K8sBackendResolver 懒 resolve 契约 IT（T015，INV-K8SHOST-2/5，真实 k3s + Spring 装配 + 真实 ensure，零桩）。
 *
 * <p>对真实装配的 {@link BackendResolver}（{@code K8sOrchestrationConfig} 按 {@code k8s-hosts} 建 provisioner）
 * 发起 {@code resolveMcpUrl}，断言<b>真实 K8S</b> 行为：K8S 模式 → 真实 ensure（注入 arthas + NodePort 暴露 +
 * 健康检查）出 mcpUrl；二次 → 缓存命中；静态模式 → empty 旁路。
 *
 * <h3>覆盖断言</h3>
 * <ul>
 *   <li>K8S 模式 backend（k8sHost=debian + pod=demo-business）→ resolve 出可达 mcpUrl（真实 ensure）。</li>
 *   <li>二次 resolve 同 logicalName → mcpUrl 不变（缓存命中，INV-K8SHOST-2，不重复 ensure）。</li>
 *   <li>静态模式 backend → {@code Optional.empty()} 旁路（INV-K8SHOST-5）。</li>
 * </ul>
 *
 * <p><b>启用门禁</b>：kubeconfig 不可读 / demo-business pod 不存在 / 无 BackendResolver bean（CI 无 k3s）→ 跳过。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "arthas-gateway.k8s.kubeconfig=test-env/k8s/kubeconfig/k3s-admin.yaml",
        // 006 适配：k8s-hosts-file 指向不存在文件 → K8sHostsConfig 回退内联 k8s-hosts（下方 debian），
        // 避免 006 项目根 config/k8s-hosts.yaml（hosts:[]）优先覆盖（store 读空 → unknown_k8s_host）
        "arthas-gateway.k8s-hosts-file=nonexistent-test-k8s-hosts.yaml",
        "arthas-gateway.k8s-hosts[0].name=debian",
        "arthas-gateway.k8s-hosts[0].kubeconfig=test-env/k8s/kubeconfig/k3s-admin.yaml",
        "arthas-gateway.k8s-hosts[0].namespace=default"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class K8sBackendResolverContractIT {

    private static final String NS = "default";
    private static final String POD = "demo-business";
    private static final String HOST = "debian";
    private static final String LOGICAL = "debian-demo-business";

    @Autowired(required = false)
    private BackendResolver resolver;
    @Autowired
    private DynamicBackendStore dynamicStore;

    @BeforeAll
    void requireClusterAndResolver() {
        Assumptions.assumeTrue(Files.isReadable(Path.of("test-env/k8s/kubeconfig/k3s-admin.yaml")),
                "跳过：未找到可读 kubeconfig，需真实 k3s 测试床");
        Assumptions.assumeTrue(resolver != null, "跳过：无 BackendResolver bean（K8S 编排未装配）");
    }

    @AfterAll
    void cleanup() {
        // 清理 ensure 可能的动态注册（幂等；避免污染后续 IT 注册表）
        try {
            dynamicStore.unregister(LOGICAL);
        } catch (RuntimeException ignored) {
            // 幂等清理
        }
    }

    /** K8S 模式 → 真实 ensure 出可达 mcpUrl。 */
    @Test
    void k8sModeBackendResolvesMcpUrlViaRealEnsure() {
        BackendConfig k8s = k8sConfig(HOST, POD);
        Optional<String> url = resolver.resolveMcpUrl(k8s);

        assertThat(url).as("K8S 模式 resolve 出 mcpUrl（真实 ensure）").isPresent();
        assertThat(url.get()).as("mcpUrl 为 http 形态").startsWith("http://");
    }

    /** 二次 resolve 同 logicalName → mcpUrl 不变（缓存命中，INV-K8SHOST-2）。 */
    @Test
    void secondResolveHitsCache() {
        BackendConfig k8s = k8sConfig(HOST, POD);
        String first = resolver.resolveMcpUrl(k8s).orElseThrow();
        String second = resolver.resolveMcpUrl(k8s).orElseThrow();

        assertThat(second).as("二次 resolve 缓存命中，mcpUrl 不变").isEqualTo(first);
    }

    /** 静态模式 → Optional.empty() 旁路（INV-K8SHOST-5）。 */
    @Test
    void staticModeBypassesResolve() {
        BackendConfig stat = new BackendConfig("static-one", "http://127.0.0.1:8563", Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5);
        assertThat(resolver.resolveMcpUrl(stat)).as("静态模式旁路返 empty").isEmpty();
    }

    private static BackendConfig k8sConfig(String host, String pod) {
        return new BackendConfig(LOGICAL, null, Protocol.STREAMABLE,
                new BackendConfig.Auth(AuthMode.NONE, null, null, null),
                5000, 30000, 5, host, pod, Source.STATIC);
    }
}
