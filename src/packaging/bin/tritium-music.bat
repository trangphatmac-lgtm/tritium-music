@echo off
setlocal
set "APP_DIR=%~dp0.."
java -jar "%APP_DIR%\lib\tritium-music.jar" %*
