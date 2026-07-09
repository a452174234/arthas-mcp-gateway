# Part 4 · 003 K8S 编排实现（重点）

> 003 是网关的精华特性：**网关在 K8S 集群外运行**，经 kubeconfig + NodePort 编排远端 pod，对 pod 内 JVM 注入 arthas 并诊断。本部分深入「拓扑设计 → kubeconfig→fabric8 → list-pods 探测 → ensure 6 步原子序列 → NodePort 暴露 → 动态注册 → 3 工具契约 + 故障矩阵 → 测试床 → 4 关键决策」。

---

## 第 22 章 远端 K8S 调用场景设计

### 22.1 真实拓扑

```
┌─────────────────────────────┐         kubeconfig          ┌──────────────────────────────┐
│  本机（Windows / 网关）       │  ────────────────────────►  │  debian 服务器 (K3S node)      │
│  arthas-mcp-gateway.jar      │   https://192.168.31.92:6443 │  192.168.31.92                │
│  :8761/mcp                   │                              │  pod: demo-business           │
│  kubeconfig:                 │  ◄──── NodePort 30000-32767 ─│  (JVM 21, OrderService)        │
│  test-env/k8s/kubeconfig/    │   arthas MCP via NodePort    │  arthas-boot.jar (注入，非镜像) │
└──────────────┬───────────────┘                              └───────────────────────────────┘
               ▲
               │ MCP (Streamable HTTP)
               ▼
        Claude Code 客户端
        （编排者：组合原子工具）
```

要点：
- **网关集群外运行**（本机 Windows），不进 K8S——验证「集群外客户端 + 远程编排」真实拓扑（生产场景：网关与业务集群分离）。
- **kubeconfig**：`test-env/k8s/setup.sh` 从远端 root-on-node 派生 admin 凭证导出到本机（server 已改 `https://192.168.31.92:6443`，**不建 RBAC**，MVP 后置 K-ENS-6）。
- **NodePort**：ensure 时自动从 `arthas-gateway.k8s.node-port-range=30000-32767` 分配，暴露 pod 内 arthas MCP。
- **arthas 不在镜像**：ensure 时经 fabric8 exec 上传 `tools/arthas-boot.jar`（静态文件，入 git）使用——镜像仅含业务类。

### 22.2 架构原则：Claude 是编排者，系统提供原子工具

经 brainstorming（`docs/superpowers/specs/2026-06-22-k8s-arthas-mcp-launch-design.md`）确认（Q4 收敛）：

> **不内建过程式编排模块**——只暴露**最小化的原子 MCP 能力**（细粒度、各自独立可调），**大模型（Claude）是编排者**，从上下文推断、组合原子工具自己把「发现→启动→暴露→纳管→诊断」串起来。

工具粒度判断标准 = **LLM 是否需要在两步之间推理**：
- 「枚举 ↔ 选 pod」之间 LLM 要推理 → `list-pods`/`list-services` 保持独立。
- 「装→启→暴露→注册→健康检查」之间**无 LLM 决策点**、是一条机械的"确保就绪"操作 → 合并为**幂等 `ensure-arthas-mcp`**。

→ 编排 MCP 面最终为 **3 个工具**：`k8s.list-pods` / `k8s.list-services` / `k8s.ensure-arthas-mcp`。

### 22.3 north-star vs P1 MVP

- **P1 MVP**：自动供给机制（`ensure-arthas-mcp`）+ 人显式指定目标 pod → 自动实例化+自动命名（`{server}-{pod}`）+ 自动纳管。
- **north-star**（后续）：大模型经对话上下文推断目标并触发自动初始化（人只提供 Linux 服务器，无需人工选 pod）。
- 二者共享供给机制，差异仅在「谁指定目标」（P1 人 / north-star LLM）。

---

## 第 23 章 K8sClientFactory — kubeconfig → fabric8 单例

**文件**：`src/main/java/com/arthas/gateway/orchestration/K8sClientFactory.java`

### 23.1 职责

启动期一次性读取 `arthas-gateway.k8s.kubeconfig` 文件 → 经 `Config.fromKubeconfig(content)` 解析（自动取 current-context）→ `KubernetesClientBuilder` 构建单例 client。线程安全，list/exec/create 共用。`AutoCloseable`：容器关闭释放 HTTP 连接池/WebSocket。

### 23.2 build（`:35-59`）—— kubeconfig 解析 + client 构建

```java
private static KubernetesClient build(GatewayProperties.K8s k8s) {
    Path kubeconfig = Path.of(k8s.getKubeconfig()).toAbsolutePath();
    String content;
    try {
        content = Files.readString(kubeconfig);
    } catch (IOException e) {
        throw new IllegalStateException("kubeconfig 读取失败：" + kubeconfig + "（" + e.getMessage() + "）", e);
    }
    if (content.isBlank()) {
        throw new IllegalStateException("kubeconfig 内容为空：" + kubeconfig);
    }
    Config config = Config.fromKubeconfig(content);  // 取 current-context（测试床单 context）
    // context 覆盖：非空时仅记 INFO（尊重 current-context，覆盖非 MVP）
    KubernetesClient c = new KubernetesClientBuilder().withConfig(config).build();
    return c;
}
```

### 23.3 装配条件

`K8sOrchestrationConfig.java:35-38`：
```java
@Bean(destroyMethod = "close")
@Conditional(K8sEnabledCondition.class)
public KubernetesClient kubernetesClient(GatewayProperties props) {
    return K8sClientFactory.build(props.getK8s());
}
```

`K8sEnabledCondition`（`K8sEnabledCondition.java`）：kubeconfig 文件存在且可读时装配，否则跳过（CI 无 k3s 时不阻断启动，3 K8S 工具调用时返"未启用"错误）。

---

## 第 24 章 K8sPodExplorer — list-pods / list-services + 探测

**文件**：`src/main/java/com/arthas/gateway/orchestration/K8sPodExplorer.java`

### 24.1 职责

fabric8 列 pod/service + 对每个 `Running` pod exec 一条组合命令探测 shell（`echo __SHELL_OK__`）与 JVM（`jps -q` 出 PID）。

