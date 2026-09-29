@echo off
REM 2026-09-03 penfix: cargo check/build for sable_rapier_f64 crate
REM 2026-09-05: precision split -> MUST build with --features prec-f64
REM   (runtime loads the f64 DLL; default/empty features = prec-f32 is
REM    deprecated and exportObj f64 literals no longer compile under f32).
REM 2026-09-05: source vcvars64 first so MSVC link.exe wins over Git's link.exe.
set CARGO_HOME=E:\Projects\MCMDK\Cryptand\.ai_cache\.cargo
set RUSTUP_HOME=E:\Projects\MCMDK\Cryptand\.ai_cache\.rustup
set "PATH=E:\Projects\MCMDK\Cryptand\.ai_cache\.rustup\toolchains\nightly-2026-01-29-x86_64-pc-windows-msvc\bin;E:\Projects\MCMDK\Cryptand\.ai_cache\.cargo\bin;%PATH%"
if exist "E:\WindowsPrograms\Microsoft Visual Studio\18\Enterprise\VC\Auxiliary\Build\vcvars64.bat" (
  call "E:\WindowsPrograms\Microsoft Visual Studio\18\Enterprise\VC\Auxiliary\Build\vcvars64.bat" >nul
)
cd /d "%~dp0"
if "%1"=="check" goto check
if "%1"=="build" goto build
:check
cargo check -p sable_rapier --features prec-f64 > e:\Projects\MCMDK\Cryptand\_cargo_check_penfix.txt 2>&1
echo CHECK_EXIT=%ERRORLEVEL%
goto end
:build
cargo build --release -p sable_rapier --features prec-f64 > e:\Projects\MCMDK\Cryptand\_cargo_build_penfix.txt 2>&1
echo BUILD_EXIT=%ERRORLEVEL%
goto end
:end