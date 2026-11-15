param([string]$OutputDirectory = (Join-Path $PSScriptRoot '../bin/native'))
$ErrorActionPreference = 'Stop'
$framework = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319'
$metadata = Join-Path $env:WINDIR 'System32/WinMetadata'
if (-not (Test-Path -LiteralPath (Join-Path $framework 'csc.exe')) -or -not (Test-Path -LiteralPath (Join-Path $metadata 'Windows.Media.winmd'))) {
    Write-Output 'Windows media companion unavailable: .NET Framework 4.x and Windows 10+ metadata are required.'
    exit 1
}
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$references = @('System.Runtime.dll','System.Runtime.WindowsRuntime.dll','System.Runtime.InteropServices.WindowsRuntime.dll','System.Threading.Tasks.dll','System.ObjectModel.dll') | ForEach-Object { '/reference:' + (Join-Path $framework $_) }
$references += @('/reference:System.Windows.Forms.dll', ('/reference:' + (Join-Path $metadata 'Windows.Media.winmd')), ('/reference:' + (Join-Path $metadata 'Windows.Foundation.winmd')))
& (Join-Path $framework 'csc.exe') /nologo /target:winexe /optimize+ /platform:anycpu ('/out:' + (Join-Path $OutputDirectory 'SangitamMediaHost.exe')) @references (Join-Path $PSScriptRoot 'MediaHost.cs')
exit $LASTEXITCODE
