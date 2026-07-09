package com.arthas.gateway.orchestration;

import java.util.regex.Pattern;

/**
 * 默认 {@link ArthasLauncher}（005 特性，US3，INV-LAUNCHER-2）。
 *
 * <p>003 既有 {@link ArthasProvisioner#locateJvm} + {@link ArthasProvisioner#startArthas} 逻辑外移：
 * PATH 的 {@code jps}（定位 JVM）+ {@code java -jar} 标准参数（启动 arthas），与 003 逐字一致。
 *
 * <p>装配：{@code @ConditionalOnMissingBean(ArthasLauncher.class)}（用户未提供自定义实现时生效，
 * INV-LAUNCHER-2 兼容现状）。
 */
public class DefaultArthasLauncher implements ArthasLauncher {

    /** 纯数字 PID 行（jps -q 输出）。 */
    private static final Pattern PID_LINE = Pattern.compile("\\s*(\\d+)\\s*");

    @Override
    public long locatePid(LaunchContext ctx) {
        K8sExec.ExecResult r;
        try {
            r = ctx.exec().exec(ctx.namespace(), ctx.pod(), ctx.locateTimeout(),
                    "sh", "-c", "jps -q 2>/dev/null | grep -E '^[0-9]+$' | head -1");
        } catch (K8sExecException e) {
            throw new LaunchException(new OrchestrationRecord.Error("locate_jvm", "k8s_unreachable", e.getMessage()));
        }
        if (r.exitCode() != 0) {
            throw new LaunchException(new OrchestrationRecord.Error("locate_jvm", "no_shell",
                    "jps exec 非零退出（exit=" + r.exitCode() + "）：" + r.stderr()));
        }
        String pidStr = r.stdout().trim();
        if (!PID_LINE.matcher(pidStr).matches()) {
            throw new LaunchException(new OrchestrationRecord.Error("locate_jvm", "no_jvm",
                    "pod 内无运行中 JVM（" + ctx.namespace() + "/" + ctx.pod() + "）"));
        }
        return Long.parseLong(pidStr.trim());
    }

    @Override
    public void startArthas(LaunchContext ctx, long pid) {
        K8sExec.ExecResult r;
        try {
            r = ctx.exec().exec(ctx.namespace(), ctx.pod(), ctx.attachTimeout(),
                    "java", "-jar", ctx.arthasBootJar(), String.valueOf(pid),
                    "--attach-only",
                    "--http-port", String.valueOf(ctx.mcpPort()),
                    "--target-ip", ctx.targetIp(),
                    "--telnet-port", "0",
                    "--use-version", ctx.arthasVersion(),
                    "--password", ctx.arthasPassword());
        } catch (K8sExecException e) {
            throw new LaunchException(new OrchestrationRecord.Error("start_arthas", "attach_failed",
                    "arthas attach 执行失败：" + e.getMessage()));
        }
        if (r.exitCode() != 0) {
            throw new LaunchException(new OrchestrationRecord.Error("start_arthas", "attach_failed",
                    "arthas attach 非零退出（exit=" + r.exitCode() + "）：" + r.stderr()));
        }
    }
}
