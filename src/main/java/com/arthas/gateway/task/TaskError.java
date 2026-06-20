package com.arthas.gateway.task;

import java.util.Objects;

/**
 * 异步任务失败原因（方案 C，gateway-tools-contract.md §2 {@code failed} 分支）。
 *
 * <p>{@code reason} 为稳定枚举串（便于调用方判别），{@code message} 为可读详情。
 * 仅<b>基础设施故障</b>产生 TaskError（后端业务错误 isError=true 走 COMPLETED，不产生 TaskError，G-TG-2）。
 *
 * @param reason  失败类别（见下方常量）
 * @param message 可读详情
 */
public record TaskError(String reason, String message) {

    /** 后端阻塞超 backend-timeout（默认 11min）仍未响应。 */
    public static final String REASON_BACKEND_TIMEOUT = "backend_timeout";
    /** 后端不可达（连接拒绝 / initialize 失败 / 读超时 / SSE 中断）。 */
    public static final String REASON_BACKEND_UNREACHABLE = "backend_unreachable";
    /** 熔断器 OPEN（连续失败达阈值，US3）。 */
    public static final String REASON_CIRCUIT_OPEN = "circuit_open";

    public TaskError {
        Objects.requireNonNull(reason, "reason 不可为空");
        Objects.requireNonNull(message, "message 不可为空");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("reason 不可为空");
        }
    }
}
