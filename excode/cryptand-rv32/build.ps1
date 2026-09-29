# ============================================================================
# Cryptand RV32 native kernel - build script (ASCII-only; saved with UTF-8 BOM
# because PowerShell 5.1 cannot read UTF-8 scripts without one).
#
# Usage (from repo root):
#   powershell -ExecutionPolicy Bypass -File excode/cryptand-rv32/build.ps1
#
# Output: build/native/cryptand_rv32.dll  (+ copied to the engine dir the mod
#         loads natives from, next to the other Cryptand natives)
#
# Toolchain: MinGW-w64 g++ (x86_64). JNI headers come from the running JDK.
# Override with env vars: CRYPTAND_MINGW_BIN, JAVA_HOME.
# ============================================================================

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = (Resolve-Path (Join-Path $here '..\..')).Path

# ---------- compiler ----------
$mingwBin = if ($env:CRYPTAND_MINGW_BIN) { $env:CRYPTAND_MINGW_BIN } else { 'E:\WindowsPrograms\mingw64\bin' }
$gpp = Join-Path $mingwBin 'g++.exe'
if (-not (Test-Path $gpp)) {
    $found = Get-Command g++ -ErrorAction SilentlyContinue
    if ($found) { $gpp = $found.Source } else { throw "g++ not found (looked in $mingwBin and PATH)" }
}

# ---------- JNI headers ----------
$javaHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { $null }
if (-not $javaHome) {
    # Gradle toolchains live under ~/.jdks
    $cand = Get-ChildItem (Join-Path $env:USERPROFILE '.jdks') -Directory -ErrorAction SilentlyContinue |
            Where-Object { Test-Path (Join-Path $_.FullName 'include\jni.h') } |
            Sort-Object Name -Descending | Select-Object -First 1
    if ($cand) { $javaHome = $cand.FullName }
}
if (-not $javaHome -or -not (Test-Path (Join-Path $javaHome 'include\jni.h'))) {
    throw 'jni.h not found: set JAVA_HOME to a JDK (e.g. C:\Users\<you>\.jdks\ms-21.x)'
}

$outDir = Join-Path $root 'build\native'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$dll = Join-Path $outDir 'cryptand_rv32.dll'

Write-Host '[1/2] compiling cryptand_rv32.dll ...' -ForegroundColor Cyan
Write-Host ("      g++      : {0}" -f $gpp)
Write-Host ("      JAVA_HOME: {0}" -f $javaHome)

$args = @(
    '-std=c++17', '-O2', '-fno-exceptions', '-fno-rtti',
    # NOTE: keep this file ASCII-only (see header). The sandbox used to pull in
    # libwinpthread-1.dll through std::mutex; it now uses native KERNEL32 sync
    # primitives instead, so no extra link flags and no extra DLL are needed.
    '-shared', '-static-libgcc', '-static-libstdc++',
    '-I', (Join-Path $here 'include'),
    '-I', (Join-Path $javaHome 'include'),
    '-I', (Join-Path $javaHome 'include\win32'),
    '-o', $dll,
    (Join-Path $here 'src\rv32_core.cpp'),
    (Join-Path $here 'src\sandbox.cpp'),
    (Join-Path $here 'src\jni_bridge.cpp')
) | ForEach-Object { $_ }
& $gpp @args
if ($LASTEXITCODE -ne 0) { throw "compile failed (exit $LASTEXITCODE)" }

Write-Host '[2/2] result' -ForegroundColor Cyan
$info = Get-Item $dll
Write-Host ("  DLL : {0}  ({1} KB)" -f $dll, [math]::Round($info.Length / 1KB, 1))

# show exported JNI symbols (sanity check)
$nm = Join-Path $mingwBin 'nm.exe'
if (Test-Path $nm) {
    $syms = & $nm -g $dll 2>$null | Select-String 'Java_com_hdf_cryptand_soc_nativebridge_NativeRv32'
    $sbox = & $nm -g $dll 2>$null | Select-String 'Java_com_hdf_cryptand_soc_nativebridge_NativeSandbox'
    Write-Host ("  exported JNI functions: {0} (NativeRv32) + {1} (NativeSandbox)" -f `
        ($syms | Measure-Object).Count, ($sbox | Measure-Object).Count)
}

# ---------- deploy to the engine dir the mod loads natives from ----------
# The DLL dynamically depends on libwinpthread-1.dll (pulled in by the C++
# standard library inside the sandbox). Windows does NOT search the loaded
# DLL's own directory for its dependencies, so we ship that DLL next to it and
# NativeRv32 preloads it. Copy both, every build, so they can never drift apart.
Write-Host '[3/3] deploying to the engine dir ...' -ForegroundColor Cyan
$engines = Join-Path $root 'neoforge\run\config\cryptand\engines'
New-Item -ItemType Directory -Force -Path $engines | Out-Null
Copy-Item -Force $dll (Join-Path $engines 'cryptand_rv32.dll')
Write-Host ("  engine  : {0}\cryptand_rv32.dll" -f $engines)

$wp = Join-Path $mingwBin 'libwinpthread-1.dll'
if (Test-Path $wp) {
    Copy-Item -Force $wp (Join-Path $engines 'libwinpthread-1.dll')
    Write-Host ("  runtime : {0}\libwinpthread-1.dll" -f $engines)
} else {
    Write-Host '  runtime : libwinpthread-1.dll not found next to g++ (skipped)' -ForegroundColor Yellow
}
