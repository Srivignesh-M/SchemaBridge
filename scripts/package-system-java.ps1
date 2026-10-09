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
    $jarCandidates = @(Get-ChildItem -LiteralPath (Join-Path $project 'target') -Filter 'fg-sql-migration-*.jar' -ErrorAction SilentlyContinue)
    if ($jarCandidates.Count -eq 0) { throw "Missing application JAR in target/. Run mvn package first." }
    if ($jarCandidates.Count -gt 1) { throw "Multiple candidate JARs found in target/: $($jarCandidates.Name -join ', '). Clean target/ and rebuild." }
    Copy-Item -LiteralPath $jarCandidates[0].FullName -Destination $app
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
