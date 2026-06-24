#!/usr/bin/env bash
# test-env/k8s/setup.sh —— 003 测试床一键幂等搭建（本机 Git Bash 运行，内部经 SSH 操作 debian）。
#
# 顺序：
#   1. 校验 reference/k3s/ 离线资源齐（前置：先跑 reference/k3s/fetch.sh）
#   2. mvn test-compile 产 demo 运行时夹具类
#   3. 组 build context（Dockerfile + 3 个夹具类含内部类）→ ship debian
#   4. 离线 airgap 装 k3s（幂等；瘦身 + tls-san）
#   5. debian 上 docker build demo 镜像 → k3s ctr import
#   6. apply demo pod → 等 Ready
#   7. 导出 root-on-node 派生 admin kubeconfig（server 改 192.168.31.92:6443，文件 600）
#   8. 自检
#
# 详见 docs/superpowers/specs/2026-06-23-k8s-test-env-setup-design.md §6/§7。
# 清理见 teardown.sh。幂等：k3s 已装则跳过、镜像已 import 则跳过、pod 已存在则不重建。
set -euo pipefail

# ---------- 配置 ----------
HOST="root@192.168.31.92"
SSH_OPTS=(-o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=8)
NODE_IP="192.168.31.92"
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
K3S_DIR="${REPO_ROOT}/reference/k3s"
THIS_DIR="${REPO_ROOT}/test-env/k8s"
KUBECONFIG_OUT="${THIS_DIR}/kubeconfig/k3s-admin.yaml"
REMOTE_ARTIFACTS="/tmp/k3s-artifacts"
REMOTE_CTX="/tmp/demo-build"
DEMO_IMAGE="arthas-gateway/demo-business:local"
FIXTURES_PKG="com/arthas/gateway/testfixtures"

remote() { ssh "${SSH_OPTS[@]}" "$HOST" "$@"; }

echo "=== [1/8] 校验 k3s 离线资源 ==="
for f in k3s k3s-airgap-images-amd64.tar.gz k3s-install.sh sha256sums.txt; do
  if [ ! -f "${K3S_DIR}/${f}" ]; then
    echo "[setup] 缺 ${K3S_DIR}/${f}（先跑 reference/k3s/fetch.sh）" >&2
    exit 1
  fi
done
echo "[setup] k3s 资源齐：${K3S_DIR}"

echo "=== [2/8] mvn test-compile 产 demo 夹具类 ==="
( cd "${REPO_ROOT}" && ./mvnw -q test-compile )
CLASSES_DIR="${REPO_ROOT}/target/test-classes/${FIXTURES_PKG}"
if [ ! -f "${CLASSES_DIR}/DemoBusinessApp.class" ]; then
  echo "[setup] 未找到 ${CLASSES_DIR}/DemoBusinessApp.class（test-compile 失败？）" >&2
  exit 1
fi
echo "[setup] 夹具类就绪：${CLASSES_DIR}"

echo "=== [3/8] 组 build context 并 ship 到 debian ==="
CTX="$(mktemp -d)"
trap 'rm -rf "${CTX}"' EXIT
mkdir -p "${CTX}/classes/${FIXTURES_PKG}"
# 3 个运行时类（DemoBusinessApp 含内部类 OrderHandler/HealthHandler → DemoBusinessApp$*.class）
cp "${CLASSES_DIR}/DemoBusinessApp"*.class "${CTX}/classes/${FIXTURES_PKG}/"
cp "${CLASSES_DIR}/OrderService.class"      "${CTX}/classes/${FIXTURES_PKG}/"
cp "${CLASSES_DIR}/OrderResult.class"       "${CTX}/classes/${FIXTURES_PKG}/"
cp "${THIS_DIR}/Dockerfile.demo"            "${CTX}/Dockerfile"
tar -czf /tmp/demo-build.tar.gz -C "${CTX}" Dockerfile classes
scp "${SSH_OPTS[@]}" /tmp/demo-build.tar.gz "${HOST}:${REMOTE_CTX}.tar.gz"
scp "${SSH_OPTS[@]}" "${THIS_DIR}/demo-pod.yaml" "${HOST}:${REMOTE_CTX}.pod.yaml"
rm -f /tmp/demo-build.tar.gz
echo "[setup] build context + pod 清单已 ship 到 ${HOST}"

