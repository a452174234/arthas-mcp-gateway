package com.arthas.gateway.admin.backend.dto;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 004 BackendDto 契约（T009）：字段全集 + 凭据脱敏（admin-invariants INV-SECRET-1）。
 */
class BackendDtoTest {

    @Test
    void dtoCarriesAllProjectionFields() {
        BackendDto dto = new BackendDto(
                "order-service", "STATIC", "ACTIVE", true, "CLOSED",
                "http://10.0.0.10:8563", "STREAMABLE", "BEARER",
                5000, 30000, 5,
                null, null, null, "static", null);

        assertThat(dto.name()).isEqualTo("order-service");
        assertThat(dto.source()).isEqualTo("STATIC");
        assertThat(dto.state()).isEqualTo("ACTIVE");
        assertThat(dto.healthy()).isTrue();
        assertThat(dto.breaker()).isEqualTo("CLOSED");
        assertThat(dto.url()).isEqualTo("http://10.0.0.10:8563");
        assertThat(dto.protocol()).isEqualTo("STREAMABLE");
        assertThat(dto.authMode()).isEqualTo("BEARER");
        assertThat(dto.connectTimeoutMs()).isEqualTo(5000);
        assertThat(dto.callTimeoutMs()).isEqualTo(30000);
        assertThat(dto.maxConcurrentTasks()).isEqualTo(5);
    }

    @Test
    void dtoNeverExposesSecrets_invSecret1() {
        // INV-SECRET-1：BackendDto record 组件不含 token/username/password（凭据脱敏，仅 authMode）
        var componentNames = Arrays.stream(BackendDto.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertThat(componentNames)
                .as("BackendDto 不得含机密字段（仅 authMode）")
                .doesNotContain("token", "username", "password")
                .contains("authMode");
    }

    @Test
    void dynamicBackendAndOpenBreakerProjected() {
        BackendDto dynamic = new BackendDto(
                "debian-demo-business", "DYNAMIC", "ACTIVE", false, "OPEN",
                "http://192.168.31.92:32017", "STREAMABLE", "BEARER",
                5000, 30000, 5,
                "debian", "demo-business", "default", "k8s:debian", "ready");
        assertThat(dynamic.source()).isEqualTo("DYNAMIC");
        assertThat(dynamic.healthy()).isFalse();
        assertThat(dynamic.breaker()).isEqualTo("OPEN");
    }
}
