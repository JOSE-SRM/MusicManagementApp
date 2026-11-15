param([string]$ScoreRecognitionDirectory)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$jdk = (Get-Command jpackage.exe -ErrorAction Stop).Source | Split-Path -Parent
$version = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$output = Join-Path $PSScriptRoot "dist/windows/$version"
$input = Join-Path $PSScriptRoot 'target/package-input'
# Use a fresh staging directory so Maven cannot leave obsolete dependencies in the image.
$input = Join-Path $input $version
$lib = Join-Path $input 'lib'
New-Item -ItemType Directory -Path $lib, $output | Out-Null

& .\mvnw.cmd -q -DskipTests compile org.apache.maven.plugins:maven-dependency-plugin:3.11.0:copy-dependencies '-DincludeScope=runtime' "-DoutputDirectory=$lib" | Out-Host
if ($LASTEXITCODE) { throw 'Maven compilation or runtime dependency copy failed.' }

$manifest = Join-Path $output 'manifest.txt'
$classpath = 'Class-Path: ' + ((Get-ChildItem -LiteralPath $lib -Filter '*.jar' | Sort-Object Name | ForEach-Object { 'lib/' + $_.Name }) -join ' ')
$lines = @('Manifest-Version: 1.0')
for ($i = 0; $i -lt $classpath.Length; $i += 64) {
    $chunk = $classpath.Substring($i, [Math]::Min(64, $classpath.Length - $i))
    if ($i -gt 0) { $chunk = ' ' + $chunk }
    $lines += $chunk
}
[IO.File]::WriteAllText($manifest, ($lines -join "`r`n") + "`r`n`r`n")
& (Join-Path $jdk 'jar.exe') --create --file (Join-Path $input 'sangitam.jar') --manifest $manifest --main-class sangitam.desktop.app.SangitamApp -C bin sangitam
if ($LASTEXITCODE) { throw 'Could not assemble the application JAR.' }

$icon = Join-Path $output 'Sangitam.ico'
& (Join-Path $jdk 'java.exe') '-Djava.awt.headless=true' -cp bin tools/WriteLauncherIcon.java $icon
if ($LASTEXITCODE) { throw 'Could not generate the launcher icon.' }

& powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'native/build-media-host.ps1') -OutputDirectory (Join-Path $input 'native') | Out-Host
if ($LASTEXITCODE) { Write-Warning 'Windows system media helper unavailable. The app will use its media-key fallback.' }

& (Join-Path $jdk 'jpackage.exe') --type app-image --name Sangitam --app-version 0.2.0 --vendor Sangitam `
    --dest $output --input $input --main-jar sangitam.jar --icon $icon `
    --add-modules 'java.desktop,java.sql,java.net.http,java.logging,java.management,java.naming,jdk.unsupported,jdk.crypto.ec,jdk.charsets,jdk.localedata,jdk.accessibility,jdk.zipfs,jdk.management' `
    --jlink-options '--strip-debug --no-header-files --no-man-pages --generate-cds-archive' `
    --java-options '--enable-native-access=ALL-UNNAMED -Xms64m -Xmx640m -XX:G1PeriodicGCInterval=30000' | Out-Host
if ($LASTEXITCODE) { throw 'jpackage could not create the Windows application image.' }

$app = Join-Path $output 'Sangitam/Sangitam.exe'
if ($ScoreRecognitionDirectory) {
    $engine = (Resolve-Path -LiteralPath $ScoreRecognitionDirectory).Path
    if (-not (Test-Path -LiteralPath (Join-Path $engine 'Audiveris.exe') -PathType Leaf)) {
        throw 'ScoreRecognitionDirectory must point to an Audiveris application folder.'
    }
    $tools = Join-Path $output 'Sangitam/tools'
    New-Item -ItemType Directory -Path $tools | Out-Null
    Copy-Item -LiteralPath $engine -Destination (Join-Path $tools 'Audiveris') -Recurse
    $recognizerConfig = Join-Path $tools 'Audiveris/app/Audiveris.cfg'
    if (Test-Path -LiteralPath $recognizerConfig) {
        $configuration = [IO.File]::ReadAllText($recognizerConfig)
        $configuration = $configuration -replace '(?m)^java-options=-Xms\S+\r?$', 'java-options=-Xms96m'
        $configuration = $configuration -replace '(?m)^java-options=-Xmx\S+\r?$', "java-options=-Xmx1280m`r`njava-options=-XX:ActiveProcessorCount=4"
        [IO.File]::WriteAllText($recognizerConfig, $configuration)
    }
}
Write-Host "Open $app. Keep the entire Sangitam folder together."
Write-Output $app
