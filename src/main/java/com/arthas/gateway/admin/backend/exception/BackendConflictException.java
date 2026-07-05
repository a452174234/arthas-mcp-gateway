package com.arthas.gateway.admin.backend.exception;

/**
 * 004 后端操作冲突/校验失败（admin-api-contract：400 + reason）。如 name 重复、改动态后端（INV-DYN-1）。
 */
public class BackendConflictException extends RuntimeException {

    private final String reason;

    public BackendConflictException(String message, String reason) {
        super(message);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
