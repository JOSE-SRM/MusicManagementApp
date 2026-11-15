param([Parameter(Mandatory)][string]$Launcher, [string]$ExistingFixture)

$ErrorActionPreference = 'Stop'
$Launcher = (Resolve-Path -LiteralPath $Launcher).Path
$appJar = Join-Path (Split-Path -Parent $Launcher) 'app/sangitam.jar'
if (-not (Test-Path -LiteralPath $appJar -PathType Leaf)) { throw 'Pass the Sangitam.exe path printed by package-windows.ps1.' }
$parent = Join-Path $PSScriptRoot 'scale-tests'
New-Item -ItemType Directory -Force -Path $parent | Out-Null
if ($ExistingFixture) {
    $fixture = (Resolve-Path -LiteralPath $ExistingFixture).Path
    if (!$fixture.StartsWith($parent + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -or
        !(Test-Path -LiteralPath (Join-Path $fixture 'READY.txt') -PathType Leaf)) {
        throw 'Choose a completed fixture under scale-tests.'
    }
} else {
    $fixture = Join-Path $parent (Get-Date -Format 'yyyyMMdd-HHmmss-fff')
    & java.exe '--enable-native-access=ALL-UNNAMED' '-Djava.awt.headless=true' -cp $appJar (Join-Path $PSScriptRoot 'tools/ScaleLibraryFixture.java') $fixture | Out-Host
    if ($LASTEXITCODE) { throw "Fixture generation failed. The partial fixture is in $fixture." }
}

$shell = New-Object -ComObject WScript.Shell
$shortcutPath = Join-Path $fixture 'Open 100k Library.lnk'
$shortcut = $shell.CreateShortcut($shortcutPath)
$shortcut.TargetPath = $Launcher
$shortcut.IconLocation = "$Launcher,0"
$shortcut.WorkingDirectory = Split-Path -Parent $Launcher
$data = Join-Path $fixture 'Data'
$report = Join-Path $fixture 'startup.log'
$shortcut.Arguments = "--data-dir `"$data`" --startup-report `"$report`" --offline-profiles"
$shortcut.Save()
Write-Output $shortcutPath
