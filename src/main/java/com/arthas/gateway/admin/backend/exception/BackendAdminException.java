package com.arthas.gateway.admin.backend.exception;

/**
 * 004 管理面基础设施错误（admin-api-contract：500），如 backends.yaml 读写 IO 失败。透明记录（宪法原则五）。
 */
public class BackendAdminException extends RuntimeException {

    public BackendAdminException(String message, Throwable cause) {
        super(message, cause);
    }
}
