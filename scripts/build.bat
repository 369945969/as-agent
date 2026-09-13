@echo off
rem Build the agentscope project: mvn clean package generates a fat jar
rem Usage: scripts\build.bat
setlocal
set "ROOT=%~dp0.."
cd /d "%ROOT%"

rem Kill any running agentscope instance first, otherwise the jar stays locked and clean fails
echo [build] killing running agentscope process (if any)...
powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { $_.Name -like 'java*' -and $_.CommandLine -like '*agentscope-1.0.0.jar*' } | ForEach-Object { Write-Output ('[build] killed PID ' + $_.ProcessId); Stop-Process -Id $_.ProcessId -Force }"

echo [build] mvn clean package...
call mvn -q clean package -DskipTests
if errorlevel 1 (echo [build] FAILED & exit /b 1)
echo [build] Done: target\agentscope-1.0.0.jar