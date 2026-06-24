package com.arthas.gateway.orchestration;

import com.arthas.gateway.auth.BackendAuthCustomizer;
import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.DynamicBackendStore;
import com.arthas.gateway.backend.Source;
import com.arthas.gateway.config.GatewayProperties;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T023 ArthasProvisioner 供给器 IT（波次 B，契约 §3，K-ENS-1/2/4/5/7/8 + K-ATOMIC-1，真实 k3s + 真实 arthas 注入，零桩）。
 *
 * <p>直接对真实 {@link ArthasProvisioner}（装配真实 {@link KubernetesClient}/{@link NodePortExposer}/
 * {@link DynamicBackendStore} bean）发起 {@code ensure}，断言<b>真实供给</b>行为：
 * <ul>
 *   <li>K-ENS-8：{@code target = {server}-{pod}}（确定性派生）。</li>
 *   <li>K-ENS-1：对含 JVM 的真实 demo-business pod → status=READY + 可达 mcpUrl + 进注册表（source=DYNAMIC）。</li>
 *   <li>K-ENS-2：对 ready target 重复 ensure → status=REUSED（零副作用）。</li>
 *   <li>K-ENS-4：对无 JVM 的 busybox pod → FAILED + reason=no_jvm + stage=locate_jvm，且未注册。</li>
 *   <li>K-ENS-5：对无 shell 的 pause pod → FAILED + reason=no_shell（真实故障，非桩）。</li>
 *   <li>K-ENS-7：arthas 绑 loopback（--target-ip 127.0.0.1）→ NodePort 不可达 → reason=health_check_timeout（验证 0.0.0.0 要求，R4）。</li>
 *   <li>K-ATOMIC-1：任一子步失败 → 注册表不含该 target（不半注册）。</li>
 * </ul>
 *
 * <p><b>真实故障夹具</b>（非桩）：{@code @BeforeAll} 用真实 fabric8 client 起 busybox（有 shell 无 java→no_jvm）、
 * pause（FROM scratch 无 shell→no_shell）两个 pod；{@code @AfterAll} 清理。K-ENS-6（k8s_forbidden 需受限 kubeconfig）
 * 与 K-ENS-9（name_conflict）由波次 A 纯逻辑测试覆盖（契约 §5 注），本 IT 不重复。
 *
 * <p><b>启用门禁</b>：kubeconfig 不可读 → {@link Assumptions#assumeTrue} 跳过（CI 无 k3s）。
 *
 * <p><b>顺序</b>：K-ENS-1 先跑（首跑下载 arthas lib 到 pod ~/.arthas/lib/4.3.0，~3min；后续 ensure 复用缓存即快）。
 */
@SpringBootTest
@TestPropertySource(properties = "arthas-gateway.k8s.kubeconfig=test-env/k8s/kubeconfig/k3s-admin.yaml")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ArthasProvisionerIT {

    private static final String NAMESPACE = "default";
    private static final String DEMO_POD = "demo-business";
    private static final String NO_JVM_POD = "apit-nojvm";
    private static final String NO_SHELL_POD = "apit-noshell";
    private static final String BUSYBOX_IMAGE = "rancher/mirrored-library-busybox:1.37.0";
    private static final String PAUSE_IMAGE = "rancher/mirrored-pause:3.6";
    private static final String SERVER = "debian";

    @Autowired
    private KubernetesClient client;
    @Autowired
    private NodePortExposer exposer;
    @Autowired
    private DynamicBackendStore dynamicStore;
    @Autowired
    private OrchestrationRecordStore recordStore;
    @Autowired
    private GatewayProperties props;

    /** 生产参数 Provisioner（0.0.0.0、8563、默认健康超时）—— K-ENS-1/2 用。 */
    private ArthasProvisioner provisioner() {
        GatewayProperties.K8s k = props.getK8s();
        return new ArthasProvisioner(client, exposer, dynamicStore, recordStore,
                k.getTargetIp(), k.getArthasBootJar(), k.getMcpPort(), k.getArthasVersion(),
                k.getArthasPassword());
    }

    /** loopback Provisioner（127.0.0.1、独立端口 8564、短健康超时 12s 快速失败）—— K-ENS-7 用。 */
    private ArthasProvisioner loopbackProvisioner() {
        GatewayProperties.K8s k = props.getK8s();
        return new ArthasProvisioner(client, exposer, dynamicStore, recordStore,
                "127.0.0.1", k.getArthasBootJar(), 8564, k.getArthasVersion(), k.getArthasPassword(),
                Duration.ofSeconds(12));
    }

    @BeforeAll
    void requireRealClusterAndFixtures() {
        Assumptions.assumeTrue(Files.isReadable(Path.of("test-env/k8s/kubeconfig/k3s-admin.yaml")),
                "跳过：未找到可读 kubeconfig，需真实 k3s 测试床");
        // 真实故障夹具：busybox（有 shell 无 java → no_jvm）
        createPod(NO_JVM_POD, BUSYBOX_IMAGE, new String[]{"sleep", "600"});
        // 真实故障夹具：pause（FROM scratch 无 shell → no_shell）
        createPod(NO_SHELL_POD, PAUSE_IMAGE, null);
        waitReady(NO_JVM_POD);
        waitReady(NO_SHELL_POD);
    }

    @AfterAll
    void cleanupFixturesAndTargets() {
        // 清理动态 target（避免污染后续 IT 的注册表）
        dynamicStore.unregister(ArthasProvisioner.deriveLogicalName(SERVER, DEMO_POD));
        dynamicStore.unregister(ArthasProvisioner.deriveLogicalName(SERVER + "-loopback", DEMO_POD));
        // 清理故障夹具 pod（幂等）
        deletePod(NO_JVM_POD);
        deletePod(NO_SHELL_POD);
        // 清理可能残留的 NodePort Service（失败用例的副作用）
        deleteServiceQuiet(NAMESPACE, "arthas-mcp-" + sanitize(ArthasProvisioner.deriveLogicalName(SERVER, DEMO_POD)));
        deleteServiceQuiet(NAMESPACE, "arthas-mcp-" + sanitize(ArthasProvisioner.deriveLogicalName(SERVER + "-loopback", DEMO_POD)));
    }

    // ===== K-ENS-8 + K-ENS-1：确定性命名 + 真实供给成功 =====

    @Test
    @Order(1)
    void k_ens_8_and_1_ensureRealJvmPodReadyAndRegistered() {
        Instant now = Clock.systemUTC().instant();
        OrchestrationRecord rec = provisioner().ensure(SERVER, DEMO_POD, NAMESPACE, now);

        // K-ENS-8：target = {server}-{pod}
        assertThat(rec.logicalName())
                .as("K-ENS-8：target 确定性派生 = {server}-{pod}")
                .isEqualTo(ArthasProvisioner.deriveLogicalName(SERVER, DEMO_POD));
        // K-ENS-1：status=READY + 可达 mcpUrl + 进注册表 source=DYNAMIC
        assertThat(rec.status()).as("K-ENS-1 失败详情：%s", rec.error()).isEqualTo(OrchestrationRecord.Status.READY);
        assertThat(rec.mcpUrl()).as("READY 记录含 mcpUrl").isNotBlank();
        assertThat(isMcpReachable(rec.mcpUrl(), props.getK8s().getArthasPassword()))
                .as("K-ENS-1：mcpUrl 真实可达（arthas MCP 握手成功）").isTrue();

        Optional<com.arthas.gateway.backend.BackendConfig> reg = dynamicStore.get(rec.logicalName());
        assertThat(reg).as("K-ENS-1：target 进动态注册表").isPresent();
        assertThat(reg.get().source()).isEqualTo(Source.DYNAMIC);
        assertThat(reg.get().url()).isEqualTo(rec.mcpUrl());
    }

    // ===== K-ENS-2：重复 ensure → reused（零副作用） =====

    @Test
    @Order(2)
    void k_ens_2_repeatEnsureReused() {
        // 先确保 ready（若 K-ENS-1 已注册则直接复用；保证本测试独立可跑）
        provisioner().ensure(SERVER, DEMO_POD, NAMESPACE, Clock.systemUTC().instant());

        OrchestrationRecord first = provisioner().ensure(SERVER, DEMO_POD, NAMESPACE, Clock.systemUTC().instant());
        assertThat(first.status())
                .as("第二次 ensure：reused（已 ready）")
                .isIn(OrchestrationRecord.Status.READY, OrchestrationRecord.Status.REUSED);
        // 第三次必 reused
        OrchestrationRecord third = provisioner().ensure(SERVER, DEMO_POD, NAMESPACE, Clock.systemUTC().instant());
        assertThat(third.status()).as("第三次 ensure：reused").isEqualTo(OrchestrationRecord.Status.REUSED);
    }

    // ===== K-ENS-4：无 JVM pod → no_jvm，未注册 =====

    @Test
    @Order(3)
    void k_ens_4_noJvmPodFailsNoJvmNotRegistered() {
        String logical = ArthasProvisioner.deriveLogicalName(SERVER, NO_JVM_POD);
        OrchestrationRecord rec = provisioner().ensure(SERVER, NO_JVM_POD, NAMESPACE, Clock.systemUTC().instant());

        assertThat(rec.status()).as("K-ENS-4：无 JVM pod → FAILED").isEqualTo(OrchestrationRecord.Status.FAILED);
        assertThat(rec.error().reason()).as("reason=no_jvm").isEqualTo("no_jvm");
        assertThat(rec.error().phase()).as("stage=locate_jvm").isEqualTo("locate_jvm");
        assertThat(dynamicStore.get(logical))
                .as("K-ATOMIC-1：no_jvm 失败 → 未注册").isEmpty();
    }

    // ===== K-ENS-5：无 shell pod → no_shell，未注册 =====

    @Test
    @Order(4)
    void k_ens_5_noShellPodFailsNoShellNotRegistered() {
        String logical = ArthasProvisioner.deriveLogicalName(SERVER, NO_SHELL_POD);
        OrchestrationRecord rec = provisioner().ensure(SERVER, NO_SHELL_POD, NAMESPACE, Clock.systemUTC().instant());

        assertThat(rec.status()).as("K-ENS-5：无 shell pod → FAILED").isEqualTo(OrchestrationRecord.Status.FAILED);
        assertThat(rec.error().reason()).as("reason=no_shell").isEqualTo("no_shell");
        assertThat(dynamicStore.get(logical))
                .as("K-ATOMIC-1：no_shell 失败 → 未注册").isEmpty();
    }

    // ===== K-ENS-7：arthas 绑 loopback → health_check_timeout，未注册 =====

    @Test
    @Order(5)
    void k_ens_7_loopbackBindHealthCheckTimeoutNotRegistered() {
        // 独立 logicalName（避免与 0.0.0.0:8563 实例抢端口/抢 Service）
        String serverLb = SERVER + "-loopback";
        String logical = ArthasProvisioner.deriveLogicalName(serverLb, DEMO_POD);
        OrchestrationRecord rec = loopbackProvisioner().ensure(serverLb, DEMO_POD, NAMESPACE, Clock.systemUTC().instant());

        assertThat(rec.status())
                .as("K-ENS-7：arthas 绑 loopback → FAILED（NodePort 不可达，健康检查超时）")
                .isEqualTo(OrchestrationRecord.Status.FAILED);
        assertThat(rec.error().reason()).as("reason=health_check_timeout").isEqualTo("health_check_timeout");
        assertThat(dynamicStore.get(logical))
                .as("K-ATOMIC-1：health_check 失败 → 未注册").isEmpty();
    }

    // ===== 真实 MCP 可达性探活（arthas MCP 根 URL，带 Bearer 鉴权——0.0.0.0 外部访问强制鉴权） =====

    private static boolean isMcpReachable(String mcpUrl, String arthasPassword) {
        // 0.0.0.0 经 NodePort 外部访问须 Bearer（arthas 4.3.0 强制鉴权），裸 transport 会 401。
        McpSyncClient c = McpClient.sync(
                HttpClientStreamableHttpTransport.builder(mcpUrl)
                        .httpRequestCustomizer(new BackendAuthCustomizer(
                                new BackendConfig.Auth(AuthMode.BEARER, arthasPassword, null, null)))
                        .build())
                .requestTimeout(Duration.ofSeconds(5))
                .build();
        try {
            c.initialize();
            return true;
        } catch (RuntimeException e) {
            return false;
        } finally {
            c.close();
        }
    }

    // ===== fabric8 pod/service 夹具管理（真实集群操作） =====

    private void createPod(String name, String image, String[] command) {
        // 幂等：先删后建。pod spec 多数字段不可变，对已存在 pod 做 createOrReplace 会被 K8S 以 Invalid 拒绝；
        // 先 delete 并等其真正消失，再 create，保证 @BeforeAll 可重跑（残留夹具不再阻塞）。
        client.pods().inNamespace(NAMESPACE).withName(name).delete();
        waitGone(name);
        Pod pod = (command != null
                ? new PodBuilder()
                .withNewMetadata().withName(name).withNamespace(NAMESPACE).endMetadata()
                .withNewSpec().addNewContainer().withName(name).withImage(image)
                .withCommand(command).endContainer().withRestartPolicy("Never").endSpec().build()
                : new PodBuilder()
                .withNewMetadata().withName(name).withNamespace(NAMESPACE).endMetadata()
                .withNewSpec().addNewContainer().withName(name).withImage(image)
                .endContainer().withRestartPolicy("Never").endSpec().build());
        client.pods().inNamespace(NAMESPACE).resource(pod).create();
    }

    /** 等待 pod 真正消失（delete 异步终止，须确认其 gone 再 create，否则命中 terminating pod）。 */
    private void waitGone(String name) {
        for (int i = 0; i < 30; i++) {
            if (client.pods().inNamespace(NAMESPACE).withName(name).get() == null) {
                return;
            }
            sleep(1000);
        }
    }

    private void deletePod(String name) {
        try {
            client.pods().inNamespace(NAMESPACE).withName(name).delete();
        } catch (RuntimeException ignored) {
            // 幂等清理
        }
    }

    private void waitReady(String name) {
        for (int i = 0; i < 30; i++) {
            Pod p = client.pods().inNamespace(NAMESPACE).withName(name).get();
            if (p != null && p.getStatus() != null
                    && p.getStatus().getPhase() != null && p.getStatus().getPhase().equals("Running")) {
                return;
            }
            sleep(1000);
        }
    }

    private void deleteServiceQuiet(String namespace, String serviceName) {
        try {
            exposer.deleteService(namespace, serviceName);
        } catch (RuntimeException ignored) {
            // 幂等
        }
    }

    /** 复用 NodePortExposer 的 sanitize（service 名派生须一致，否则清理删不到）。 */
    private static String sanitize(String logicalName) {
        // 与 NodePortExposer.sanitizeServiceName 同算法：小写、非法→-、截断
        String lower = logicalName.toLowerCase();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lower.length() && sb.length() < 253; i++) {
            char c = lower.charAt(i);
            sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.' ? c : '-');
        }
        return sb.toString().replaceAll("^[^a-z0-9]+", "").replaceAll("[^a-z0-9]+$", "");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
