# ============================================================================
# Build ALL Cryptand system images -- PARALLEL (2026-09-25).
#
# User: "compile with as many threads as possible, single-threaded is too slow".
# Each system is an independent riscv-none-elf-gcc + LVGL/FreeRTOS build, so they
# are launched as 4 concurrent processes instead of a sequential loop.
#
#   system 1  cryptand-os       FreeRTOS + shell (classic CLI)   -> cryptand-os.bin
#   system 2  cryptand-ui-os    FreeRTOS + LVGL (Win-like UI)    -> cryptand-ui-os.bin
#   system 3  cryptand-boot     BIOS / bootloader                -> cryptand-boot.bin
#   system 4  cryptand-mini-os  minimal bring-up (<4KB loader)   -> cryptand-mini-os.bin
#
# Copies the .bin into the mod assets (common): that is the ONLY firmware artifact
# that ships in the jar (FreeRTOS/LVGL sources stay in .ai_cache/c).
#
# NOTE (2026-09-25): success is judged by **the artifact existing**, not by the
# child exit code. The linker writes thousands of newlib stub warnings to stderr
# ("_close is not implemented"), and PowerShell turns stderr into error records,
# so a perfectly good build can still report exit code 1 -- that is exactly what
# used to abort this script halfway (system 3/4 never got built).
#
# Usage (from repo root):
#   powershell -ExecutionPolicy Bypass -File excode/firmware/build-all.ps1
# ============================================================================

$ErrorActionPreference = 'Continue'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = (Resolve-Path (Join-Path $here '..\..')).Path
$assets = Join-Path $root 'common\src\main\resources\assets\cryptand\soc'
$outDir = Join-Path $root 'build\soc-fw'

# system 5 (2026-09-26): cryptand-pe = Cryptand OS PE (CLI installation environment:
#   disks / format / install / install-all). System floppies boot into it and it
#   installs a system onto a target disk -- UI OS and non-UI OS alike.
$systems = @('cryptand-os', 'cryptand-ui-os', 'cryptand-boot', 'cryptand-mini-os', 'cryptand-pe')
$logs = Join-Path $env:TEMP ('cryptand-fw-' + (Get-Date -Format 'HHmmss'))
New-Item -ItemType Directory -Force -Path $logs | Out-Null

Write-Host ('=== building {0} systems in parallel ===' -f $systems.Count) -ForegroundColor Green
# Stale-artifact guard (2026-09-27): a FAILED child build used to leave the PREVIOUS run's
# .bin in place, and the shipping loop treated 'artifact exists' as success -- so a broken
# system silently shipped an old binary (we got a false-green exactly that way). Delete the
# target first, then require a FRESH artifact (mtime >= build start).
$buildStart = Get-Date
foreach ($s in $systems) { Remove-Item (Join-Path $outDir ($s + '.bin')) -Force -ErrorAction SilentlyContinue }
$procs = @{}
foreach ($s in $systems) {
    $stdout = Join-Path $logs ($s + '.out.log')
    $stderr = Join-Path $logs ($s + '.err.log')
    $script = Join-Path $here ($s + '\build.ps1')
    # NOTE: one line on purpose -- backslash is NOT a line continuation in PowerShell.
    $procs[$s] = Start-Process -FilePath 'powershell' -NoNewWindow -PassThru -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $script) -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    Write-Host ('  started {0,-18} (pid {1})' -f $s, $procs[$s].Id)
}

foreach ($s in $systems) { $procs[$s] | Wait-Process }

Write-Host ''
Write-Host '=== shipping binaries into mod assets ===' -ForegroundColor Cyan
$failed = @()
foreach ($s in $systems) {
    $src = Join-Path $outDir ($s + '.bin')
    $code = $procs[$s].ExitCode
    if (-not (Test-Path $src)) {
        $failed += $s
        Write-Host ('  FAILED  {0,-18} no artifact (child exit {1}); see {2}' -f $s, $code, $logs) -ForegroundColor Red
        continue
    }
    if ((Get-Item $src).LastWriteTime -lt $buildStart) {
        $failed += $s
        Write-Host ('  FAILED  {0,-18} STALE artifact (child exit {1}): the build did not produce it -- see {2}' -f $s, $code, $logs) -ForegroundColor Red
        continue
    }
    $dst = Join-Path $assets ($s + '.bin')
    Copy-Item $src $dst -Force
    $size = (Get-Item $src).Length
    $note = if ($code -ne 0) { ' (child exit ' + $code + ': stderr warnings only, artifact fresh)' } else { '' }
    Write-Host ('  {0,-18} {1,8} bytes -> {2}{3}' -f $s, $size, $dst.Replace($root, '.'), $note)
}

if ($failed.Count -gt 0) {
    Write-Host ''
    Write-Host ('FAILED systems: ' + ($failed -join ', ')) -ForegroundColor Red
    exit 1
}
Write-Host ''
Write-Host 'all firmware artifacts rebuilt and shipped.' -ForegroundColor Green
exit 0
