param(
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [string]$WebView2Sdk = $env:FINGRESS_WEBVIEW2_SDK
)
$ErrorActionPreference = 'Stop'
if (!$WebView2Sdk) { throw 'Set FINGRESS_WEBVIEW2_SDK or pass -WebView2Sdk with an extracted Microsoft.Web.WebView2 SDK directory.' }
$managed = Join-Path $WebView2Sdk 'lib/net462'
if (!(Test-Path -LiteralPath $managed)) { $managed = $WebView2Sdk }
$core = Join-Path $managed 'Microsoft.Web.WebView2.Core.dll'
$forms = Join-Path $managed 'Microsoft.Web.WebView2.WinForms.dll'
$loader = Join-Path $WebView2Sdk 'runtimes/win-x64/native/WebView2Loader.dll'
foreach ($file in @($core, $forms, $loader)) {
    if (!(Test-Path -LiteralPath $file)) { throw "Missing WebView2 SDK component: $file" }
}
$compiler = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
if (!(Test-Path -LiteralPath $compiler)) { throw 'The Windows .NET Framework C# compiler is required.' }
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
Copy-Item -LiteralPath $core,$forms,$loader -Destination $OutputDirectory
$source = Join-Path (Split-Path $PSScriptRoot -Parent) 'desktop/FingressDesktop.cs'
$icon = Join-Path (Split-Path $PSScriptRoot -Parent) 'src/main/resources/static/images/schemabridge.ico'
$hostExecutable = Join-Path $OutputDirectory 'FingressDesktop.exe'
& $compiler /nologo /target:winexe /platform:x64 /optimize+ "/win32icon:$icon" "/out:$hostExecutable" "/reference:$core" "/reference:$forms" /reference:System.Windows.Forms.dll /reference:System.Drawing.dll $source
if ($LASTEXITCODE -ne 0) { throw 'Desktop host compilation failed.' }
$sdkVersion = [Reflection.AssemblyName]::GetAssemblyName($core).Version.ToString()
@"
Fingress SQL Migration uses Microsoft WebView2 SDK $sdkVersion.
Copyright Microsoft Corporation. All rights reserved.
SDK license: https://www.nuget.org/packages/Microsoft.Web.WebView2/$sdkVersion/License
SDK: https://www.nuget.org/packages/Microsoft.Web.WebView2/$sdkVersion
The Evergreen WebView2 Runtime is installed and updated separately by Microsoft.
"@ | Set-Content -LiteralPath (Join-Path $OutputDirectory 'WEBVIEW2-NOTICE.txt') -Encoding UTF8
