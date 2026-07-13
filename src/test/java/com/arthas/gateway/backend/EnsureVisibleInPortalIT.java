package com.arthas.gateway.backend;

import com.arthas.gateway.admin.backend.BackendAdminService;
import com.arthas.gateway.admin.backend.dto.BackendDto;
import com.arthas.gateway.orchestration.ArthasProvisioner;
import com.arthas.gateway.orchestration.OrchestrationRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ensure 可见性契约 IT（006 波4，T028，INV-DISP-1）。
 *
 * <p>真实测试床：{@link ArthasProvisioner#ensure} 纳管一个 target 后，portal {@link BackendAdminService#list}
 * 必含该 target（名字 {@code {server}-{pod}}）。守护 T030 list 兜底修复（compose 异常吞咽时 holder 缺新 dynamic
 * → list 从 dynamicStore 补全）+ T032 前端自动刷新（IT 聚焦后端 list 可见性）。
 *
 * <p>仅当 {@code TEST_K3S_HOST} 设定时运行（CI 无测试床跳过；本地设 {@code 192.168.31.92}，需测试床 kubeconfig
 * {@code test-env/k8s/kubeconfig/k3s-admin.yaml} + demo-business pod Running）。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "TEST_K3S_HOST", matches = ".+")
class EnsureVisibleInPortalIT {

    @Autowired
    ArthasProvisioner provisioner;

    @Autowired
    BackendAdminService adminService;

    @Test
    void ensureProducesVisibleInPortalList() {
        OrchestrationRecord rec = provisioner.ensure("debian-it", "demo-business", "default", Instant.now());
        assertThat(rec.status())
                .as("ensure 应成功（ready/reused）")
                .isIn(OrchestrationRecord.Status.READY, OrchestrationRecord.Status.REUSED);

        assertThat(adminService.list().stream().map(BackendDto::name))
                .as("portal list 必含 ensure 纳管的 target（INV-DISP-1，T030 兜底守护）")
                .contains("debian-it-demo-business");
    }
}
