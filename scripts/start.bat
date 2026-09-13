@echo off
rem Start agentscope (Web HTTP + WebSocket streaming, both opened together)
rem Usage: scripts\start.bat [port]
rem Environment variables:
rem   DSH_TOKEN=...                        required, auth key
rem   DSH_PORT=8766                        port
rem   DSH_MODEL_CONFIG=~/.dsh/model-config.json  model config file
rem Models (apiKey/baseUrl/model) are loaded from the model config file,
rem a request may carry a "model" id to pick a specific model.
rem On startup both the Web (port) and WebSocket (port+1) servers are opened
rem and their ports are printed to the log.
setlocal
set "ROOT=%~dp0.."
cd /d "%ROOT%"

rem Port: first argument takes priority, then DSH_PORT, otherwise default 8766
if not "%~1"=="" (set "PORT=%~1") else (if defined DSH_PORT (set "PORT=%DSH_PORT%") else (set "PORT=8766"))

set "JAR=%ROOT%\target\agentscope-1.0.0.jar"

if not exist "%JAR%" (echo [start] Jar not found, run scripts\build.bat first & exit /b 1)
if "%DSH_TOKEN%"=="" (echo [start] Please set DSH_TOKEN environment variable & exit /b 1)

rem Kill any previous agentscope instance so the ports are free
echo [start] killing previous agentscope process (if any)...
powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { $_.Name -like 'java*' -and $_.CommandLine -like '*agentscope-1.0.0.jar*' } | ForEach-Object { Write-Output ('[start] killed PID ' + $_.ProcessId); Stop-Process -Id $_.ProcessId -Force }"

echo [start] launching agentscope (port=%PORT%, ws=%PORT%+1)...
java -jar "%JAR%" "%PORT%"