echo "=== [4/8] ship k3s 离线资源到 debian（幂等） ==="
remote "mkdir -p ${REMOTE_ARTIFACTS} ${REMOTE_CTX}"
# 仅传缺失/变更的资源（k3s 二进制 ~60MB、airgap ~180MB，已传则跳过省时）
for f in k3s k3s-airgap-images-amd64.tar.gz k3s-install.sh sha256sums.txt; do
  if ! remote "test -s ${REMOTE_ARTIFACTS}/${f}"; then
    scp "${SSH_OPTS[@]}" "${K3S_DIR}/${f}" "${HOST}:${REMOTE_ARTIFACTS}/${f}"
    echo "[setup]   传 ${f}"
  fi
done
echo "[setup] 校验完整性 sha256..."
remote "cd ${REMOTE_ARTIFACTS} && sha256sum -c sha256sums.txt >/dev/null" || {
  echo "[setup] sha256 校验失败，删除重传" >&2
  remote "rm -f ${REMOTE_ARTIFACTS}/k3s ${REMOTE_ARTIFACTS}/k3s-airgap-images-amd64.tar.gz"
  exit 1
}

echo "[setup] 离线 airgap 装 k3s（幂等：已装则跳过）..."
remote 'set -e
  if command -v k3s >/dev/null 2>&1 && k3s kubectl get --raw=/healthz >/dev/null 2>&1; then
    echo "[setup]   k3s 已装且健康，跳过安装"
  else
    install -m 755 /tmp/k3s-artifacts/k3s /usr/local/bin/k3s
    mkdir -p /var/lib/rancher/k3s/agent/images
    cp -f /tmp/k3s-artifacts/k3s-airgap-images-amd64.tar.gz /var/lib/rancher/k3s/agent/images/
    INSTALL_K3S_SKIP_DOWNLOAD=true sh /tmp/k3s-artifacts/k3s-install.sh \
      --disable traefik --disable servicelb --disable metrics-server \
      --tls-san '"${NODE_IP}"'
  fi'

echo "=== [5/8] debian 上 build demo 镜像 + k3s ctr import（幂等） ==="
remote "set -e
  rm -rf ${REMOTE_CTX}/d && mkdir -p ${REMOTE_CTX}/d
  tar -xzf ${REMOTE_CTX}.tar.gz -C ${REMOTE_CTX}/d
  if k3s ctr images check 2>/dev/null | grep -q '${DEMO_IMAGE}'; then
    echo '[setup]   镜像 ${DEMO_IMAGE} 已 import，跳过 build'
  else
    docker build -t '${DEMO_IMAGE}' ${REMOTE_CTX}/d
    docker save '${DEMO_IMAGE}' | k3s ctr images import -
    echo '[setup]   镜像已 build + import'
  fi"

echo "=== [6/8] apply demo pod + 等 Ready ==="
remote "set -e
  if k3s kubectl -n default get pod demo-business >/dev/null 2>&1; then
    echo '[setup]   demo-business pod 已存在，不重建'
  else
    k3s kubectl apply -f ${REMOTE_CTX}.pod.yaml
  fi
  echo '[setup]   等 demo-business Ready...'
  k3s kubectl -n default wait --for=condition=Ready pod/demo-business --timeout=180s"

echo "=== [7/8] 导出 root-on-node 派生 admin kubeconfig ==="
mkdir -p "$(dirname "${KUBECONFIG_OUT}")"
remote "cat /etc/rancher/k3s/k3s.yaml" \
  | sed "s|https://127.0.0.1:6443|https://${NODE_IP}:6443|" \
  > "${KUBECONFIG_OUT}"
chmod 600 "${KUBECONFIG_OUT}"
echo "[setup] kubeconfig → ${KUBECONFIG_OUT}"

echo "=== [8/8] 自检 ==="
remote "ss -tlnp 2>/dev/null | grep -q ':6443' && echo '[setup]   API server :6443 监听 OK' || echo '[setup]   WARN: :6443 未监听'"
echo "[setup] demo pod 状态（远程）："
remote "k3s kubectl -n default get pod demo-business -o wide 2>&1 || true"

cat <<EOF

[setup] 完成。
  - 测试集群：k3s @ ${NODE_IP}
  - demo pod：default/demo-business（含 shell+java+JVM）
  - kubeconfig：${KUBECONFIG_OUT}（server=https://${NODE_IP}:6443）
本机验证：
  kubectl --kubeconfig "${KUBECONFIG_OUT}" get pods
NodePort 可达性（ensure 后）：
  Test-NetConnection ${NODE_IP} -Port <nodePort>
EOF
