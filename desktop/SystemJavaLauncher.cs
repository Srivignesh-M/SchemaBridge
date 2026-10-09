using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;
using System.Windows.Forms;

internal static class SystemJavaLauncher
{
    internal static string Quote(string argument)
    {
        // Windows CommandLineToArgvW quoting, including trailing backslashes.
        var result = new StringBuilder("\"");
        int slashes = 0;
        foreach (char character in argument)
        {
            if (character == '\\') { slashes++; continue; }
            if (character == '"') result.Append('\\', slashes * 2 + 1).Append('"');
            else result.Append('\\', slashes).Append(character);
            slashes = 0;
        }
        return result.Append('\\', slashes * 2).Append('"').ToString();
    }

    internal static int MajorVersion(string text)
    {
        var match = Regex.Match(text, @"(?m)^\s*java\.specification\.version\s*=\s*(?:1\.)?(\d+)\s*$");
        int major;
        return match.Success && int.TryParse(match.Groups[1].Value, out major) ? major : 0;
    }

    internal static IEnumerable<string> Candidates(string javaHome, string path)
    {
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var directories = new List<string>();
        if (!string.IsNullOrWhiteSpace(javaHome)) directories.Add(javaHome.Trim().Trim('"') + "\\bin");
        directories.AddRange((path ?? "").Split(';'));
        foreach (string value in directories)
        {
            if (string.IsNullOrWhiteSpace(value)) continue;
            string candidate;
            try {
                string directory = Environment.ExpandEnvironmentVariables(value.Trim().Trim('"'));
                candidate = Path.GetFullPath(Path.Combine(directory, "java.exe"));
            } catch (Exception) { continue; }
            if (seen.Add(candidate) && File.Exists(candidate)) yield return candidate;
        }
    }

    internal static string FindJava(string javaHome, string path)
    {
        foreach (string candidate in Candidates(javaHome, path))
        {
            try
            {
                var output = new StringBuilder();
                var start = new ProcessStartInfo(candidate, "-XshowSettings:properties -version") {
                    UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true
                };
                using (var process = new Process { StartInfo = start })
                {
                    DataReceivedEventHandler collect = (sender, args) => { if (args.Data != null) lock (output) { if (output.Length < 65536) output.AppendLine(args.Data); } };
                    process.OutputDataReceived += collect; process.ErrorDataReceived += collect;
                    process.Start(); process.BeginOutputReadLine(); process.BeginErrorReadLine();
                    if (!process.WaitForExit(8000)) { process.Kill(); continue; }
                    process.WaitForExit();
                    if (process.ExitCode == 0 && MajorVersion(output.ToString()) >= 21) return candidate;
                }
            }
            catch (Exception) { /* A stale JAVA_HOME or PATH entry must not prevent fallback. */ }
        }
        return null;
    }

    [STAThread]
    private static int Main(string[] args)
    {
        bool check = args.Length == 1 && args[0] == "--check-java";
        try
        {
            string java = FindJava(Environment.GetEnvironmentVariable("JAVA_HOME"), Environment.GetEnvironmentVariable("PATH"));
            if (java == null)
            {
                const string message = "Java 21 or newer was not found.\nAsk IT to install Java 21+ and set JAVA_HOME or add its bin folder to PATH.\nThis package does not include Java.";
                if (check) Console.Error.WriteLine(message); else MessageBox.Show(message, "Fingress SQL Migration", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return 2;
            }
            if (check) { Console.WriteLine(java); return 0; }
            string root = AppDomain.CurrentDomain.BaseDirectory.TrimEnd(Path.DirectorySeparatorChar);
            string appDir = Path.Combine(root, "app");
            string[] jarCandidates = Directory.Exists(appDir) ? Directory.GetFiles(appDir, "fg-sql-migration-*.jar") : new string[0];
            if (jarCandidates.Length == 0 || !File.Exists(Path.Combine(appDir, "desktop", "FingressDesktop.exe")))
                throw new FileNotFoundException("Application files are missing. Extract the complete ZIP and keep the app folder beside this executable.");
            if (jarCandidates.Length > 1)
                throw new FileNotFoundException("Multiple application JARs found in the app folder; keep only one fg-sql-migration-*.jar file.");
            string jar = jarCandidates[0];
            var arguments = new List<string> { "-Dmigration.desktop-home=" + root, "-jar", jar, "--desktop" };
            foreach (string arg in args)
            {
                if (arg == "--desktop") continue;
                if (!arg.StartsWith("--desktop-smoke-dir=", StringComparison.Ordinal)) throw new ArgumentException("Unsupported launcher argument.");
                arguments.Add(arg);
            }
            var quoted = new List<string>(); foreach (string arg in arguments) quoted.Add(Quote(arg));
            var launch = new ProcessStartInfo(java, string.Join(" ", quoted)) {
                WorkingDirectory = root, UseShellExecute = false, CreateNoWindow = true
            };
            using (var process = Process.Start(launch)) { process.WaitForExit(); return process.ExitCode; }
        }
        catch (Exception error)
        {
            if (check) Console.Error.WriteLine(error.Message);
            else MessageBox.Show(error.Message, "Fingress SQL Migration", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }
}
