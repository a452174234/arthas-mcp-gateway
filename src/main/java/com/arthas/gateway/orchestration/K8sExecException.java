package com.arthas.gateway.orchestration;

/**
 * pod exec 失败（003 特性）。封装超时、通道故障、基础设施错误（无 shell / RBAC 403 / API 不可达）。
 *
 * <p>携带 {@code exitCode}（-1 表示未获取到，如超时/通道异常）与 {@code stderr}（已捕获的容器标准错误，
 * 供调用方分类故障原因）。由 {@link ArthasProvisioner} 捕获后映射为契约 {@code reason/stage}。
 */
public class K8sExecException extends RuntimeException {

    private final int exitCode;
    private final String stderr;

    public K8sExecException(String message, Throwable cause, int exitCode, String stderr) {
        super(message, cause);
        this.exitCode = exitCode;
        this.stderr = stderr == null ? "" : stderr;
    }

    /** exec exit code（-1 = 未获取，如超时/通道异常）。 */
    public int exitCode() {
        return exitCode;
    }

    /** 已捕获的容器标准错误（可能为空）。 */
    public String stderr() {
        return stderr;
    }
}
