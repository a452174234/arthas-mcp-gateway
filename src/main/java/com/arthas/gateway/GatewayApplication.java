package com.arthas.gateway;

import com.arthas.gateway.config.GatewayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * arthas MCP 网关入口。
 *
 * <p>作为标准 MCP 服务端（stdio + Streamable HTTP）向 Claude Code 等客户端暴露统一的 arthas 诊断能力，
 * 同时作为 MCP 客户端连接并管理多个目标 JVM 的 arthas MCP 后端。详见 specs/001-arthas-mcp-gateway/。
 *
 * <p>传输装配（transport 包）与协议核心（handler 包）在 Phase 2+ 接入；本类仅承载 Spring Boot 生命周期。
 */
@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = GatewayProperties.class)
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
