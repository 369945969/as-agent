@echo off
setlocal

set COMMIT_MSG=auto commit

git add -A
if errorlevel 1 goto :error

git commit -m "%COMMIT_MSG%"
if errorlevel 1 goto :error

git push
if errorlevel 1 goto :error

echo.^
echo Push succeeded.
goto :end

:error
echo.
echo Error occurred. Push aborted.
exit /b 1

:end
exit /b 0

cmd