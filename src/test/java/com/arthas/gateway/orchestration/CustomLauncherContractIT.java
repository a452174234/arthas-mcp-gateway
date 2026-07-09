package com.arthas.gateway.orchestration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 005 US3 自定义 ArthasLauncher 契约 IT（T024，INV-LAUNCHER-3，真实 Spring 装配 + @Primary 覆盖）。
 *
 * <p>验证 SPI 替换机制：用户 {@code @Primary} 自定义 {@link ArthasLauncher} 覆盖 {@code DefaultArthasLauncher}
 * （{@code @ConditionalOnMissingBean} 让位），ArthasProvisioner 注入自定义实现。
 *
 * <p>用 {@link TestArthasLauncher}（真实实现 fixture，T022）经 {@code @TestConfiguration} 显式装配为 @Primary bean
 * （<b>不</b>用 @Component，避免污染其他 IT 的 Default 装配）。
 *
 * <p><b>启用门禁</b>：kubeconfig 不可读 → 跳过（CI 无 k3s）。@Primary 覆盖是 Spring 装配行为，不依赖集群可达性。
 */
@SpringBootTest
@Import(CustomLauncherContractIT.TestLauncherConfig.class)
@TestPropertySource(properties = "arthas-gateway.k8s.kubeconfig=test-env/k8s/kubeconfig/k3s-admin.yaml")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomLauncherContractIT {

    @Autowired(required = false)
    private ArthasLauncher launcher;

    @BeforeAll
    void requireK8s() {
        Assumptions.assumeTrue(Files.isReadable(Path.of("test-env/k8s/kubeconfig/k3s-admin.yaml")),
                "跳过：未找到可读 kubeconfig（K8S 编排未装配，DefaultArthasLauncher/自定义 launcher 均不装配）");
        Assumptions.assumeTrue(launcher != null, "跳过：无 ArthasLauncher bean");
    }

    /** INV-LAUNCHER-3：@Primary 自定义 launcher 覆盖 DefaultArthasLauncher。 */
    @Test
    void customPrimaryLauncherReplacesDefault() {
        assertThat(launcher)
                .as("INV-LAUNCHER-3：@Primary TestArthasLauncher 覆盖 DefaultArthasLauncher（@ConditionalOnMissingBean 让位）")
                .isInstanceOf(TestArthasLauncher.class);
    }

    /** 用户自定义实现零代码侵入即可定制 javaPath/启动命令（SPI 口子，005 第三点需求）。 */
    @Test
    void customLauncherIsRealImplNotMock() {
        assertThat(launcher).as("真实实现 fixture，非 mock（INV-LAUNCHER-5）").isInstanceOf(TestArthasLauncher.class);
        // 探针字段可观测（证明是真实记录的实现，非桩）
        assertThat(((TestArthasLauncher) launcher).lastPid()).as("初始未调 startArthas").isEqualTo(-1L);
    }

    /** 显式装配 TestArthasLauncher 为 @Primary ArthasLauncher（覆盖 DefaultArthasLauncher）。 */
    @TestConfiguration
    static class TestLauncherConfig {
        @Bean
        @Primary
        ArthasLauncher testArthasLauncher() {
            return new TestArthasLauncher();
        }
    }
}
