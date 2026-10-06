package com.fingress.migration;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.*;
import javax.swing.JOptionPane;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/** Owns the local server for the lifetime of the embedded Windows interface. */
final class DesktopLauncher {
    private static Process window;

    static Path dataDirectory() {
        String local = System.getenv("LOCALAPPDATA");
        return Path.of(local == null || local.isBlank() ? System.getProperty("user.home") : local,
                "Fingress SQL Migration").toAbsolutePath();
    }

    static void launch(String[] args) {
        System.setProperty("java.awt.headless", "false");
        Path data = dataDirectory();
        ConfigurableApplicationContext context = null;
        int result = 0;
        try {
            Files.createDirectories(data);
            String home = System.getProperty("migration.desktop-home");
            String launcher = System.getProperty("jpackage.app-path");
            if (home == null && launcher == null) throw new IllegalStateException("Use the packaged Windows application for desktop mode.");
            Path root = home == null ? Path.of(launcher).getParent() : Path.of(home);
            Path host = root.resolve("app/desktop/FingressDesktop.exe");
            if (!Files.isRegularFile(host)) throw new IllegalStateException("Desktop components are missing. Extract the entire application ZIP.");
            try (FileChannel channel = FileChannel.open(data.resolve("desktop.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = channel.tryLock()) {
                if (lock == null) {
                    JOptionPane.showMessageDialog(null, "Fingress SQL Migration is already running.\nUse its existing application window.");
                    return;
                }
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    if (window != null && window.isAlive()) window.destroy();
                }, "desktop-window-cleanup"));
                SpringApplication application = new SpringApplication(MigrationApplication.class);
                application.setHeadless(false);
                context = application.run("--server.address=127.0.0.1", "--server.port=0",
                        "--migration.work-dir=" + data.resolve("work"),
                        "--spring.config.import=optional:" + data.resolve("catalog-local.properties").toUri(),
                        "--logging.file.name=" + data.resolve("logs/application.log"));
                int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
                List<String> command = new ArrayList<>(List.of(host.toString(), "http://127.0.0.1:" + port,
                        data.resolve("webview").toString(), Long.toString(ProcessHandle.current().pid())));
                for (String arg : args) if (arg.startsWith("--desktop-smoke-dir=")) {
                    command.add("--smoke-test");
                    command.add(Path.of(arg.substring("--desktop-smoke-dir=".length())).toAbsolutePath().toString());
                }
                window = new ProcessBuilder(command).redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(data.resolve("logs/desktop.log").toFile())).start();
                result = window.waitFor();
            }
        } catch (Exception failure) {
            result = 1;
            try {
                Files.createDirectories(data.resolve("logs"));
                java.io.StringWriter trace = new java.io.StringWriter();
                failure.printStackTrace(new java.io.PrintWriter(trace));
                Files.writeString(data.resolve("logs/launcher-error.log"), trace.toString());
            } catch (Exception ignored) { }
            if (Arrays.stream(args).noneMatch(arg -> arg.startsWith("--desktop-smoke-dir=")))
                JOptionPane.showMessageDialog(null, "The application could not start.\n"
                    + "Use the complete Windows package and check the logs in\n" + data.resolve("logs"),
                    "Fingress SQL Migration", JOptionPane.ERROR_MESSAGE);
        } finally {
            if (context != null) context.close();
        }
        System.exit(result);
    }
}
