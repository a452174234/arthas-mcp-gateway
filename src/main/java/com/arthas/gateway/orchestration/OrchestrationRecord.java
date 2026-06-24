package com.arthas.gateway.orchestration;

import java.time.Instant;
import java.util.Objects;

/**
 * 一次 {@code ensure-arthas-mcp} 供给的结构化可观测记录（data-model §8，宪法原则五：网关可被诊断）。
 *
 * <p>不可变值对象；状态转换以"返回新记录"的拷贝风格实现（{@link #ready}/{@link #reused}/
 * {@link #failed}），保留 logicalName/server/pod/namespace/createdAt 不变。
 *
 * <p>{@code createdAt} 为<b>传入</b>瞬时量（非进程内取时），与 001 {@code GatewayTask.createdAt} 一致，
 * 便于确定性测试。状态机见 {@link Status}（data-model §9）。
 *
 * @param logicalName  逻辑名 {@code {server}-{pod}}（与注册的 target 名一致）
 * @param server       供给来源服务器名
 * @param pod          目标 pod 名
 * @param namespace    K8S namespace（缺省 default）
 * @param mcpUrl       暴露端点 {@code http://<nodeIP>:<nodePort>}（arthas MCP 根 URL，无 /mcp）；未暴露前 null
 * @param serviceRef   NodePort Service 引用（{@code name/nodePort}）；未创建前 null
 * @param status       供给状态（状态机）
 * @param error        失败原因（仅 failed 时；含 phase/reason/message）
 * @param createdAt    供给发起时间（传入瞬时量）
 * @param completedAt  完成/失败时间（仅终态非 null）
 */
public record OrchestrationRecord(
        String logicalName,
        String server,
        String pod,
        String namespace,
        String mcpUrl,
        String serviceRef,
        Status status,
        Error error,
        Instant createdAt,
        Instant completedAt) {

    /** 供给状态机（data-model §9）。 */
    public enum Status {
        /** 供给进行中（注入/暴露/健康检查/注册），非终态。 */
        ENSURING(false),
        /** 新供给完成、已注册且健康，终态。 */
        READY(true),
        /** 命中幂等复用（注册表已有且健康，零副作用），终态。 */
        REUSED(true),
        /** 任一子步失败且未注册，终态。 */
        FAILED(true);

        private final boolean terminal;

        Status(boolean terminal) {
            this.terminal = terminal;
        }

        /** 是否终态（ready/reused/failed 为终态，ensuring 非终态）。 */
        public boolean isTerminal() {
            return terminal;
        }
    }

    /** 失败原因（data-model §8 {@code error}，仅 failed 时）。 */
    public record Error(String phase, String reason, String message) {
        public Error {
            Objects.requireNonNull(phase, "error.phase 不可为空");
            Objects.requireNonNull(reason, "error.reason 不可为空");
            Objects.requireNonNull(message, "error.message 不可为空");
        }
    }

    /** 起始态工厂：ensuring（非终态），{@code createdAt} 为传入瞬时量。 */
    public static OrchestrationRecord ensuring(String logicalName, String server, String pod,
                                               String namespace, Instant createdAt) {
        return new OrchestrationRecord(logicalName, server, pod, namespace,
                null, null, Status.ENSURING, null, createdAt, null);
    }

    /**
     * 记录已暴露的副作用（Service 已建、mcpUrl 已知），仍处 ensuring（健康检查/注册前）。
     *
     * <p>§4.1：ensure 失败时已打的 pod label / 已建的 Service 作为可清理副作用保留进 failed 记录供运维追溯。
     */
    public OrchestrationRecord withExposed(String mcpUrl, String serviceRef) {
        return new OrchestrationRecord(logicalName, server, pod, namespace,
                mcpUrl, serviceRef, status, error, createdAt, completedAt);
    }

    /** 转换到 ready（全部子步成功、已注册且健康），终态。 */
    public OrchestrationRecord ready(String mcpUrl, String serviceRef, Instant completedAt) {
        return new OrchestrationRecord(logicalName, server, pod, namespace,
                mcpUrl, serviceRef, Status.READY, null, createdAt, completedAt);
    }

    /** 转换到 reused（幂等复用命中，零副作用），终态。 */
    public OrchestrationRecord reused(String mcpUrl, String serviceRef, Instant completedAt) {
        return new OrchestrationRecord(logicalName, server, pod, namespace,
                mcpUrl, serviceRef, Status.REUSED, null, createdAt, completedAt);
    }

    /** 转换到 failed（任一子步失败、未注册），终态；保留已暴露副作用字段。 */
    public OrchestrationRecord failed(Error error, Instant completedAt) {
        return new OrchestrationRecord(logicalName, server, pod, namespace,
                mcpUrl, serviceRef, Status.FAILED, error, createdAt, completedAt);
    }
}
