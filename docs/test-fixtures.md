# 真实测试夹具使用指南

> 测试夹具 = 冒烟/集成测试用的**真实业务后端**（真实 arthas MCP + 真实业务 JVM），**非桩**。本文档说明夹具结构、编译方式、使用场景与真实性证据。
>
> **宪法原则七（CLAUDE.md 真实性硬约束）**：每次测试至少启动一个真实 arthas MCP + 一个真实业务服务；诊断数据由触发业务服务真实调用产生；故障类用真实故障条件（停后端=不可达、错 token=arthas 真实 401、`Thread.sleep`=慢响应、真实并发越界）。夹具即承载这套真实性的代码。

---

## 1. 两层夹具结构

### 1.1 核心夹具 — `src/test/java/com/arthas/gateway/testfixtures/`

由 `mvn test-compile` 编到 `target/test-classes/`（标准 Maven 测试编译，无需额外配置）。

| 类 | 职责 |
|----|------|
| `ArthasMcpBackend` | 拉起**一个真实 arthas MCP 后端**（业务 JVM + arthas http console `/mcp`）；冒烟与契约 IT 共用 |
| `DemoBusinessApp` | 真实业务 JVM（含 `OrderService.hotMethod` 自驱动 hotLoop + `/health` + `/order` HTTP 端点） |
| `OrderService` | 真实业务类（`hotMethod` 被 arthas `watch`/`trace` 捕获） |
| `OrderResult` | 业务返回值（`watch` 结果含 `accessPoint`/`className`/`value`） |
| `McpClientHarness` | 官方 MCP Java SDK client 封装（契约 IT / 一致性 A/B 驱动） |
| `FakeBackendClient` | 测试用 backend client（**测试 client，非桩后端**） |

### 1.2 冒烟启动器 — `smoke/`

**不在 `src/`，Maven 不编译**；需手动 `javac` 到 `target/smoke-classes/`。

| 文件 | 职责 |
|------|------|
| `SmokeDemoLauncher.java` | 一键拉起**双真实后端**（order-service + payment）+ 写 `config/backends-runtime.yaml`（动态端口）+ 常驻 |
| `SmokeMcpClient.java` | MCP client 冒烟（连网关调工具） |
| `SmokeWatchAsync.java` | 异步 `watch` 冒烟 |
| `gateway-start.sh` | 一键启动：双后端 + 网关（用 `target/test-classes;target/smoke-classes`） |
| `gateway-stop.sh` | 停全部相关 java 进程 |
| `r4-bind-test.sh` | arthas NodePort 绑定地址实证（003 R4） |

---

## 2. 编译步骤（全新环境复现）

### 2.1 核心夹具（Maven 编译）

```bash
./mvnw test-compile
# 产出 target/test-classes/com/arthas/gateway/testfixtures/*.class
```

> `mvn verify` / `mvn package` 也含 `test-compile`，无需单独跑。

### 2.2 SDK classpath 快照（`target/sdk-cp.txt`）

冒烟启动器 `javac` 需 MCP SDK 依赖路径（`SmokeMcpClient` 引用 `McpSyncClient` 等）：

```bash
./mvnw -q dependency:build-classpath -Dmdep.outputFile=target/sdk-cp.txt
# 产出 target/sdk-cp.txt（~14KB，含 MCP SDK + Spring 依赖路径，分号分隔）
```

### 2.3 冒烟启动器（手动 javac）

```bash
J21="/c/Program Files/Java/jdk-21"          # JDK 21 路径（按你本机调整）
mkdir -p target/smoke-classes
CP="target/test-classes;$(cat target/sdk-cp.txt)"   # Windows/Git Bash 用 ; 分隔（Linux/Mac 用 :）
"$J21/bin/javac" -cp "$CP" -d target/smoke-classes \
  smoke/SmokeDemoLauncher.java smoke/SmokeMcpClient.java smoke/SmokeWatchAsync.java
# 产出 target/smoke-classes/com/arthas/gateway/smoke/*.class
```

> 注：`javac` 会打印「无注解处理器」提示，无害（不影响产物）。

### 2.4 一键启动（自动用上述产物）

```bash
bash smoke/gateway-start.sh
# 内部执行：
#   java -Dbasedir=<ROOT> -cp "target/test-classes;target/smoke-classes" com.arthas.gateway.smoke.SmokeDemoLauncher
#   java -jar target/arthas-mcp-gateway-0.1.0-SNAPSHOT.jar --arthas-gateway.backends-file=config/backends-runtime.yaml
```

⚠️ `gateway-start.sh` 假设 `target/smoke-classes` **已编译**。全新环境首次须先跑 §2.1–2.3。

---

## 3. K8S 场景夹具（ship 到远端 pod 镜像）

K8S 测试床把核心夹具的 **3 个运行时类**（`DemoBusinessApp` + `OrderService` + `OrderResult`）ship 到 debian 服务器，打成 docker 镜像，import k3s，apply 为 `demo-business` pod：

```bash
bash test-env/k8s/setup.sh
# 顺序：mvn test-compile 产 demo class → ship 到 debian → docker build 镜像
#      → k3s ctr import → kubectl apply demo pod → 导出 root 派生 admin kubeconfig
```

要点：
- 镜像**仅 3 个业务类**（不含 `ArthasMcpBackend`/`McpClientHarness`/`*Test`）。
- **arthas 不在镜像**：`k8s.ensure-arthas-mcp` 时经 fabric8 exec 上传 `tools/arthas-boot.jar`（静态文件，入 git）使用。
- 清理：`bash test-env/k8s/teardown.sh`。

---

## 4. 真实性证据（实测，非桩）

`jvm` 工具对夹具后端返回的诊断含**该业务 JVM 自身真实数据**：

| 字段 | 实测值（证明真实） |
|------|---------------------|
| `INPUT-ARGUMENTS` | `[-Ddemo.slowMs=0]`（`DemoBusinessApp` 启动参数） |
| `CLASS-PATH` | `…target/test-classes`（夹具 classpath） |
| `MACHINE-NAME` | `<pid>@<host>`（真实进程 ID + 主机名） |
| `VM-VERSION` | `21.0.5+9-LTS` / `21.0.11+10-LTS`（本机/远端 pod 各自 JVM） |
| `watch` 结果 | `OrderService.hotMethod` 真实 `accessPoint`/`cost`/`value` |

→ 这些数据**只能**来自运行中的真实 JVM，桩无法伪造。

---

## 5. 文件位置总览

| 路径 | 说明 | 入 git |
|------|------|--------|
| `src/test/java/com/arthas/gateway/testfixtures/` | 核心夹具**源码** | ✓ |
| `smoke/` | 冒烟启动器**源码** + 启停脚本 | ✓ |
| `tools/arthas-boot.jar` | 静态 arthas 工具（夹具 + K8S ensure 用） | ✓（例外：非构建产物的工具文件） |
| `target/test-classes/` | `mvn test-compile` 产物 | ✗ gitignored |
| `target/smoke-classes/` | 手动 `javac` 产物 | ✗ gitignored |
| `target/sdk-cp.txt` | SDK classpath 快照 | ✗ gitignored |
| `config/backends-runtime.yaml` | `SmokeDemoLauncher` 每次启动按动态端口重写 | ✗ gitignored |

> 全部产物在 `target/`（gitignored），全新环境照 §2 重编即可复现。源码（夹具 + smoke + arthas-boot.jar）全入库，clone 后即可用。
