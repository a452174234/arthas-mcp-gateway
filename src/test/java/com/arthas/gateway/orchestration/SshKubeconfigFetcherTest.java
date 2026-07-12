package com.arthas.gateway.orchestration;

import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.password.PasswordAuthenticator;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.command.CommandFactory;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.session.ServerSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SshKubeconfigFetcher 真实 SSH 协议测试（006 波1，T006，TDD 红）。
 *
 * <p>用 <b>Apache MINA SSHD embedded server</b>（真实 SSH 协议握手，非桩）：password auth + 自定义 CatCommand
 * 模拟 {@code cat <path>}。验证：① password auth + 文件存在 → 返 kubeconfig 文本；② 错密码 → ssh_auth_failed；
 * ③ host 不可达 → ssh_unreachable；④ 路径不存在 → kubeconfig_not_found；⑤ 内容空 → kubeconfig_invalid。
 *
 * <p>零桩（宪法原则七）：MINA SSHD 是真实 SSH 服务端实现，sshj 客户端真实握手。research.md R1/R10。
 */
class SshKubeconfigFetcherTest {

    private SshServer sshd;
    private int port;
    private final Map<String, String> files = new HashMap<>();

    @BeforeEach
    void startServer() throws Exception {
        files.clear();
        sshd = SshServer.setUpDefaultServer();
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());
        sshd.setPasswordAuthenticator(new PasswordAuthenticator() {
            @Override
            public boolean authenticate(String username, String password, ServerSession session) {
                return "root".equals(username) && "pwd".equals(password);
            }
        });
        sshd.setCommandFactory(new CommandFactory() {
            @Override
            public Command createCommand(ChannelSession channel, String command) {
                return new CatCommand(command, files);
            }
        });
        sshd.start();
        port = sshd.getPort();
    }

    @AfterEach
    void stopServer() throws Exception {
        sshd.stop();
    }

    private SshBootstrap bootstrapWith(String path) {
        return new SshBootstrap("127.0.0.1", port, "root", "pwd", null, null, path, null, false);
    }

    @Test
    void fetchesKubeconfigContent() {
        files.put("/etc/kubeconfig", "apiVersion: v1\nclusters: []\n");
        String content = new SshKubeconfigFetcher().fetchKubeconfig(bootstrapWith("/etc/kubeconfig"));
        assertThat(content).isEqualTo("apiVersion: v1\nclusters: []\n");
    }

    @Test
    void wrongPasswordRejectedAsAuthFailed() {
        SshBootstrap bad = new SshBootstrap("127.0.0.1", port, "root", "wrong", null, null, "/etc/k", null, false);
        assertThatThrownBy(() -> new SshKubeconfigFetcher().fetchKubeconfig(bad))
                .isInstanceOf(SshBootstrapException.class)
                .extracting(e -> ((SshBootstrapException) e).reason())
                .isEqualTo(SshBootstrapException.Reason.SSH_AUTH_FAILED);
    }

    @Test
    void unreachableHostReported() {
        SshBootstrap down = new SshBootstrap("127.0.0.1", 1, "root", "pwd", null, null, "/p", null, false);
        assertThatThrownBy(() -> new SshKubeconfigFetcher().fetchKubeconfig(down))
                .isInstanceOf(SshBootstrapException.class)
                .extracting(e -> ((SshBootstrapException) e).reason())
                .isEqualTo(SshBootstrapException.Reason.SSH_UNREACHABLE);
    }

    @Test
    void kubeconfigPathNotFound() {
        assertThatThrownBy(() -> new SshKubeconfigFetcher().fetchKubeconfig(bootstrapWith("/none")))
                .isInstanceOf(SshBootstrapException.class)
                .extracting(e -> ((SshBootstrapException) e).reason())
                .isEqualTo(SshBootstrapException.Reason.KUBECONFIG_NOT_FOUND);
    }

    @Test
    void emptyContentIsInvalid() {
        files.put("/etc/empty", "");
        assertThatThrownBy(() -> new SshKubeconfigFetcher().fetchKubeconfig(bootstrapWith("/etc/empty")))
                .isInstanceOf(SshBootstrapException.class)
                .extracting(e -> ((SshBootstrapException) e).reason())
                .isEqualTo(SshBootstrapException.Reason.KUBECONFIG_INVALID);
    }

    /** 模拟 {@code cat <path>}：查 files map 返内容（exitCode 0）或 "No such file"（exitCode 1）。 */
    static class CatCommand implements Command {
        private final String command;
        private final Map<String, String> files;
        private OutputStream out;
        private OutputStream err;
        private ExitCallback callback;

        CatCommand(String command, Map<String, String> files) {
            this.command = command;
            this.files = files;
        }

        @Override
        public void setInputStream(InputStream in) {
            // 不读 stdin
        }

        @Override
        public void setOutputStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void setErrorStream(OutputStream err) {
            this.err = err;
        }

        @Override
        public void setExitCallback(ExitCallback callback) {
            this.callback = callback;
        }

        @Override
        public void start(ChannelSession channel, Environment env) throws IOException {
            String path = command.replaceFirst("(?i)^cat\\s+", "").trim();
            String content = files.get(path);
            if (content == null) {
                err.write(("cat: " + path + ": No such file or directory\n").getBytes());
                err.flush();
                callback.onExit(1);
            } else {
                out.write(content.getBytes());
                out.flush();
                callback.onExit(0);
            }
        }

        @Override
        public void destroy(ChannelSession channel) {
            // 无资源
        }
    }
}
