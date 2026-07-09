package com.arthas.gateway.orchestration;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 005 ArthasLauncher SPI 接口契约（T003）：LaunchContext 字段全集 + LaunchException 携带 Error。
 */
class ArthasLauncherTest {

    @Test
    void launchContextCarriesAllFields() {
        K8sExec exec = mock(K8sExec.class);
        ArthasLauncher.LaunchContext ctx = new ArthasLauncher.LaunchContext(
                "default", "demo-business", exec,
                8563, "0.0.0.0", "4.3.0", "secret",
                Path.of("/tmp/arthas-boot.jar"),
                Duration.ofSeconds(300), Duration.ofSeconds(10));
        assertThat(ctx.namespace()).isEqualTo("default");
        assertThat(ctx.pod()).isEqualTo("demo-business");
        assertThat(ctx.exec()).isSameAs(exec);
        assertThat(ctx.mcpPort()).isEqualTo(8563);
        assertThat(ctx.targetIp()).isEqualTo("0.0.0.0");
        assertThat(ctx.arthasVersion()).isEqualTo("4.3.0");
        assertThat(ctx.arthasPassword()).isEqualTo("secret");
        assertThat(ctx.arthasBootJar()).isEqualTo(Path.of("/tmp/arthas-boot.jar"));
        assertThat(ctx.attachTimeout()).isEqualTo(Duration.ofSeconds(300));
        assertThat(ctx.locateTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void launchExceptionCarriesErrorAndMessage() {
        OrchestrationRecord.Error err = new OrchestrationRecord.Error("start_arthas", "attach_failed", "exit=1");
        ArthasLauncher.LaunchException ex = new ArthasLauncher.LaunchException(err);
        assertThat(ex.error()).isEqualTo(err);
        assertThat(ex.getMessage()).contains("attach_failed", "start_arthas", "exit=1");
    }
}
