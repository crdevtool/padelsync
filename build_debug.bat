@echo off
rem Builds the two test builds (the phone APK and the Wear OS APK) on this
rem computer and puts them in the builds folder. They are installed with adb;
rem see docs\install.md.
rem
rem The real work is done by build-android.ps1, which sits next to this file.
setlocal
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-android.ps1"
set "RESULT=%ERRORLEVEL%"
echo.
pause
exit /b %RESULT%
