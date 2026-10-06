using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

internal sealed class MigrationWindow : Form
{
    private readonly WebView2 view = new WebView2();
    private readonly Uri home;
    private readonly string profile;
    private readonly Process parent;
    private readonly string smokeDirectory;
    private readonly Timer lifetime = new Timer();
    private readonly Label status = new Label();
    private bool allowClose, smokeStarted;
    private readonly TaskCompletionSource<bool> downloadFinished = new TaskCompletionSource<bool>();

    [STAThread]
    private static int Main(string[] args)
    {
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        try
        {
            if (args.Length != 3 && args.Length != 5) throw new ArgumentException("Launch through Fingress SQL Migration.exe.");
            Uri home = new Uri(args[0]);
            if (home.Scheme != "http" || home.Host != "127.0.0.1" || home.AbsolutePath != "/")
                throw new ArgumentException("The application address must be local.");
            string smoke = args.Length == 5 && args[3] == "--smoke-test" ? args[4] : null;
            Application.Run(new MigrationWindow(home, args[1], Process.GetProcessById(int.Parse(args[2])), smoke));
            return Environment.ExitCode;
        }
        catch (Exception error)
        {
            MessageBox.Show(error.Message, "Fingress SQL Migration", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }

    private MigrationWindow(Uri home, string profile, Process parent, string smokeDirectory)
    {
        this.home = home; this.profile = profile; this.parent = parent; this.smokeDirectory = smokeDirectory;
        Text = "Fingress SQL Migration"; Size = new Size(1280, 900); MinimumSize = new Size(850, 600);
        StartPosition = FormStartPosition.CenterScreen;
        view.Dock = DockStyle.Fill; Controls.Add(view);
        status.Text = "Starting application..."; status.Dock = DockStyle.Bottom; status.Height = 26;
        status.Padding = new Padding(8, 4, 0, 0); Controls.Add(status);
        Shown += async (sender, args) => await Initialize();
        FormClosing += (sender, args) => {
            if (!allowClose && MessageBox.Show(this,
                "Exit the application? Wait for any migration to finish first.\nClosing interrupts active work and may leave partial changes.",
                Text, MessageBoxButtons.OKCancel, MessageBoxIcon.Question) != DialogResult.OK) args.Cancel = true;
        };
        FormClosed += (sender, args) => { lifetime.Stop(); view.Dispose(); parent.Dispose(); };
        lifetime.Interval = 1000;
        lifetime.Tick += (sender, args) => { if (parent.HasExited) { allowClose = true; Close(); } };
        lifetime.Start();
    }

    private bool IsLocal(string address)
    {
        Uri uri;
        return Uri.TryCreate(address, UriKind.Absolute, out uri) && uri.Scheme == home.Scheme
            && uri.Host == home.Host && uri.Port == home.Port;
    }

    private async Task Initialize()
    {
        try
        {
            Console.WriteLine("Desktop window initializing.");
            CoreWebView2Environment.GetAvailableBrowserVersionString();
            Directory.CreateDirectory(profile);
            var options = new CoreWebView2EnvironmentOptions();
            if (smokeDirectory != null)
                options.AdditionalBrowserArguments = "--enable-logging --log-file=\"" + Path.Combine(profile, "engine.log") + "\"";
            var environment = await CoreWebView2Environment.CreateAsync(null, profile, options);
            Console.WriteLine("WebView2 environment created.");
            var initialization = view.EnsureCoreWebView2Async(environment);
            if (await Task.WhenAny(initialization, Task.Delay(25000)) != initialization)
                throw new Exception("WebView2 startup timed out. Check that the Microsoft WebView2 Runtime is healthy and permitted by your organization's Windows policy.");
            await initialization;
            Console.WriteLine("WebView2 control ready.");
            view.CoreWebView2.Settings.AreDevToolsEnabled = false;
            view.CoreWebView2.Settings.AreDefaultContextMenusEnabled = false;
            view.CoreWebView2.Settings.IsStatusBarEnabled = false;
            view.CoreWebView2.Settings.IsWebMessageEnabled = false;
            view.CoreWebView2.NavigationStarting += (sender, args) => {
                if (!IsLocal(args.Uri) && !args.Uri.StartsWith("blob:" + home.GetLeftPart(UriPartial.Authority) + "/", StringComparison.Ordinal)) args.Cancel = true;
            };
            view.CoreWebView2.NewWindowRequested += (sender, args) => { args.Handled = true; };
            view.CoreWebView2.PermissionRequested += (sender, args) => { args.State = CoreWebView2PermissionState.Deny; };
            view.CoreWebView2.DownloadStarting += Download;
            view.CoreWebView2.ProcessFailed += (sender, args) => {
                Fail("The application display stopped. Reopen the application. Check migration reports before retrying work.");
            };
            view.CoreWebView2.NavigationCompleted += async (sender, args) => {
                if (!args.IsSuccess) { Fail("The application page could not load. Reopen the application."); return; }
                status.Text = "Ready";
                if (smokeDirectory != null && !smokeStarted) { smokeStarted = true; await SmokeTest(); }
            };
            view.CoreWebView2.Navigate(home.ToString());
        }
        catch (WebView2RuntimeNotFoundException)
        {
            Fail("Microsoft Edge WebView2 Runtime is required.\nAsk IT to install the Microsoft Evergreen WebView2 Runtime, then reopen the application.\nhttps://developer.microsoft.com/microsoft-edge/webview2");
        }
        catch (Exception error) { Console.WriteLine(error); Fail("Unable to open the application window: " + error.Message); }
    }

    private void Download(object sender, CoreWebView2DownloadStartingEventArgs args)
    {
        var deferral = args.GetDeferral();
        args.Handled = true;
        BeginInvoke(new Action(() => {
            try
            {
                if (smokeDirectory != null)
                {
                    args.ResultFilePath = Path.Combine(smokeDirectory, "migration.zip");
                    args.DownloadOperation.StateChanged += (s, e) => {
                        if (args.DownloadOperation.State == CoreWebView2DownloadState.Completed) downloadFinished.TrySetResult(true);
                        if (args.DownloadOperation.State == CoreWebView2DownloadState.Interrupted) downloadFinished.TrySetResult(false);
                    };
                    return;
                }
                using (var dialog = new SaveFileDialog())
                {
                    dialog.Title = "Save migration package";
                    dialog.FileName = Path.GetFileName(args.ResultFilePath);
                    dialog.Filter = "ZIP archive (*.zip)|*.zip|All files (*.*)|*.*";
                    dialog.OverwritePrompt = true;
                    if (dialog.ShowDialog(this) == DialogResult.OK) {
                        args.ResultFilePath = dialog.FileName;
                        var operation = args.DownloadOperation;
                        string filename = dialog.FileName;
                        status.Text = "Saving " + Path.GetFileName(filename) + "...";
                        operation.StateChanged += (s, e) => {
                            if (IsDisposed) return;
                            if (operation.State == CoreWebView2DownloadState.Completed) status.Text = "Saved: " + filename;
                            if (operation.State == CoreWebView2DownloadState.Interrupted) status.Text = "Download interrupted. Please save the package again.";
                        };
                    }
                    else args.Cancel = true;
                }
            }
            catch (Exception error) { args.Cancel = true; MessageBox.Show(this, error.Message, "Download failed"); }
            finally { deferral.Complete(); }
        }));
    }

    private void Fail(string message)
    {
        Environment.ExitCode = 1;
        if (smokeDirectory != null) { Directory.CreateDirectory(smokeDirectory); File.WriteAllText(Path.Combine(smokeDirectory, "failure.txt"), message); }
        else MessageBox.Show(this, message, Text, MessageBoxButtons.OK, MessageBoxIcon.Error);
        allowClose = true; Close();
    }

    private async Task SmokeTest()
    {
        try
        {
            Directory.CreateDirectory(smokeDirectory);
            var result = await view.CoreWebView2.ExecuteScriptAsync(@"(() => {
              if (!document.getElementById('analyse') || document.getElementById('askAi')) throw Error('Unexpected interface');
              if (!document.getElementById('folder').hasAttribute('webkitdirectory')) throw Error('Folder upload missing');
              window.desktopTest = 'running';
              (async () => {
                let id;
                try {
                  const headers = {'Content-Type':'application/json','X-Migration-Client':'migration-ui'};
                  const response = await fetch('/api/plans',{method:'POST',headers,body:JSON.stringify({sourceDialect:'ORACLE',targetDialect:'POSTGRESQL',targetSchema:'public',sql:'CREATE TABLE demo (id NUMBER(5)); INSERT INTO demo VALUES (1);'})});
                  if (!response.ok) throw Error('Plan failed');
                  const plan = await response.json(); id=plan.id;
                  if (plan.issues.length || plan.tables.length !== 1) throw Error('Invalid conversion');
                  const zip = await fetch('/api/plans/'+id+'/download',{method:'POST',headers,body:JSON.stringify({demo:'CREATE_AND_LOAD'})});
                  if (!zip.ok) throw Error('Export failed');
                  save(await zip.blob(),'migration.zip');
                  window.desktopTest='passed';
                } catch (e) { window.desktopTest=String(e); }
                finally { if (id) await fetch('/api/plans/'+id,{method:'DELETE',headers:{'X-Migration-Client':'migration-ui'}}); }
              })();
              return 'started';
            })()");
            if (result != "\"started\"") throw new Exception("Embedded script did not start: " + result);
            for (int i = 0; i < 100; i++)
            {
                await Task.Delay(100);
                result = await view.CoreWebView2.ExecuteScriptAsync("window.desktopTest");
                if (result != "\"running\"") break;
            }
            if (result != "\"passed\"") throw new Exception("Embedded conversion failed: " + result);
            if (await Task.WhenAny(downloadFinished.Task, Task.Delay(10000)) != downloadFinished.Task || !downloadFinished.Task.Result)
                throw new Exception("Embedded ZIP download failed.");
            using (var screenshot = File.Create(Path.Combine(smokeDirectory, "window.png")))
                await view.CoreWebView2.CapturePreviewAsync(CoreWebView2CapturePreviewImageFormat.Png, screenshot);
            File.WriteAllText(Path.Combine(smokeDirectory, "passed.txt"), "Embedded interface, folder input, conversion, blob ZIP download and shutdown passed.");
            allowClose = true; Close();
        }
        catch (Exception error) { Fail(error.ToString()); }
    }
}
