package com.example.omniroute;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Builds a runtime rootfs from the official container image and installs it.
 *
 * <p>The upstream project publishes its runtime as a Linux container image
 * rather than as an Android package, so there is no release asset that the
 * wrapper can simply download and unpack. The image layers are, however, a
 * complete and self-contained root filesystem: a Debian base with the Node
 * runtime in {@code /usr/local/bin/node} and the application in {@code /app}.
 *
 * <p>Two adjustments turn that image into the layout the wrapper expects:
 *
 * <ul>
 *   <li>the application is moved from {@code /app} to {@code /opt/omniroute},
 *       which is the path the server launches and the installer validates;</li>
 *   <li>an {@code entrypoint.sh} is written, because the container image uses
 *       its own entrypoint script that is not part of the application layer.</li>
 * </ul>
 *
 * <p>Layers are applied in the order the manifest lists them, so a later layer
 * overwrites an earlier one, exactly as the container runtime would do.
 */
public final class RootfsImageInstaller {

    private static final String TAG = "RootfsImageInstaller";

    /** Progress callback for the UI. */
    public interface Progress extends RootfsInstaller.Progress {
    }

    /** Byte-level progress callback for the UI. */
    public interface DownloadProgress {
        void onProgress(long downloaded, long total);
    }

    /** Default official source: the upstream container registry. */
    public static final String DEFAULT_REGISTRY = "ghcr.io";
    public static final String DEFAULT_REPOSITORY = "diegosouzapw/omniroute";
    public static final String DEFAULT_TAG = "latest";

    private RootfsImageInstaller() {}

    /**
     * Downloads the official image and installs it as the active rootfs.
     *
     * @param context   Android context
     * @param registry  registry host, e.g. {@code ghcr.io}
     * @param repository repository path inside the registry
     * @param tag       tag to resolve
     * @param progress  message progress callback
     * @param download  byte progress callback
     * @return the installed rootfs version
     */
    public static String installFromRegistry(Context context, String registry,
            String repository, String tag, Progress progress, DownloadProgress download)
            throws IOException {
        OciImageClient client = new OciImageClient(registry, repository);

        progress.onProgress("正在解析官方镜像 " + registry + "/" + repository + ":" + tag);
        OciImageClient.Manifest manifest = client.resolveArm64(tag);
        List<OciImageClient.Layer> layers = manifest.layers;
        long total = manifest.totalCompressedSize();
        progress.onProgress("官方镜像共 " + layers.size() + " 层，"
                + formatSize(total) + "（压缩）");

        File staging = AppPaths.stagingDir(context);
        if (staging.exists() && !deleteRecursively(staging)) {
            throw new IOException("无法清理暂存目录：" + staging.getAbsolutePath());
        }
        if (!staging.mkdirs()) {
            throw new IOException("无法创建暂存目录：" + staging.getAbsolutePath());
        }
        File blobCache = new File(context.getCacheDir(), "oci-blobs");
        if (!blobCache.isDirectory() && !blobCache.mkdirs()) {
            throw new IOException("无法创建缓存目录：" + blobCache.getAbsolutePath());
        }

        long downloadedAcrossLayers = 0L;
        try {
            for (int i = 0; i < layers.size(); i++) {
                OciImageClient.Layer layer = layers.get(i);
                progress.onProgress("第 " + (i + 1) + "/" + layers.size() + " 层："
                        + formatSize(layer.size));

                File blob = new File(blobCache, layer.digest.replace(':', '_'));
                // The layer's own partial file is resumed, so the bytes
                // already on disk must not be counted twice in the overall
                // progress. The holder lets the lambda see the current value.
                final long alreadyOnDisk = blob.isFile() ? blob.length() : 0L;
                final long[] completedBefore = {downloadedAcrossLayers};
                client.downloadBlob(layer.digest, layer.size, blob,
                        (done, layerTotal) -> {
                    long overall = completedBefore[0] - alreadyOnDisk + done;
                    if (download != null) {
                        download.onProgress(overall, total);
                    }
                });
                downloadedAcrossLayers += layer.size;

                progress.onProgress("正在解包第 " + (i + 1) + "/" + layers.size() + " 层");
                extractLayer(blob, staging);
                // The blob is not needed any more; keeping several hundred
                // megabytes of layers around would waste internal storage.
                OciImageClient.deleteQuietly(blob);
            }

            progress.onProgress("正在整理运行时目录");
            String version = assembleRuntime(context, staging, manifest, tag);

            progress.onProgress("正在安装运行时");
            RootfsInstaller.commitStaging(context, staging, version, progress);
            return version;
        } catch (IOException failure) {
            deleteRecursively(staging);
            throw failure;
        } finally {
            deleteRecursively(blobCache);
        }
    }

