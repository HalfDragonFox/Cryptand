# ============================================================================
# Cryptand OS PE build script - the CLI installation environment.
# ASCII-only on purpose (PowerShell 5.1 mangles BOM-less UTF-8 with non-ASCII).
#
# Usage (from repo root):
#   powershell -ExecutionPolicy Bypass -File excode/firmware/cryptand-pe/build.ps1
#
# Output: build/soc-fw/cryptand-pe.bin
#
# User decision (2026-09-26): the system floppy boots into **Cryptand OS PE** -- a
# command-line environment that partitions / formats / installs (one-shot install
# included). UI OS and non-UI OS both install through this very same PE, so PE is
# the *only* installer to maintain. It is deliberately small: FreeRTOS + console +
# a handful of commands, no LVGL.
# ============================================================================

$ErrorActionPreference = 'Stop'
$systemDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $systemDir '..\common\fw-common.ps1')

# A system is now: kernel (main.c) + libraries (common) + modules (common/cmd_*.c).
# User decision (2026-09-18): "library-ize the OS -- the OS itself has no content, like
# linux it gets commands and features from libs and modules". So this list IS the
# dependency list; the kernel no longer implements any command.
Build-CryptandFirmware -SystemName 'cryptand-pe' -Sources @(
    (Join-Path $script:Here 'console.c')      # lib: character terminal + shell
    (Join-Path $script:Here 'module.c')       # lib: module framework
    (Join-Path $script:Here 'pe_client.c')    # lib: host PE service protocol
    (Join-Path $script:Here 'cmd_pe.c')       # module: disks/info/format/install/install-all
    (Join-Path $script:Here 'cmd_sysinfo.c')  # module: version/mem/uptime/sysinfo/tasks
    (Join-Path $script:Here 'cmd_gpu.c')      # module: gpu self-check + draw (image frame producer)
    (Join-Path $script:Here 'display.c')      # lib: display backend (VRAM window + doorbell)
    (Join-Path $systemDir 'modules.c')        # this system's module list
    (Join-Path $systemDir 'main.c')           # kernel skeleton
)
