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
}
