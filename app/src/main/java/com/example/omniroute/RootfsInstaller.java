package com.example.omniroute;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class RootfsInstaller {
    public interface Progress {
        void onProgress(String message);
    }

    private RootfsInstaller() {}

    public static String assetVersion(Context context) {
        try (InputStream input = context.getAssets().open(AppPaths.VERSION_ASSET);
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if (line != null && !line.trim().isEmpty()) {
                return line.trim();
            }
        } catch (Exception ignored) {
        }
        return AppPaths.DEFAULT_VERSION;
    }

    public static String installedVersion(Context context) {
        File marker = AppPaths.versionMarker(context);
        if (!marker.isFile()) {
            return null;
        }
        try (InputStream input = new java.io.FileInputStream(marker);
             java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream()) {
            byte[] chunk = new byte[1 << 10];
            int count;
            while ((count = input.read(chunk)) > 0) {
                buffer.write(chunk, 0, count);
            }
            String value = new String(buffer.toByteArray(), StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? null : value;
        } catch (Exception exception) {
            return null;
        }
    }

    public static File activeRootfs(Context context) {
        String version = installedVersion(context);
        if (version == null) {
            return null;
        }
        File dir = AppPaths.rootfsDir(context, version);
        // Validate the files the server actually execs, not a marker file: a
        // half-finished extraction must not look "active".
        return RuntimeEnvironment.validate(dir) == null ? dir : null;
    }

    /**
     * Returns a usable rootfs, or {@code null} when the app has no runtime yet.
     *
     * <p>The APK no longer bundles a rootfs: a trimmed archive baked into
     * assets shipped hundreds of megabytes of dead weight and still lacked the
     * optional dependencies that make several providers work. The runtime is
     * now always supplied by {@link #importFrom}, so this method only reports
     * whether an import has happened. It never extracts anything and never
     * throws for a missing runtime: a first launch has nothing to unpack, and
     * that is a normal state the UI explains rather than an error.
     *
     * <p>The assets path is still honoured if an archive happens to be present
     * (useful for a locally built APK with a runtime baked in), but its absence
     * is not a failure.
     */
    public static File ensureInstalled(Context context, Progress progress) {
        File active = activeRootfs(context);
        if (active != null) {
            progress.onProgress("Rootfs " + installedVersion(context) + " already installed");
            return active;
        }

        String target = assetVersion(context);
        if (AppPaths.DEFAULT_VERSION.equals(target) || !hasBundledArchive(context)) {
            // Nothing bundled and nothing imported yet: the caller decides how
            // to surface this (the service records a status, the settings
            // screen offers the import action).
            progress.onProgress("未安装运行时，请先导入全量包");
            return null;
        }

        File staging = AppPaths.stagingDir(context);
        deleteRecursively(staging);
        staging.mkdirs();

        progress.onProgress("Extracting rootfs " + target);
        File archive = AppPaths.stagingArchive(context);
        copyAsset(context, AppPaths.ROOTFS_ASSET, archive);
        untar(archive, staging, progress);
        archive.delete();

        String problem = RuntimeEnvironment.validate(staging);
        if (problem != null) {
            deleteRecursively(staging);
            progress.onProgress("内置 rootfs 不完整：" + problem);
            return null;
        }

        File destination = AppPaths.rootfsDir(context, target);
        deleteRecursively(destination);
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("failed to create rootfs parent directory");
        }
        if (!staging.renameTo(destination)) {
            throw new IllegalStateException("failed to promote staged rootfs");
        }

        writeVersionMarker(context, target);
        pruneOldVersions(context, target);
        ensureRuntimeEnv(destination, progress);
        progress.onProgress("Rootfs " + target + " installed");
        return destination;
    }

    /** True when an archive is actually present under the assets rootfs path. */
    private static boolean hasBundledArchive(Context context) {
        try (InputStream ignored = context.getAssets().open(AppPaths.ROOTFS_ASSET)) {
            return true;
        } catch (Exception absent) {
            return false;
        }
    }

    /**
     * Promotes an already-extracted staging tree to the active rootfs.
     *
     * <p>Used by {@link RootfsImageInstaller}, which assembles the runtime
     * from the official container image instead of unpacking an archive. The
     * tree is validated, its executables are repaired and the directory is
     * promoted atomically, exactly like the archive path does, so a failed
     * download can never leave a half-installed runtime behind.
     *
     * @param context  Android context
     * @param staging  extracted rootfs tree
     * @param version  version identifier recorded in the marker
     * @param progress progress callback
     * @return the installed rootfs directory
     */
    public static File commitStaging(Context context, File staging, String version,
            Progress progress) {
        if (staging == null || !staging.isDirectory()) {
            throw new IllegalStateException("暂存目录不存在");
        }
        String problem = RuntimeEnvironment.validate(staging);
        if (problem != null) {
            deleteRecursively(staging);
            throw new IllegalStateException("运行时不完整：" + problem);
        }

        repairExecutables(staging);

        File destination = AppPaths.rootfsDir(context, version);
        deleteRecursively(destination);
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("无法创建 rootfs 目录");
        }
        if (!staging.renameTo(destination)) {
            deleteRecursively(staging);
            throw new IllegalStateException("提升暂存目录失败");
        }

        writeVersionMarker(context, version);
        writeImportMarker(context, version);
        pruneOldVersions(context, version);
        ensureRuntimeEnv(destination, progress);
        progress.onProgress("运行时已安装：" + version);
        return destination;
    }

    /**
     * Installs a full rootfs archive chosen by the user from external storage.
     *
     * <p>Differs from {@link #ensureInstalled} in exactly two ways: the source
     * is a user-picked file rather than the bundled asset, and the version is
     * derived from the import action rather than read from assets. Extraction,
     * validation, atomic promotion and old-version pruning are shared with the
     * bundled path, so any optional dependency present in the full archive
     * (playwright, wreq-js, onnxruntime) becomes available after an import.
     *
     * <p>Because the marker is rewritten to the {@code import-*} version, the
     * next launch's {@code ensureInstalled} sees that the assets version no
     * longer matches and will not silently overwrite the imported tree.
     */
    public static File importFrom(Context context, File archive, Progress progress) {
        if (archive == null || !archive.isFile()) {
            throw new IllegalStateException("导入文件不存在");
        }
        String name = archive.getName().toLowerCase(java.util.Locale.US);
        if (!name.endsWith(".tar.gz") && !name.endsWith(".tgz") && !name.endsWith(".gz")) {
            throw new IllegalStateException("请选择 .tar.gz / .tgz 全量包");
        }

        String version = "import-"
                + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                        .format(new java.util.Date());

        File staging = AppPaths.importStagingDir(context);
        deleteRecursively(staging);
        // mkdirs() returns false when the directory already exists, so a stale
        // staging tree left behind by an earlier failed import would make this
        // look like a creation failure even though the path is usable. Accept
        // an existing directory and only fail when the path is genuinely
        // unusable (a file in the way, or no space left on the volume).
        if (!staging.isDirectory() && !staging.mkdirs()) {
            File parent = staging.getParentFile();
            long freeMb = parent == null ? -1 : parent.getUsableSpace() / (1024 * 1024);
            String reason = staging.exists()
                    ? "路径已被非目录占用：" + staging.getAbsolutePath()
                    : "可用空间不足或权限被拒：" + staging.getAbsolutePath()
                            + (freeMb >= 0 ? "（剩余 " + freeMb + " MB）" : "");
            throw new IllegalStateException("无法创建导入暂存目录 — " + reason);
        }

        progress.onProgress("解包全量包…");
        untar(archive, staging, progress);

        String problem = RuntimeEnvironment.validate(staging);
        if (problem != null) {
            deleteRecursively(staging);
            throw new IllegalStateException("全量包不完整：" + problem);
        }

        repairExecutables(staging);

        File destination = AppPaths.rootfsDir(context, version);
        deleteRecursively(destination);
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("无法创建 rootfs 目录");
        }
        if (!staging.renameTo(destination)) {
            deleteRecursively(staging);
            throw new IllegalStateException("提升暂存目录失败");
        }

        writeVersionMarker(context, version);
        writeImportMarker(context, version);
        pruneOldVersions(context, version);
        ensureRuntimeEnv(destination, progress);
        progress.onProgress("全量包已导入：" + version);
        return destination;
    }

    /** Returns the imported rootfs version marker, or null when none exists. */
    public static String importedVersion(Context context) {
        File marker = AppPaths.importMarker(context);
        if (!marker.isFile()) {
            return null;
        }
        try (InputStream input = new java.io.FileInputStream(marker);
             java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream()) {
            byte[] chunk = new byte[1 << 10];
            int count;
            while ((count = input.read(chunk)) > 0) {
                buffer.write(chunk, 0, count);
            }
            String value = new String(buffer.toByteArray(), StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? null : value;
        } catch (Exception exception) {
            return null;
        }
    }

    /**
     * Forces the executable bit on the handful of files the runtime needs.
     *
     * <p>A tar header carries mode bits, but restoring them is not reliable:
     * the tree is written by the app uid, and on some Android filesystems the
     * set is accepted and then ignored. proot then fails at the very first
     * exec with {@code execve("/usr/bin/sh"): Permission denied}, which looks
     * like a proot fault but is really a mode bit. This pass runs after
     * extraction and re-applies 0755 to the shell, node and the entrypoint,
     * plus execute permission on the directories needed to reach them.
     */
    private static void repairExecutables(File rootfs) {
        String[] dirs = {
                "", "usr", "usr/bin", "usr/local", "usr/local/bin",
                "usr/lib", "opt", "opt/omniroute", "bin", "lib", "tmp",
        };
        for (String rel : dirs) {
            File dir = rel.isEmpty() ? rootfs : new File(rootfs, rel);
            if (dir.isDirectory()) {
                chmod(dir, 0755);
            }
        }
        String[] files = {
                "usr/bin/dash", "usr/bin/sh", "usr/bin/bash",
                "usr/local/bin/node", "entrypoint.sh",
        };
        for (String rel : files) {
            File file = new File(rootfs, rel);
            if (file.exists()) {
                chmod(file, 0755);
            } else {
                android.util.Log.w("RootfsInstaller", "expected executable missing: " + rel);
            }
        }
    }

    private static void chmod(File target, int mode) {
        try {
            android.system.Os.chmod(target.getAbsolutePath(), mode);
        } catch (Exception refused) {
            android.util.Log.w("RootfsInstaller", "chmod failed: " + target, refused);
        }
    }

    /**
     * Dashboard password seeded into a runtime that arrives without a .env.
     *
     * <p>OmniRoute refuses to finish first-run onboarding from a peer it cannot
     * prove is loopback until a one-time bootstrap token is pasted back in. An
     * embedded WebView is exactly such a peer: its requests carry no forwarding
     * headers, so the locality check classifies it as remote even though the
     * dashboard was reached over 127.0.0.1, and the wizard becomes unreachable
     * without adb. Pre-seeding a password makes that gate a no-op, which is why
     * an npm install of the same version never showed it: the upstream
     * postinstall runs sync-env and creates the file.
     *
     * <p>The value is a documented default rather than a secret: the service
     * binds 127.0.0.1 unless the user opts into LAN mode, the file lives in
     * app-private storage, and the dashboard offers a password change. Anyone
     * who can read this constant can already read the .env it is written to.
     */
    public static final String DEFAULT_DASHBOARD_PASSWORD = "omniroute";

    /**
     * Writes a minimal .env when the runtime does not already carry one.
     *
     * <p>Only the initial password is seeded. JWT_SECRET and API_KEY_SECRET are
     * generated and persisted by the server itself on first boot, so adding
     * them here would duplicate a mechanism the runtime already has.
     *
     * <p>An existing .env is never touched: an operator who already configured
     * the runtime must not have their settings replaced by an import.
     */
    private static void ensureRuntimeEnv(File rootfs, Progress progress) {
        File env = new File(rootfs, "opt/omniroute/.env");
        if (env.isFile()) {
            return;
        }
        File parent = env.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            progress.onProgress("无法创建 opt/omniroute，跳过 .env");
            return;
        }
        String body = "# Written by the Android wrapper when this runtime was imported.\n"
                + "# OmniRoute asks for a one-time bootstrap token on first run whenever no\n"
                + "# dashboard password is configured and the peer is not recognised as\n"
                + "# loopback; a WebView request carries no forwarding headers, so it lands\n"
                + "# in the remote branch and the wizard cannot be completed without adb.\n"
                + "# Seeding the password up front makes that gate a no-op.\n"
                + "# Change it from Dashboard > Settings > Security.\n"
                + "INITIAL_PASSWORD=" + DEFAULT_DASHBOARD_PASSWORD + "\n";
        try (FileOutputStream output = new FileOutputStream(env)) {
            output.write(body.getBytes(StandardCharsets.UTF_8));
            progress.onProgress("已写入初始仪表盘密码");
        } catch (Exception exception) {
            progress.onProgress("写入 .env 失败：" + exception.getMessage());
        }
    }

    private static void writeImportMarker(Context context, String version) {
        try (FileOutputStream output = new FileOutputStream(AppPaths.importMarker(context))) {
            output.write(version.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("failed to write import marker", exception);
        }
    }

    private static void writeVersionMarker(Context context, String version) {
        File marker = AppPaths.versionMarker(context);
        try (FileOutputStream output = new FileOutputStream(marker)) {
            output.write(version.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("failed to write rootfs version marker", exception);
        }
    }

    private static void pruneOldVersions(Context context, String keep) {
        File[] children = AppPaths.rootfsRoot(context).listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (!child.getName().equals(keep)) {
                deleteRecursively(child);
            }
        }
    }

    private static void copyAsset(Context context, String assetPath, File destination) {
        try (InputStream input = context.getAssets().open(assetPath);
             OutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[1 << 16];
            int count;
            while ((count = input.read(buffer)) > 0) {
                output.write(buffer, 0, count);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("failed to copy rootfs asset", exception);
        }
    }

    private static void untar(File archive, File destination, Progress progress) {
        // Extracted in-process: device tar may be missing, is never guaranteed to
        // support gzip, and cannot restore executable bits reliably.
        try {
            TarExtractor.extract(archive, destination, (entries, current) -> {
                if (progress == null) {
                    return;
                }
                // A null current name marks the end of the stream; report the
                // completion without quoting a file that is no longer being
                // written.
                if (current == null) {
                    progress.onProgress("解包完成，正在校验…");
                } else {
                    progress.onProgress("解包中：" + entries + " 项 — " + current);
                }
            });
        } catch (Exception exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            String detail = cause.getClass().getSimpleName()
                    + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
            // Without the cause a failed import shows only "failed to extract
            // rootfs", which is undiagnosable from the phone. Keep the real
            // reason in the message the status bar renders.
            throw new IllegalStateException("failed to extract rootfs — " + detail, exception);
        }
    }

    /**
     * Deletes a tree, retrying the unlink and reporting what could not be removed.
     *
     * <p>The previous version called {@code delete()} once and fell back to
     * {@code deleteOnExit()} on failure. That fallback is useless on Android: it
     * only fires on a normal JVM shutdown, which a long-lived app process never
     * performs. A stale staging tree therefore survived an earlier failed import
     * and made the next {@code mkdirs()} return false, surfacing as
     * "无法创建导入暂存目录". Retry with a short backoff, and leave a log line
     * naming whatever is still in the way so a repeat failure is diagnosable.
     */
    public static void deleteRecursively(File target) {
        if (target == null) {
            return;
        }
        // exists() is false for a dangling symlink, but the link itself still
        // has to be unlinked or the stale tree never disappears.
        boolean symlink = java.nio.file.Files.isSymbolicLink(target.toPath());
        if (!target.exists() && !symlink) {
            return;
        }
        if (target.isDirectory() && !symlink) {
            // A directory left read-only (0555) by a tar header blocks deletion
            // of its own children: unlink needs write+execute on the parent.
            // Extraction is done as the app uid, not as the guest root, so a
            // staging tree from a failed import would otherwise survive every
            // cleanup attempt and make the next import fail to create its
            // staging directory. Restore owner access before descending.
            target.setWritable(true, true);
            target.setExecutable(true, true);
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        for (int attempt = 0; attempt < 3 && target.exists(); attempt++) {
            if (target.delete()) {
                return;
            }
            try {
                Thread.sleep(120L * (attempt + 1));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (target.exists()) {
            android.util.Log.w("RootfsInstaller", "failed to delete " + target);
        }
    }
}
