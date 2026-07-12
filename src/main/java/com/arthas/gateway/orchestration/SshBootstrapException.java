package com.arthas.gateway.orchestration;

/**
 * SSH 引导异常（006 波1，T007）：携带结构化 {@link Reason}，由 {@link SshKubeconfigFetcher} 抛出。
 *
 * <p>映射进 ensure failed 记录（沿用 005 结构化错误形态，{@code data-model.md §4}）。reason 枚举对齐
 * [contracts INV-SSH-2](../../../../specs/006-k8s-host-remote-access/contracts/host-remote-access-invariants.md)。
 */
public class SshBootstrapException extends RuntimeException {

    public enum Reason {
        SSH_UNREACHABLE,
        SSH_AUTH_FAILED,
        KUBECONFIG_NOT_FOUND,
        KUBECONFIG_INVALID,
        TLS_HANDSHAKE_FAILED
    }

    private final Reason reason;

    public SshBootstrapException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
