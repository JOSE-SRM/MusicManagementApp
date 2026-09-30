@echo off
setlocal

if not exist bin mkdir bin
dir /s /b src\*.java > sources.txt
javac -encoding UTF-8 -d bin @sources.txt
if errorlevel 1 goto :error
del sources.txt
echo Build complete. Classes are in bin\sangitam\desktop\
exit /b 0

:error
del sources.txt 2>nul
echo Build failed.
exit /b 1