### 24.2 关键方法

- `listPods(namespace)`（`:55-80`）：列 pod + 逐个探测 hasShell/hasJvm。
- `listServices(namespace)`（`:83-106`）：列 service + 解析 NodePort。
- `isReady(pod)`（`:109-115`）：仅 `Running`（`ready=true`）pod 才探测。

### 24.3 组合探测命令（`:66-75`）

```java
K8sExec.ExecResult r = exec.exec(namespace, name, PROBE_TIMEOUT, "sh", "-c",
        "echo " + SHELL_OK_MARKER
                + "; command -v jps >/dev/null 2>&1 && jps -q 2>/dev/null | grep -E '^[0-9]+$' | head -1 || true");
String stdout = r.stdout();
hasShell = stdout.contains(SHELL_OK_MARKER);
hasJvm = hasShell && stdout.lines().anyMatch(K8sPodExplorer::isPureDigits);
```

**探测超时**：单 pod 5 秒（`PROBE_TIMEOUT`，`:32`），避免悬挂 pod 阻塞 list。

**要点**：
- 仅 `Running`（`ready=true`）pod 才探测（避免 exec 挂在 Pending）。
- 探测失败 → `hasShell/hasJvm=false`（不可诊断标记，非保证）。
- K8S API 故障由 `K8sToolHandlers.k8sApiError` 映射为 `INVALID_PARAMS + reason=k8s_forbidden(403)/k8s_unreachable`。

---

## 第 25 章 K8sExec — fabric8 exec 同步化助手

**文件**：`src/main/java/com/arthas/gateway/orchestration/K8sExec.java`

### 25.1 职责

把 fabric8 异步 `ExecWatch` 收敛为同步 `ExecResult(exitCode, stdout, stderr)`。

### 25.2 关键决策（`:21-24` 注释）

**用 `ExecWatch.exitCode()` 的 `CompletableFuture<Integer>` 取真实进程退出码**，**不用** `ExecListener.onClose(code)`（那是 WebSocket 关闭码 1000=NORMAL，非进程退出状态，曾导致误判）。

### 25.3 核心实现（`:53-89`）

```java
public ExecResult exec(String namespace, String pod, Duration timeout, String... command) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    ExecWatch watch;
    try {
        watch = client.pods().inNamespace(namespace).withName(pod)
                .writingOutput(out)
                .writingError(err)
                .exec(command);
    } catch (RuntimeException e) {
        // exec 启动即失败（API 不可达 / RBAC 403 / pod 不存在）
        throw new K8sExecException("exec 启动失败：" + ..., e, -1, err.toString(StandardCharsets.UTF_8));
    }
    try {
        int code = watch.exitCode().get(timeout.toSeconds(), TimeUnit.SECONDS);  // 真实进程退出码
        return new ExecResult(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    } catch (TimeoutException e) {
        throw new K8sExecException("exec 超时（" + timeout + "）：" + ..., e, -1, ...);
    } catch (ExecutionException e) {
        /* exitCode() future 异常完成：403/不可达/无 shell */
        throw new K8sExecException(...);
    } finally {
        watch.close();
    }
}
```

`K8sExecException`（`K8sExecException.java`）携带 `exitCode`（-1 = 未获取，如超时/通道异常）与 `stderr`，供 `ArthasProvisioner` 分类故障 `reason/stage`。

---

## 第 26 章 ArthasProvisioner — ensure 全流程编排核心

**文件**：`src/main/java/com/arthas/gateway/orchestration/ArthasProvisioner.java`

### 26.1 职责

对指定 pod **原子幂等**完成「定位 JVM → 上传 arthas → 启动 arthas MCP（绑 0.0.0.0）→ NodePort 暴露 → 内部 MCP 握手健康检查 → 动态注册」。任一子步失败 → 不注册（status=failed，K-ATOMIC-1）；全部成功 → status=ready；幂等命中 → status=reused。

### 26.2 关键常量（`:56-75`）

| 常量 | 值 | 说明 |
|------|-----|------|
| `DEFAULT_MCP_PORT` | `8563` | pod 内 arthas MCP 监听端口（NodePort targetPort） |
| `LOCATE_TIMEOUT` | `10s` | jps 定位 JVM 超时 |
| `ATTACH_TIMEOUT` | `300s` | arthas attach 超时（首跑下载运行时到 `~/.arthas/lib/4.3.0`，留余量） |
| `DEFAULT_HEALTH_CHECK_TIMEOUT` | `90s` | 生产默认健康检查（K-ENS-7 loopback 故障用例注入短超时快速失败） |
| `HEALTH_PROBE_REQUEST_TIMEOUT` | `3s` | 单次 MCP 握手探测超时 |
| `HEALTH_POLL_INTERVAL` | `1s` | 健康轮询间隔 |
| `REMOTE_ARTHAS_JAR` | `/tmp/arthas-boot.jar` | pod 内落点 |
| `CONNECT_TIMEOUT_MS` | `5000` | 动态后端默认连接超时 |
| `CALL_TIMEOUT_MS` | `30000` | 动态后端默认调用超时 |
| `MAX_CONCURRENT_TASKS` | `5` | 动态后端默认并发（复用 001 默认值） |

### 26.3 ensure 入口（`:145-172`）—— 幂等复用优先

```java
public OrchestrationRecord ensure(String server, String pod, String namespace, Instant now) {
    String logicalName = deriveLogicalName(server, pod);  // {server}-{pod}（K-ENS-8）
    OrchestrationRecord rec = OrchestrationRecord.ensuring(logicalName, server, pod, namespace, now);
    recordStore.record(rec);

    // 幂等复用：注册表已有且后端健康 → 零副作用 reused（K-ENS-2）
    Optional<BackendConfig> existing = dynamicStore.get(logicalName);
    if (existing.isPresent()) {
        String url = existing.get().url();
        if (probeHealthy(url, Duration.ofSeconds(HEALTH_PROBE_REQUEST_TIMEOUT.toSeconds() * 2))) {
            OrchestrationRecord reused = rec.reused(url, null, now);
            recordStore.record(reused);
            log.info("ensure 幂等复用：target={} url={}", logicalName, url);
            return reused;
        }
        log.info("ensure 注册表命中但后端不健康，重新供给：target={}", logicalName);
    }
    // 供给（任一子步失败 → failed 不注册，K-ATOMIC-1）
    OrchestrationRecord terminal = doProvision(rec, namespace, pod, logicalName, now);
    recordStore.record(terminal);
    return terminal;
}
```

