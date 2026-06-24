package com.arthas.gateway.orchestration;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecWatch;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * pod 内同步命令执行助手（003 特性，research.md R2）：封装 fabric8 exec，捕获 stdout/stderr/exit code，
 * 把异步 {@link ExecWatch} 收敛为同步 {@link ExecResult}。
 *
 * <p>供 {@link K8sPodExplorer}（探测 hasJvm/hasShell）与 {@link ArthasProvisioner}（定位 JVM PID、启动 arthas）
 * 共用。命令以 argv 形式下发（如 {@code sh -c "..."}），fabric8 经 K8S exec 子资源在目标 pod 容器内执行。
 *
 * <p><b>进程退出码（关键）</b>：经 {@link ExecWatch#exitCode()}（{@code CompletableFuture<Integer>}）获取
 * <b>真实进程退出码</b>——<b>不</b>用 {@code ExecListener.onClose(code)} 的 code（那是 WebSocket 关闭码，
 * 如 1000=NORMAL，非进程退出状态，曾导致误判）。调用方据此区分「进程退出码非 0」（无 shell/命令失败）与
 * 「exec 通道故障」（API 不可达 / RBAC 403）。
 *
 * <p><b>超时与基础设施失败</b>：命令未在 {@code timeout} 内退出、或 exec 通道本身故障（无 shell / RBAC 403 /
 * API 不可达）→ 抛 {@link K8sExecException}（携带已捕获的 stderr 与 cause）。
 *
 * <p>线程安全：无状态（仅持共享 {@link KubernetesClient}）。
 */
public final class K8sExec {

    private final KubernetesClient client;

    public K8sExec(KubernetesClient client) {
        this.client = Objects.requireNonNull(client, "client 不可为空");
    }

    /** 单次执行结果（exit code + stdout + stderr，UTF-8 解码）。 */
    public record ExecResult(int exitCode, String stdout, String stderr) {
    }

    /**
     * 在指定 pod 容器内同步执行命令。
     *
     * @param namespace 命名空间
     * @param pod       pod 名
     * @param timeout   执行超时（含等待 exit code）
     * @param command   命令 argv（如 {@code "sh", "-c", "echo hi"}）
     * @return 执行结果
     * @throws K8sExecException 超时 / exec 通道故障（无 shell / 403 / API 不可达）
     */
    public ExecResult exec(String namespace, String pod, Duration timeout, String... command) {
        Objects.requireNonNull(namespace, "namespace 不可为空");
        Objects.requireNonNull(pod, "pod 不可为空");
        Objects.requireNonNull(timeout, "timeout 不可为空");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        ExecWatch watch;
        try {
            watch = client.pods().inNamespace(namespace).withName(pod)
                    .writingOutput(out)
                    .writingError(err)
                    .exec(command);
        } catch (RuntimeException e) {
            // exec 启动即失败（API 不可达 / RBAC 403 / pod 不存在）
            throw new K8sExecException("exec 启动失败：" + String.join(" ", command), e, -1,
                    err.toString(StandardCharsets.UTF_8));
        }
        try {
            // 真实进程退出码（CompletableFuture）——非 WebSocket 关闭码
            int code = watch.exitCode().get(timeout.toSeconds(), TimeUnit.SECONDS);
            return new ExecResult(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } catch (TimeoutException e) {
            throw new K8sExecException("exec 超时（" + timeout + "）：" + String.join(" ", command),
                    e, -1, err.toString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new K8sExecException("exec 被中断：" + String.join(" ", command), e, -1,
                    err.toString(StandardCharsets.UTF_8));
        } catch (ExecutionException e) {
            // exitCode() future 异常完成：exec 通道故障（403 / pod 不可达 / 无 shell）
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new K8sExecException("exec 通道故障：" + String.join(" ", command) + "（" + cause + "）",
                    cause, -1, err.toString(StandardCharsets.UTF_8));
        } finally {
            watch.close();
        }
    }
}
