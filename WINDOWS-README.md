# Windows application

## Browser edition

`dist/Fingress-SQL-Migration-1.0.0-Browser.zip` contains the current application JAR and a trimmed Java runtime. Extract it, run `Start SchemaBridge.bat`, then open `http://localhost:8098`. Keep its command window open while working. History and logs live under `%LOCALAPPDATA%\Fingress SQL Migration`. Build or refresh it with `./scripts/package-browser.ps1`.

## Smaller package using installed Java

`dist/Fingress-SQL-Migration-1.0.0-System-Java.zip` omits the Java runtime. Extract it and launch `Fingress SQL Migration.exe`; keep its `app` directory beside it. The native launcher checks `JAVA_HOME/bin/java.exe` first, then Java installations on `PATH`, accepting Java 21 or newer. Invalid or older installations fall back to the next candidate. If no compatible Java is found, it shows instructions. Users still need Windows x64, .NET Framework 4.8 and WebView2 Runtime for the embedded interface. Share the entire ZIP.

Build using `./scripts/package-system-java.ps1 -WebView2Sdk PATH` (or set `FINGRESS_WEBVIEW2_SDK`). This edition does not require jpackage, jlink or WiX. Run `./scripts/test-system-java.ps1 -ApplicationDirectory 'PATH/TO/Fingress SQL Migration'` to verify detection and the packaged launcher with installed Java. The backend handoff test substitutes a small test host for WebView2; it verifies backend startup and shutdown, not embedded rendering. The separate `test-desktop.ps1` remains the full embedded-display check. End-user instructions are included as `README.txt` in the ZIP.

## Bundled-Java edition

The Windows package bundles Java and displays the migration interface inside its own desktop window using Microsoft WebView2. No browser tab is opened. End users do not need Java, Maven, Docker, or terminal commands. Windows x64, .NET Framework 4.8 and Microsoft Edge WebView2 Evergreen Runtime are required; these Windows components are not bundled. WebView2 is normally present on Windows 11. If missing, the application explains that IT must install it.

## Use the application

For the installer, run `Fingress-SQL-Migration-1.0.0-Setup.exe`, then launch **Fingress SQL Migration** from the Start menu or desktop shortcut. It installs for the current Windows user.

For the portable ZIP, extract the whole ZIP and double-click `Fingress SQL Migration.exe`. Keep the `app` and `runtime` folders beside it.

The application opens directly in one desktop window. Select SQL files or folders using the upload controls. ZIP exports show a Windows Save dialog. Closing the window asks for confirmation, then stops the local server. Wait for migrations to finish before exiting because interruption can leave committed partial changes. If the server process stops unexpectedly, its desktop window also closes.

It listens only on `127.0.0.1` and chooses an available port each launch. One desktop instance runs per Windows user. Work files, logs, and optional catalogue settings live in `%LOCALAPPDATA%\Fingress SQL Migration`, outside the installation directory. The main log is `logs\application.log` in that folder. Uninstalling the application does not remove those user files.

Use the existing connection forms to enter database details. SQL-file conversion does not require a database. LCNC catalogue access is optional: an administrator can supply `catalog-local.properties` in the user data folder or the existing `MIGRATION_CATALOG_*` environment variables. The package never includes the developer's catalogue configuration or credentials. The installed application does not automatically provision organizational database access.

## Build on Windows

Build prerequisites: JDK 21 with `jpackage`, Maven, Windows .NET Framework C# compiler, the Microsoft.Web.WebView2 SDK, and (for setup EXE only) WiX Toolset 3 with `candle.exe` and `light.exe` on PATH. These tools are only needed on the build machine. Extract the Microsoft.Web.WebView2 NuGet package and set `FINGRESS_WEBVIEW2_SDK` to its directory, or pass `-WebView2Sdk PATH`. The SDK must contain `lib/net462` managed assemblies and `runtimes/win-x64/native/WebView2Loader.dll`. A flattened managed-assembly directory with the same runtimes subdirectory is also accepted. This build was validated with SDK 1.0.3485.44. Only the WebView2 SDK redistributable assemblies and loader are copied into the app; the browser engine uses the installed Evergreen Runtime.

```powershell
./scripts/package-windows.ps1 -WixBin 'C:\tools\wix3'
```

The script verifies the Maven build, stages only the application JAR, bundles the local JDK runtime, and creates `dist/Fingress-SQL-Migration-1.0.0-Setup.exe`. A stable upgrade UUID is retained across releases; pass `-Version 1.0.1` for the next package version.

The bundled runtime contains a selected set of Java modules instead of the entire JDK. The list comes from `jdeps` analysis of application classes and bundled dependency JARs, with TLS, character-set, locale and ZIP providers explicitly included for dynamic loading. Recheck it when dependencies change. Debug symbols, headers and manual pages are omitted. Compression is left to the distribution ZIP/installer, which yields a smaller download than compressing the module archive first. Both Oracle and PostgreSQL drivers remain included.

Without WiX, build a portable application ZIP:

```powershell
./scripts/package-windows.ps1 -Type app-image
```

Use `-SkipBuild` only after a successful `mvn verify`. Builds use unique directories under `target/packaging-*`. Use the JDK distribution approved by your organization for redistribution. The package is built for the build JDK's Windows architecture; it is not a macOS/Linux package.

Installers are unsigned unless your release process signs them with your organization's code-signing certificate. Windows may show an unknown-publisher prompt. Before distribution, test install, embedded display, file/folder selection, ZIP saving, migration, stop, relaunch, upgrade, and uninstall on a clean Windows machine without Java.

Run `./scripts/test-desktop.ps1 -ApplicationDirectory 'PATH/TO/Fingress SQL Migration'` for the embedded desktop smoke test. It uses isolated user storage and exercises real WebView2 rendering, local HTTP conversion, blob ZIP download, screenshot capture, and shutdown. It does not connect to a database. Actual file-picker interaction and live database migration still require manual validation. The local browser profile is stored in the user data folder under `webview`.

WebView2 distribution reference: https://learn.microsoft.com/en-us/microsoft-edge/webview2/concepts/distribution

Build validation on 2026-10-06: all 56 Java tests pass and the Windows host compiles. The embedded smoke test is blocked in the restricted build environment: Chromium reports `Access is denied (0x5)` for its process communication channel and profile cache. Embedded rendering, file dialogs, ZIP saving and shutdown need verification when launched normally from Windows Explorer. The previous browser-based package is retained separately as `Fingress-SQL-Migration-1.0.0-Browser.zip`.

System-Java edition validation: launcher detection/fallback/version and Windows argument-roundtrip tests pass. The packaged launcher selects installed Java 21 and starts the local server. The end-to-end test is blocked when Java tries to create the desktop host process (`CreateProcess error=5, Access is denied`) in the restricted environment. This does not establish that the embedded desktop works on employee machines; run the included checks outside this environment before rollout.

Packaging reference: https://docs.oracle.com/en/java/javase/21/docs/specs/man/jpackage.html