### 26.4 doProvision（`:181-232`）—— 6 步原子序列

```java
private OrchestrationRecord doProvision(OrchestrationRecord rec, String namespace, String pod,
                                        String logicalName, Instant now) {
    try {
        long pid = locateJvm(namespace, pod);              // 1. 定位 JVM PID
        installArthas(namespace, pod);                     // 2. 上传 arthas-boot.jar
        startArthas(namespace, pod, pid);                  // 3. 启动 arthas（attach 进程）
        NodePortExposer.ExposeResult exposed;
        try {
            exposed = exposer.expose(namespace, pod, logicalName, mcpPort);  // 4. NodePort 暴露
        } catch (RuntimeException e) {
            throw new ProvisionException(errorOf("nodeport_alloc_failed", "expose_nodeport", ...));
        }
        String mcpUrl = exposed.mcpUrl();
        String serviceRef = exposed.serviceRef();
        rec = rec.withExposed(mcpUrl, serviceRef);         // 记录已暴露副作用（供 failed 追溯清理）

        if (!probeHealthy(mcpUrl, healthCheckTimeout)) {   // 5. 健康检查（MCP 握手轮询）
            throw new ProvisionException(errorOf("health_check_timeout", "health_check",
                    "arthas MCP 在超时内未就绪（mcpUrl=" + mcpUrl + "，target-ip=" + targetIp + "）"));
        }

        BackendConfig cfg = new BackendConfig(              // 6. 动态注册（source=DYNAMIC）
                logicalName, mcpUrl, Protocol.STREAMABLE,
                auth,
                CONNECT_TIMEOUT_MS, CALL_TIMEOUT_MS, MAX_CONCURRENT_TASKS, Source.DYNAMIC);
        try {
            dynamicStore.register(cfg);
        } catch (BackendConfigException e) {
            // 命名冲突（动态名 ∩ 静态种子 / 同名异 URL）→ name_conflict（K-ENS-9）
            throw new ProvisionException(errorOf("name_conflict", "register", e.getMessage()));
        }

        log.info("ensure 供给完成：target={} mcpUrl={}", logicalName, mcpUrl);
        return rec.ready(mcpUrl, serviceRef, now);
    } catch (ProvisionException e) {
        OrchestrationRecord failed = rec.failed(e.error, now);
        log.warn("ensure 失败（未注册）：target={} reason={} stage={}", logicalName,
                e.error.reason(), e.error.phase(), e);  // 末位传 e：完整异常链 + 堆栈
        return failed;
    }
}
```

### 26.5 子步细节

**locateJvm（`:237-255`）**：`jps -q | grep -E '^[0-9]+$' | head -1`；无 PID 行匹配 → `reason=no_jvm, stage=locate_jvm`。

**installArthas（`:259-278`）** —— fabric8 `.file().upload()`：
```java
boolean ok = client.pods().inNamespace(namespace).withName(pod)
        .file(REMOTE_ARTHAS_JAR).upload(arthasBootJar);
if (!ok) {
    throw new ProvisionException(errorOf("attach_failed", "install_arthas", ...));
}
```
返回 false 或抛异常 → `reason=attach_failed, stage=install_arthas`（含原始异常堆栈，避免此前被吞）。

**startArthas（`:282-301`）** —— arthas 启动命令（绑 0.0.0.0，R4 决策）：
```java
r = exec.exec(namespace, pod, ATTACH_TIMEOUT,
        "java", "-jar", REMOTE_ARTHAS_JAR, String.valueOf(pid),
        "--attach-only",
        "--http-port", String.valueOf(mcpPort),
        "--target-ip", targetIp,         // 0.0.0.0（NodePort 可达）；127.0.0.1 → loopback 不可达（K-ENS-7）
        "--telnet-port", "0",
        "--use-version", arthasVersion,  // 4.3.0（设计 §5）
        "--password", arthasPassword);   // arthas 绑 0.0.0.0 强制鉴权（避免外部访问 401）
```
非零退出 → `reason=attach_failed, stage=start_arthas`。

**probeHealthy（`:306-322`）** —— MCP initialize 握手轮询（零桩，真实 MCP 客户端）：
```java
boolean probeHealthy(String mcpUrl, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
        try (McpSyncClient c = McpClient.sync(
                HttpClientStreamableHttpTransport.builder(mcpUrl)
                        .httpRequestCustomizer(new BackendAuthCustomizer(auth))  // Bearer = arthasPassword
                        .build())
                .requestTimeout(HEALTH_PROBE_REQUEST_TIMEOUT)
                .build()) {
            c.initialize();  // arthas MCP 就绪则握手成功
            return true;
        } catch (RuntimeException e) {
            sleepQuiet(HEALTH_POLL_INTERVAL);
        }
    }
    return false;
}
```

### 26.6 故障分类（`:332-366`）

`classifyExecResult` / `classifyExecError` 把 exec 失败映射为：
- `code==403 或 stderr.contains("forbidden")` → `k8s_forbidden`
- `exitCode<0 || contains("executable file not found"|"not found in path"|"no such file or directory"|"not a directory")` → `no_shell`
- 其余 → `k8s_unreachable`

---

## 第 27 章 NodePortExposer — pod label + NodePort Service

**文件**：`src/main/java/com/arthas/gateway/orchestration/NodePortExposer.java`

### 27.1 职责

为目标 pod 打唯一 label（`arthas-mcp-gateway/target=<sanitize(logical)>`）+ create-or-get NodePort Service（selector 命中该 label）+ 解析 nodeIP（优先 ExternalIP，其次 InternalIP）→ 产出 `mcpUrl=http://<nodeIP>:<nodePort>`（根 URL，无 /mcp）。

