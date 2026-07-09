package com.arthas.gateway.orchestration;

import com.arthas.gateway.backend.DynamicBackendStore;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 005 US3 ArthasLauncher SPI 委托单测（T023，INV-LAUNCHER-1/4）。
 *
 * <p>验证 {@link ArthasProvisioner} 委托 {@link ArthasLauncher}（locatePid + startArthas，<b>非硬编码</b>，
 * INV-LAUNCHER-1）+ {@code LaunchException} 映射为 failed 记录（携带 error reason/phase，INV-LAUNCHER-4）。
 * {@code @Primary} 覆盖（INV-LAUNCHER-3）与真实链路由 {@code CustomLauncherContractIT}（T024）覆盖。
 *
 * <p>用 {@link org.mockito.Mockito#spy} 覆盖 {@code installArthas}（跳过真实 fabric8 upload）+ {@code probeHealthy}
 *（返 true 跳过真实 MCP 握手），聚焦 launcher 委托。
 */
class ArthasLauncherSpiTest {

    /** 构造 spy ArthasProvisioner（mock exposer/store/client，注入指定 launcher）。 */
    private ArthasProvisioner provisioner(ArthasLauncher launcher) {
        NodePortExposer exposer = mock(NodePortExposer.class);
        when(exposer.expose(any(), any(), any(), anyInt())).thenReturn(
                new NodePortExposer.ExposeResult("arthas-mcp-svc", 30000,
                        "http://1.2.3.4:30000", "arthas-mcp-svc/30000"));
        DynamicBackendStore store = mock(DynamicBackendStore.class);
        when(store.get(any())).thenReturn(Optional.empty()); // 无幂等复用 → 走供给
        OrchestrationRecordStore rs = mock(OrchestrationRecordStore.class);
        KubernetesClient client = mock(KubernetesClient.class);
        return spy(new ArthasProvisioner(client, exposer, store, rs, "0.0.0.0",
                "tools/arthas-boot.jar", 8563, "4.3.0", "pwd", Duration.ofMillis(50), launcher));
    }

    /** INV-LAUNCHER-1：ArthasProvisioner.ensure 委托 launcher.locatePid + startArthas（pid 传递）。 */
    @Test
    void provisionerDelegatesLocatePidAndStartArthasToLauncher() {
        ArthasLauncher launcher = mock(ArthasLauncher.class);
        when(launcher.locatePid(any())).thenReturn(12345L);
        ArthasProvisioner p = provisioner(launcher);
        doNothing().when(p).installArthas(any(), any()); // 跳过真实 upload
        doReturn(true).when(p).probeHealthy(any(), any()); // 跳过真实握手

        OrchestrationRecord rec = p.ensure("debian", "demo-business", "default", Instant.EPOCH);

        verify(launcher).locatePid(any()); // 委托定位
        verify(launcher).startArthas(any(), eq(12345L)); // 委托启动 + pid 透传
        assertThat(rec.status()).as("全子步成功 → ready").isEqualTo(OrchestrationRecord.Status.READY);
    }

    /** INV-LAUNCHER-4：launcher.locatePid 抛 LaunchException → failed 记录（携带 error reason/phase）。 */
    @Test
    void launcherLaunchExceptionMappedToFailedRecord() {
        ArthasLauncher launcher = mock(ArthasLauncher.class);
        when(launcher.locatePid(any())).thenThrow(new ArthasLauncher.LaunchException(
                new OrchestrationRecord.Error("locate_jvm", "no_jvm", "测试注入故障")));
        ArthasProvisioner p = provisioner(launcher);

        OrchestrationRecord rec = p.ensure("debian", "demo-business", "default", Instant.EPOCH);

        assertThat(rec.status()).as("LaunchException → failed（不注册）").isEqualTo(OrchestrationRecord.Status.FAILED);
        assertThat(rec.error().reason()).isEqualTo("no_jvm");
        assertThat(rec.error().phase()).isEqualTo("locate_jvm");
    }

    /** INV-LAUNCHER-4：launcher.startArthas 抛 LaunchException → failed（attach_failed@start_arthas）。 */
    @Test
    void startArthasLaunchExceptionMappedToFailedRecord() {
        ArthasLauncher launcher = mock(ArthasLauncher.class);
        when(launcher.locatePid(any())).thenReturn(12345L);
        doThrow(new ArthasLauncher.LaunchException(
                new OrchestrationRecord.Error("start_arthas", "attach_failed", "测试注入启动故障")))
                .when(launcher).startArthas(any(), anyLong());
        ArthasProvisioner p = provisioner(launcher);
        doNothing().when(p).installArthas(any(), any());

        OrchestrationRecord rec = p.ensure("debian", "demo-business", "default", Instant.EPOCH);

        assertThat(rec.status()).isEqualTo(OrchestrationRecord.Status.FAILED);
        assertThat(rec.error().reason()).isEqualTo("attach_failed");
        assertThat(rec.error().phase()).isEqualTo("start_arthas");
    }
}
