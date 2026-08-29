@echo off
rem ============================================================
rem   Stop the standalone kernel server.
rem   Usage: stop.bat [port]     (default port 12787)
rem   Kills the java process listening on the kernel port and/or
rem   the "Cryptand-Kernel" window's java process.
rem ============================================================
setlocal
set "PORT=12787"
if not "%~1"=="" set "PORT=%~1"

echo [stop] closing kernel on port %PORT% ...

rem 1) kill java process whose window title is Cryptand-Kernel
taskkill /F /FI "IMAGENAME eq java.exe" /FI "WINDOWTITLE eq Cryptand-Kernel" >nul 2>&1

rem 2) kill any process listening on the kernel port
for /f "tokens=5" %%p in ('netstat -ano -p tcp ^| findstr /R /C:":%PORT% .*LISTENING"') do (
    echo [stop] killing PID %%p (port %PORT%)
    taskkill /F /PID %%p >nul 2>&1
)

echo [stop] done. (closing the Cryptand-Kernel window also stops the kernel)
pause
endlocal
