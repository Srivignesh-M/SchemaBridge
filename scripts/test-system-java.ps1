param([string]$ApplicationDirectory)
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$testRoot = Join-Path $project ('target/launcher-tests-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
$compiler = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
$source = Join-Path $project 'desktop/SystemJavaLauncher.cs'
$tests = Join-Path $project 'desktop/tests/LauncherTests.cs'
& $compiler /nologo /target:exe /main:FakeJava "/out:$(Join-Path $testRoot 'FakeJava.exe')" /reference:System.Windows.Forms.dll $source $tests
if ($LASTEXITCODE -ne 0) { throw 'Test fixture compilation failed.' }
& $compiler /nologo /target:exe /main:LauncherTests "/out:$(Join-Path $testRoot 'LauncherTests.exe')" /reference:System.Windows.Forms.dll $source $tests
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
& (Join-Path $testRoot 'LauncherTests.exe') $testRoot
if ($LASTEXITCODE -ne 0) { throw 'Launcher tests failed.' }
if ($ApplicationDirectory) {
    $package = Join-Path $testRoot 'Package with spaces'
    $app = Join-Path $package 'app'
    $desktop = Join-Path $app 'desktop'
    New-Item -ItemType Directory -Path $desktop | Out-Null
    Copy-Item -LiteralPath (Join-Path $ApplicationDirectory 'Fingress SQL Migration.exe') -Destination $package
    Copy-Item -LiteralPath (Join-Path $ApplicationDirectory 'app/fg-sql-migration-1.0.0-SNAPSHOT.jar') -Destination $app
    & $compiler /nologo /target:winexe /main:BackendProbeHost "/out:$(Join-Path $desktop 'FingressDesktop.exe')" /reference:System.Windows.Forms.dll $source $tests
    if ($LASTEXITCODE -ne 0) { throw 'Backend probe compilation failed.' }
    $previousData = $env:LOCALAPPDATA
    $process = $null
    try {
        $env:LOCALAPPDATA = Join-Path $testRoot 'user data'
        $launcher = Join-Path $package 'Fingress SQL Migration.exe'
        $checkInfo = New-Object System.Diagnostics.ProcessStartInfo
        $checkInfo.FileName = $launcher
        $checkInfo.Arguments = '--check-java'
        $checkInfo.UseShellExecute = $false
        $checkInfo.CreateNoWindow = $true
        $checkInfo.RedirectStandardOutput = $true
        $check = [System.Diagnostics.Process]::Start($checkInfo)
        if (!$check.WaitForExit(30000)) { $check.Kill(); throw 'Java detection timed out.' }
        if ($check.ExitCode -ne 0) { throw 'Packaged launcher could not find compatible system Java.' }
        Write-Host 'Selected installed Java:'
        Write-Output $check.StandardOutput.ReadToEnd()
        $check.Dispose()
        $process = Start-Process -FilePath $launcher -ArgumentList ('"--desktop-smoke-dir=' + $testRoot + '"') -WorkingDirectory $testRoot -WindowStyle Hidden -PassThru
        if (!$process.WaitForExit(30000)) { throw 'System-Java backend startup/shutdown timed out.' }
        $proof = Join-Path $env:LOCALAPPDATA 'Fingress SQL Migration/webview/backend-passed.txt'
        if ($process.ExitCode -ne 0 -or !(Test-Path -LiteralPath $proof)) {
            $errorLog = Join-Path $env:LOCALAPPDATA 'Fingress SQL Migration/logs/launcher-error.log'
            if (Test-Path -LiteralPath $errorLog) { Get-Content -LiteralPath $errorLog }
            throw "System-Java backend handoff failed. Test logs: $testRoot"
        }
        Get-Content -LiteralPath $proof
        Write-Host 'Packaged launcher and backend shut down successfully.'
    } finally {
        if ($process -and !$process.HasExited) { Stop-Process -Id $process.Id -Force }
        $env:LOCALAPPDATA = $previousData
    }
}
