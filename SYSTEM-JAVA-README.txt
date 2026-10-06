Fingress SQL Migration - system Java edition

1. Extract the entire ZIP into a folder.
2. Double-click Fingress SQL Migration.exe.
3. Use the connection forms or SQL file upload to prepare your migration.

Keep the app folder beside the executable. Share the full ZIP, not just the EXE.

Requirements:
- Windows x64 with .NET Framework 4.8.
- Java 21 or newer installed on the machine. Java is NOT included in this ZIP.
  The launcher checks JAVA_HOME/bin/java.exe first, then entries on PATH.
  If a Java installation is too old or unavailable, it tries the next entry.
  If none are compatible, it shows instructions instead of starting migration.
- Microsoft Edge WebView2 Evergreen Runtime for the embedded desktop interface.
- Network access, database credentials and permissions for connected migrations.

The application chooses a local port and displays its interface in a desktop
window. Closing the window asks for confirmation and shuts down the server.
Wait for migrations to finish before closing it.

Settings, logs and work files live in:
%LOCALAPPDATA%\Fingress SQL Migration

No developer database credentials or catalogue configuration are included.
Your administrator can configure LCNC catalogue access separately.

The embedded display still needs validation on your normal Windows desktop:
the restricted build environment blocks Java from starting the desktop host
and blocks WebView2 process communication. Java detection and the server's
startup were verified, along with 56 backend tests.
Test the application on a representative employee computer before broad rollout.
