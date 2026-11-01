@echo off
setlocal
cd /d "%~dp0"

rem These shared UI classes must be present. A partial copy of the branch causes
rem hundreds of misleading "cannot find symbol" errors in other Java files.
for %%F in (AppIcons.java Theme.java UiFactory.java NotificationCenter.java CommandPaletteDialog.java SoundscapeCanvas.java) do (
    if not exist "src\sangitam\desktop\ui\%%F" goto :missingSources
)

call mvnw.cmd -q -DskipTests clean compile
if errorlevel 1 goto :error
echo Build complete. Classes are in bin\sangitam\desktop\
exit /b 0

:missingSources
echo This folder is missing required Sangitam UI source files.
echo Download or check out the full codex/modular-swing-ui branch, then run build.bat again.
exit /b 1

:error
echo Build failed.
exit /b 1
