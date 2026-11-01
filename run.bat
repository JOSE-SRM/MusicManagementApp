@echo off
setlocal
call mvnw.cmd -q -DskipTests compile
if errorlevel 1 exit /b 1
call mvnw.cmd -q exec:java
