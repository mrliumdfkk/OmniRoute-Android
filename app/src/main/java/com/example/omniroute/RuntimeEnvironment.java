package com.example.omniroute;

import android.content.Context;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the executables needed to run OmniRoute inside the bundled rootfs.
 *
 * <p>How proot avoids Android's W^X policy: PRoot normally injects a syscall
 * shim by writing its loader to a temp file and mapping it, and the guest also
 * needs an executable mapping the app seccomp policy refuses. Supplying the
 * loader as an existing file through {@code PROOT_LOADER} sidesteps that
 * entirely, which is why the loader ships as its own tiny static ELF
 * ({@code libproot_loader.so}, 1128 bytes, fixed load address
 * {@code 0x2000000000}) instead of being embedded in the proot binary.
 *
 * <p>This is the same arrangement RikkaHub uses, and it keeps working at
 * {@code targetSdk 37}: no anonymous {@code PROT_EXEC} mapping is ever created,
 * so no SIGSYS.
 *
 * <p>Both ELFs live in {@code nativeLibraryDir} because Android only permits
 * exec of files in that directory plus system locations; anything extracted
 * into {@code filesDir} carries the {@code app_data_file} label and is refused.
 */
final class RuntimeEnvironment {

    /** proot itself, shipped as a native library like RikkaHub ships it. */
    static final String PROOT_EXEC = "libproot_exec.so";
    /** PRoot's syscall-injection loader, referenced via PROOT_LOADER. */
    static final String PROOT_LOADER = "libproot_loader.so";

    /** Directory inside the rootfs that holds the guest runtime. */
    static final String NODE_BIN = "usr/local/bin/node";
    static final String APP_DIR = "opt/omniroute";
    static final String ROOTFS_SHELL = "usr/bin/dash";
    /** Bind target for persistent state; matches the bundled entrypoint.sh. */
    static final String GUEST_DATA_DIR = "/data";

    /** Kernel filesystems worth exposing; each is bound only if it exists. */
    private static final String[] BIND_PATHS = {"/dev", "/proc", "/sys"};

    private RuntimeEnvironment() {}

    static File nativeLibrary(Context context, String name) {
        return new File(context.getApplicationInfo().nativeLibraryDir, name);
    }

    static File nodeBinary(File rootfs) {
        return new File(rootfs, NODE_BIN);
    }

    static File appDirectory(File rootfs) {
        return new File(rootfs, APP_DIR);
    }

    static List<String> bindPaths() {
        List<String> available = new ArrayList<>();
        for (String path : BIND_PATHS) {
            if (new File(path).exists()) {
                available.add(path);
            }
        }
        return available;
    }

    /**
     * Explains what is missing, or returns null when the runtime looks usable.
     * Checked before spawning so a broken extraction reports a concrete cause
     * instead of a silent exit.
     */
    static String validate(File rootfs) {
        if (rootfs == null || !rootfs.isDirectory()) {
            return "rootfs directory is missing";
        }
        File node = nodeBinary(rootfs);
        if (!node.isFile()) {
            return "missing " + NODE_BIN + " (rootfs incomplete)";
        }
        // The entrypoint execs /opt/omniroute/dist/server.js, but archives built
        // by different toolchains have shipped either dist/server.js or a
        // server.js hoisted to the app root. Accept both: this check exists to
        // catch a half-extracted tree, not to pin one packaging layout.
        File distServer = new File(appDirectory(rootfs), "dist/server.js");
        File rootServer = new File(appDirectory(rootfs), "server.js");
        if (!distServer.isFile() && !rootServer.isFile()) {
            return "missing opt/omniroute/dist/server.js (rootfs incomplete)";
        }
        return null;
    }

    /**
     * Files that will be exec'ed and therefore need the executable bit. Android
     * does not preserve mode bits the way a normal filesystem does, and a
     * non-root tar extraction can drop them, so the app repairs them at start.
     */
    static List<File> executablesToRepair(File rootfs, Context context) {
        List<File> targets = new ArrayList<>();
        targets.add(nativeLibrary(context, PROOT_EXEC));
        targets.add(nativeLibrary(context, PROOT_LOADER));
        targets.add(nodeBinary(rootfs));
        // The guest shell must be executable or proot dies with
        // `execve("/usr/bin/sh"): Permission denied` before the
        // entrypoint ever runs. Extraction cannot be trusted to preserve
        // the bit: the app writes the tree under its own uid, and
        // File.setExecutable() silently returns false on some Android
        // filesystems.
        for (String shell : new String[] {
                "usr/bin/dash", "usr/bin/sh", "usr/bin/bash", "entrypoint.sh",
                // The ELF interpreter is opened through open_exec(), which
                // requires an execute bit on the file itself. Debian ships
                // ld-linux at 0644, so a faithful extraction leaves every
                // dynamic ELF unexecutable: execve("/usr/bin/sh") then fails
                // with EACCES even though dash is already 0755. This is the
                // bit every earlier repair pass missed, because the search
                // only covered usr/bin.
                "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
                "lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
                "usr/lib/ld-linux-aarch64.so.1",
                "lib/ld-linux-aarch64.so.1"}) {
            targets.add(new File(rootfs, shell));
        }
        // A bulk extraction that loses the bit loses it across whole
        // directories, not just the shell: entrypoint.sh itself calls mkdir and
        // chmod from /usr/bin, and the guest may exec anything from these
        // directories. They are small on a bookworm minbase (usr/bin holds
        // ~1500 entries), so scanning them keeps every boot path usable without
        // a full tree walk.
        for (String dir : new String[] {
                "usr/bin", "usr/sbin", "usr/local/bin", "usr/local/sbin",
                "usr/lib/aarch64-linux-gnu", "lib/aarch64-linux-gnu",
                "entrypoint.sh"}) {
            File candidate = new File(rootfs, dir);
            if (candidate.isFile()) {
                targets.add(candidate);
                continue;
            }
            File[] children = candidate.listFiles();
            if (children == null) {
                continue;
            }
            for (File child : children) {
                if (child.isFile()) {
                    targets.add(child);
                }
            }
        }
        return targets;
    }
}