### 27.2 关键常量

- `TARGET_LABEL_KEY = "arthas-mcp-gateway/target"`（`:37`）。
- `SERVICE_PREFIX = "arthas-mcp-"`（`:39`）。

### 27.3 expose 主流程（`:60-80`）

```java
public ExposeResult expose(String namespace, String pod, String logicalName, int mcpPort) {
    String labelValue = sanitizeLabelValue(logicalName);
    String serviceName = sanitizeServiceName(SERVICE_PREFIX + labelValue);
    labelPod(namespace, pod, labelValue);                                   // 1. label pod（幂等）
    int nodePort = ensureNodePortService(namespace, serviceName, labelValue, mcpPort);  // 2. create-or-get
    String nodeIp = resolveNodeIp();                                        // 3. 解析 nodeIP
    String mcpUrl = "http://" + nodeIp + ":" + nodePort;
    String serviceRef = serviceName + "/" + nodePort;
    return new ExposeResult(serviceName, nodePort, mcpUrl, serviceRef);
}
```

### 27.4 ensureNodePortService（`:112-138`）—— K8S 自动分配 30000-32767

```java
private int ensureNodePortService(String namespace, String serviceName, String labelValue, int mcpPort) {
    Service existing = client.services().inNamespace(namespace).withName(serviceName).get();
    if (existing != null) {
        Integer np = firstNodePort(existing);
        if (np != null) return np;  // 复用既有 NodePort（幂等）
    }
    Service svc = new ServiceBuilder()
            .withNewMetadata().withName(serviceName).endMetadata()
            .withNewSpec()
            .withType("NodePort")
            .addToSelector(TARGET_LABEL_KEY, labelValue)
            .addNewPort()
            .withPort(mcpPort)
            .withTargetPort(new IntOrString(mcpPort))
            .endPort()
            .endSpec()
            .build();
    client.services().inNamespace(namespace).resource(svc).create();
    Service created = client.services().inNamespace(namespace).withName(serviceName).get();
    Integer np = created != null ? firstNodePort(created) : null;
    if (np == null) throw new IllegalStateException("NodePort 分配失败...");
    return np;
}
```

**幂等**：Service 名确定性派生 `arthas-mcp-<sanitize(logical)>`，已存在则复用其 NodePort 不重建。

### 27.5 resolveNodeIp（`:148-169`）

优先 ExternalIP，其次 InternalIP。k3s 单节点测试床 InternalIP=192.168.31.92（网关经 LAN 可达，验证"集群外运行"拓扑）。

### 27.6 sanitize（`:181-198`）

label value ≤63 字符、service name ≤253 字符（DNS-subdomain），非法字符替换为 `-`，去首尾非字母数字。

### 27.7 deleteService（`:83-91`）

清理副作用，幂等（fabric8 7.x `delete()` 返回 `List<StatusDetails>`：非空表示实际删除）。

---

## 第 28 章 OrchestrationRecord + Store — 供给可观测

### 28.1 OrchestrationRecord — 不可变 record 状态机

**文件**：`src/main/java/com/arthas/gateway/orchestration/OrchestrationRecord.java`

record 字段（`:26-36`）：`logicalName, server, pod, namespace, mcpUrl, serviceRef, status, error, createdAt, completedAt`。

**状态机枚举（`:39-59`）**：
- `ENSURING`（非终态，进行中）。
- `READY`（终态，新供给完成已注册）。
- `REUSED`（终态，幂等复用零副作用）。
- `FAILED`（终态，未注册）。

**状态转换（拷贝风格，`:70-103`）**：`ensuring()` 起始工厂；`withExposed(mcpUrl, serviceRef)` 记录已暴露副作用仍处 ensuring；`ready()`/`reused()`/`failed()` 转终态。`createdAt` 为**传入瞬时量**（非进程内取时），便于确定性测试。

**Error 子 record（`:62-68`）**：`phase, reason, message` 三元组，紧凑构造器强制非空。

### 28.2 OrchestrationRecordStore — 进程级内存态

**文件**：`src/main/java/com/arthas/gateway/orchestration/OrchestrationRecordStore.java`

`ConcurrentHashMap<String, OrchestrationRecord>`，按 logicalName 覆盖最新状态（一次 ensure 全程可能 ensuring→ready/reused/failed，仅终态对外有意义，但中间态亦写入便于近实时观测）。MVP 不持久化，可加 TTL。

---

## 第 29 章 K8sToolHandlers + K8sToolRegistry — 3 工具规格与处理器

### 29.1 K8sToolRegistry — 静态工具规格（始终注册）

**文件**：`src/main/java/com/arthas/gateway/orchestration/K8sToolRegistry.java`

3 个工具（`:32-57`）：
- `k8s.list-pods`（参数 `namespace?`，返回 pod 清单含 hasJvm/hasShell）。
- `k8s.list-services`（参数 `namespace?`，返回 service 清单）。
- `k8s.ensure-arthas-mcp`（参数 `server`/`pod` 必填 + `namespace?`，原子幂等供给）。

`routingMode=GATEWAY_LOCAL`（无 target 参数），但**实际不经 ToolsCallRouter**（handler 自带闭包，R6 决策）。

### 29.2 K8sToolHandlers — 分派 + 错误映射

**文件**：`src/main/java/com/arthas/gateway/orchestration/K8sToolHandlers.java`

`handle(tool, request)` 按 `tool.name()` switch 分派（`:58-65`）。`listPods`/`listServices` 转 JSON 视图（`:69-107`）。

**ensureArthasMcp（`:111-136`）—— 状态映射**：
```java
return switch (record.status()) {
    case READY, REUSED -> McpJson.json(readyView(record));   // {target, status, mcpUrl, namespace}
    case FAILED -> throw ensureFailedError(record.logicalName(),
            record.error().reason(), record.error().phase(), record.error().message());
    case ENSURING -> throw new IllegalStateException("ensure 返回非终态：" + record);
};
```

