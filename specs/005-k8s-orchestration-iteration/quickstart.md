# Quickstart：K8S 编排能力迭代（005 端到端验证）

**Feature**: 005-k8s-orchestration-iteration | **Date**: 2026-07-10

> 本文是 005 三点迭代的**端到端验证指南**（证明 Service 复用 / K8S host 懒 resolve / JDK SPI 适配跑通）。实现细节归 `tasks.md`；契约见 [contracts/](./contracts/)；实体见 [data-model.md](./data-model.md)；决策见 [research.md](./research.md)。前置：003 测试床（k3s @ debian 192.168.31.92 + demo-business pod）已就绪（`bash test-env/k8s/setup.sh`）。

---

## 0. 前置环境

| 项 | 要求 |
|----|------|
| 003 测试床 | k3s @ debian（192.168.31.92）+ demo-business pod Running（含 JVM） |
| 网关 | `./mvnw clean verify` + `bash smoke/gateway-start.sh`（:8761/mcp） |
| kubeconfig | `test-env/k8s/kubeconfig/k3s-admin.yaml`（gitignored） |
| Claude Code | 真实 CC（`claude -p --mcp-config`）做端到端冒烟 |

---

## 1. 场景 A · Service 复用（US1，K-ENS-10/11/12）

验证 ensure 优先复用带 label 的业务 Service（不新建独立 Service）。

### A.1 给业务 Service 打 label

```bash
# K8S 上给 demo-business 的 Service（或新建一个）打 label
ssh root@192.168.31.92 'kubectl label svc demo-business -n default arthas-mcp-gateway/target=debian-demo-business'
# 或新建带 label 的 Service（若 demo-business 无 Service）
ssh root@192.168.31.92 'kubectl apply -f - <<EOF
apiVersion: v1
kind: Service
metadata:
  name: demo-business
  namespace: default
  labels:
    arthas-mcp-gateway/target: debian-demo-business   # 关键：标记关联 logical target
spec:
  type: ClusterIP                                      # 测 ClusterIP 自动改 NodePort（K-ENS-11）
  selector:
    app: demo-business
  ports:
    - port: 8080
      targetPort: 8080
EOF'
```

### A.2 ensure 复用（经网关 MCP）

```bash
claude -p --mcp-config .mcp.json --permission-mode bypassPermissions \
  "调用 k8s.ensure-arthas-mcp server=debian pod=demo-business namespace=default。返回 target/status/mcpUrl。"
```

**预期（K-ENS-10）**：
- `status:ready`，mcpUrl 经**现有 demo-business Service** 的 NodePort 可达。
- `kubectl get svc -n default` → **无新 `arthas-mcp-debian-demo-business`**（复用，不新建）。
- `kubectl get svc demo-business -o yaml` → `spec.type: NodePort`（ClusterIP 已自动改，K-ENS-11）+ ports 含 arthas MCP 端口（8563）的 NodePort。

### A.3 幂等（K-ENS-12）

```bash
# 二次 ensure
claude -p ... "再次调 k8s.ensure-arthas-mcp server=debian pod=demo-business"
```
**预期**：`status:reused`；`demo-business` Service ports 数不变（不重复加端口）。

---

## 2. 场景 B · K8S Host 配置 + 懒 resolve（US2，INV-K8SHOST-1~5）

验证声明 K8S Host + K8S 模式 backend，首次诊断自动 ensure（懒 resolve + 缓存）。

### B.1 配置 K8S Host + K8S 模式 backend

`application.yml`：
```yaml
arthas-gateway:
  k8s-hosts:
    - name: debian-prod
      kubeconfig: test-env/k8s/kubeconfig/k3s-admin.yaml
      namespace: default
```

`config/backends.yaml`（增量一个 K8S 模式 backend）：
```yaml
backends:
  - name: order-service
    k8s-host: debian-prod      # K8S 模式（与 url 二选一）
    pod: demo-business          # 关联业务 pod
  - name: payment               # 静态 url 模式（现状，共存）
    url: http://127.0.0.1:61816
    protocol: STREAMABLE
    auth: { mode: NONE }
```

### B.2 启网关 + 首次诊断（懒 resolve）

```bash
bash smoke/gateway-start.sh   # 重启加载 K8S Host 配置
```

```bash
claude -p --mcp-config .mcp.json --permission-mode bypassPermissions \
  "调用 jvm target=order-service（K8S 模式 backend）。返回 MACHINE-NAME/VM-VERSION。"
```

**预期（INV-K8SHOST-1~5）**：
- 首次路由触发懒 resolve（调 ensure `server=debian-prod pod=demo-business`）→ mcpUrl 自动产出 → 纳管 → 诊断成功。
- 返回 `MACHINE-NAME=1@demo-business`（pod JVM 真实信息，来自远端）。
- 网关日志见「懒 resolve：logicalName=debian-prod-demo-business → ensure → mcpUrl=...」。

### B.3 缓存幂等（INV-K8SHOST-2）

```bash
# 二次诊断同 target
claude -p ... "再次 jvm target=order-service"
```
**预期**：日志见「缓存命中：debian-prod-demo-business」（不调 ensure）；诊断结果一致。

### B.4 静态模式旁路（INV-K8SHOST-5）

