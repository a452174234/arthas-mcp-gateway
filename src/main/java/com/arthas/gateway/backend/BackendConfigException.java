package com.arthas.gateway.backend;

/**
 * 后端配置加载/校验失败（data-model.md §11 规则 7）。
 *
 * <p>YAML 解析、字段校验或跨实例校验（name 重复等）失败时抛出。热重载调用方（T039）
 * 捕获后<b>保留旧注册表</b>、记 ERROR、不半替换（原子性）。
 */
public class BackendConfigException extends RuntimeException {

    public BackendConfigException(String message) {
        super(message);
    }

    public BackendConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
