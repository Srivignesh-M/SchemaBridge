param(
    [string]$Version = '1.0.0',
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$jpackage = (Get-Command jpackage -ErrorAction Stop).Source
$jlink = Join-Path (Split-Path $jpackage -Parent) 'jlink.exe'
Push-Location $project
try {
    if (!$SkipBuild) {
        & mvn -B -ntp verify
        if ($LASTEXITCODE -ne 0) { throw 'Maven verification failed.' }
    }
    $jarCandidates = @(Get-ChildItem -LiteralPath (Join-Path $project 'target') -Filter 'fg-sql-migration-*.jar' -ErrorAction SilentlyContinue)
    if ($jarCandidates.Count -eq 0) { throw "Missing application JAR in target/. Run mvn package first." }
    if ($jarCandidates.Count -gt 1) { throw "Multiple candidate JARs found in target/: $($jarCandidates.Name -join ', '). Clean target/ and rebuild." }
    $jar = $jarCandidates[0].FullName
    $jarName = $jarCandidates[0].Name
    $stage = Join-Path $project ('target/browser-package-' + [guid]::NewGuid().ToString('N'))
    $package = Join-Path $stage 'Fingress SQL Migration'
    $runtime = Join-Path $stage 'runtime'
    New-Item -ItemType Directory -Path $package | Out-Null
    Copy-Item -LiteralPath $jar -Destination $package
    $modules = 'java.base,java.compiler,java.desktop,java.instrument,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.sql,jdk.jfr,jdk.net,jdk.security.jgss,jdk.unsupported,jdk.crypto.ec,jdk.charsets,jdk.localedata,jdk.zipfs'
    & $jlink --add-modules $modules --output $runtime --strip-debug --no-header-files --no-man-pages --compress=zip-0
    if ($LASTEXITCODE -ne 0) { throw 'jlink runtime creation failed.' }
    @"
@echo off
setlocal
cd /d "%~dp0"
if "%LOCALAPPDATA%"=="" (
  echo Windows local application data is unavailable.
  exit /b 1
)
set "DATA_DIR=%LOCALAPPDATA%\Fingress SQL Migration"
if not exist "%DATA_DIR%\logs" mkdir "%DATA_DIR%\logs"
if not exist "%DATA_DIR%\work" mkdir "%DATA_DIR%\work"
echo Starting SchemaBridge. When it is ready, open http://localhost:8098 in your browser.
echo Keep this window open. Press Ctrl+C to stop the local server.
"runtime\bin\java.exe" -jar "$jarName" "--migration.work-dir=%DATA_DIR%\work" "--logging.file.name=%DATA_DIR%\logs\application.log"
"@ | Set-Content -LiteralPath (Join-Path $package 'Start SchemaBridge.bat') -Encoding ASCII
    @'
SchemaBridge browser edition

1. Double-click "Start SchemaBridge.bat".
2. Wait for the server-started message, then open http://localhost:8098.
3. Keep the command window open while using the application. Press Ctrl+C there to stop it.

Migration history, staged data and logs are stored in %LOCALAPPDATA%\Fingress SQL Migration. The application listens on this computer only. Wait for active migrations to finish before stopping the server; committed work can remain after an interruption.

This package includes Java. It requires 64-bit Windows and a current Microsoft Edge WebView2 Runtime is not required for this browser edition. Use a supported desktop package if you want the application in its own window.
'@ | Set-Content -LiteralPath (Join-Path $package 'README.txt') -Encoding UTF8
    Copy-Item -LiteralPath $runtime -Destination $package -Recurse
    $dist = Join-Path $project 'dist'
    New-Item -ItemType Directory -Force -Path $dist | Out-Null
    $artifact = Join-Path $dist "Fingress-SQL-Migration-$Version-Browser.zip"
    Compress-Archive -LiteralPath $package -DestinationPath $artifact -Force
    Get-FileHash -LiteralPath $artifact -Algorithm SHA256 | Format-List
    Write-Host "Created: $artifact"
    Write-Host "Package directory: $package"
} finally { Pop-Location }
