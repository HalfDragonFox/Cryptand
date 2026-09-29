# ============================================================================
# Cryptand mini OS build script - SYSTEM 4: minimal bring-up image.
# ASCII-only; saved with UTF-8 BOM (PowerShell 5.1 needs it for UTF-8 scripts).
#
# Usage (from repo root):
#   powershell -ExecutionPolicy Bypass -File excode/firmware/cryptand-mini-os/build.ps1
#
# Output: build/soc-fw/cryptand-mini-os.bin
#
# 用户定案（2026-09-24）：mini OS 面向低配 MCU 档。不链 LVGL、不起 shell、不用调度器，
# 只证明"系统能被 BIOS 从盘里引导起来并输出" —— 体积目标 < 8KB，让 hdd1（8KB）也能装系统。
# ============================================================================

$ErrorActionPreference = 'Stop'
$systemDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $systemDir '..\common\fw-common.ps1')

Build-CryptandFirmware -SystemName 'cryptand-mini-os' -Sources @(
    (Join-Path $systemDir 'main.c')
) -Layout system
