param(
    [string]$Version = '1.0.0',
    [string]$WebView2Sdk = $env:FINGRESS_WEBVIEW2_SDK,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
Push-Location $project
try {
    if (!$SkipBuild) {
        & mvn -B -ntp verify
        if ($LASTEXITCODE -ne 0) { throw 'Maven verification failed.' }
    }
    $stage = Join-Path $project ('target/system-java-' + [guid]::NewGuid().ToString('N'))
    $package = Join-Path $stage 'Fingress SQL Migration'
    $app = Join-Path $package 'app'
    New-Item -ItemType Directory -Path $app | Out-Null
    Copy-Item -LiteralPath 'target/fg-sql-migration-1.0.0-SNAPSHOT.jar' -Destination $app
    & (Join-Path $PSScriptRoot 'build-desktop.ps1') -OutputDirectory (Join-Path $app 'desktop') -WebView2Sdk $WebView2Sdk
    $compiler = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
    $icon = Join-Path $project 'src/main/resources/static/images/schemabridge.ico'
    & $compiler /nologo /target:winexe /platform:x64 /optimize+ "/win32icon:$icon" "/out:$(Join-Path $package 'Fingress SQL Migration.exe')" /reference:System.Windows.Forms.dll (Join-Path $project 'desktop/SystemJavaLauncher.cs')
    if ($LASTEXITCODE -ne 0) { throw 'System Java launcher compilation failed.' }
    Copy-Item -LiteralPath 'SYSTEM-JAVA-README.txt' -Destination (Join-Path $package 'README.txt')
    $dist = Join-Path $project 'dist'
    New-Item -ItemType Directory -Force -Path $dist | Out-Null
    $artifact = Join-Path $dist "Fingress-SQL-Migration-$Version-System-Java.zip"
    Compress-Archive -LiteralPath $package -DestinationPath $artifact -Force
    Get-FileHash -LiteralPath $artifact -Algorithm SHA256 | Format-List
    Write-Host "Created: $artifact"
    Write-Host "Application directory: $package"
} finally { Pop-Location }
