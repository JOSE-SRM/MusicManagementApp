@echo off
setlocal

call mvnw.cmd -q -DskipTests compile
if errorlevel 1 goto :error
echo Build complete. Classes are in bin\sangitam\desktop\
exit /b 0

:error
echo Build failed.
exit /b 1
