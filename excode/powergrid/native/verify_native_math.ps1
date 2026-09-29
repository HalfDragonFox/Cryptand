# verify_native_math.ps1
# Verify libpowergridNative7.dll exports the expected JNI symbols (2026-08-30)
# ASCII-only file. Run from native dir after build.

$dll = 'E:\Projects\MCMDK\Cryptand\native\build\libpowergridNative7.dll'
if (-not (Test-Path $dll)) { Write-Host 'DLL NOT FOUND'; exit 1 }

Write-Host ('DLL size: ' + (Get-Item $dll).Length)
Write-Host ('DLL time: ' + (Get-Item $dll).LastWriteTime)

# Expected JNI symbols (NativeMath)
$expected = @(
    'Java_com_hdf_cryptand_math_NativeMath_nativeDenseSolveReal',
    'Java_com_hdf_cryptand_math_NativeMath_nativeDenseSolveComplex',
    'Java_com_hdf_cryptand_math_NativeMath_nativeSolveSparseComplex',
    'Java_com_hdf_cryptand_math_NativeMath_nativeDgemm',
    'Java_com_hdf_cryptand_math_NativeMath_nativeDaxpy',
    'Java_com_hdf_cryptand_math_NativeMath_nativeDdot',
    'Java_com_hdf_cryptand_math_NativeMath_nativeDnrm2'
)

# Use dumpbin if available (MSVC), else objdump (mingw)
$objdump = 'E:\WindowsPrograms\mingw64\bin\objdump.exe'
if (Test-Path $objdump) {
    & $objdump -p $dll | Out-File -Encoding ascii 'E:\Projects\MCMDK\Cryptand\native\dll_exports.txt'
    $txt = Get-Content 'E:\Projects\MCMDK\Cryptand\native\dll_exports.txt' -Raw
    $allOk = $true
    foreach ($s in $expected) {
        if ($txt -match [regex]::Escape($s)) { Write-Host ('OK  ' + $s) }
        else { Write-Host ('MISS ' + $s); $allOk = $false }
    }
    if ($allOk) { Write-Host 'ALL JNI SYMBOLS PRESENT' }
} else {
    Write-Host 'objdump not found - skip symbol verification'
}
