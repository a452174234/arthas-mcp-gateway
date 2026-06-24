#!/usr/bin/env bash
# =============================================================================
# 停止常驻 arthas MCP 网关 + 双后端（按命令行匹配杀全部相关 java 进程）
# 覆盖：网关 arthas-mcp-gateway.jar / GatewayApplication / 启动器 SmokeDemoLauncher /
#       业务服务 DemoBusinessApp / arthas-boot.jar
# 注：网关以 `java -jar arthas-mcp-gateway-*.jar` 启动时命令行不含主类名 GatewayApplication，
#     仅含 jar 路径，故须显式匹配 arthas-mcp-gateway（否则 java -jar 启动的网关杀不掉）。
# 用法： bash smoke/gateway-stop.sh
# =============================================================================
powershell -NoProfile -Command \
  "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'arthas-mcp-gateway|GatewayApplication|SmokeDemoLauncher|DemoBusinessApp|arthas-boot' } | ForEach-Object { Write-Output ('kill PID=' + \$_.ProcessId); Stop-Process -Id \$_.ProcessId -Force }" \
  2>/dev/null || true
echo "[stop] 已清理相关 java 进程（若有）。"
