package com.arthas.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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

    /**
     * K8S Host 列表（005 特性，{@code arthas-gateway.k8s-hosts}）：远端 Linux K8S 集群入口声明。
     *
     * <p>每个 host 含独立 kubeconfig + namespace；BackendConfig 的 K8S 模式（{@code k8sHost}）引用此 name。
     * 配置位置 = application.yml（重启生效，热重载后置，research.md R4）。MVP 按 host 建独立 provisioner（R5）。
     */
    private List<K8sHost> k8sHosts = new ArrayList<>();

    /**
     * K8S Host 配置文件路径（006 波2，{@code arthas-gateway.k8s-hosts-file}）：独立 {@code config/k8s-hosts.yaml}
     * 热重载源（仿 {@code backends-file}）。文件不存在回退内联 {@link #k8sHosts}（005 兼容，INV-HOT-5）。
     */
    private String k8sHostsFile = "config/k8s-hosts.yaml";

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

    public List<K8sHost> getK8sHosts() {
        return k8sHosts;
    }

    public void setK8sHosts(List<K8sHost> k8sHosts) {
        this.k8sHosts = k8sHosts;
    }

    public String getK8sHostsFile() {
        return k8sHostsFile;
    }

    public void setK8sHostsFile(String k8sHostsFile) {
        this.k8sHostsFile = k8sHostsFile;
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
     * K8S Host（005 特性）：一台被管理的远端 Linux K8S 集群入口。
     *
     * <p>BackendConfig 的 K8S 模式（{@code k8sHost}）引用此 {@code name}；{@link K8sBackendResolver} 据 host
     * 的 kubeconfig 构建独立 KubernetesClient + ArthasProvisioner（research.md R5）。实体见
     * [data-model.md §2](../../specs/005-k8s-orchestration-iteration/data-model.md)。
     */
    public static class K8sHost {
        /** host 逻辑名（跨 host 唯一，BackendConfig.k8sHost 引用此名）。 */
        private String name;
        /** kubeconfig 文件路径（本地凭证；与 {@link #ssh} 互斥，005 既有模式）。 */
        private String kubeconfig;
        /** 默认 namespace（缺省 {@code default}）。 */
        private String namespace = "default";
        /**
         * SSH 引导（006 特性）：远端 master SSH 凭证 + kubeconfig 远端路径。与 {@link #kubeconfig}（本地文件）互斥。
         * 用户只配 master IP+root+密码，网关 SSH 取 admin kubeconfig，免处理 K8S 鉴权。
         */
        private Ssh ssh;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getKubeconfig() {
            return kubeconfig;
        }

        public void setKubeconfig(String kubeconfig) {
            this.kubeconfig = kubeconfig;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public Ssh getSsh() {
            return ssh;
        }

        public void setSsh(Ssh ssh) {
            this.ssh = ssh;
        }

        /**
         * SSH 引导参数（006 特性，{@code arthas-gateway.k8s-hosts[].ssh}）。
         *
         * <p>网关经 SSH 登 master 读取 {@link #kubeconfigRemotePath} 指向的 admin kubeconfig
         * （标准 K8S {@code /etc/kubernetes/admin.conf}；k3s {@code /etc/rancher/k3s/k3s.yaml}），构造 fabric8 client。
         * 实体见 [data-model.md §3](../../specs/006-k8s-host-remote-access/data-model.md)。
         */
        public static class Ssh {
            /** master/control-plane 节点 IP（必填）。 */
            private String host;
            /** SSH 端口（缺省 22）。 */
            private int port = 22;
            /** SSH 用户（必填，通常 root）。 */
            private String user;
            /** SSH 密码（与 privateKey 二选一；走 {@code ${ENV}} 占位符或 portal AES-GCM 加密值）。 */
            private String password;
            /** SSH 私钥（内容或路径；与 password 二选一）。 */
            private String privateKey;
            /** 私钥口令（可选）。 */
            private String passphrase;
            /** 远端 kubeconfig 路径（必填；标准 K8S /etc/kubernetes/admin.conf；k3s /etc/rancher/k3s/k3s.yaml）。 */
            private String kubeconfigRemotePath;
            /** kubeconfig server 替换值（可选；server 不可达时，如 127.0.0.1/VIP/不可解析域名）。 */
            private String serverOverride;
            /** 跳过 TLS 证书校验（可选；apiserver SAN 不含连接地址时兜底，默认 false，开启需知中间人风险）。 */
            private boolean insecureSkipTlsVerify;

            public String getHost() {
                return host;
            }

            public void setHost(String host) {
                this.host = host;
            }

            public int getPort() {
                return port;
            }

            public void setPort(int port) {
                this.port = port;
            }

            public String getUser() {
                return user;
            }

            public void setUser(String user) {
                this.user = user;
            }

            public String getPassword() {
                return password;
            }

            public void setPassword(String password) {
                this.password = password;
            }

            public String getPrivateKey() {
                return privateKey;
            }

            public void setPrivateKey(String privateKey) {
                this.privateKey = privateKey;
            }

            public String getPassphrase() {
                return passphrase;
            }

            public void setPassphrase(String passphrase) {
                this.passphrase = passphrase;
            }

            public String getKubeconfigRemotePath() {
                return kubeconfigRemotePath;
            }

            public void setKubeconfigRemotePath(String kubeconfigRemotePath) {
                this.kubeconfigRemotePath = kubeconfigRemotePath;
            }

            public String getServerOverride() {
                return serverOverride;
            }

            public void setServerOverride(String serverOverride) {
                this.serverOverride = serverOverride;
            }

            public boolean isInsecureSkipTlsVerify() {
                return insecureSkipTlsVerify;
            }

            public void setInsecureSkipTlsVerify(boolean insecureSkipTlsVerify) {
                this.insecureSkipTlsVerify = insecureSkipTlsVerify;
            }
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
