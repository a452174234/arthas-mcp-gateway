package com.arthas.gateway.orchestration;

import com.arthas.gateway.auth.BackendAuthCustomizer;
import com.arthas.gateway.backend.AuthMode;
import com.arthas.gateway.backend.BackendConfig;
import com.arthas.gateway.backend.BackendConfigException;
import com.arthas.gateway.backend.DynamicBackendStore;
import com.arthas.gateway.backend.Protocol;
import com.arthas.gateway.backend.Source;
import com.arthas.gateway.config.K8sParams;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * arthas MCP 供给器（003 特性，契约 §3，data-model §8）：对指定 K8S pod <b>原子幂等</b>完成
 * 「注入 arthas → 启动绑 0.0.0.0 的 arthas MCP → NodePort 暴露 → 内部健康检查 → 动态注册进网关」，
 * 任一子步失败 → <b>不注册</b>（status=failed），全部成功 → status=ready，幂等命中 → status=reused。
 *
 * <h3>流程（契约 §3 行为）</h3>
 * <ol>
 *   <li>派生 {@code logicalName = {server}-{pod}}（K-ENS-8）。</li>
 *   <li>注册表已有且后端健康（MCP 握手成功）→ <b>零副作用复用</b>（status=reused，K-ENS-2）。</li>
 *   <li>否则供给（ensuring）：
 *     <ul>
 *       <li>exec 定位 JVM PID（无 shell/403/不可达→分类故障；无 PID→no_jvm）。</li>
 *       <li>上传 arthas-boot.jar 进 pod /tmp/。</li>
 *       <li>exec 启动 {@code java -jar /tmp/arthas-boot.jar <pid> --attach-only --http-port <mcpPort>
 *           --target-ip <targetIp> --use-version 4.3.0}（R4：0.0.0.0 方 NodePort 可达）。</li>
 *       <li>NodePort 暴露 → mcpUrl（根 URL，无 /mcp）。</li>
 *       <li>内部健康检查：轮询 mcpUrl 的 initialize 握手确认就绪（超时→health_check_timeout；K-ENS-7
 *           loopback 经 NodePort 不可达即触发）。</li>
 *       <li>{@link DynamicBackendStore#register}（source=DYNAMIC）+ composer 原子 swap effective。</li>
 *     </ul>
 *   </li>
 *   <li>任一子步失败 → 不注册、记 failed（已打 label/已建 Service 进 record 供清理，K-ATOMIC-1）。</li>
 * </ol>
 *
 * <p><b>真实性</b>：全程真实 fabric8 exec + 真实 arthas 注入 + 真实 NodePort + 真实 MCP 握手健康检查（零桩）。
 * 诊断复用 gateway-core 既有路由（ensure 仅产出 target，诊断由既有 watch/trace 等经 {@code ToolsCallRouter}）。
 */
public class ArthasProvisioner {

    private static final Logger log = LoggerFactory.getLogger(ArthasProvisioner.class);

    /** pod 内 arthas MCP 监听端口（NodePort targetPort）。 */
    static final int DEFAULT_MCP_PORT = 8563;
    /** locate JVM / install / start exec 超时。 */
    private static final Duration LOCATE_TIMEOUT = Duration.ofSeconds(10);
    /** arthas attach 进程超时（首跑下载 arthas 4.3.0 运行时到 ~/.arthas/lib/4.3.0，留足余量）。 */
    private static final Duration ATTACH_TIMEOUT = Duration.ofSeconds(300);
    /** 健康检查轮询总超时（arthas MCP 就绪握手）默认值。 */
    static final Duration DEFAULT_HEALTH_CHECK_TIMEOUT = Duration.ofSeconds(90);
    /** 健康检查单次握手请求超时。 */
    private static final Duration HEALTH_PROBE_REQUEST_TIMEOUT = Duration.ofSeconds(3);
    /** 健康检查轮询间隔。 */
    private static final Duration HEALTH_POLL_INTERVAL = Duration.ofMillis(1000);
    /** 动态注册后端的连接/调用超时与并发槽（复用 001 默认）。 */
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int CALL_TIMEOUT_MS = 30000;
    private static final int MAX_CONCURRENT_TASKS = 5;
    /** pod 内 arthas-boot.jar 落点。 */
    private static final String REMOTE_ARTHAS_JAR = "/tmp/arthas-boot.jar";
    /** 纯数字 PID 行（jps -q 输出）。 */
    private static final Pattern PID_LINE = Pattern.compile("\\s*(\\d+)\\s*");

    private final KubernetesClient client;
    private final K8sExec exec;
    private final NodePortExposer exposer;
    /** 005 US3：arthas 启动委托（locatePid + startArthas）；用户 @Primary 实现覆盖 DefaultArthasLauncher（INV-LAUNCHER-3）。 */
    private final ArthasLauncher launcher;
    /**
     * 006 波2 T020：动态 K8S 参数供应（全局参数热生效，INV-HOT-3）。
     * <p>{@code null} = 用构造值（005 兼容；006 装配经 {@code setParamsSupplier(K8sHostStore::currentParams)} 注入）。
     * {@link #buildContext} 读 supplier 取 mcpPort/targetIp/arthasVersion/arthasPassword。
     */
    private Supplier<K8sParams> paramsSupplier;

    /** 006 波2 T020：注入参数供应（{@code K8sHostStore::currentParams}），使 ensure 读最新全局参数。 */
    public void setParamsSupplier(Supplier<K8sParams> paramsSupplier) {
        this.paramsSupplier = paramsSupplier;
    }
    private final DynamicBackendStore dynamicStore;
    private final OrchestrationRecordStore recordStore;
    private final String targetIp;
    private final Path arthasBootJar;
    private final int mcpPort;
    private final String arthasVersion;
    /** arthas HTTP 访问密码（--password 下发；Bearer 令牌注入动态后端 Auth + 健康检查）。 */
    private final String arthasPassword;
    /** 动态后端认证声明（BEARER + arthasPassword），供注册与健康检查复用。 */
    private final BackendConfig.Auth auth;
    /** 健康检查轮询总超时（生产用默认 90s；K-ENS-7 loopback 故障用例注入短超时快速失败）。 */
    private final Duration healthCheckTimeout;

    /** 生产装配（参数来自 {@link com.arthas.gateway.config.GatewayProperties.K8s}），launcher 用 {@link DefaultArthasLauncher}。 */
    public ArthasProvisioner(KubernetesClient client, NodePortExposer exposer,
                             DynamicBackendStore dynamicStore, OrchestrationRecordStore recordStore,
                             String targetIp, String arthasBootJar, int mcpPort, String arthasVersion,
                             String arthasPassword) {
        this(client, exposer, dynamicStore, recordStore, targetIp, arthasBootJar, mcpPort,
                arthasVersion, arthasPassword, DEFAULT_HEALTH_CHECK_TIMEOUT, new DefaultArthasLauncher());
    }

    /**
     * 测试构造器：可注入健康检查轮询总超时（launcher 默认 {@link DefaultArthasLauncher}）。
     *
     * <p>K-ENS-7（arthas 绑 loopback → NodePort 不可达 → 健康检查超时）需<b>短</b>超时快速失败。
     */
    ArthasProvisioner(KubernetesClient client, NodePortExposer exposer,
                      DynamicBackendStore dynamicStore, OrchestrationRecordStore recordStore,
                      String targetIp, String arthasBootJar, int mcpPort, String arthasVersion,
                      String arthasPassword, Duration healthCheckTimeout) {
        this(client, exposer, dynamicStore, recordStore, targetIp, arthasBootJar, mcpPort,
                arthasVersion, arthasPassword, healthCheckTimeout, new DefaultArthasLauncher());
    }

    /**
     * 005 US3：注入 {@link ArthasLauncher}（locatePid + startArthas 委托；用户 @Primary 实现覆盖 Default）。
     */
    public ArthasProvisioner(KubernetesClient client, NodePortExposer exposer,
                             DynamicBackendStore dynamicStore, OrchestrationRecordStore recordStore,
                             String targetIp, String arthasBootJar, int mcpPort, String arthasVersion,
                             String arthasPassword, Duration healthCheckTimeout, ArthasLauncher launcher) {
        this.client = Objects.requireNonNull(client, "client 不可为空");
        this.exec = new K8sExec(client);
        this.exposer = Objects.requireNonNull(exposer, "exposer 不可为空");
        this.dynamicStore = Objects.requireNonNull(dynamicStore, "dynamicStore 不可为空");
        this.recordStore = Objects.requireNonNull(recordStore, "recordStore 不可为空");
        this.targetIp = Objects.requireNonNull(targetIp, "targetIp 不可为空");
        this.mcpPort = mcpPort > 0 ? mcpPort : DEFAULT_MCP_PORT;
        this.arthasVersion = Objects.requireNonNull(arthasVersion, "arthasVersion 不可为空");
        this.arthasPassword = Objects.requireNonNull(arthasPassword, "arthasPassword 不可为空");
        this.auth = new BackendConfig.Auth(AuthMode.BEARER, arthasPassword, null, null);
        this.healthCheckTimeout = Objects.requireNonNull(healthCheckTimeout, "healthCheckTimeout 不可为空");
        this.launcher = Objects.requireNonNull(launcher, "launcher 不可为空");
        Path jar = Path.of(Objects.requireNonNull(arthasBootJar, "arthasBootJar 不可为空"));
        if (!Files.isReadable(jar)) {
            throw new IllegalStateException("arthas-boot.jar 不可读：" + jar.toAbsolutePath()
                    + "（应作为静态工具文件置于工程 tools/，见 memory arthas-no-dependency）");
        }
        this.arthasBootJar = jar;
    }

    /** 005 US3：构造 LaunchContext（namespace/pod + exec + arthas 启动参数 + 远程 jar path），传 ArthasLauncher。 */
    private ArthasLauncher.LaunchContext buildContext(String namespace, String pod) {
        // 006 波2 T020：paramsSupplier 设了 → 读动态全局参数（热生效，INV-HOT-3）；null → 构造值（005 兼容）
        K8sParams p = paramsSupplier != null ? paramsSupplier.get() : null;
        return new ArthasLauncher.LaunchContext(namespace, pod, exec,
                p != null ? p.mcpPort() : mcpPort,
                p != null ? p.targetIp() : targetIp,
                p != null ? p.arthasVersion() : arthasVersion,
                p != null ? p.arthasPassword() : arthasPassword,
                REMOTE_ARTHAS_JAR, ATTACH_TIMEOUT, LOCATE_TIMEOUT);
    }

    /** 确定性派生逻辑名（target 名）= {@code {server}-{pod}}（K-ENS-8）。 */
    public static String deriveLogicalName(String server, String pod) {
        return server + "-" + pod;
    }

    /**
     * 原子幂等供给。
     *
     * @param server    服务器名（逻辑名前缀）
     * @param pod       目标 pod 名
     * @param namespace K8S namespace
     * @param now       供给发起时间（传入瞬时量，便于确定性测试）
     * @return 终态记录（ready/reused/failed）
     */
    public OrchestrationRecord ensure(String server, String pod, String namespace, Instant now) {
        Objects.requireNonNull(server, "server 不可为空");
        Objects.requireNonNull(pod, "pod 不可为空");
        Objects.requireNonNull(namespace, "namespace 不可为空");
        Objects.requireNonNull(now, "now 不可为空");

        String logicalName = deriveLogicalName(server, pod);
        OrchestrationRecord rec = OrchestrationRecord.ensuring(logicalName, server, pod, namespace, now);
        recordStore.record(rec);

        // 幂等复用：注册表已有且后端健康 → 零副作用 reused（K-ENS-2）
        Optional<BackendConfig> existing = dynamicStore.get(logicalName);
        if (existing.isPresent()) {
            String url = existing.get().url();
            if (probeHealthy(url, Duration.ofSeconds(HEALTH_PROBE_REQUEST_TIMEOUT.toSeconds() * 2))) {
                OrchestrationRecord reused = rec.reused(url, null, now);
                recordStore.record(reused);
                log.info("ensure 幂等复用：target={} url={}", logicalName, url);
                return reused;
            }
            log.info("ensure 注册表命中但后端不健康，重新供给：target={}", logicalName);
        }

        // 供给（任一子步失败 → failed 不注册，K-ATOMIC-1）
        OrchestrationRecord terminal = doProvision(rec, namespace, pod, logicalName, now);
        recordStore.record(terminal);
        return terminal;
    }

    /**
     * 供给子流程。任一子步抛 {@link ProvisionException} → 返回 failed 记录（<b>不</b>注册）；
     * 全部成功 → 返回 ready 记录。
     *
     * <p>failed 记录由<b>局部</b> {@code rec} 派生，故即便失败发生在 NodePort 暴露之后（如 health_check），
     * 已打的 mcpUrl/serviceRef 副作用仍随 failed 记录留存（K-ATOMIC-1：副作用供运维追溯清理）。
     */
    private OrchestrationRecord doProvision(OrchestrationRecord rec, String namespace, String pod,
                                            String logicalName, Instant now) {
        try {
            // 1. 定位 JVM PID（无 shell/403/不可达/无 JVM → 分类）
            long pid = locateJvm(namespace, pod);

            // 2. 上传 arthas-boot.jar
            installArthas(namespace, pod);

            // 3. 启动 arthas（attach 进程短命，首跑下载 arthas 运行时）
            startArthas(namespace, pod, pid);

            // 4. NodePort 暴露 → mcpUrl
            NodePortExposer.ExposeResult exposed;
            try {
                exposed = exposer.expose(namespace, pod, logicalName, mcpPort);
            } catch (RuntimeException e) {
                throw new ProvisionException(errorOf("nodeport_alloc_failed", "expose_nodeport",
                        "NodePort Service 创建失败：" + e.getMessage()));
            }
            String mcpUrl = exposed.mcpUrl();
            String serviceRef = exposed.serviceRef();
            rec = rec.withExposed(mcpUrl, serviceRef); // 记录已暴露副作用（供 failed 追溯清理）

            // 5. 健康检查：轮询 mcpUrl MCP 握手（超时 → health_check_timeout；K-ENS-7 loopback 不可达即触发）
            if (!probeHealthy(mcpUrl, healthCheckTimeout)) {
                throw new ProvisionException(errorOf("health_check_timeout", "health_check",
                        "arthas MCP 在超时内未就绪（mcpUrl=" + mcpUrl + "，target-ip=" + targetIp + "）"));
            }

            // 6. 动态注册（source=DYNAMIC）+ composer 原子 swap
            BackendConfig cfg = new BackendConfig(
                    logicalName, mcpUrl, Protocol.STREAMABLE,
                    auth,
                    CONNECT_TIMEOUT_MS, CALL_TIMEOUT_MS, MAX_CONCURRENT_TASKS, Source.DYNAMIC);
            try {
                dynamicStore.register(cfg);
            } catch (BackendConfigException e) {
                // 命名冲突（动态名 ∩ 静态种子 / 同名异 URL）→ name_conflict（K-ENS-9）
                throw new ProvisionException(errorOf("name_conflict", "register", e.getMessage()));
            }

            log.info("ensure 供给完成：target={} mcpUrl={}", logicalName, mcpUrl);
            return rec.ready(mcpUrl, serviceRef, now);
        } catch (ProvisionException e) {
            OrchestrationRecord failed = rec.failed(e.error, now);
            // 末位传 e：SLF4J 打印完整异常链 + 堆栈（宪法原则五可观测性；上传失败原委此前被吞）
            log.warn("ensure 失败（未注册）：target={} reason={} stage={}", logicalName,
                    e.error.reason(), e.error.phase(), e);
            return failed;
        }
    }

    // ===== 子步：定位 JVM =====

    /** 005 US3：委托 {@link ArthasLauncher#locatePid}（定位 JVM PID；003 既有 jps 逻辑外移至 DefaultArthasLauncher）。 */
    private long locateJvm(String namespace, String pod) {
        try {
            return launcher.locatePid(buildContext(namespace, pod));
        } catch (ArthasLauncher.LaunchException e) {
            throw new ProvisionException(e.error());
        }
    }

    // ===== 子步：上传 arthas-boot.jar =====

    /** package-private 供 spy 测试覆盖（T023 跳过真实 upload，聚焦 launcher 委托）。 */
    void installArthas(String namespace, String pod) {
        try {
            boolean ok = client.pods().inNamespace(namespace).withName(pod)
                    .file(REMOTE_ARTHAS_JAR).upload(arthasBootJar);
            if (!ok) {
                log.warn("installArthas：fabric8 upload 返回 false（namespace={}, pod={}, jar={}）",
                        namespace, pod, arthasBootJar);
                throw new ProvisionException(errorOf("attach_failed", "install_arthas",
                        "上传 arthas-boot.jar 返回 false（" + namespace + "/" + pod + "）"));
            }
        } catch (RuntimeException e) {
            if (e instanceof ProvisionException) {
                throw e;
            }
            // 记录原始异常堆栈（宪法原则五：上传失败根因此前被吞，仅留 reason/stage）
            log.warn("installArthas：fabric8 upload 抛异常（namespace={}, pod={}）", namespace, pod, e);
            throw new ProvisionException(errorOf("attach_failed", "install_arthas",
                    "上传 arthas-boot.jar 失败：" + e.getMessage()), e);
        }
    }

    // ===== 子步：启动 arthas =====

    /** 005 US3：委托 {@link ArthasLauncher#startArthas}（启动 arthas attach；003 既有 java -jar 逻辑外移至 DefaultArthasLauncher）。 */
    private void startArthas(String namespace, String pod, long pid) {
        try {
            launcher.startArthas(buildContext(namespace, pod), pid);
        } catch (ArthasLauncher.LaunchException e) {
            throw new ProvisionException(e.error());
        }
    }

    // ===== 健康检查（MCP 握手轮询） =====

    /** 轮询 mcpUrl 直至 MCP initialize 握手成功（arthas MCP 就绪）；超时返 false。 */
    boolean probeHealthy(String mcpUrl, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            try (McpSyncClient c = McpClient.sync(
                    HttpClientStreamableHttpTransport.builder(mcpUrl)
                            .httpRequestCustomizer(new BackendAuthCustomizer(auth))
                            .build())
                    .requestTimeout(HEALTH_PROBE_REQUEST_TIMEOUT)
                    .build()) {
                c.initialize(); // arthas MCP 就绪则握手成功
                return true;
            } catch (RuntimeException e) {
                sleepQuiet(HEALTH_POLL_INTERVAL);
            }
        }
        return false;
    }

    private static void sleepQuiet(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** exec 非零退出分类（locate_jvm 等）：负值=通道故障/无 shell；stderr 含 not-found→no_shell；其余→k8s_unreachable。 */
    private static OrchestrationRecord.Error classifyExecResult(K8sExec.ExecResult r, String stage) {
        String combined = (r.stdout() + " " + r.stderr()).toLowerCase();
        if (r.exitCode() < 0
                || combined.contains("executable file not found") || combined.contains("not found in path")
                || combined.contains("no such file or directory") || combined.contains("not a directory")) {
            return errorOf("no_shell", stage,
                    "pod 内无可用 shell（exec 非零退出 exit=" + r.exitCode() + "）：" + r.stderr().trim());
        }
        if (combined.contains("forbidden")) {
            return errorOf("k8s_forbidden", stage, "exec 被 RBAC 拒绝（exit=" + r.exitCode() + "）：" + r.stderr().trim());
        }
        return errorOf("k8s_unreachable", stage,
                "exec 故障（exit=" + r.exitCode() + "）：" + r.stderr().trim());
    }

    // ===== 故障分类 =====

    /** exec 基础设施故障分类：403→k8s_forbidden；命令未找到→no_shell；其余→k8s_unreachable。 */
    private static OrchestrationRecord.Error classifyExecError(K8sExecException e, String stage) {
        String msg = (e.getMessage() + " " + e.stderr()).toLowerCase();
        Throwable cause = e.getCause();
        int code = -1;
        if (cause instanceof io.fabric8.kubernetes.client.KubernetesClientException kce) {
            code = kce.getCode();
        }
        if (code == 403 || msg.contains("forbidden")) {
            return errorOf("k8s_forbidden", stage, "exec 被 RBAC 拒绝（403）：" + e.getMessage());
        }
        if (msg.contains("executable file not found") || msg.contains("not found in path")
                || msg.contains("no such file or directory") || msg.contains("not a directory")) {
            return errorOf("no_shell", stage, "pod 内无可用 shell（exec 失败）：" + e.getMessage());
        }
        return errorOf("k8s_unreachable", stage, "exec 通道故障：" + e.getMessage());
    }

    private static OrchestrationRecord.Error errorOf(String reason, String stage, String message) {
        return new OrchestrationRecord.Error(stage, reason, message);
    }

    /** 供给子步失败（携带 reason/phase/message，供 ensure 映射为 failed 记录）。 */
    private static final class ProvisionException extends RuntimeException {
        final OrchestrationRecord.Error error;

        ProvisionException(OrchestrationRecord.Error error) {
            super(error.reason() + "@" + error.phase() + ": " + error.message());
            this.error = error;
        }

        ProvisionException(OrchestrationRecord.Error error, Throwable cause) {
            super(error.reason() + "@" + error.phase() + ": " + error.message(), cause);
            this.error = error;
        }
    }
}
