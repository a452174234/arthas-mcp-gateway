package com.arthas.gateway.admin.backend.exception;

import java.util.List;

/**
 * 004 目标后端不存在（admin-api-contract：404 + available[]）。携带当前可用名，便于错误提示。
 */
public class BackendNotFoundException extends RuntimeException {

    private final String name;
    private final List<String> available;

    public BackendNotFoundException(String name, List<String> available) {
        super("未知后端：" + name);
        this.name = name;
        this.available = available == null ? List.of() : List.copyOf(available);
    }

    public String getName() {
        return name;
    }

    public List<String> getAvailable() {
        return available;
    }
}
