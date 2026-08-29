@echo off
rem ============================================================
rem   Run standalone kernel simulation server (no Minecraft)
rem   Usage: run.bat [port]   (default 12787)
rem   Serves http://127.0.0.1:port/health and /simulate
rem   Ctrl+C to stop
rem ============================================================
setlocal
set "JAVA=java"
if exist "J:\Java-jdk\jvm\jdk-21.0.1\bin\java.exe" set "JAVA=J:\Java-jdk\jvm\jdk-21.0.1\bin\java"
if exist "C:\Program Files\Java\jdk-21\bin\java.exe" set "JAVA=C:\Program Files\Java\jdk-21\bin\java"

set "BASE=%~dp0"
set "OUT=%BASE%out"
set "LIB=%BASE%lib\gson-2.11.0.jar"

if not exist "%OUT%\com\hdf\cryptand\simserver\KernelServer.class" (
    echo [run] not compiled yet, running build.bat ...
    call "%BASE%build.bat"
    if errorlevel 1 exit /b 1
)

echo [run] starting standalone kernel server ...
"%JAVA%" -cp "%OUT%;%LIB%" com.hdf.cryptand.simserver.KernelServer %*
