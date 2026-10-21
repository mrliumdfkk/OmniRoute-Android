package com.example.omniroute;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class OmniRouteServer {
    private static final String TAG = "OmniRouteServer";

    /** Fallback when settings cannot be read; the live value comes from AppSettings. */
    public static final int DEFAULT_PORT = AppSettings.DEFAULT_PORT;

    private final Context context;
    private Process process;
    private Thread logPump;
    /**
     * Startup facts captured before the spawn. Kept in memory so the failure
     * message can quote them directly: proot prints about nine lines of its own
     * advice after the real error, which pushed every diagnostic out of the
     * log-tail window and made a failed start look identical to the previous,
     * unfixed one.
     */
    private final List<String> diagnostics = new ArrayList<>();

    public OmniRouteServer(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Port the server is configured to use. */
    public static int port(Context context) {
        return AppSettings.port(context);
    }

    public synchronized boolean isRunning() {
        return ProcessCompat.isAlive(process);
    }

    public synchronized void start() {
        if (isRunning()) {
            return;
        }

        ServerState.record(context, ServerState.STATUS_STARTING, "preparing rootfs");
        File rootfs = RootfsInstaller.ensureInstalled(context, message ->
                ServerState.record(context, ServerState.STATUS_STARTING, message));
        File dataDir = AppPaths.dataDir(context);
        if (!dataDir.isDirectory() && !dataDir.mkdirs()) {
            throw new IllegalStateException("failed to create data dir");
        }

        // No bundled rootfs any more: a first launch has nothing to unpack, and
        // that is a normal state the UI explains rather than a failure. Record a
        // message the main screen can show, and stop without spawning proot.
        if (rootfs == null) {
            ServerState.record(context, ServerState.STATUS_STOPPED,
                    "未安装运行时：请在设置中导入全量包");
            appendLog("no rootfs installed; waiting for an import");
            return;
        }

        String problem = RuntimeEnvironment.validate(rootfs);
        if (problem != null) {
            ServerState.record(context, ServerState.STATUS_ERROR, problem);
            throw new IllegalStateException(problem);
        }

        File proot = RuntimeEnvironment.nativeLibrary(context, RuntimeEnvironment.PROOT_EXEC);
        File loader = RuntimeEnvironment.nativeLibrary(context, RuntimeEnvironment.PROOT_LOADER);
        if (!proot.isFile()) {
            throw new IllegalStateException("libproot_exec.so missing from nativeLibraryDir");
        }
        if (!loader.isFile()) {
            throw new IllegalStateException("libproot_loader.so missing from nativeLibraryDir");
        }

        // Executed files lose their mode bit across extraction on some devices;
        // restore it before spawning or execve fails with EACCES.
        //
        // This must be a direct chmod. File.setExecutable() routes through
        // libcore's stat-then-chmod helper, which can return true while the bit
        // stays clear; that is exactly how /usr/bin/dash ended up mode 644 and
        // proot died with `execve("/usr/bin/sh"): Permission denied` before the
        // entrypoint ever ran. A failed chmod is visible, so the repair either
        // verifiably works or reports why it did not.
        // Unconditional chmod. File.canExecute() reports only the file's own
        // bits and ignores whether an ancestor directory is searchable, so it
        // can answer true while execve still fails with EACCES. A chmod is
        // idempotent and costs one syscall, so the mode is simply forced for
        // every path the guest might exec.
        int forced = 0;
        int refused = 0;
        for (File executable : RuntimeEnvironment.executablesToRepair(rootfs, context)) {
            String path = executable.getAbsolutePath();
            if (!executable.isFile() && !java.nio.file.Files.isSymbolicLink(executable.toPath())) {
                continue;
            }
            try {
                android.system.Os.chmod(path, 0755);
                forced++;
            } catch (Exception failure) {
                refused++;
                appendLog("chmod refused for " + path + ": " + failure);
            }
        }
        String pass = "exec-bit pass: forced 0755 on " + forced + " path(s), "
                + refused + " refused";
        appendLog(pass);
        diagnostics.add(pass);

        // execve returns EACCES both for a file whose own execute bit is clear
        // and for one reached through a directory that is not searchable, and
        // proot's message cannot tell the two apart. Log what the kernel will
        // actually see, before the spawn, for the paths proot touches first.
        for (String relative : new String[] {
                RuntimeEnvironment.ROOTFS_SHELL,
                "usr/bin/sh",
                "usr/local/bin/node",
                "entrypoint.sh",
                "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"}) {
            String line = describeExecPath(new File(rootfs, relative));
            appendLog(line);
            diagnostics.add(line);
        }

        ServerState.record(context, ServerState.STATUS_STARTING, "launching proot");
        // PROOT_LOADER is the whole point: it hands PRoot a prebuilt loader file
        // so it never creates an anonymous executable mapping, which Android's
        // seccomp/W^X policy rejects with SIGSYS.
        List<String> command = new ArrayList<>();
        command.add(proot.getAbsolutePath());
        command.add("--root-id");
        command.add("--link2symlink");
        command.add("--kill-on-exit");
        command.add("-r");
        command.add(rootfs.getAbsolutePath());
        command.add("-w");
        command.add("/");
        // Persistent state survives rootfs upgrades because it lives in filesDir.
        command.add("-b");
        command.add(dataDir.getAbsolutePath() + ":" + RuntimeEnvironment.GUEST_DATA_DIR);
        for (String path : RuntimeEnvironment.bindPaths()) {
            command.add("-b");
            command.add(path);
        }
        command.add("/bin/sh");
        command.add("/entrypoint.sh");

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(AppPaths.dataDir(context));
        int port = AppSettings.port(context);
        builder.environment().put("PROOT_LOADER", loader.getAbsolutePath());
        builder.environment().put("PROOT_TMP_DIR", dataDir.getAbsolutePath());
        builder.environment().put("TMPDIR", dataDir.getAbsolutePath());
        builder.environment().put("HOME", RuntimeEnvironment.GUEST_DATA_DIR);
        builder.environment().put("DATA_DIR", RuntimeEnvironment.GUEST_DATA_DIR);
        builder.environment().put("PORT", String.valueOf(port));
        // OmniRoute reads HOSTNAME for its bind address. Binding 0.0.0.0 exposes
        // the dashboard to the LAN, which is opt-in only.
        builder.environment().put("HOSTNAME",
                AppSettings.listenAll(context) ? "0.0.0.0" : "127.0.0.1");
        // Cap V8's old space so a heavy request degrades instead of inviting the
        // Android low-memory killer to take the whole process down.
        builder.environment().put("NODE_OPTIONS",
                "--max-old-space-size=" + AppSettings.heapMb(context));
        builder.environment().put("NODE_ENV", "production");
        // Seed the dashboard password through the process environment rather
        // than relying on a .env file. OmniRoute opens the first-run wizard with
        // a one-time bootstrap token whenever no password is configured and the
        // peer is not provably loopback; a WebView request carries no forwarding
        // headers, so it lands in the remote branch and the wizard cannot be
        // completed without adb. The check is literally
        // `!settings.hasPassword && !process.env.INITIAL_PASSWORD`, so a value
        // here disables the gate outright.
        //
        // The environment is used instead of writing opt/omniroute/.env because
        // the standalone server's working directory is opt/omniroute/dist, and
        // whether it loads a .env from its parent is an implementation detail of
        // the Next.js runtime, not a contract. This also repairs a runtime that
        // was imported before the wrapper wrote one.
        builder.environment().put("INITIAL_PASSWORD",
                RootfsInstaller.DEFAULT_DASHBOARD_PASSWORD);
        // Tell the runtime what origin the browser is actually using.
        //
        // Dashboard writes are guarded by a same-origin check plus a
        // session-bound CSRF token. An embedded WebView does not present the
        // same Origin/Referer signals a normal browser does, so the guard
        // rejects writes with "Invalid request origin" even though the request
        // is genuinely same-origin over loopback. The upstream default for
        // NEXT_PUBLIC_BASE_URL is http://localhost:20128; without it the
        // runtime has no canonical origin to compare against, and the only
        // signal left is whatever the WebView happened to send.
        //
        // Both variables are set: NEXT_PUBLIC_BASE_URL is the documented
        // default, and OMNIROUTE_PUBLIC_BASE_URL is the highest-priority
        // override that also feeds public-origin validation. The host is
        // loopback because the dashboard is only ever reached at 127.0.0.1 in
        // this packaging (LAN mode is opt-in and changes the bind address, not
        // the origin the WebView uses).
        String publicBaseUrl = "http://127.0.0.1:" + port;
        builder.environment().put("NEXT_PUBLIC_BASE_URL", publicBaseUrl);
        builder.environment().put("OMNIROUTE_PUBLIC_BASE_URL", publicBaseUrl);
        builder.redirectErrorStream(true);
        appendLog("--- server start " + new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                .format(new Date()) + " ---");
        appendLog("exec: " + proot.getAbsolutePath() + " -r " + rootfs.getAbsolutePath());
        appendLog("loader: " + loader.getAbsolutePath());
        appendLog("port: " + port + "  host: "
                + (AppSettings.listenAll(context) ? "0.0.0.0" : "127.0.0.1")
                + "  heap: " + AppSettings.heapMb(context) + "MB");
        try {
            process = builder.start();
        } catch (Exception exception) {
            ServerState.record(context, ServerState.STATUS_ERROR, "spawn failed: " + exception.getMessage());
            throw new IllegalStateException("failed to start proot", exception);
        }
        startLogPump(process);

        if (awaitReady(90, port)) {
            ServerState.record(context, ServerState.STATUS_RUNNING, "listening on " + port);
        } else {
            // The exit code alone is useless for diagnosis: proot forwards the
            // guest's exit status verbatim, so a node crash and a missing shell
            // both surface as the same bare number. The reason is in the log the
            // log pump just wrote, so quote its tail in the status the UI shows.
            String detail = exitDetail(process);
            StringBuilder message =
                    new StringBuilder("server did not become ready").append(detail);
            for (String line : diagnostics) {
                message.append('\n').append(line);
            }
            String tail = logTail(context, 40);
            if (!tail.isEmpty()) {
                message.append('\n').append(tail);
            }
            ServerState.record(context, ServerState.STATUS_ERROR, message.toString());
        }
    }

    /**
     * Returns the last {@code maxLines} lines of the server log.
     *
     * <p>Read from the end of the file rather than by streaming the whole thing:
     * the log is append-only and can be large after repeated restarts, while the
     * only part that explains a failure is the tail.
     */
    private static String logTail(Context context, int maxLines) {
        File log = AppPaths.logFile(context);
        if (!log.isFile()) {
            return "";
        }
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(log, "r")) {
            long length = file.length();
            int window = (int) Math.min(length, 8 * 1024);
            byte[] buffer = new byte[window];
            file.seek(length - window);
            file.readFully(buffer);
            String text = new String(buffer, StandardCharsets.UTF_8);
            String[] lines = text.split("\n");
            int start = Math.max(0, lines.length - maxLines);
            StringBuilder builder = new StringBuilder();
            for (int index = start; index < lines.length; index++) {
                String line = lines[index].trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(line);
            }
            return builder.toString();
        } catch (Exception exception) {
            return "";
        }
    }

    /**
     * Records what the kernel will actually see for a path proot is about to
     * exec.
     *
     * <p>{@code execve} reports EACCES both when the file's own execute bit is
     * clear and when any ancestor directory lacks search permission, and proot's
     * "Permission denied" message cannot tell the two apart. Logging the mode
     * bits of the file, the mode of its symlink target, and the mode of each
     * ancestor directory makes the difference visible without guessing — the
     * previous round could not distinguish "the bit was never set" from "the
     * bit is set but a parent directory is 0644", and both produce the same
     * line in the status bar.
     */
    private static String describeExecPath(File file) {
        StringBuilder builder = new StringBuilder("exec-check: ").append(file.getAbsolutePath());
        try {
            if (java.nio.file.Files.isSymbolicLink(file.toPath())) {
                builder.append(" -> ").append(java.nio.file.Files.readSymbolicLink(file.toPath()));
            }
        } catch (Exception unreadable) {
            builder.append(" -> (link target unreadable)");
        }
        try {
            // stat() follows symlinks, so this is the mode execve acts on.
            android.system.StructStat resolved = android.system.Os.stat(file.getAbsolutePath());
            builder.append(" | resolved=")
                    .append(Integer.toOctalString(resolved.st_mode & 07777));
        } catch (Exception missing) {
            builder.append(" | resolved=stat-failed(")
                    .append(missing.getClass().getSimpleName()).append(')');
        }
        try {
            android.system.StructStat direct = android.system.Os.lstat(file.getAbsolutePath());
            builder.append(" | link=").append(Integer.toOctalString(direct.st_mode & 07777));
        } catch (Exception ignored) {
            builder.append(" | link=stat-failed");
        }
        // One 0644 ancestor is enough to make the whole path unexecutable, and
        // /opt was recorded as drwx------ in this archive, which is exactly the
        // kind of entry that only bites when the tree is written by a non-root
        // uid.
        File parent = file.getParentFile();
        int levels = 0;
        while (parent != null && levels < 8) {
            try {
                android.system.StructStat stat = android.system.Os.stat(parent.getAbsolutePath());
                builder.append(" | ").append(parent.getName())
                        .append('=').append(Integer.toOctalString(stat.st_mode & 07777));
            } catch (Exception refused) {
                builder.append(" | ").append(parent.getName()).append("=stat-failed");
            }
            parent = parent.getParentFile();
            levels++;
        }
        return builder.toString();
    }

    /**
     * A process that dies before writing anything otherwise looks identical to
     * a slow start, so always surface the exit code and signal in the status.
     */
    private static String exitDetail(Process target) {
        if (target == null) {
            return "";
        }
        try {
            int code = target.exitValue();
            // Android reports a signal death as 128 + signal on most builds.
            if (code > 128) {
                int signal = code - 128;
                return " (proot killed by signal " + signal + signalHint(signal) + ")";
            }
            return " (proot exited with code " + code + ")";
        } catch (IllegalThreadStateException stillRunning) {
            return " (proot still running)";
        }
    }

    /**
     * Names the signals that actually show up here. SIGSYS in particular means
     * the app's seccomp policy rejected a syscall, which is not obvious from
     * the number alone.
     */
    private static String signalHint(int signal) {
        switch (signal) {
            case 31:
                return " = SIGSYS: a syscall was blocked by the app's seccomp policy";
            case 11:
                return " = SIGSEGV";
            case 9:
                return " = SIGKILL";
            case 6:
                return " = SIGABRT";
            default:
                return "";
        }
    }


    public synchronized void stop() {
        Process stopping = process;
        Thread stoppingLogPump = logPump;

        if (stopping == null) {
            process = null;
            logPump = null;
            ServerState.record(context, ServerState.STATUS_STOPPED, "stopped");
            return;
        }

        // Closing the child causes its pipe to reach EOF, which is the only
        // reliable way to unblock BufferedReader.readLine() on every API level.
        ProcessCompat.terminate(stopping);
        process = null;
        logPump = null;

        if (stoppingLogPump != null) {
            try {
                stoppingLogPump.join(5000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            if (ProcessCompat.isThreadAlive(stoppingLogPump)) {
                stoppingLogPump.interrupt();
            }
        }

        ServerState.record(context, ServerState.STATUS_STOPPED, "stopped");
    }

    public synchronized void restart() {
        stop();
        start();
    }

    private void startLogPump(Process target) {
        File log = AppPaths.logFile(context);
        File parent = log.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        // The server mints a fresh bootstrap token on every start, so a value
        // captured from an earlier run is already dead. Drop it before the pump
        // begins scanning, or the settings screen would offer a token the server
        // has since replaced.
        BootstrapToken.clear(context);
        logPump = new Thread(() -> {
            try (InputStreamReader reader = new InputStreamReader(
                         target.getInputStream(), StandardCharsets.UTF_8);
                 Writer writer = new OutputStreamWriter(
                         new FileOutputStream(log, true), StandardCharsets.UTF_8)) {
                // The server writes startup errors without a trailing newline,
                // so a BufferedReader.readLine() loop blocks forever and the
                // log stays empty on exactly the failures we need to see.
                // Drain raw chars and flush on every read instead.
                char[] buffer = new char[4096];
                int count;
                // The one-time bootstrap token is printed once, near the start of
                // a run, and is then buried under the runtime's own output, so a
                // scan of the finished log's tail finds nothing. Scrape it as the
                // bytes stream past instead, holding a rolling window because a
                // line can straddle two reads, and stop looking once it lands.
                StringBuilder recent = new StringBuilder();
                boolean captured = false;
                while ((count = reader.read(buffer)) != -1) {
                    writer.write(buffer, 0, count);
                    writer.flush();
                    if (captured) {
                        continue;
                    }
                    recent.append(buffer, 0, count);
                    if (recent.length() > 262144) {
                        recent.delete(0, recent.length() - 131072);
                    }
                    String token = BootstrapToken.scan(recent.toString());
                    if (token != null) {
                        BootstrapToken.save(context, token);
                        captured = true;
                        recent.setLength(0);
                    }
                }
            } catch (Exception ignored) {
            }
        }, "omniroute-log");
        logPump.setDaemon(true);
        logPump.start();
    }

    private void appendLog(String text) {
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(AppPaths.logFile(context), true), StandardCharsets.UTF_8)) {
            writer.write(text);
            writer.write('\n');
        } catch (Exception ignored) {
        }
    }

    private boolean awaitReady(int attempts, int port) {
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (process != null && !ProcessCompat.isAlive(process)) {
                return false;
            }
            // Always probe loopback: the server binds there even in LAN mode,
            // and probing the wildcard address is not meaningful.
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return true;
            } catch (Exception ignored) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }
}
