package com.arthas.gateway.orchestration;

import java.time.Duration;

/**
 * arthas 启动策略 SPI（005 特性，US3，research.md R7）。
 *
 * <p>把 003 既有 {@link ArthasProvisioner} 硬编码的「定位 JVM + 启动 arthas」抽为策略点：
 * 默认实现 {@link DefaultArthasLauncher}（PATH 的 java/jps + 标准参数，= 003 现状，INV-LAUNCHER-2）；
 * 用户写 {@code @org.springframework.context.annotation.Primary @Component} 实现类覆盖，
 * 定制 javaPath + 完整命令模板（适配容器独立 JDK 部署，FR-009~012，INV-LAUNCHER-3）。
 *
 * <p>失败抛 {@link LaunchException}（携带 {@link OrchestrationRecord.Error}），由
 * {@link ArthasProvisioner} 映射 ensure failed 记录（K-ENS-4/5 不破，INV-LAUNCHER-4）。
 *
 * <p>SPI 测试含 test fixture 真实实现（{@code TestArthasLauncher}，非 mock，INV-LAUNCHER-5）。
 */
public interface ArthasLauncher {

    /** 定位 JVM PID（默认实现：{@code jps -q | head -1}）。失败抛 {@link LaunchException}。 */
    long locatePid(LaunchContext ctx);

    /** 启动 arthas（默认实现：{@code java -jar arthas-boot.jar <pid> --attach-only ...}）。失败抛 {@link LaunchException}。 */
    void startArthas(LaunchContext ctx, long pid);

    /**
     * 启动上下文（SPI 入参，封装 ensure 子步所需）。record 不可变。
     *
     * @param namespace      K8S namespace
     * @param pod            目标 pod 名
     * @param exec           fabric8 exec 工具（默认实现用；自定义实现可用可不用）
     * @param mcpPort        pod 内 arthas MCP 端口（NodePort targetPort）
     * @param targetIp       arthas 绑定地址（0.0.0.0，NodePort 可达）
     * @param arthasVersion  arthas 版本（4.3.0）
     * @param arthasPassword arthas 鉴权密码
     * @param arthasBootJar  pod 内 jar 路径（/tmp/arthas-boot.jar，forward slash，Linux pod 原样用）
     * @param attachTimeout  启动 arthas 超时
     * @param locateTimeout  定位 JVM 超时
     */
    record LaunchContext(
            String namespace,
            String pod,
            K8sExec exec,
            int mcpPort,
            String targetIp,
            String arthasVersion,
            String arthasPassword,
            String arthasBootJar,
            Duration attachTimeout,
            Duration locateTimeout) {
    }

    /** 启动失败（携带 {@link OrchestrationRecord.Error}，供 ArthasProvisioner 映射 failed@locate_jvm/start_arthas）。 */
    class LaunchException extends RuntimeException {
        private final OrchestrationRecord.Error error;

        public LaunchException(OrchestrationRecord.Error error) {
            super(error.reason() + "@" + error.phase() + ": " + error.message());
            this.error = error;
        }

        public OrchestrationRecord.Error error() {
            return error;
        }
    }
}