**readyView（`:139-146`）**：`{target, status:ready/reused, mcpUrl, namespace}`。

**ensureFailedError（`:149-158`）** —— INVALID_PARAMS + 结构化 data：
```java
McpError.builder(McpErrorCodes.INVALID_PARAMS)
    .message("ensure-arthas-mcp 失败：target=... reason=... stage=...（...）")
    .data(Map.of(
        "target", target,
        "status", "failed",
        "error", Map.of("reason", reason, "stage", stage, "message", message)))
    .build();
```

**k8sApiError（`:161-173`）**：`KubernetesClientException.code==403 → k8s_forbidden`；其余 → `k8s_unreachable`。

---

## 第 30 章 动态注册：DynamicBackendStore + RegistryComposer

> 动态注册是 003 的核心——ensure 完成后把新 target 加进网关注册表，与静态种子共存。详见 Part 2 §11.8–11.9。这里聚焦 003 特有的「冲突检测 + 共存」语义。

### 30.1 DynamicBackendStore.register 三层冲突检测（`DynamicBackendStore.java:47-69`）

```java
public void register(BackendConfig cfg) {
    if (cfg.source() != Source.DYNAMIC) throw new BackendConfigException("动态注册须 source=DYNAMIC...");
    String name = cfg.name();
    if (staticNames.get().contains(name))      // 1. 动态名 ∩ 静态种子名 → 拒绝（保护静态）
        throw new BackendConfigException("动态注册名与静态种子冲突，拒绝：" + name);
    BackendConfig existing = byName.get(name);
    if (existing != null) {
        if (!existing.url().equals(cfg.url()))  // 2. 同名异 URL → 拒绝
            throw new BackendConfigException("动态注册名与既有动态同名异 URL，拒绝：...");
        if (existing.equals(cfg)) return;       // 3. 同名同 URL 同字段 → 幂等（无回调）
    }
    byName.put(name, cfg);
    onChange.run();  // 触发 compose → holder.getAndSet
}
```

### 30.2 RegistryComposer.compose — 静态∪动态合并（`RegistryComposer.java:47-93`）

1. 静态 target：staticReg 的 Entry 优先复用 previous 中同 config 旧实例（`:57-68`）。
2. 动态 target：previous 中同 config 复用，否则 `factory.create(cfg)`（`:70-80`）。
3. 未复用的旧 Entry → toRetire（`:82-87`）。
4. `sameEffective` 判定（identity 比较，`:99-109`）：名字集相同 + 每个 target 复用同一 Entry 实例 → 未变更，跳过 getAndSet（version 去重，D-VERSION-1）。

### 30.3 关键不变量

- **I-2**（热重载不误删动态）：热重载只更新 static，dynamic 列表仍含动态 target → 合并结果保留动态。
- **I-3**（命名冲突保护）：动态名 ∩ 静态种子名 → 拒绝（保护静态不被覆盖）。
- **D-COEXIST-1**：静态热重载与动态 target 共存。

### 30.4 装配关系（DynamicRegistrationConfig）

`DynamicRegistrationConfig`（无条件，always 装配）：
- `RegistryComposer` bean + `DynamicBackendStore` bean。
- 循环依赖规避：watcher 经 `ObjectProvider<DynamicBackendStore>` 懒解析 store；store bean 以 watcher 的方法引用（`staticSnapshotNames`/`recomposeForDynamicChange`）作为 staticNames 供应商与 onChange 回调（`:37-39`）。

`GatewayMcpServerConfig.mcpToolSpecifications`（`:77-114`）：35 静态工具 handler 委托 `ToolsCallRouter.route`；3 编排工具 handler 自带闭包，经 `ObjectProvider<K8sToolHandlers>` 懒解析——bean 缺失（CI 无 k3s）→ 返 "K8S 编排未启用" INVALID_PARAMS 错误，而非启动期崩。

---

## 第 31 章 ensure-arthas-mcp 完整数据流

