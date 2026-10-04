@echo off
rem Builds the two signed app bundles for Google Play (the phone app and the
rem Wear OS app) on this computer and puts them in the builds folder.
rem
rem Double-click it, or run it from a terminal:
rem     build_release.bat            asks for the release number
rem     build_release.bat 5          uses release number 5
rem
rem The release number decides the version codes: phone = number x 10 + 1,
rem watch = number x 10 + 2. Google Play refuses a code it has already been
rem given, so use a number higher than every release uploaded so far.
rem
rem It asks for the upload keystore's password. The real work is done by
rem build-android.ps1, which sits next to this file.
setlocal
cd /d "%~dp0"
set "RELEASE=%~1"
if "%RELEASE%"=="" set /p "RELEASE=Release number (higher than the last one uploaded to Google Play): "
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-android.ps1" -Release -ReleaseNumber "%RELEASE%"
set "RESULT=%ERRORLEVEL%"
echo.
pause
exit /b %RESULT%
