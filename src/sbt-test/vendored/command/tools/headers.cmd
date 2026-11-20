@echo off
if not exist "%~2" mkdir "%~2"
copy /Y "%~1" "%~2" >nul