```
[Claude/SDK client]
   │ tools/call k8s.ensure-arthas-mcp(server, pod, namespace?)
   ▼
GatewayMcpServerConfig.mcpToolSpecifications（handler 闭包，:99-110）
   │ ObjectProvider.getIfAvailable() → K8sToolHandlers
   ▼
K8sToolHandlers.handle → ensureArthasMcp（:111-136）
   │ namespace 缺省取 props.getK8s().getNamespace()（default "default"）
   ▼
ArthasProvisioner.ensure(server, pod, namespace, clock.instant())（:145-172）
   │
   ├─ 0. deriveLogicalName(server, pod) → "{server}-{pod}"（K-ENS-8，:132-134）
   │    OrchestrationRecord.ensuring(...) → recordStore.record（中间态写入）
   │
   ├─ 1. 幂等检查：dynamicStore.get(logicalName)（:156）
   │    └─ 命中且 probeHealthy(MCP initialize) → rec.reused → recordStore.record → 返回 reused
   │
   └─ doProvision（:181-232）：
        │
        ├─ 2. locateJvm（:237-255）：exec "sh -c 'jps -q | grep ^[0-9]+$ | head -1'"
        │    └─ K8sExec.exec → ExecResult；非零退出/异常 → classifyExec* 映射 no_jvm/no_shell/k8s_*
        │
        ├─ 3. installArthas（:259-278）：
        │    client.pods().inNamespace(ns).withName(pod).file("/tmp/arthas-boot.jar").upload(arthasBootJar)
        │    └─ false/异常 → reason=attach_failed/stage=install_arthas
        │
        ├─ 4. startArthas（:282-301）：exec "java -jar /tmp/arthas-boot.jar <pid>
        │       --attach-only --http-port 8563 --target-ip 0.0.0.0 --telnet-port 0
        │       --use-version 4.3.0 --password <arthasPassword>"
        │    └─ 非零退出 → reason=attach_failed/stage=start_arthas
        │
        ├─ 5. NodePortExposer.expose（:60-80）：
        │    ├─ labelPod（:94-109）：打 label arthas-mcp-gateway/target=<sanitize(logical)>（幂等）
        │    ├─ ensureNodePortService（:112-138）：create NodePort Service（selector 匹配 label，K8S 自动分配 30000-32767）
        │    └─ resolveNodeIp（:148-169）：ExternalIP 优先，其次 InternalIP（k3s=192.168.31.92）
        │    → mcpUrl = http://<nodeIP>:<nodePort>，serviceRef = "<svcName>/<nodePort>"
        │    rec = rec.withExposed(mcpUrl, serviceRef)  // 记录副作用供 failed 追溯
        │
        ├─ 6. probeHealthy（:306-322）：轮询 mcpUrl 的 MCP initialize 握手
        │    McpClient.sync(HttpClientStreamableHttpTransport + BackendAuthCustomizer(Bearer=arthasPassword))
        │    └─ 超时未就绪 → reason=health_check_timeout/stage=health_check（K-ENS-7 loopback 即触发）
        │
        ├─ 7. DynamicBackendStore.register（:47-69）：
        │    ├─ 强制 source=DYNAMIC
        │    ├─ staticNames 检查：∩ 静态种子名 → BackendConfigException → reason=name_conflict（K-ENS-9）
        │    ├─ 同名异 URL → BackendConfigException → reason=name_conflict
        │    └─ 同名同 URL → 幂等（无回调）
        │    onChange.run() →
        │       └─ BackendConfigWatcher.recomposeForDynamicChange（:190-196）
        │              └─ applyCompose（:212-221）：
        │                     RegistryComposer.compose(previous, staticSnapshot, dynamicList)
        │                     └─ changed=true → holder.getAndSet(next) + retireAll(toRetire)
        │
        └─ rec.ready(mcpUrl, serviceRef, now) → recordStore.record → 返回 ready
   │
   ▼
K8sToolHandlers.ensureArthasMcp（:130-135）：
   READY/REUSED → McpJson.json({target, status, mcpUrl, namespace})
   FAILED → McpError INVALID_PARAMS + data.{target, status:failed, error:{reason,stage,message}}
   │
   ▼
[Client 收到 {target="debian-demo-business", status:"ready", mcpUrl:"http://192.168.31.92:30415", namespace:"default"}]
   │
   ▼ 后续诊断：Claude 用 target=debian-demo-business 调既有 watch/trace
     （经 ToolsCallRouter → BackendEntry → 经 NodePort → pod JVM）
```

---

## 第 32 章 3 K8S 工具契约 + 真实故障矩阵

### 32.1 工具契约（`contracts/k8s-orchestration-tools-contract.md`）

| 工具 | 必填参数 | 返回（成功） | 返回（失败） |
|------|----------|--------------|--------------|
| `k8s.list-pods` | `namespace?` | `{pods:[{name,namespace,ready,hasJvm,hasShell}], namespace}` | INVALID_PARAMS + `data.reason ∈ {k8s_unreachable, k8s_forbidden}` |
| `k8s.list-services` | `namespace?` | `{services:[{name,namespace,type,clusterIp,ports:[{port,nodePort}]}], namespace}` | 同上 |
| `k8s.ensure-arthas-mcp` | **`server`, `pod`**, `namespace?` | `{target:"{server}-{pod}", status:"ready"\|"reused", mcpUrl:"http://<nodeIP>:<nodePort>", namespace}` | INVALID_PARAMS + `data:{target, status:"failed", error:{reason, stage, message}}` |

**ensure 失败 reason 枚举**：`no_jvm` | `no_shell` | `attach_failed` | `health_check_timeout` | `nodeport_alloc_failed` | `name_conflict` | `k8s_unreachable` | `k8s_forbidden`。

**stage 枚举**：`locate_jvm` | `install_arthas` | `start_arthas` | `expose_nodeport` | `health_check` | `register`。

### 32.2 真实故障条件矩阵（K-ENS-4 ~ K-ENS-9 + K-ATOMIC-1，零桩）

| 断言 ID | 真实故障条件（非桩） | 触发路径 | 期望结果 |
|---------|----------------------|----------|----------|
| **K-ENS-4** | pod 内**无 JVM**（jps 无输出） | locateJvm：`jps -q` 无 PID 行 | `reason=no_jvm, stage=locate_jvm`，未注册 |
| **K-ENS-5** | pod 内**无 shell**（distroless 镜像，sh 不存在） | exec `sh -c ...` → `executable file not found` | `reason=no_shell`（classifyExecResult 命中 "executable file not found"） |
| **K-ENS-6** | kubeconfig **无 exec 权限**（RBAC 403） | fabric8 exec → `KubernetesClientException.code==403` | `reason=k8s_forbidden` |
| **K-ENS-7** | arthas 绑 **loopback**（`--target-ip 127.0.0.1`） | NodePort 路由不进 loopback → probeHealthy 轮询超时 | `reason=health_check_timeout, stage=health_check`（验证 0.0.0.0 要求） |
| **K-ENS-8** | 命名确定性 | `deriveLogicalName(server, pod)` | `target = "{server}-{pod}"` |
| **K-ENS-9** | 动态名 ∩ **静态种子名** | DynamicBackendStore.register：`staticNames.contains(name)` | `reason=name_conflict, stage=register` |
| **K-ATOMIC-1** | 任一子步失败 | doProvision try/catch → 不调 register | 注册表**不含**该 target（不半注册） |
| **K-ENS-2** | 重复 ensure（已 ready） | dynamicStore.get 命中 + probeHealthy 通过 | `status=reused`，零副作用 |
| **K-ENS-3** | 用动态 target 诊断 | watch/trace 经网关 → NodePort → pod | 捕获**该 pod JVM** 的真实诊断 |
| **K-COEXIST-2** | pod 删除（SC-003） | 网关健康监控 30s 标 unhealthy | 隔离 + 熔断；其他 target 不受影响 |

---

## 第 33 章 K8S 测试床（test-env/k8s/）

### 33.1 setup.sh — 一键幂等搭建

**文件**：`test-env/k8s/setup.sh`

