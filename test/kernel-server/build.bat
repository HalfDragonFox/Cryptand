@echo off
rem ============================================================
rem   Build standalone kernel server (no Minecraft needed)
rem   Engine sources are referenced directly from common module
rem   (com.hdf.cryptand.engine, pure Java).
rem   Server sources live in src\com\hdf\cryptand\simserver.
rem   Output -> out\
rem ============================================================
setlocal
set "JAVAC=javac"
if exist "J:\Java-jdk\jvm\jdk-21.0.1\bin\javac.exe" set "JAVAC=J:\Java-jdk\jvm\jdk-21.0.1\bin\javac"
if exist "C:\Program Files\Java\jdk-21\bin\javac.exe" set "JAVAC=C:\Program Files\Java\jdk-21\bin\javac"

set "BASE=%~dp0"
set "ENGINE=%BASE%..\..\common\src\main\java\com\hdf\cryptand\engine"
set "SERVER=%BASE%src"
set "OUT=%BASE%out"
set "LIB=%BASE%lib\gson-2.11.0.jar"

if not exist "%OUT%" mkdir "%OUT%"

rem collect all sources into an argfile (javac has no ** glob)
set "SRCLIST=%OUT%\_sources.txt"
del "%SRCLIST%" 2>nul
dir /s /b "%ENGINE%\*.java" > "%SRCLIST%"
dir /s /b "%SERVER%\*.java" >> "%SRCLIST%"

echo [build] compiling engine + server ...
"%JAVAC%" -encoding UTF-8 -cp "%LIB%" -d "%OUT%" @"%SRCLIST%"
if errorlevel 1 (
    echo [build] FAILED
    del "%SRCLIST%" 2>nul
    exit /b 1
)
del "%SRCLIST%" 2>nul
echo [build] OK: %OUT%
exit /b 0
