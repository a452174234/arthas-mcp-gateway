#!/usr/bin/env bash
# =============================================================================
# 常驻启动 arthas MCP 网关 + 双真实后端（detached：nohup + &，进程脱离调用会话）
# 幂等：先清残留再起。用法： bash smoke/gateway-start.sh
# 停止： bash smoke/gateway-stop.sh
# =============================================================================
set -u
J21="${J21:-/c/Program Files/Java/jdk-21}"
ROOT="D:/vibe_Coding/arthas-gateway"
cd "$ROOT" || { echo "[start] cd 失败"; exit 1; }

echo "[start] 清理残留进程（若有）..."
bash smoke/gateway-stop.sh >/dev/null 2>&1 || true
sleep 1

echo "[start] ① 启动双真实后端（SmokeDemoLauncher）..."
nohup "$J21/bin/java" -Dbasedir="$ROOT" \
  -cp "target/test-classes;target/smoke-classes" \
  com.arthas.gateway.smoke.SmokeDemoLauncher > target/smoke-launcher.log 2>&1 &
# 非交互 bash 退出不发 SIGHUP；进程被重父到 session，脱离会话托管（常驻）

echo "[start] ② 等待后端 READY（最多 90s，含首跑 arthas 4.3.0 下载）..."
ok=0
for i in $(seq 1 90); do
  if grep -q "SMOKE_DEMO_READY" target/smoke-launcher.log 2>/dev/null; then ok=1; break; fi
  sleep 1
done
if [ "$ok" != "1" ]; then
  echo "[start] ✗ 后端未就绪，见 target/smoke-launcher.log"; tail -20 target/smoke-launcher.log; exit 1
fi
grep -E "baseUrl|appPort|mcpPort|SMOKE_DEMO_READY" target/smoke-launcher.log

echo "[start] ③ 启动网关 :8761（指向 backends-runtime.yaml）..."
nohup "$J21/bin/java" -jar target/arthas-mcp-gateway-0.1.0-SNAPSHOT.jar \
  --arthas-gateway.backends-file=config/backends-runtime.yaml > target/smoke-gateway.log 2>&1 &

echo "[start] ④ 等待网关 :8761 就绪（最多 40s）..."
ok=0
for i in $(seq 1 40); do
  if curl -sf --max-time 3 http://127.0.0.1:8761/actuator/health >/dev/null 2>&1; then ok=1; break; fi
  sleep 1
done
if [ "$ok" != "1" ]; then
  echo "[start] ✗ 网关未就绪，见 target/smoke-gateway.log"; tail -30 target/smoke-gateway.log; exit 1
fi

echo "[start] ⑤ 健康检查："
curl -s --max-time 5 http://127.0.0.1:8761/actuator/health | tr ',' '\n' \
  | grep -E "summary|\"healthy\"|state|breaker" | head
echo ""
echo "[start] ✓ 常驻就绪。MCP 端点 http://127.0.0.1:8761/mcp"
echo "       停止： bash smoke/gateway-stop.sh"
