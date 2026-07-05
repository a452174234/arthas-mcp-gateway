package com.arthas.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 网关顶层配置（前缀 {@code arthas-gateway.*}）。绑定后端映射表文件路径与异步任务默认值。
 *
 * <p><b>后端列表本身不在此绑定</b>：{@code config/backends.yaml} 的内容由 {@code BackendConfigLoader}
 * 直接解析并通过 {@code WatchService} 热重载（免重启，SC-002）。此处仅持有"文件位置"，
 * 以免 Spring {@code @ConfigurationProperties}（启动期一次性加载）与热重载语义冲突。
 *
 * <p><b>传输配置不在本类</b>（SDK 2.0.0 / Spring AI 2.0.0 偏离 spec 的处理，见 memory
 * sdk2-vs-spec-divergences）：MCP 传输由 Spring AI starter 自动装配，经标准属性控制——
 * HTTP 端口/地址走 {@code server.port}/{@code server.address}，MCP 端点走
 * {@code spring.ai.mcp.server.streamable-http.mcp-endpoint}，能力协商走 {@code spring.ai.mcp.server.*}。
 * MVP 仅 Streamable HTTP（stdio 与 HTTP 互斥，stdio 延后）。
 *
 * <p>实体定义见 {@code data-model.md}，配置版本与校验规则见 {@code data-model.md §2/§11}。
 */
@ConfigurationProperties(prefix = "arthas-gateway")
public class GatewayProperties {

    /** 后端映射表文件路径（热重载源）。 */
    private String backendsFile = "config/backends.yaml";

    /** 应用层异步任务（方案 C）默认值。 */
    private Task task = new Task();

    /** K8S 编排子段（003 特性：k8s.list-* / k8s.ensure-arthas-mcp 工具的集群连接与供给参数）。 */
    private K8s k8s = new K8s();

    /** portal 管理面能力开关子段（004 特性，{@code arthas-gateway.admin.*}）。 */
    private Admin admin = new Admin();

    public String getBackendsFile() {
        return backendsFile;
    }

    public void setBackendsFile(String backendsFile) {
        this.backendsFile = backendsFile;
    }

    public Task getTask() {
        return task;
    }

    public void setTask(Task task) {
        this.task = task;
    }

    public K8s getK8s() {
        return k8s;
    }

    public void setK8s(K8s k8s) {
        this.k8s = k8s;
    }

    public Admin getAdmin() {
        return admin;
    }

    public void setAdmin(Admin admin) {
        this.admin = admin;
    }

    /** 异步任务默认值（方案 C，详见 research.md §4）。 */
    public static class Task {
        /** 后台阻塞等后端路①的兜底超时（> 后端 10min 上限，超此标 failed）。 */
        private Duration backendTimeout = Duration.ofMinutes(11);
        /** 已完成任务可查询保留时长（TTL 清理）。 */
        private Duration resultTtl = Duration.ofHours(1);
        /**
         * 全局在途异步任务上限(跨 target 累计,P2-4/FR-010)。
         * <p>{@code null}/未设 → 动态默认 = 注册表后端数 × 5(每次 submit 按当前注册表 size 计算,
         * 后端增减随之伸缩)。显式设正值 → 固定上限(便于压测/限流调优)。
         */
        private Integer globalMaxInflight;

        public Duration getBackendTimeout() {
            return backendTimeout;
        }

        public void setBackendTimeout(Duration backendTimeout) {
            this.backendTimeout = backendTimeout;
        }

        public Duration getResultTtl() {
            return resultTtl;
        }

        public void setResultTtl(Duration resultTtl) {
            this.resultTtl = resultTtl;
        }

        public Integer getGlobalMaxInflight() {
            return globalMaxInflight;
        }

        public void setGlobalMaxInflight(Integer globalMaxInflight) {
            this.globalMaxInflight = globalMaxInflight;
        }
    }

    /**
     * K8S 编排子段（003 特性，{@code arthas-gateway.k8s.*}）。
     *
     * <p>承载 {@code k8s.list-*} / {@code k8s.ensure-arthas-mcp} 工具连接测试集群（debian 上 k3s）
     * 与供给（arthas 注入 + NodePort 暴露）所需的参数。kubeconfig 指向 root-on-node 派生的 admin 凭证
     * （见 [K8S 测试环境设计](../../docs/superpowers/specs/2026-06-23-k8s-test-env-setup-design.md)）。
     * 决策见 [research.md R2/R3](../specs/003-k8s-arthas-mcp-launch/research.md)。
     */
    public static class K8s {

        /** kubeconfig 文件路径（集群外运行网关时远程连 k3s API）。缺省指向测试床导出凭证。 */
        private String kubeconfig = "test-env/k8s/kubeconfig/k3s-admin.yaml";

