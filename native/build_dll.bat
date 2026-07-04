@echo off
set "PATH=E:\WindowsPrograms\mingw64\bin;E:\WindowsPrograms\CMake\bin;%PATH%"
cd /d E:\Projects\MCMDK\Cryptand\native

:: Clean previous build
if exist build rmdir /s /q build

:: Configure (uses PowerGrid's pre-cloned OpenBLAS/SuperLU)
cmake -S . -B build -G Ninja ^
  -D "JVM_INCLUDE=C:/Users/26602/.jdks/ms-21.0.11/include;C:/Users/26602/.jdks/ms-21.0.11/include/win32" ^
  -D CMAKE_BUILD_TYPE=Release
if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%

:: Build
cmake --build build --config Release
if %ERRORLEVEL% neq 0 exit /b %ERRORLEVEL%

:: Copy output DLL to mod resources
copy /y build\libpowergridNative7.dll ..\neoforge\src\main\resources\assets\cryptand\native\libpowergridNative7.dll

echo.
echo ============================================
echo BUILD SUCCESS - DLL copied to mod resources
echo ============================================
exit /b 0
