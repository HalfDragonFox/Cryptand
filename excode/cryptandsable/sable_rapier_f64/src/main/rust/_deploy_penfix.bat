@echo off
REM 2026-09-03 penfix deploy: copy freshly built sable_rapier.dll to 4 locations
set SRC=E:\Projects\MCMDK\Cryptand\excode\sable_rapier_f64\src\main\rust\target\release\sable_rapier.dll
copy /y "%SRC%" "E:\Projects\MCMDK\Cryptand\neoforge\src\main\resources\assets\cryptand\sable_natives\f64\sable_rapier_x86_64_windows.dll" >nul
copy /y "%SRC%" "E:\Projects\MCMDK\Cryptand\neoforge\build\resources\main\assets\cryptand\sable_natives\f64\sable_rapier_x86_64_windows.dll" >nul
copy /y "%SRC%" "E:\Projects\MCMDK\Cryptand\neoforge\run\cryptand\natives\sable_rapier_x86_64_windows.dll" >nul
copy /y "%SRC%" "E:\Projects\MCMDK\Cryptand\neoforge\run\.sable\natives\sable_rapier_x86_64_windows.dll" >nul
echo DEPLOY_DONE