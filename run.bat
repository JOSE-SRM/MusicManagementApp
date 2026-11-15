@echo off
setlocal
cd /d "%~dp0"
if not exist "bin\sangitam\desktop\app\SangitamApp.class" (
    echo Sangitam has not been built yet. Run build.bat once, then run.bat to launch.
    exit /b 1
)
call mvnw.cmd -q exec:java
