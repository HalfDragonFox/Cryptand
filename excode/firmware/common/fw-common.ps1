# ============================================================================
# Shared firmware build helpers (fw-common.ps1, 2026-09-17)
#
# ASCII-only on purpose: PowerShell 5.1 mangles UTF-8 scripts without a BOM.
#
# Library policy (user requirement):
#   * official sources, ZERO edits inside them
#       FreeRTOS : .ai_cache/c/FreeRTOSv202411.00/FreeRTOS/Source
#       LVGL     : .ai_cache/c/lvgl-9.6.0
#   * we add only: firmware/common (config + port glue + HAL) and the app dir.
#
# Two systems:
#   system 1  cryptand-os       FreeRTOS only                 -> cryptand-os.bin
#   system 2  cryptand-os-lvgl  FreeRTOS + LVGL (graphics)     -> cryptand-os-lvgl.bin
# ============================================================================

$script:Here = $PSScriptRoot
# Non-Java code all lives under excode/: this file is excode/firmware/common => up three = repo root
$script:RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path

function Get-CryptandToolchain {
    $gcc = $null
    if ($env:CRYPTAND_RISCV_BIN) {
        $gcc = Join-Path $env:CRYPTAND_RISCV_BIN 'riscv-none-elf-gcc.exe'
    } else {
        $toolRoot = Join-Path $script:RepoRoot 'neoforge\run\cryptand\tool'
        if (Test-Path $toolRoot) {
            $found = Get-ChildItem $toolRoot -Recurse -Filter 'riscv-none-elf-gcc.exe' -ErrorAction SilentlyContinue |
                     Select-Object -First 1
            if ($found) { $gcc = $found.FullName }
        }
    }
    if (-not $gcc -or -not (Test-Path $gcc)) {
        throw 'riscv-none-elf-gcc not found. Install via the in-game panel (/cryptand soc tools ui) or set CRYPTAND_RISCV_BIN.'
    }
    return (Split-Path -Parent $gcc)
}

function Get-FreeRtosSource {
    # prefer the official release the user dropped into .ai_cache/c
    $official = if ($env:CRYPTAND_FREERTOS) { $env:CRYPTAND_FREERTOS }
                else { Join-Path $script:RepoRoot '.ai_cache\c\FreeRTOSv202411.00\FreeRTOS\Source' }
    if (Test-Path (Join-Path $official 'tasks.c')) { return $official }
    # fallback: the plain kernel clone
    $clone = Join-Path $script:RepoRoot '.ai_cache\FreeRTOS-Kernel'
    if (Test-Path (Join-Path $clone 'tasks.c')) { return $clone }
    throw "FreeRTOS sources not found (looked in '$official' and '$clone')."
}

function Get-LvglSource {
    $lvgl = if ($env:CRYPTAND_LVGL) { $env:CRYPTAND_LVGL }
            else { Join-Path $script:RepoRoot '.ai_cache\c\lvgl-9.6.0' }
    if (-not (Test-Path (Join-Path $lvgl 'lvgl.h'))) {
        throw "LVGL sources not found at '$lvgl' (expected .ai_cache/c/lvgl-9.6.0)."
    }
    return $lvgl
}

