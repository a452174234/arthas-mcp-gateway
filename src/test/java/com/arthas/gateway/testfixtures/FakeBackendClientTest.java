package com.arthas.gateway.testfixtures;

import com.arthas.gateway.handler.McpErrorCodes;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T002 {@link FakeBackendClient} 自验证:确认 test double 可配置行为正确(它是测试基建,自身须可信)。
 */
class FakeBackendClientTest {

    @Test
    void initializeIsCountedAndDefaultsToSuccess() {
        FakeBackendClient fake = new FakeBackendClient();
        assertThat(fake.initializeCount()).isZero();
        assertThat(fake.isInitialized()).isFalse();

        fake.initialize();
        fake.initialize();

        assertThat(fake.initializeCount()).isEqualTo(2);
        assertThat(fake.isInitialized()).isTrue();
    }

    @Test
    void initializeCanThrowConfiguredException() {
        FakeBackendClient fake = new FakeBackendClient();
        fake.setInitializeThrow(new IllegalStateException("initialize boom"));

        assertThatThrownBy(fake::initialize).isInstanceOf(IllegalStateException.class);
        assertThat(fake.initializeCount()).isEqualTo(1); // 仍计数(供 CAS 测试观测)
    }

    @Test
    void callToolReturnsConfiguredResult() {
        FakeBackendClient fake = new FakeBackendClient();
        CallToolResult businessError = new CallToolResult(
                List.of(new TextContent("isError=true 业务错误")), true, null, null);
        fake.setCallToolResult(businessError);

        CallToolResult r = fake.callTool("watch", java.util.Map.of());

        assertThat(r).isSameAs(businessError);
        assertThat(r.isError()).isTrue();
    }

    @Test
    void callToolCanThrowConfiguredRuntimeException() {
        FakeBackendClient fake = new FakeBackendClient();
        fake.setCallToolThrow(new java.io.UncheckedIOException(new java.io.IOException("conn refused")));

        assertThatThrownBy(() -> fake.callTool("jvm", java.util.Map.of()))
                .isInstanceOf(java.io.UncheckedIOException.class);
    }

    @Test
    void callToolCanThrowMcpError() {
        FakeBackendClient fake = new FakeBackendClient();
        fake.setCallToolThrow(McpError.builder(McpErrorCodes.INVALID_PARAMS)
                .message("后端业务错误").build());

        assertThatThrownBy(() -> fake.callTool("jvm", java.util.Map.of()))
                .isInstanceOf(McpError.class);
    }

    @Test
    void callToolBlockPreservesInterruptStatusWhenInterrupted() throws Exception {
        FakeBackendClient fake = new FakeBackendClient();
        CountDownLatch block = new CountDownLatch(1);
        fake.setCallToolBlock(block);
        Thread t = Thread.currentThread();

        // 另一线程中断当前线程,callTool.await 应抛并恢复中断态
        Thread interrupter = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                // ignore
            }
            t.interrupt();
        });
        interrupter.start();

        assertThatThrownBy(() -> fake.callTool("watch", java.util.Map.of()))
                .isInstanceOf(RuntimeException.class);
        assertThat(Thread.interrupted()).as("中断态已恢复(供 invoke 检测跳过 recordFailure)").isTrue();
    }
}
