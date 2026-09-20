@echo off
chcp 65001 >nul
title enterprise-RAG 启动器
cd /d "%~dp0"

echo ============================================
echo   enterprise-RAG 一键启动
echo   存储: enterprise-RAG VM (192.168.88.130)
echo   端口: 9090  (Swagger: http://localhost:9090/swagger-ui.html)
echo ============================================

REM ---- 清理旧实例（9090~9095 端口的残留 java 进程）----
echo [1/3] 清理旧实例...
for /l %%p in (9090,1,9095) do (
  for /f "tokens=5" %%a in ('netstat -ano ^| findstr "LISTENING" ^| findstr ":%%p "') do (
    taskkill /F /PID %%a >nul 2>&1
  )
)

REM ---- API Key：从系统环境变量读取（已用 setx 配置，新终端自动生效）----
if "%DASHSCOPE_API_KEY%"=="" (
  echo [2/3] 未检测到 DASHSCOPE_API_KEY 环境变量
  set /p DASHSCOPE_API_KEY=请输入硅基流动 API Key:
)
echo [2/3] API Key 已就绪

REM ---- 启动（数据库地址已写死在 application.yml，端口 9090）----
echo [3/3] 启动中... （首次提问约 90 秒冷启动，属正常现象）
mvn spring-boot:run

pause
