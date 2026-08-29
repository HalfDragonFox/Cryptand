@echo off
setlocal EnableDelayedExpansion

rem ============================================================
rem   Cryptand circuit simulator launcher
rem   1) start frontend static server (node serve.js) + open browser
rem   2) auto-start standalone Java kernel server
rem      (test\kernel-server\run.bat; no Minecraft needed)
rem      - skipped if kernel already ready
rem   3) poll kernel until ready (http://127.0.0.1:PORT/health)
rem
rem   IMPORTANT: keep this file ASCII-only (no Chinese, no full-width
rem   chars). cmd parses batch files with the console codepage; on
rem   GBK systems non-ASCII bytes get mangled and break line parsing,
rem   producing "not recognized as a command" / "filename syntax"
rem   errors. All user-facing Chinese lives in the frontend/README.
rem ============================================================

set "SCRIPT_DIR=%~dp0"
set "KERNEL_PORT=12787"
set "WEB_PORT=12789"
set "KERNEL_URL=http://127.0.0.1:%KERNEL_PORT%/health"
set "WEB_URL=http://127.0.0.1:%WEB_PORT%/index.html"
set "TIMEOUT_SEC=600"
set "POLL_SEC=2"

rem env overrides (testing/automation):
rem   CRYPTAND_WAIT_TIMEOUT  wait timeout seconds (default 600)
rem   CRYPTAND_KERNEL_PORT / CRYPTAND_WEB_PORT  port overrides
rem   CRYPTAND_NO_BROWSER=1  skip frontend server + browser
rem   CRYPTAND_NO_KERNEL=1   skip kernel auto-start
rem   CRYPTAND_NO_MC=1       alias of NO_KERNEL (old name)
rem   CRYPTAND_NO_PAUSE=1    do not pause at exit
if defined CRYPTAND_WAIT_TIMEOUT set "TIMEOUT_SEC=%CRYPTAND_WAIT_TIMEOUT%"
if defined CRYPTAND_KERNEL_PORT set "KERNEL_PORT=%CRYPTAND_KERNEL_PORT%"
if defined CRYPTAND_WEB_PORT set "WEB_PORT=%CRYPTAND_WEB_PORT%"
set "KERNEL_URL=http://127.0.0.1:%KERNEL_PORT%/health"
set "WEB_URL=http://127.0.0.1:%WEB_PORT%/index.html"

echo ============================================
echo   Cryptand circuit simulator launcher
echo ============================================

rem ---- 1) frontend static server + browser ----
if defined CRYPTAND_NO_BROWSER goto launch_kernel
echo [1/3] starting frontend server ( %WEB_URL% )
set "USE_FILE=1"

rem if web server already up, skip launching
powershell -NoProfile -Command "try{(Invoke-WebRequest -Uri '%WEB_URL%' -UseBasicParsing -TimeoutSec 2)|Out-Null;exit 0}catch{exit 1}" >nul 2>&1
if not errorlevel 1 (
    echo        web server already up
    set "USE_FILE=0"
    goto web_ok
)

start "" /b node "%SCRIPT_DIR%serve.js" %WEB_PORT% >"%TEMP%\cryptand_serve_%WEB_PORT%_%RANDOM%.log" 2>&1
ping -n 2 127.0.0.1 >nul
powershell -NoProfile -Command "try{(Invoke-WebRequest -Uri '%WEB_URL%' -UseBasicParsing -TimeoutSec 2)|Out-Null;exit 0}catch{exit 1}" >nul 2>&1
if not errorlevel 1 (
    echo        frontend server OK
    set "USE_FILE=0"
) else (
    echo        node server unavailable, opening file:// instead
)

:web_ok
echo [2/3] opening frontend page ...
if "%USE_FILE%"=="1" (
    start "" "%SCRIPT_DIR%index.html"
) else (
    start "" "%WEB_URL%"
)

:launch_kernel
rem ---- 2) auto-start standalone Java kernel server ----
echo [3/3] starting standalone Java kernel server ...
if defined CRYPTAND_NO_KERNEL goto wait_kernel_start
if defined CRYPTAND_NO_MC goto wait_kernel_start
powershell -NoProfile -Command "try{(Invoke-WebRequest -Uri '%KERNEL_URL%' -UseBasicParsing -TimeoutSec 2)|Out-Null;exit 0}catch{exit 1}" >nul 2>&1
if not errorlevel 1 (
    echo        kernel already ready, skipping launch
    goto wait_kernel_start
)
echo        launching kernel in a new window (first run compiles engine) ...
start "Cryptand-Kernel" cmd /k call "%SCRIPT_DIR%kernel-server\run.bat" %KERNEL_PORT%

:wait_kernel_start
rem ---- 3) wait for kernel ----
echo waiting for kernel ( %KERNEL_URL% ) ...
set /a waited=0

:wait_kernel
powershell -NoProfile -Command "try{(Invoke-WebRequest -Uri '%KERNEL_URL%' -UseBasicParsing -TimeoutSec 2)|Out-Null;exit 0}catch{exit 1}" >nul 2>&1
if not errorlevel 1 goto kernel_ready
set /a waited+=%POLL_SEC%
if !waited! GEQ %TIMEOUT_SEC% goto kernel_timeout
set /a mod=!waited! %% 10
if !mod! EQU 0 echo        kernel not ready ... waited !waited! s
ping -n 3 127.0.0.1 >nul
goto wait_kernel

:kernel_ready
echo.
echo  [OK] kernel ready (waited ~%waited% s)!
echo       In the page: toolbar "kernel" - select "MC kernel" - click "connect".
echo       (kernel runs in its own window; closing it stops the kernel)
rem multi-user: print LAN access URLs
powershell -NoProfile -Command "$ip=(Get-NetIPAddress -AddressFamily IPv4 | Where-Object {$_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*'} | Select-Object -First 1).IPAddress; if($ip){Write-Output ('LAN access (multi-user, each client independent):'); Write-Output ('  web:    http://'+$ip+':%WEB_PORT%/'); Write-Output ('  kernel: http://'+$ip+':%KERNEL_PORT%/')}" >nul 2>&1
powershell -NoProfile -Command "[console]::beep(880,300); Start-Sleep -Milliseconds 150; [console]::beep(1175,400)" >nul 2>&1
echo.
if not defined CRYPTAND_NO_PAUSE pause
exit /b 0

:kernel_timeout
echo.
echo  [TIMEOUT] kernel not ready after %TIMEOUT_SEC% s.
echo       Check:
echo         1. the Cryptand-Kernel window started without errors
echo         2. first run compiles the engine (test\kernel-server\build.bat) - slower
echo         3. port not occupied (run run.bat PORT to change)
echo         4. use CRYPTAND_NO_KERNEL=1 to only wait for a manually started kernel
echo.
if not defined CRYPTAND_NO_PAUSE pause
exit /b 1

endlocal
