# ============================================================================
# Cryptand OS build script - SYSTEM 3: Cryptand UI OS (custom UI).
# ASCII-only on purpose (PowerShell 5.1 mangles UTF-8 scripts without a BOM).
#
# Usage (from repo root):
#   powershell -ExecutionPolicy Bypass -File excode/firmware/cryptand-ui-os/build.ps1
#
# Output: build/soc-fw/cryptand-ui-os.elf / .bin / .map
#
# Library policy: official sources only, ZERO edits inside them
#   FreeRTOS : .ai_cache/c/FreeRTOSv202411.00/FreeRTOS/Source
#   LVGL     : .ai_cache/c/lvgl-9.6.0   (configuration = our lv_conf.h only)
#
# The console (character layer) is built with a smaller grid so that it fits the
# terminal window drawn by LVGL: 36 cols x 16 rows at screen cell (5,6).
# ============================================================================

$ErrorActionPreference = 'Stop'
$systemDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $systemDir '..\common\fw-common.ps1')

# A system is now: kernel (main.c) + libraries (common) + modules (common/cmd_*.c).
# Only THIS system links the lvgl module -- that is the point of library-izing.
Build-CryptandFirmware -SystemName 'cryptand-ui-os' -Sources @(
    (Join-Path $script:Here 'console.c')      # lib: character terminal + shell
    (Join-Path $script:Here 'module.c')       # lib: module framework
    (Join-Path $script:Here 'cmd_sysinfo.c')  # module: version/mem/uptime/sysinfo/tasks
    (Join-Path $script:Here 'cmd_gpu.c')      # module: gpu self-check
    (Join-Path $script:Here 'cmd_lvgl.c')     # module: lvgl self-check (UI only)
    (Join-Path $script:Here 'display.c')      # lib: display backend (VRAM window + colors)
    (Join-Path $systemDir 'modules.c')        # this system's module list
    (Join-Path $systemDir 'lv_port_disp.c')
    (Join-Path $systemDir 'main.c')           # kernel + desktop
) -UseLvgl -ExtraDefines @('-DCON_COLS=36', '-DCON_ROWS=16')
