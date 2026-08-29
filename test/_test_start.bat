@echo off
rem temp wrapper to reproduce start.bat error (with browser branch)
set CRYPTAND_NO_PAUSE=1
set CRYPTAND_WAIT_TIMEOUT=5
call "%~dp0start.bat" > "%~dp0_start_out.txt" 2>&1
echo EXITCODE=%errorlevel%
type "%~dp0_start_out.txt"
