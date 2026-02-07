@echo off
REM Move to parent-of the directory containing this batch file
cd /d "%~dp0.."

REM Run the target
make run-server-stdio-node