    /**
     * Unpacks one image layer into {@code staging}.
     *
     * <p>OCI layers are gzip-compressed tar streams. A layer that removes a
     * file records it as a {@code .wh.&lt;name&gt;} marker instead of an
     * empty directory, so the markers are turned into deletions after the
     * extraction: the assembled tree then matches what the container runtime
     * would see.
     */
    private static void extractLayer(File blob, File staging) throws IOException {
        TarExtractor.Progress progress = (entries, current) -> { };
        TarExtractor.extract(blob, staging, progress);
        applyWhiteouts(staging);
    }

    /**
     * Applies the whiteout markers of the layer that was just extracted.
     *
     * <p>A {@code .wh.&lt;name&gt;} marker means "&lt;name&gt; was deleted". The
     * markers are removed after they are applied, because a container runtime
     * never leaves them in the assembled tree either.
     */
    private static void applyWhiteouts(File staging) {
        deleteWhiteouts(staging);
    }

    private static void deleteWhiteouts(File directory) {
        File[] children = directory.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                deleteWhiteouts(child);
                continue;
            }
            String name = child.getName();
            if (!name.startsWith(".wh.")) {
                continue;
            }
            String targetName = name.substring(".wh.".length());
            File target = new File(child.getParentFile(), targetName);
            if (target.exists()) {
                deleteRecursively(target);
            }
            child.delete();
        }
    }

    /**
     * Turns the extracted image tree into the rootfs layout the wrapper runs.
     *
     * <p>The application layer puts the server in {@code /app}; the wrapper
     * launches {@code /opt/omniroute}. The directory is therefore renamed, and
     * an entrypoint script is written because the image's own entrypoint is
     * part of the container build and not of the application layer.
     */
    private static String assembleRuntime(Context context, File staging,
            OciImageClient.Manifest manifest, String tag) throws IOException {
        File appDir = new File(staging, "app");
        File target = new File(staging, "opt/omniroute");
        File optDir = new File(staging, "opt");
        if (!optDir.isDirectory() && !optDir.mkdirs()) {
            throw new IOException("无法创建 opt 目录");
        }
        if (appDir.isDirectory()) {
            if (target.exists() && !deleteRecursively(target)) {
                throw new IOException("无法清理 opt/omniroute");
            }
            if (!appDir.renameTo(target)) {
                // A rename across the staging boundary can fail when the
                // destination exists; fall back to a copy.
                copyTree(appDir, target);
                deleteRecursively(appDir);
            }
        }
        if (!target.isDirectory()) {
            throw new IOException("镜像中没有应用目录（/app）");
        }

        writeEntrypoint(staging, target);
        writeVersionMarker(target, manifest, tag);

        String problem = RuntimeEnvironment.validate(staging);
        if (problem != null) {
            throw new IOException("镜像不是完整的运行时：" + problem);
        }
        return versionFor(manifest, tag);
    }

    /**
     * Writes the entrypoint script.
     *
     * <p>The upstream container image starts the server through
     * {@code node dev/run-standalone.mjs}; proot needs a script it can exec
     * directly, so the wrapper provides one. The script mirrors the upstream
     * entrypoint: it fixes up the base path, then hands over to the server.
     */
    private static void writeEntrypoint(File staging, File appDir) throws IOException {
        String script = "#!/bin/sh\n"
                + "# Entry point for the OmniRoute runtime inside the Android rootfs.\n"
                + "#\n"
                + "# The upstream container image starts the server through\n"
                + "# `node dev/run-standalone.mjs`; the Android wrapper starts it through\n"
                + "# this script instead, because proot needs a script it can exec directly.\n"
                + "set -e\n"
                + "\n"
                + "export PATH=\"/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\"\n"
                + "export NODE_ENV=\"${NODE_ENV:-production}\"\n"
                + "export DATA_DIR=\"${DATA_DIR:-/data}\"\n"
                + "export HOME=\"${HOME:-/data}\"\n"
                + "export PORT=\"${PORT:-20128}\"\n"
                + "export HOSTNAME=\"${HOSTNAME:-127.0.0.1}\"\n"
                + "export TMPDIR=\"${TMPDIR:-/tmp}\"\n"
                + "export XDG_CACHE_HOME=\"${XDG_CACHE_HOME:-/data/.cache}\"\n"
                + "export OMNIROUTE_MIGRATIONS_DIR="
                + "\"${OMNIROUTE_MIGRATIONS_DIR:-/opt/omniroute/migrations}\"\n"
                + "\n"
                + "mkdir -p \"$XDG_CACHE_HOME\" /tmp 2>/dev/null || true\n"
                + "\n"
                + "cd /opt/omniroute\n"
                + "\n"
                + "# The upstream entrypoint fixes up the base path and the data directory\n"
                + "# before handing over to the server; do the same so the dashboard is\n"
                + "# reachable at the root of the tunnel.\n"
                + "if [ -f docker/ensure-docker-base-path.mjs ]; then\n"
                + "  node docker/ensure-docker-base-path.mjs || exit 1\n"
                + "fi\n"
                + "\n"
                + "# run-standalone.mjs is the upstream launcher: it resolves the runtime\n"
                + "# ports, calibrates the heap limit and prefers the WebSocket-aware\n"
                + "# wrapper over the bare Next.js server.\n"
                + "exec node dev/run-standalone.mjs\n";
        File entrypoint = new File(staging, "entrypoint.sh");
        try (FileOutputStream output = new FileOutputStream(entrypoint)) {
            output.write(script.getBytes(StandardCharsets.UTF_8));
        }
        try {
            android.system.Os.chmod(entrypoint.getAbsolutePath(), 0755);
        } catch (Exception failure) {
            Log.w(TAG, "could not chmod entrypoint.sh", failure);
        }
    }

    /** Records where this runtime came from, so the UI can show it. */
    private static void writeVersionMarker(File appDir, OciImageClient.Manifest manifest,
            String tag) {
        String body = "oci:" + manifest.configDigest + " tag=" + tag + "\n";
        try (FileOutputStream output = new FileOutputStream(new File(appDir, ".omniroute-source"))) {
            output.write(body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception failure) {
            Log.w(TAG, "could not write source marker", failure);
        }
    }

    /** Builds a version string that identifies the installed image. */
    private static String versionFor(OciImageClient.Manifest manifest, String tag) {
        String digest = manifest.configDigest;
        String shortDigest = digest.startsWith("sha256:")
                ? digest.substring("sha256:".length())
                : digest;
        if (shortDigest.length() > 12) {
            shortDigest = shortDigest.substring(0, 12);
        }
        return "oci-" + tag + "-" + shortDigest;
    }

    /** Recursively copies a directory tree. */
    private static void copyTree(File source, File destination) throws IOException {
        File[] children = source.listFiles();
        if (children == null) {
            throw new IOException("无法读取目录：" + source.getAbsolutePath());
        }
        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new IOException("无法创建目录：" + destination.getAbsolutePath());
        }
        for (File child : children) {
            File target = new File(destination, child.getName());
            if (child.isDirectory()) {
                copyTree(child, target);
            } else {
                try (InputStream input = new java.io.FileInputStream(child);
                     FileOutputStream output = new FileOutputStream(target)) {
                    byte[] buffer = new byte[1 << 16];
                    int count;
                    while ((count = input.read(buffer)) > 0) {
                        output.write(buffer, 0, count);
                    }
                }
            }
        }
    }

    /** Removes a file or directory tree, ignoring failures. */
    static boolean deleteRecursively(File target) {
        if (target == null || !target.exists()) {
            return true;
        }
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        return target.delete();
    }

    /** Formats a byte count for the UI. */
    static String formatSize(long bytes) {
        if (bytes < 0) {
            return "未知大小";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
