#!/usr/bin/env bash
# =============================================================================
# 停止常驻 arthas MCP 网关 + 双后端（按命令行匹配杀全部相关 java 进程）
# 覆盖：网关 GatewayApplication / 启动器 SmokeDemoLauncher / 业务服务 DemoBusinessApp / arthas-boot.jar
# 用法： bash smoke/gateway-stop.sh
# =============================================================================
powershell -NoProfile -Command \
  "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'GatewayApplication|SmokeDemoLauncher|DemoBusinessApp|arthas-boot' } | ForEach-Object { Write-Output ('kill PID=' + \$_.ProcessId); Stop-Process -Id \$_.ProcessId -Force }" \
  2>/dev/null || true
echo "[stop] 已清理相关 java 进程（若有）。"