本机 Git Bash 运行、内部 SSH 操作 debian（192.168.31.92）。8 步幂等：
1. 校验 k3s 离线资源（`reference/k3s/`）。
2. `mvn test-compile` 产 demo 夹具（DemoBusinessApp/OrderService/OrderResult → `target/test-classes`）。
3. ship build context 到 debian。
4. 离线 airgap 装 k3s（`INSTALL_K3S_SKIP_DOWNLOAD=true` + `--disable traefik/servicelb/metrics-server` + `--tls-san 192.168.31.92`）。
5. docker build demo 镜像（`Dockerfile.demo`：`eclipse-temurin:21-jdk` 基础，含 jps 需 JDK 非 JRE）。
6. k3s ctr import 镜像。
7. kubectl apply demo pod（`demo-pod.yaml`：imagePullPolicy: Never，readinessProbe `/actuator/health:8081`，无 Service——ensure 才建 NodePort）。
8. 导出 root-on-node 派生 admin kubeconfig（server 改 `https://192.168.31.92:6443`，文件 600）。
9. 自检。

### 33.2 teardown.sh — 幂等清理

**文件**：`test-env/k8s/teardown.sh`

卸 k3s + 删本机凭证 + 清远端临时文件。

### 33.3 kubeconfig 身份

**文件**：`test-env/k8s/kubeconfig/k3s-admin.yaml`（gitignored，敏感）

身份 = root-on-node 派生 admin（不建 ServiceAccount/Role；K-ENS-6 RBAC 后置）。

### 33.4 demo pod / 镜像

- `demo-pod.yaml`：`eclipse-temurin:21-jdk` 基础（含 shell+java+JVM），imagePullPolicy: Never，readinessProbe `/actuator/health:8081`。无 Service（ensure 才建 NodePort）。
- `Dockerfile.demo`：仅 3 个 demo 类（`DemoBusinessApp`/`OrderService`/`OrderResult`）。
- `on-debian` 脚本不在仓库（memory `debian-docker-ssh-access`：在 `~/bin`，走 SSH key 免密 `root@192.168.31.92`）。

---

## 第 34 章 4 项关键架构决策

### 决策 1：gateway-core 零 K8S 依赖（包级边界，R1 + R6）

- **实现**：`orchestration` 包承载全部 K8S 编排；`backend/handler/tool/task/auth/obs`（gateway-core）**不出现 fabric8 import**。
- 编排工具 handler **绕过 ToolsCallRouter**（`GatewayMcpServerConfig:99-110` handler 自带闭包直调 `K8sToolHandlers.handle`），`ToolsCallRouter`/`GatewayToolHandlers` 完全不知 `k8s.*` 工具存在。
- ArchUnit T030 守护单向依赖：禁止诊断核心 → orchestration；config（组合根）允许依赖 orchestration。
- 物理拆分（Maven 多模块）后置 P3，P1 用包级边界即可（禁止过早抽象）。

### 决策 2：arthas 不在镜像（运行时上传）

- `Dockerfile.demo` 仅 3 个 demo 类，基础镜像 `eclipse-temurin:21-jdk`（带 jps，需 JDK 非 JRE）。
- `tools/arthas-boot.jar` 作**静态工具文件**（CLAUDE.md memory `arthas-no-dependency`：不入 pom、不构建 reference 源码）。
- ensure 时 `ArthasProvisioner.installArthas`（`:259-278`）经 fabric8 `.file().upload()` 上传进 pod `/tmp/arthas-boot.jar`。

### 决策 3：commons-compress 运行时依赖坑（fabric8 upload 必需）

- **问题**：fabric8 `PodUpload` 用 commons-compress 的 `TarArchiveOutputStream`/`TarArchiveEntry` 打 tar 流（javap 字节码铁证）。fabric8 把 commons-compress 声明为 **optional**（不传递）→ spring-boot uber jar 重打包缺失 → 运行时 `NoClassDefFoundError`，ensure 失败于 `install_arthas` 阶段。
- **解法**：`pom.xml:107-111` 显式声明 `org.apache.commons:commons-compress:1.28.0`（与 fabric8 7.6.1 同版本），使其进 `BOOT-INF/lib`。
- 相关 memory：`fabric8-upload-needs-commons-compress`。

### 决策 4：`--target-ip 0.0.0.0`（arthas MCP 监听绑定地址，R4）

- **arthas 4.3.0 源码证据链**（research.md R4）：
  - `Bootstrap.java:522` → `Arthas.java:68` → `ArthasBootstrap.java:457` → `HttpTermServer.java:50` → `NettyWebsocketTtyBootstrap.java:76` `b.bind(host, port)`。
  - `--target-ip` 值直接决定 Netty ServerBootstrap 绑定地址；MCP `/mcp` 端点搭乘同一 Netty 管线（`HttpRequestHandler.java:52/80-86`）。
- **本机 A/B 实证**（2026-06-22）：
  - A 组 `--target-ip 0.0.0.0` → netstat `0.0.0.0:39182 LISTENING` + `[::]:39182`（双 wildcard，NodePort 可达）。
  - B 组 `--target-ip 127.0.0.1` → netstat `127.0.0.1:39184`（仅 loopback，NodePort 不可达）。
- **配套**：arthas 绑 0.0.0.0 时**强制鉴权**（不配则随机密码、外部访问 401）→ `GatewayProperties.K8s.arthasPassword`（缺省 `"arthas-mcp-gateway"`，生产应覆盖）→ `--password` 下发 + Bearer 令牌注入动态后端 `Auth` 与健康检查 `BackendAuthCustomizer`。
- K-ENS-7 故障用例：注入 `--target-ip 127.0.0.1` + 短健康超时 → 验证 loopback 不可达（回归守护 0.0.0.0 要求）。

---

## 第 35 章 真实端到端验证（SC-001 实测）

> 以下为 2026-07-08 本机 + 远端 debian k3s 实测捕获（非桩）。

### 35.1 链路

