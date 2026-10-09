param(
    [string]$UpgradeCode = 'ba9019d0-8cdb-4ec8-852b-8025e61349a0'
)
$ErrorActionPreference = 'Stop'
$installer = New-Object -ComObject WindowsInstaller.Installer
$relatedProducts = $installer.GetType().InvokeMember('RelatedProducts', 'InvokeMethod', $null, $installer, @("{$UpgradeCode}"))
$found = $false
foreach ($productCode in $relatedProducts) {
    $found = $true
    Write-Host "Removing installed product $productCode ..."
    $process = Start-Process -FilePath msiexec.exe -ArgumentList "/x $productCode /qn" -Wait -PassThru
    if ($process.ExitCode -ne 0) { throw "msiexec /x $productCode failed with exit code $($process.ExitCode)." }
}
if (!$found) {
    Write-Host 'No installed Fingress SQL Migration product found for this user.'
} else {
    Write-Host 'Removal complete. Setup.exe can now run a clean install.'
}
