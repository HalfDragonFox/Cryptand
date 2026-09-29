@echo off
set "PATH=E:\WindowsPrograms\mingw64\bin;E:\WindowsPrograms\CMake\bin;%PATH%"
cd /d E:\Projects\MCMDK\Cryptand\native

:: ============ INCREMENTAL BUILD (2026-08-30: no more full rebuild) ============
:: First run or missing build dir -> full configure (OpenBLAS DYNAMIC_ARCH is
:: slow, ~30 min); afterwards only recompiles changed C sources (seconds).
:: To force full rebuild: delete native\build dir then run this script.
if not exist build\build.ninja (
    echo [Native] First build: full configure ^(takes ~30 min^)...
    cmake -S . -B build -G Ninja ^
      -D "JVM_INCLUDE=C:/Users/26602/.jdks/ms-21.0.11/include;C:/Users/26602/.jdks/ms-21.0.11/include/win32" ^
      -D CMAKE_BUILD_TYPE=Release
    if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%
)

:: Build (Ninja incremental: only changed sources recompiled)
cmake --build build --config Release
if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%

:: Copy output DLL to mod resources
copy /y build\libpowergridNative7.dll ..\neoforge\src\main\resources\assets\cryptand\native\libpowergridNative7.dll

echo.
echo ============================================
echo BUILD SUCCESS - DLL copied to mod resources
echo ============================================
exit /b 0
