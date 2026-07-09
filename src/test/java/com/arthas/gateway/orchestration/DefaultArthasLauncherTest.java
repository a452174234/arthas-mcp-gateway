package com.arthas.gateway.orchestration;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 005 DefaultArthasLauncher（T005，INV-LAUNCHER-2 兼容现状）：
 * locatePid = jps -q | head -1、startArthas = java -jar 标准参数；故障分类 no_jvm/no_shell/attach_failed。
 */
class DefaultArthasLauncherTest {

    private ArthasLauncher.LaunchContext ctx(K8sExec exec) {
        return new ArthasLauncher.LaunchContext("default", "demo", exec,
                8563, "0.0.0.0", "4.3.0", "pwd",
                "/tmp/arthas-boot.jar", Duration.ofSeconds(300), Duration.ofSeconds(10));
    }

    @Test
    void locatePidParsesJpsOutput() {
        K8sExec exec = mock(K8sExec.class);
        when(exec.exec(eq("default"), eq("demo"), any(), eq("sh"), eq("-c"), any()))
                .thenReturn(new K8sExec.ExecResult(0, "12345\n", ""));
        assertThat(new DefaultArthasLauncher().locatePid(ctx(exec))).isEqualTo(12345L);
    }

    @Test
    void locatePidNoJvmThrowsNoJvm() {
        K8sExec exec = mock(K8sExec.class);
        when(exec.exec(eq("default"), eq("demo"), any(), eq("sh"), eq("-c"), any()))
                .thenReturn(new K8sExec.ExecResult(0, "", ""));
        assertThatThrownBy(() -> new DefaultArthasLauncher().locatePid(ctx(exec)))
                .isInstanceOf(ArthasLauncher.LaunchException.class)
                .hasFieldOrPropertyWithValue("error.reason", "no_jvm");
    }

    @Test
    void locatePidNonZeroExitThrowsNoShell() {
        K8sExec exec = mock(K8sExec.class);
        when(exec.exec(eq("default"), eq("demo"), any(), eq("sh"), eq("-c"), any()))
                .thenReturn(new K8sExec.ExecResult(127, "", "jps: not found"));
        assertThatThrownBy(() -> new DefaultArthasLauncher().locatePid(ctx(exec)))
                .isInstanceOf(ArthasLauncher.LaunchException.class)
                .hasFieldOrPropertyWithValue("error.reason", "no_shell")
                .hasFieldOrPropertyWithValue("error.phase", "locate_jvm");
    }

    @Test
    void startArthasInvokesJavaJarCommand() {
        K8sExec exec = mock(K8sExec.class);
        when(exec.exec(eq("default"), eq("demo"), any(), eq("java"), eq("-jar"), any(), eq("12345"),
                eq("--attach-only"), eq("--http-port"), eq("8563"), eq("--target-ip"), eq("0.0.0.0"),
                eq("--telnet-port"), eq("0"), eq("--use-version"), eq("4.3.0"), eq("--password"), eq("pwd")))
                .thenReturn(new K8sExec.ExecResult(0, "", ""));
        new DefaultArthasLauncher().startArthas(ctx(exec), 12345L);
        verify(exec, times(1)).exec(eq("default"), eq("demo"), any(), eq("java"), eq("-jar"), any(), eq("12345"),
                eq("--attach-only"), eq("--http-port"), eq("8563"), eq("--target-ip"), eq("0.0.0.0"),
                eq("--telnet-port"), eq("0"), eq("--use-version"), eq("4.3.0"), eq("--password"), eq("pwd"));
    }

    @Test
    void startArthasNonZeroExitThrowsAttachFailed() {
        K8sExec exec = mock(K8sExec.class);
        when(exec.exec(any(), any(), any(), eq("java"), eq("-jar"), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new K8sExec.ExecResult(1, "", "err"));
        assertThatThrownBy(() -> new DefaultArthasLauncher().startArthas(ctx(exec), 12345L))
                .isInstanceOf(ArthasLauncher.LaunchException.class)
                .hasFieldOrPropertyWithValue("error.reason", "attach_failed")
                .hasFieldOrPropertyWithValue("error.phase", "start_arthas");
    }
}
