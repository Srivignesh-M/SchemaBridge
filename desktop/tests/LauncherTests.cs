using System;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Net;

// Test-only replacement for the embedded window: verifies the real Java server
// handoff and lifecycle without requiring WebView2 in the restricted test host.
internal static class BackendProbeHost
{
    public static int Main(string[] args)
    {
        if (args.Length != 3 && args.Length != 5) return 1;
        try {
            var request = (HttpWebRequest) WebRequest.Create(args[0] + "/api/capabilities");
            request.Proxy = null;
            request.Timeout = 5000;
            using (var reply = request.GetResponse())
            using (var reader = new StreamReader(reply.GetResponseStream())) {
                string response = reader.ReadToEnd();
                if (!response.Contains("INSERT_ONLY") || response.Contains("aiConfigured")) return 2;
            }
        } catch (Exception error) { Console.Error.WriteLine(error); return 3; }
        Directory.CreateDirectory(args[1]);
        File.WriteAllText(Path.Combine(args[1], "backend-passed.txt"), "Real system Java started the packaged server and handed its URL to the desktop host.");
        return 0;
    }
}

internal static class FakeJava
{
    public static int Main(string[] args)
    {
        if (args.Length == 2 && args[0] == "-XshowSettings:properties")
            Console.Error.WriteLine("    java.specification.version = " + (AppDomain.CurrentDomain.BaseDirectory.Contains("old-java") ? "17" : "21"));
        else foreach (string arg in args) Console.WriteLine(Convert.ToBase64String(Encoding.UTF8.GetBytes(arg)));
        return 0;
    }
}

internal static class LauncherTests
{
    private static void Check(bool condition, string message) { if (!condition) throw new Exception(message); }
    public static int Main(string[] args)
    {
        string root = Path.GetFullPath(args[0]);
        string oldHome = Path.Combine(root, "old-java");
        string goodHome = Path.Combine(root, "good java");
        foreach (string home in new[] {oldHome, goodHome}) {
            Directory.CreateDirectory(Path.Combine(home, "bin"));
            File.Copy(Path.Combine(root, "FakeJava.exe"), Path.Combine(home, "bin", "java.exe"), true);
        }
        string good = Path.Combine(goodHome, "bin", "java.exe");
        Check(SystemJavaLauncher.MajorVersion("java.specification.version = 1.8") == 8, "Java 8 parsing");
        Check(SystemJavaLauncher.MajorVersion("  java.specification.version = 25\r\n") == 25, "Java 25 parsing");
        Check(SystemJavaLauncher.MajorVersion("unrelated version 99") == 0, "Unknown version must fail closed");
        Check(SystemJavaLauncher.FindJava(oldHome, Path.Combine(goodHome, "bin")) == good, "Old JAVA_HOME must fall back to compatible PATH");
        Check(SystemJavaLauncher.FindJava(goodHome, Path.Combine(oldHome, "bin")) == good, "Compatible JAVA_HOME should win");
        Check(SystemJavaLauncher.FindJava(Path.Combine(root, "missing"), Path.Combine(goodHome, "bin")) == good, "Missing JAVA_HOME fallback");
        Check(SystemJavaLauncher.FindJava(oldHome, "") == null, "Old Java must be rejected");
        Check(SystemJavaLauncher.FindJava(null, "") == null, "Missing Java must be rejected");
        string[] samples = {"", @"C:\Program Files\Java\", "quotes\"and\\slashes", "--desktop-smoke-dir=C:\\Folder with spaces\\", "plain"};
        var quoted = new string[samples.Length];
        for (int i=0; i<samples.Length; i++) quoted[i] = SystemJavaLauncher.Quote(samples[i]);
        using (var process = Process.Start(new ProcessStartInfo(good, string.Join(" ", quoted)) {
            UseShellExecute=false, CreateNoWindow=true, RedirectStandardOutput=true
        })) {
            for (int i=0; i<samples.Length; i++) {
                string line = process.StandardOutput.ReadLine();
                Check(line != null && Encoding.UTF8.GetString(Convert.FromBase64String(line)) == samples[i], "Argument roundtrip " + i);
            }
            process.WaitForExit(); Check(process.ExitCode == 0, "Argument echo process failed");
        }
        Console.WriteLine("Launcher tests passed: JAVA_HOME/PATH fallback, version checks, missing Java, and real Windows argument quoting.");
        return 0;
    }
}
