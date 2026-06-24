#!/usr/bin/env bash
# reference/k3s/fetch.sh —— 一次性预置 k3s 离线资源（防 github 间歇不可达，memory github-com-unreachable）。
#
# 锁定单一 k3s 稳定版（amd64）。版本号 + sha256 写入本文件与 sha256sums.txt（运行时生成）。
# 当前锁定 v1.35.5+k3s1（2026-06 stable channel）。升级时显式改 K3S_VERSION + 重跑本脚本刷 sha256sums.txt。
#
# 详见 docs/superpowers/specs/2026-06-23-k8s-test-env-setup-design.md §6.1。
set -euo pipefail

K3S_VERSION="v1.35.5+k3s1"
ARCH=amd64
BASE="https://github.com/k3s-io/k3s/releases/download/${K3S_VERSION}"
OUT="$(cd "$(dirname "$0")" && pwd)"

# 断言架构（debian 测试床须 x86_64）
if [ "$(uname -m)" != "x86_64" ] && [ "$(uname -m)" != "amd64" ]; then
  echo "[fetch] 仅支持 amd64，实得 $(uname -m)" >&2
  exit 1
fi

echo "[fetch] 预置 k3s 离线资源 → ${OUT}（版本 ${K3S_VERSION}）"

# 带重试下载（github 间歇不可达：坏时重试/等几分钟，memory github-com-unreachable）
retry() {
  local n=0
  until "$@"; do
    n=$((n + 1))
    if [ "$n" -ge 6 ]; then
      echo "[fetch] 重试 5 次仍失败，放弃（github 间歇不可达，请等几分钟后重跑）" >&2
      return 1
    fi
    echo "[fetch] 失败，重试 ${n}/5（等 30s）..." >&2
    sleep 30
  done
}

retry curl -fL --retry 3 --retry-delay 5 "${BASE}/k3s" -o "${OUT}/k3s"
retry curl -fL --retry 3 --retry-delay 5 "${BASE}/k3s-airgap-images-${ARCH}.tar.gz" -o "${OUT}/k3s-airgap-images-${ARCH}.tar.gz"
retry curl -fL --retry 3 --retry-delay 5 "https://raw.githubusercontent.com/k3s-io/k3s/${K3S_VERSION}/install.sh" -o "${OUT}/k3s-install.sh"

# 完整性校验文件（setup.sh 安装前校验）
( cd "${OUT}" && sha256sum k3s k3s-airgap-images-${ARCH}.tar.gz k3s-install.sh > sha256sums.txt )
chmod +x "${OUT}/k3s" "${OUT}/k3s-install.sh"

echo "[fetch] 完成：k3s / k3s-airgap-images-${ARCH}.tar.gz / k3s-install.sh + sha256sums.txt"
echo "[fetch] 下一步：bash test-env/k8s/setup.sh"