        /** kubeconfig 内使用的 context（null/缺省取 kubeconfig current-context）。 */
        private String context;

        /** 默认 namespace（list-* / ensure 缺省 namespace 时）。 */
        private String namespace = "default";

        /** NodePort 分配范围（K8S 默认 30000–32767）。ensure 建 NodePort Service 时由集群在此范围自动分配。 */
        private String nodePortRange = "30000-32767";

        /** ensure 全流程超时（注入 + 暴露 + 健康检查 + 注册）。须 > arthas attach + 健康轮询时间。 */
        private Duration ensureTimeout = Duration.ofMinutes(5);

        /** ensure 启动 arthas MCP 的绑定 IP（research.md R4：0.0.0.0 产出 wildcard、NodePort 可达；127.0.0.1 不可达）。 */
        private String targetIp = "0.0.0.0";

        /** arthas-boot.jar 静态工具文件路径（memory arthas-no-dependency：不入 pom、ensure 时上传进 pod）。 */
        private String arthasBootJar = "tools/arthas-boot.jar";

        /** ensure 注入的 arthas MCP 在 pod 内监听端口（NodePort targetPort）。 */
        private int mcpPort = 8563;

        /** 锁定的 arthas 版本（设计 §5：--use-version 4.3.0）。 */
        private String arthasVersion = "4.3.0";

        /**
         * arthas MCP HTTP 服务的访问密码（003 实测发现：arthas 绑 0.0.0.0 暴露外部时强制鉴权，
         * 不配则自动生成随机密码且外部访问 401）。ensure 经 {@code --password} 下发已知值，
         * 并以 Bearer 令牌形态注入动态后端 {@code Auth}，使网关与健康检查均可鉴权访问。缺省为测试床占位值，
         * 生产应显式覆盖（{@code arthas-gateway.k8s.arthas-password}）。
         */
        private String arthasPassword = "arthas-mcp-gateway";

        public String getKubeconfig() {
            return kubeconfig;
        }

        public void setKubeconfig(String kubeconfig) {
            this.kubeconfig = kubeconfig;
        }

        public String getContext() {
            return context;
        }

        public void setContext(String context) {
            this.context = context;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public String getNodePortRange() {
            return nodePortRange;
        }

        public void setNodePortRange(String nodePortRange) {
            this.nodePortRange = nodePortRange;
        }

        public Duration getEnsureTimeout() {
            return ensureTimeout;
        }

        public void setEnsureTimeout(Duration ensureTimeout) {
            this.ensureTimeout = ensureTimeout;
        }

        public String getTargetIp() {
            return targetIp;
        }

        public void setTargetIp(String targetIp) {
            this.targetIp = targetIp;
        }

        public String getArthasBootJar() {
            return arthasBootJar;
        }

        public void setArthasBootJar(String arthasBootJar) {
            this.arthasBootJar = arthasBootJar;
        }

        public int getMcpPort() {
            return mcpPort;
        }

        public void setMcpPort(int mcpPort) {
            this.mcpPort = mcpPort;
        }

        public String getArthasVersion() {
            return arthasVersion;
        }

        public void setArthasVersion(String arthasVersion) {
            this.arthasVersion = arthasVersion;
        }

        public String getArthasPassword() {
            return arthasPassword;
        }

        public void setArthasPassword(String arthasPassword) {
            this.arthasPassword = arthasPassword;
        }
    }

    /**
     * portal 管理面能力开关子段（004 特性，{@code arthas-gateway.admin.*}）。
     *
     * <p>承载后端 CRUD 与任务导出能力的按需启用开关（research.md R9 / spec FR-014）。
     * 各能力经 {@code @ConditionalOnProperty} 独立装配，关闭则对应端点 404、前端降级提示。
     */
    public static class Admin {

        /** 后端配置 CRUD 能力（{@code arthas-gateway.admin.crud.enabled}，默认开）。 */
        private Crud crud = new Crud();

        /** 异步任务结果导出能力（{@code arthas-gateway.admin.export.enabled}，默认开）。 */
        private Export export = new Export();

        public Crud getCrud() {
            return crud;
        }

        public void setCrud(Crud crud) {
            this.crud = crud;
        }

        public Export getExport() {
            return export;
        }

        public void setExport(Export export) {
            this.export = export;
        }

        /** 后端配置 CRUD 开关（/admin/backends）。 */
        public static class Crud {
            private boolean enabled = true;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }
        }

        /** 任务结果导出开关（/admin/tasks/{id}/export）。 */
        public static class Export {
            private boolean enabled = true;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }
        }
    }
}
