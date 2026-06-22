#!/usr/bin/env bash
# R4 实证（一次性验证工具，非生产代码 / 非 spec 任务）：
# arthas --target-ip 直接决定 MCP HTTP 监听 socket 的绑定地址。
# A/B 对照：同一真实 DemoBusinessApp JVM，分别以 0.0.0.0 / 127.0.0.1 attach，
# netstat 断言监听 socket 分别为 0.0.0.0:<port>（wildcard，NodePort 可达）与 127.0.0.1:<port>（loopback）。
#
# 前置：先 `mvn test-compile`（产出 target/test-classes/DemoBusinessApp）；arthas 4.3.0 运行时已缓存于 ~/.arthas/lib/4.3.0。
# 用法：bash smoke/r4-bind-test.sh
set -u
cd "$(dirname "$0")/.."   # 切到工程根

APP_A_PORT=39181
MCP_A_PORT=39182   # --target-ip 0.0.0.0
APP_B_PORT=39183
MCP_B_PORT=39184   # --target-ip 127.0.0.1

KILL_PIDS=()
cleanup() {
  echo "---CLEANUP---"
  for p in "${KILL_PIDS[@]:-}"; do
    [ -n "$p" ] && taskkill //F //PID "$p" >/dev/null 2>&1 || kill -9 "$p" 2>/dev/null || true
  done
}
trap cleanup EXIT

# 端口空闲检查
for p in $APP_A_PORT $MCP_A_PORT $APP_B_PORT $MCP_B_PORT; do
  if netstat -ano | grep -q ":$p .*LISTENING"; then echo "PORT_BUSY $p（请换端口）"; exit 2; fi
done

# 用 bash /dev/tcp 轮询端口监听
wait_listen() {  # $1=port
  local port=$1 i
  for i in $(seq 1 100); do
    if (exec 3<>/dev/tcp/127.0.0.1/$port) 2>/dev/null; then exec 3>&- 3<&-; return 0; fi
    sleep 0.5
  done
  return 1
}

# jps 取 DemoBusinessApp 的 OS PID（规避 Git Bash $! 与 Windows PID 不一致）
app_pid_by_jps() {
  jps -l 2>/dev/null | awk '/DemoBusinessApp/{print $1; exit}'
}

run_phase() {  # $1=label $2=appPort $3=mcpPort $4=targetIp
  local label=$1 appPort=$2 mcpPort=$3 targetIp=$4
  echo ""
  echo "===== PHASE $label : target-ip=$targetIp  app=$appPort  mcp=$mcpPort ====="
  local log="target/r4-app-$label.log"
  java -cp target/test-classes -Ddemo.slowMs=0 \
    com.arthas.gateway.testfixtures.DemoBusinessApp "$appPort" > "$log" 2>&1 &
  local bashpid=$!
  KILL_PIDS+=("$bashpid")
  if ! wait_listen "$appPort"; then echo "[$label] APP 未就绪，见 $log"; return 1; fi
  local pid
  pid=$(app_pid_by_jps)
  if [ -z "$pid" ]; then echo "[$label] jps 未找到 DemoBusinessApp"; return 1; fi
  echo "[$label] DemoBusinessApp OS PID=$pid"
  local alog="target/r4-attach-$label.log"
  # attach 进程注入 agent 后 exit 0；MCP HTTP 常驻目标 JVM
  if ! java -jar tools/arthas-boot.jar "$pid" \
        --attach-only --target-ip "$targetIp" \
        --telnet-port 0 --http-port "$mcpPort" \
        --use-version 4.3.0 > "$alog" 2>&1; then
    echo "[$label] arthas attach 失败（exit!=0），见 $alog"; return 1
  fi
  if ! wait_listen "$mcpPort"; then echo "[$label] MCP 端口未监听，见 $alog"; return 1; fi
  echo "[$label] MCP 已监听，netstat 本地地址："
  netstat -ano | grep ":$mcpPort " | grep LISTENING | awk '{print "      " $2 "  state=" $4 "  pid=" $5}'
  echo "[$label] 期望绑定地址前缀: $targetIp:$mcpPort"
}

PASS=0
run_phase A "$APP_A_PORT" "$MCP_A_PORT" "0.0.0.0"   && PASS=$((PASS+1))
run_phase B "$APP_B_PORT" "$MCP_B_PORT" "127.0.0.1" && PASS=$((PASS+1))

echo ""
echo "===== 结论 ====="
echo "两阶段均完成：$PASS/2"
echo "PHASE A 绑定地址应形如 0.0.0.0:$MCP_A_PORT（wildcard → NodePort 可达）"
echo "PHASE B 绑定地址应形如 127.0.0.1:$MCP_B_PORT（loopback → NodePort 不可达）"
exit 0
