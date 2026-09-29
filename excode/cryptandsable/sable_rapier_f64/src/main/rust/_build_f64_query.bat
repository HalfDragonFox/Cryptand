@echo off
REM 2026-09-04 build f64 DLL with queryAabbIntersecting
set "CARGO_HOME=E:\Projects\MCMDK\Cryptand\.ai_cache\.cargo"
set "RUSTUP_HOME=E:\Projects\MCMDK\Cryptand\.ai_cache\.rustup"
set "PATH=E:\Projects\MCMDK\Cryptand\.ai_cache\.rustup\toolchains\nightly-2026-01-29-x86_64-pc-windows-msvc\bin;E:\Projects\MCMDK\Cryptand\.ai_cache\.cargo\bin;%PATH%"
cd /d "E:\Projects\MCMDK\Cryptand\excode\sable_rapier_f64\src\main\rust"
cargo build --release -p sable_rapier --features prec-f64 > E:\Projects\MCMDK\Cryptand\_cargo_build_f64.txt 2>&1
echo BUILD_EXIT=%ERRORLEVEL%
