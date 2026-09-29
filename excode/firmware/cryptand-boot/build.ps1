# ============================================================================
# Cryptand Boot build script - SYSTEM 3: BIOS / bootloader.
# ASCII-only; saved with UTF-8 BOM (PowerShell 5.1 needs it for UTF-8 scripts).
#
# Usage (from repo root):
#   powershell -ExecutionPolicy Bypass -File excode/firmware/cryptand-boot/build.ps1
#
# Output: build/soc-fw/cryptand-boot.bin
#
# 用户定义（2026-09-17）：Cryptand Boot 类似 ARM 的 Bootloader / PC 的 BIOS：
#   从机箱内第一个硬盘开始读，直到找到有效程序并运行 —— 用来模拟裸机 MCU/SOC 的启动。
#   镜像极小（不链 LVGL、不起 shell）。
# ============================================================================

$ErrorActionPreference = 'Stop'
$systemDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $systemDir '..\common\fw-common.ps1')

Build-CryptandFirmware -SystemName 'cryptand-boot' -Sources @(
    (Join-Path $systemDir 'main.c')
) -Layout boot