function Build-CryptandFirmware {
    param(
        [Parameter(Mandatory = $true)][string]$SystemName,
        [Parameter(Mandatory = $true)][array]$Sources,
        [switch]$UseLvgl,
        [array]$ExtraDefines = @(),
        # boot  = ROM 0x0000_0000（Boot/EEPROM 自己跑的那块，宿主**不会**覆盖它）
        # system= ROM 0x0001_0000（系统镜像；由 Boot 刷进系统区后跳进来执行）
        # ⚠ 两块不能合并：系统刷到 0x0 = 在 Boot 执行到一半时换掉它的代码（2026-09-17 真机踩过）
        [ValidateSet('boot','system')][string]$Layout = 'system'
    )

    $tc = Get-CryptandToolchain
    $gcc     = Join-Path $tc 'riscv-none-elf-gcc.exe'
    $objcopy = Join-Path $tc 'riscv-none-elf-objcopy.exe'
    $objdump = Join-Path $tc 'riscv-none-elf-objdump.exe'
    $size    = Join-Path $tc 'riscv-none-elf-size.exe'

    $kernel = Get-FreeRtosSource
    $systemDir = Join-Path $script:RepoRoot ("excode\firmware\" + $SystemName)
    $outDir    = Join-Path $script:RepoRoot 'build\soc-fw'
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null

    $chipExt = Join-Path $kernel 'portable\GCC\RISC-V\chip_specific_extensions\RISCV_MTIME_CLINT_no_extensions'

    # ---- FreeRTOS (official sources, untouched) ----
    $allSources = @(
        (Join-Path $kernel 'tasks.c'),
        (Join-Path $kernel 'list.c'),
        (Join-Path $kernel 'queue.c'),
        (Join-Path $kernel 'portable\MemMang\heap_4.c'),
        (Join-Path $kernel 'portable\GCC\RISC-V\port.c'),
        (Join-Path $kernel 'portable\GCC\RISC-V\portASM.S')
    )
    $includes = @(
        "-I$(Join-Path $kernel 'include')",
        "-I$(Join-Path $kernel 'portable\GCC\RISC-V')",
        "-I$chipExt",
        "-I$script:Here",     # FreeRTOSConfig.h + hal.h
        "-I$systemDir"        # lv_conf.h (system 2) + system headers
    )
    $defines = @() + $ExtraDefines

    # ---- our shared layer ----
    $allSources += @(
        (Join-Path $script:Here 'start.S'),
        (Join-Path $script:Here 'libc_shim.c'),
        (Join-Path $script:Here 'hal.c')
    )

    # ---- LVGL (system 2): compile the library as-is, let --gc-sections drop unused ----
    if ($UseLvgl) {
        $lvgl = Get-LvglSource
        $defines += '-DCRYPTAND_WITH_LVGL=1'
        $includes += "-I$lvgl"
        $lvglSources = Get-ChildItem (Join-Path $lvgl 'src') -Recurse -Filter *.c |
                       Where-Object { $_.FullName -notmatch '\\libs\\' } |   # third-party decoders: skip
                       ForEach-Object { $_.FullName }
        $allSources += $lvglSources
    }

    $allSources += $Sources

    # 运行库：-nostdlib 会把 libgcc 一起挡掉，而 LVGL 需要 __divdi3/__umoddi3/__clzsi2/__ffssi2
    # 等软运算辅助 ⇒ 改成 -nostartfiles（保留默认库解析）+ 显式链 libc_nano/libnosys/libgcc，
    # 并用 --start-group 包住以避免库之间互相引用的顺序问题。将来 .cpp 时同样补 -lstdc++。
    $allSources += @('-Wl,--start-group', '-lc_nano', '-lnosys', '-lgcc', '-Wl,--end-group')

    $elf = Join-Path $outDir ($SystemName + '.elf')
    $bin = Join-Path $outDir ($SystemName + '.bin')
    $map = Join-Path $outDir ($SystemName + '.map')

    $label = if ($UseLvgl) { 'FreeRTOS + LVGL' } else { 'FreeRTOS only' }
    Write-Host ("[1/3] building {0} ({1}, layout={2}) ..." -f $SystemName, $label, $Layout) -ForegroundColor Cyan
    Write-Host ("      FreeRTOS : {0}" -f $kernel.Replace($script:RepoRoot, '.'))
    if ($UseLvgl) { Write-Host ("      LVGL     : {0} ({1} .c files)" -f (Get-LvglSource).Replace($script:RepoRoot, '.'), $lvglSources.Count) }

    # ROM 分区（2026-09-17 真机踩坑后定案；改之前先读两个 .ld 的头部注释）：
    #   boot   0x0000_0000  64KB —— Boot/EEPROM 自己就在这块里跑，宿主不覆盖它
    #   system 0x0001_0000 448KB —— 系统镜像；Boot 刷进来后跳 0x0001_0000
    #   （RAM 镜像布局也试过：UI OS 有 405KB，塞不进 128KB RAM —— 详见记忆）
    if ($Layout -eq 'boot') {
        $linkerScript = Join-Path $script:Here 'cryptand_boot.ld'
    } else {
        $linkerScript = Join-Path $script:Here 'cryptand_os.ld'
    }

$gccArgs = @(
        # GCC 14+ : rv32im no longer implies zicsr, but the FreeRTOS RISC-V port is full of
        # "csrs/csrc mstatus, 8" => zicsr must be listed explicitly.
        '-march=rv32im_zicsr', '-mabi=ilp32', '-nostartfiles', '-ffreestanding',
        '-Os', '-Wall', '-Wno-unused-parameter', '-Wno-unused-function',
        '-fno-common', '-fno-builtin',
        '-ffunction-sections', '-fdata-sections', '-Wl,--gc-sections',
        '-T', $linkerScript,
        ('-Wl,-Map=' + $map),
        '-o', $elf
    ) + $defines + $includes + $allSources

    # Windows caps a command line at ~32k chars and LVGL alone is 457 .c files, so the
    # whole argument list goes into a GCC response file (@file) instead.
    # NOTE: inside a response file GCC treats '\' as an escape => always write forward slashes.
    $rsp = Join-Path $outDir ($SystemName + '.rsp')
    $rspLines = $gccArgs | ForEach-Object {
        $a = $_.Replace('\', '/')
        if ($a -match '\s') { '"' + $a + '"' } else { $a }
    }
    Set-Content -Path $rsp -Value $rspLines -Encoding ASCII
    & $gcc ('@' + $rsp)
    if ($LASTEXITCODE -ne 0) { throw ("compile failed (exit {0}); response file: {1}" -f $LASTEXITCODE, $rsp) }

    Write-Host '[2/3] objcopy -> raw binary' -ForegroundColor Cyan
    & $objcopy -O binary $elf $bin
    if ($LASTEXITCODE -ne 0) { throw 'objcopy failed' }

    Write-Host '[3/3] result' -ForegroundColor Cyan
    $binSize = (Get-Item $bin).Length
    Write-Host ("  BIN  : {0}  ({1} bytes)" -f $bin, $binSize)
    Write-Host ("  ELF  : {0}" -f $elf)
    if (Test-Path $size) { & $size $elf | ForEach-Object { Write-Host ('  SIZE : ' + $_) } }
    Write-Host '  ---- entry (offset 0 must be _start) ----'
    & $objdump -d $elf | Select-Object -Skip 3 -First 6 | ForEach-Object { Write-Host ('  ' + $_) }
    if ($binSize -gt (512 * 1024)) {
        Write-Host '  WARN: binary exceeds the 512KB ROM region' -ForegroundColor Yellow
    }
    return [pscustomobject]@{ System = $SystemName; Bin = $bin; Size = $binSize }
}
