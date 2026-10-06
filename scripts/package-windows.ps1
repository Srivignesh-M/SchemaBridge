param(
    [ValidateSet('exe', 'app-image')][string]$Type = 'exe',
    [string]$Version = '1.0.0',
    [string]$WixBin,
    [string]$WebView2Sdk = $env:FINGRESS_WEBVIEW2_SDK,
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$jpackage = (Get-Command jpackage -ErrorAction Stop).Source
$jlink = Join-Path (Split-Path $jpackage -Parent) 'jlink.exe'
if ($WixBin) { $env:PATH = "$WixBin;$env:PATH" }
if ($Type -eq 'exe') {
    if (!(Get-Command candle.exe -ErrorAction SilentlyContinue) -or !(Get-Command light.exe -ErrorAction SilentlyContinue)) {
        throw 'JDK 21 requires WiX 3 candle.exe and light.exe. Install WiX 3 or pass -WixBin PATH. Use -Type app-image for a portable application without WiX.'
    }
}
Push-Location $project
try {
    if (!$SkipBuild) {
        & mvn -B -ntp verify
        if ($LASTEXITCODE -ne 0) { throw 'Maven verification failed.' }
    }
    $jar = Join-Path $project 'target/fg-sql-migration-1.0.0-SNAPSHOT.jar'
    if (!(Test-Path -LiteralPath $jar)) { throw "Missing application JAR: $jar" }
    # A unique staging directory contains only the deliverable JAR, never local credentials or reports.
    $stage = Join-Path $project ('target/packaging-' + [guid]::NewGuid().ToString('N'))
    $inputDir = Join-Path $stage 'input'
    $outputDir = Join-Path $stage 'output'
    New-Item -ItemType Directory -Path $inputDir,$outputDir | Out-Null
    Copy-Item -LiteralPath $jar -Destination $inputDir
    & (Join-Path $PSScriptRoot 'build-desktop.ps1') -OutputDirectory (Join-Path $inputDir 'desktop') -WebView2Sdk $WebView2Sdk
    # jdeps over application classes and bundled dependencies, plus dynamically
    # loaded TLS, charset, locale and ZIP providers. Avoid shipping the whole JDK.
    $modules = 'java.base,java.compiler,java.desktop,java.instrument,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.sql,jdk.jfr,jdk.net,jdk.security.jgss,jdk.unsupported,jdk.crypto.ec,jdk.charsets,jdk.localedata,jdk.zipfs'
    $runtimeDir = Join-Path $stage 'runtime'
    # Leave the module archive uncompressed so the outer ZIP/MSI can compress
    # across classes. Double compression produced a larger download in testing.
    & $jlink --add-modules $modules --output $runtimeDir --strip-debug --no-header-files --no-man-pages --compress=zip-0
    if ($LASTEXITCODE -ne 0) { throw 'jlink runtime creation failed.' }
    $packageArgs = @('--type', $Type, '--name', 'Fingress SQL Migration',
        '--app-version', $Version, '--vendor', 'Fingress',
        '--description', 'Oracle and PostgreSQL SQL migration',
        '--input', $inputDir, '--dest', $outputDir,
        '--main-jar', (Split-Path $jar -Leaf),
        '--main-class', 'org.springframework.boot.loader.launch.JarLauncher',
        '--arguments', '--desktop', '--java-options', '-Djava.awt.headless=false',
        '--runtime-image', $runtimeDir)
    if ($Type -eq 'exe') {
        $packageArgs += @('--win-per-user-install', '--win-dir-chooser', '--win-menu',
            '--win-menu-group', 'Fingress', '--win-shortcut',
            '--win-upgrade-uuid', 'ba9019d0-8cdb-4ec8-852b-8025e61349a0')
    }
    & $jpackage @packageArgs
    if ($LASTEXITCODE -ne 0) { throw 'jpackage failed.' }
    $dist = Join-Path $project 'dist'
    New-Item -ItemType Directory -Force -Path $dist | Out-Null
    if ($Type -eq 'exe') {
        $installer = Get-ChildItem -LiteralPath $outputDir -Filter '*.exe' | Select-Object -First 1
        if (!$installer) { throw 'jpackage did not create an installer.' }
        $artifact = Join-Path $dist "Fingress-SQL-Migration-$Version-Setup.exe"
        Copy-Item -LiteralPath $installer.FullName -Destination $artifact
    } else {
        $artifact = Join-Path $dist "Fingress-SQL-Migration-$Version-Windows.zip"
        Compress-Archive -LiteralPath (Join-Path $outputDir 'Fingress SQL Migration') -DestinationPath $artifact -Force
    }
    Get-FileHash -LiteralPath $artifact -Algorithm SHA256 | Format-List
    Write-Host "Created: $artifact"
    Write-Host "Staging directory: $stage"
} finally { Pop-Location }
