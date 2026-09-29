# ============================================================================
# Cryptand OS build script - SYSTEM 1: **Cryptand OS** (FreeRTOS + shell).
# ASCII-only; saved with UTF-8 BOM (PowerShell 5.1 needs it for UTF-8 scripts).
#
# Usage (from repo root):
#   powershell -ExecutionPolicy Bypass -File excode/firmware/cryptand-os/build.ps1
#
# Output: build/soc-fw/cryptand-os.bin
#
# 用户定义（2026-09-17）：Cryptand OS = **仅 FreeRTOS** + 类 Lua 命令行系统；
#   LVGL 是**可选包**（后续可从硬盘加载），所以本镜像**不链接 LVGL**（~10KB）。
# Library policy: official sources only, ZERO edits inside them.
# ============================================================================

$ErrorActionPreference = 'Stop'
$systemDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $systemDir '..\common\fw-common.ps1')

# A system is now: kernel (main.c) + libraries (common) + modules (common/cmd_*.c).
# User decision (2026-09-18): "library-ize the OS -- the OS itself has no content".
Build-CryptandFirmware -SystemName 'cryptand-os' -Sources @(
    (Join-Path $script:Here 'console.c')      # lib: character terminal + shell
    (Join-Path $script:Here 'module.c')       # lib: module framework
    (Join-Path $script:Here 'cmd_sysinfo.c')  # module: version/mem/uptime/sysinfo/tasks
    (Join-Path $script:Here 'cmd_gpu.c')      # module: gpu self-check
    (Join-Path $script:Here 'display.c')      # lib: display backend (VRAM window + doorbell)
    (Join-Path $systemDir 'modules.c')        # this system's module list
    (Join-Path $systemDir 'main.c')           # kernel skeleton
)