```bash
claude -p ... "jvm target=payment（静态 url 模式）"
```
**预期**：行为与 001/003 完全一致（不调 resolver/ensure；用 config.url 直连）。

### B.5 错误场景

```bash
# host 不存在（INV-K8SHOST-3）：backends.yaml 配 k8s-host: nonexistent → 诊断返 unknown_k8s_host
# 无 K8S（INV-K8SHOST-4）：application.yml 删 k8s/k8s-hosts → K8S 模式 backend 诊断返 no_k8s_resolver
```

---

## 3. 场景 C · 自定义 ArthasLauncher（US3，INV-LAUNCHER-1~5）

验证 SPI 委托 + @Primary 覆盖 + 独立 JDK 启动。

### C.1 写自定义实现（@Primary）

`src/main/java/com/arthas/gateway/orchestration/CustomArthasLauncher.java`（示例）：
```java
package com.arthas.gateway.orchestration;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class CustomArthasLauncher implements ArthasLauncher {
    private static final String JAVA = "/opt/jdk-21/bin/java";   // 独立 JDK
    private static final String JPS = "/opt/jdk-21/bin/jps";

    @Override
    public long locatePid(LaunchContext ctx) {
        var r = ctx.exec().exec(ctx.namespace(), ctx.pod(), ctx.locateTimeout(),
                "sh", "-c", JPS + " -q 2>/dev/null | grep -E '^[0-9]+$' | head -1");
        return Long.parseLong(r.stdout().trim());
    }

    @Override
    public void startArthas(LaunchContext ctx, long pid) {
        String cmd = JAVA + " -jar " + ctx.arthasBootJar() + " " + pid
                + " --attach-only --http-port " + ctx.mcpPort()
                + " --target-ip " + ctx.targetIp()
                + " --use-version " + ctx.arthasVersion()
                + " --password " + ctx.arthasPassword();
        ctx.exec().exec(ctx.namespace(), ctx.pod(), ctx.attachTimeout(), "sh", "-c", cmd);
    }
}
```

### C.2 启网关 + ensure（验证 SPI 生效）

```bash
bash smoke/gateway-start.sh
claude -p --mcp-config .mcp.json --permission-mode bypassPermissions \
  "调用 k8s.ensure-arthas-mcp server=debian pod=demo-business。返回 status/mcpUrl。"
```

**预期（INV-LAUNCHER-1/3）**：
- `status:ready`（CustomArthasLauncher 生效，非 Default）。
- 网关日志见「ArthasLauncher=CustomArthasLauncher」（@Primary 覆盖）。
- pod 内 arthas 经 `/opt/jdk-21/bin/java` 启动（若 pod 装了该 JDK；否则 startArthas 失败 → `attach_failed@start_arthas`，INV-LAUNCHER-4）。

### C.3 默认兼容（INV-LAUNCHER-2）

删除 CustomArthasLauncher（或去 @Primary）→ 重启 → ensure 行为 = 003 现状（DefaultArthasLauncher，PATH 的 java/jps）。

---

## 4. 场景 D · 回归（FR-013，003 契约不破）

```bash
# 003 既有 K8S 契约 IT（DefaultArthasLauncher，无自定义实现）
./mvnw verify -Dit.test='K8sEnsureContractIT,K8sListToolsContractIT,ArthasProvisionerIT' -DfailIfNoTests=false

# 包边界（零 K8S 依赖 + BackendResolver 接口）
./mvnw test -Dtest='PackageBoundaryTest'

# 全量回归
./mvnw verify -DskipFrontend=true
```

**预期**：003 K-ENS-1~9 + K-ATOMIC-1 + SC-001（5min 端到端）+ ArchUnit 包边界全绿。

---

## 5. SPI test fixture 验证（INV-LAUNCHER-5，TDD）

```bash
# test fixture 真实实现验证 SPI 机制（非 mock）
./mvnw test -Dtest='ArthasLauncherSpiTest,DefaultArthasLauncherTest'
```

**预期**：
- `DefaultArthasLauncherTest`：默认实现 = 003 现状逻辑（jps -q / java -jar 标准参数）。
- `ArthasLauncherSpiTest`（注入 TestArthasLauncher fixture）：验证 ArthasProvisioner 真委托 launcher + @Primary 覆盖 + 接口契约。

---

## 6. 验证清单（Done Definition）

- [ ] 场景 A：Service 复用（K-ENS-10/11/12）—— 业务 Service 打 label → ensure 复用 + ClusterIP 自动改 NodePort + 幂等。
- [ ] 场景 B：K8S Host 懒 resolve（INV-K8SHOST-1~5）—— 声明 host+pod backend → 首次诊断自动 ensure + 缓存 + 静态旁路 + 错误场景。
- [ ] 场景 C：自定义 launcher（INV-LAUNCHER-1~4）—— @Primary 实现 → 独立 JDK 启动 + Default 兼容。
- [ ] 场景 D：回归（FR-013）—— 003 K-ENS-*/K-ATOMIC-1/SC-001 + ArchUnit 全绿。
- [ ] SPI test fixture（INV-LAUNCHER-5）—— TestArthasLauncher 真实实现验证委托/替换/契约。
- [ ] 零 gateway-core K8S 依赖（INV-BOUNDARY-1/2）—— BackendResolver 接口无 fabric8 import（ArchUnit）。
