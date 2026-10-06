param([Parameter(Mandatory=$true)][string]$ApplicationDirectory)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$testRoot = Join-Path $project ('target/embedded-smoke-' + [guid]::NewGuid().ToString('N'))
$reports = Join-Path $testRoot 'reports'
New-Item -ItemType Directory -Path $reports | Out-Null
$previousData = $env:LOCALAPPDATA
$process = $null
try {
    $env:LOCALAPPDATA = $testRoot
    $executable = Join-Path $ApplicationDirectory 'Fingress SQL Migration.exe'
    $process = Start-Process -FilePath $executable -ArgumentList @('--desktop', ('"--desktop-smoke-dir=' + $reports + '"')) -WindowStyle Hidden -PassThru
    if (!$process.WaitForExit(45000)) { throw "Embedded desktop test timed out. Logs: $testRoot" }
    if ($process.ExitCode -ne 0 -or !(Test-Path -LiteralPath (Join-Path $reports 'passed.txt'))) {
        if (Test-Path -LiteralPath (Join-Path $reports 'failure.txt')) { Get-Content -LiteralPath (Join-Path $reports 'failure.txt') }
        throw "Embedded desktop test failed. Logs: $testRoot"
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead((Join-Path $reports 'migration.zip'))
    try {
        if (!($zip.Entries | Where-Object FullName -Match '01-tables.sql$')) { throw 'Downloaded ZIP is missing SQL.' }
    } finally { $zip.Dispose() }
    Get-Content -LiteralPath (Join-Path $reports 'passed.txt')
    Write-Host "Desktop screenshot and reports: $reports"
} finally {
    if ($process -and !$process.HasExited) { Stop-Process -Id $process.Id -Force }
    $env:LOCALAPPDATA = $previousData
}
