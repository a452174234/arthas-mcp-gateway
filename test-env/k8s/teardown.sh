#!/usr/bin/env bash
# test-env/k8s/teardown.sh —— 003 测试床幂等清理（卸 k3s + 删本机导出凭证 + 清远端临时文件）。
#
# 详见 docs/superpowers/specs/2026-06-23-k8s-test-env-setup-design.md §九。
# 幂等：k3s 未装则跳过；凭证不存在则跳过。
set -euo pipefail

HOST="root@192.168.31.92"
SSH_OPTS=(-o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=8)
REPO_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
KUBECONFIG_OUT="${REPO_ROOT}/test-env/k8s/kubeconfig/k3s-admin.yaml"

remote() { ssh "${SSH_OPTS[@]}" "$HOST" "$@"; }

echo "=== [1/3] 远端卸 k3s（幂等） ==="
if remote "test -x /usr/local/bin/k3s-uninstall.sh"; then
  remote "/usr/local/bin/k3s-uninstall.sh" </dev/null
  echo "[teardown] k3s 已卸（容器/镜像/cni 清理）"
else
  echo "[teardown] 远端未装 k3s，跳过"
fi

echo "=== [2/3] 清远端临时文件 ==="
remote "rm -rf /tmp/k3s-artifacts /tmp/demo-build /tmp/demo-build.tar.gz /tmp/demo-build.pod.yaml" \
  && echo "[teardown] 远端临时文件已清" || echo "[teardown] 远端临时文件清理（无则跳过）"

echo "=== [3/3] 删本机导出凭证（幂等） ==="
if [ -f "${KUBECONFIG_OUT}" ]; then
  rm -f "${KUBECONFIG_OUT}"
  echo "[teardown] 已删 ${KUBECONFIG_OUT}"
else
  echo "[teardown] 本机凭证不存在，跳过"
fi

echo "[teardown] 完成。"