```
1. ensure-arthas-mcp(server=debian, pod=demo-business)
   → {target:"debian-demo-business", status:"ready", mcpUrl:"http://192.168.31.92:30415", namespace:"default"}

2. jvm(target=debian-demo-business)
   → MACHINE-NAME=1@demo-business（pod 内容器真实主机名）
   → VM-VERSION=21.0.11+10-LTS（Eclipse Adoptium）（pod JVM 真实版本）
   → OS=Linux/amd64、JVM-START-TIME=2026-06-22 19:34:58、statusCode=0、success=true

3. list-targets
   → 3 targets：order-service + payment（静态种子）+ debian-demo-business（动态纳管，共存）
```

→ **远程 pod JVM 诊断完全可达**（结果来自远端 debian 上的 pod，非本机），K8S 编排闭环 SC-001 验证通过。

### 35.2 故障韧性（SC-003，已验证）

```bash
ssh root@192.168.31.92 'kubectl delete pod demo-business'
```
- 网关 **30s 内**把 `debian-demo-business` 标 `healthy=false`。
- 对该 target 诊断 → 结构化错误（INVALID_PARAMS + `reason=backend_unreachable`）。
- **其他 target（payment/order-service）不受影响**（隔离）。
- pod 重新部署后再次 `ensure-arthas-mcp` → `status:ready` 恢复纳管。

---

## 第 32 章 005 K8S 编排能力迭代（Service 复用 / K8S Host / JDK SPI）

005 在 003 编排基础上做三点迭代，适配「复用现有 Service 暴露 / 远端 K8S 集群入口 / 容器独立 JDK」三类运维场景。设计见 `docs/superpowers/specs/2026-07-10-k8s-orchestration-iteration-design.md`，规格见 `specs/005-k8s-orchestration-iteration/`。

### 32.1 US1 Service 复用（NodePortExposer label + patch + 回退）

003 `NodePortExposer.expose` 总新建独立 `arthas-mcp-<logical>` Service。005 改为优先复用带 `arthas-mcp-gateway/target=<labelValue>` label 的现有业务 Service（运维预打 label 即声明复用）：

- `findLabeledService(ns, labelValue)`：labelSelector 查带 label 的 Service。
- `patchServiceAddNodePort(svc, mcpPort)`：复用既有 `targetPort=mcpPort` 端口（K-ENS-12 幂等），仅改 `type=NodePort`（K-ENS-11，K8S 为既有端口分配 nodePort）；多端口补 name 避免冲突。
- 未命中 → 回退 `ensureNodePortService`（003 现状新建，K-ENS-10 回退，向后兼容）。

单测 `NodePortExposerTest`（fabric8 KubernetesMockServer）+ 契约 IT `NodePortExposerContractIT`（真实 k3s：复用 30050 / ClusterIP→NodePort 30847 / 幂等 30052 / 回退 30421）。

### 32.2 US2 K8S Host 配置 + BackendConfig K8S 模式 + 懒 resolve

新增 `arthas-gateway.k8s-hosts` 配置（远端 Linux K8S 集群入口：name + kubeconfig + namespace）。`BackendConfig` 加 `k8sHost` + `pod`（与 `url` 互斥，INV-K8SHOST-1）——声明「远端 host + 业务 pod」，运行时懒 resolve 出 mcpUrl。

- `BackendResolver` 接口（gateway-core，零 fabric8，INV-BOUNDARY-1）：`Optional<String> resolveMcpUrl(config)`。
- `K8sBackendResolver`（orchestration，INV-BOUNDARY-2）：按 host 建 provisioner，`resolveMcpUrl` → `ArthasProvisioner.ensure` + `ConcurrentHashMap` 缓存（INV-K8SHOST-2）；静态模式旁路 empty（INV-K8SHOST-5）；未知 host → `unknown_k8s_host`（INV-K8SHOST-3）。
- `BackendEntry.initializeOnce` 懒 resolve：K8S 模式（client=null）首调时经 `Supplier<Optional<BackendResolver>>` resolve mcpUrl → `withResolvedUrl` 建 `HttpBackendClient`；无 resolver → `no_k8s_resolver`（INV-K8SHOST-4）。

装配用 `ObjectProvider` + `@Lazy`（打破 `registryHolder↔dynamicBackendStore↔backendConfigWatcher` 启动期环）。

### 32.3 US3 JDK 适配 SPI（ArthasLauncher + DefaultArthasLauncher + @Primary）

003 `ArthasProvisioner` 硬编码 `jps` 定位 + `java -jar` 启动（假设 PATH）。005 抽 `ArthasLauncher` SPI（locatePid + startArthas + LaunchContext），适配容器独立 JDK：

- `DefaultArthasLauncher`（003 现状外移，INV-LAUNCHER-2）：PATH 的 jps + java -jar。
- 用户写 `@Primary` 实现覆盖（INV-LAUNCHER-3）：定制 javaPath / 完整命令模板（FR-009~012）。
- `ArthasProvisioner` 委托 launcher（删硬编码，INV-LAUNCHER-1）；`LaunchException` → failed 映射（INV-LAUNCHER-4）。
- `LaunchContext.arthasBootJar` 为 String（非 Path，避免 Windows `\` 转换破坏远程 Linux path）。

单测 `ArthasLauncherSpiTest`（spy 委托 + 异常映射）+ 契约 IT `CustomLauncherContractIT`（@Primary 覆盖）。test fixture `TestArthasLauncher`（真实实现非 mock，INV-LAUNCHER-5）。

### 32.4 不变量守护

- 零 gateway-core K8S 依赖不变（ArchUnit `PackageBoundaryTest` 加 BackendResolver 接口位置 + K8sBackendResolver 在 orchestration，INV-BOUNDARY-1/2）。
- 003 契约全不破（`ArthasProvisionerIT` 5/5 回归：K-ENS-1/2/4/5/7 + K-ATOMIC-1；委托改造行为不变）。
- 向后兼容：静态 url backend + 无 label Service + 无自定义 launcher → 行为 = 003/004 现状。

---

> **下一步**：Part 5 深入 004 portal 管理面（后端 CRUD/任务 + 前端 SPA + 构建）。
