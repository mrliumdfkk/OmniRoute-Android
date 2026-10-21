package com.example.omniroute;

import android.content.Context;
import java.io.File;

public final class AppPaths {
    public static final String DEFAULT_VERSION = "unknown";
    public static final String VERSION_ASSET = "rootfs/omniroute-rootfs.version";
    // NOTE: must not end in ".gz" — aapt2 strips the suffix and *decompresses*
    // the payload for known compressed extensions, which corrupts assets.
    public static final String ROOTFS_ASSET = "rootfs/omniroute-rootfs.bundle";

    private AppPaths() {}

    public static File rootfsRoot(Context context) {
        return new File(context.getFilesDir(), "rootfs");
    }

    public static File rootfsDir(Context context, String version) {
        return new File(rootfsRoot(context), version);
    }

    public static File stagingDir(Context context) {
        return new File(context.getFilesDir(), "rootfs-staging");
    }

    public static File stagingArchive(Context context) {
        return new File(context.getFilesDir(), "rootfs-download.tar.gz");
    }

    public static File versionMarker(Context context) {
        return new File(context.getFilesDir(), "installed-rootfs.version");
    }

    public static File dataDir(Context context) {
        return new File(context.getFilesDir(), "omniroute-data");
    }

    public static File exportDir(Context context) {
        File external = context.getExternalFilesDir(null);
        File base = external != null ? external : context.getFilesDir();
        return new File(base, "export");
    }

    public static File logFile(Context context) {
        return new File(context.getFilesDir(), "logs/omniroute.log");
    }

    /**
     * The dashboard's persisted settings, including the management password hash.
     *
     * <p>Lives beside {@code storage.sqlite} in the data directory the runtime
     * sees as {@code /data}. OmniRoute resolves its own data files from
     * {@code DATA_DIR}, and this path mirrors that layout on the host side.
     */
    public static File settingsFile(Context context) {
        return new File(dataDir(context), "settings.json");
    }

    /**
     * Landing spot for an externally imported full rootfs archive.
     *
     * <p>Kept separate from {@link #stagingArchive(Context)} so an in-flight
     * assets install can never collide with a user-driven import.
     */
    public static File importedArchive(Context context) {
        return new File(context.getFilesDir(), "imported-rootfs.tar.gz");
    }

    /**
     * Marker recording the imported rootfs version (for example
     * {@code import-20261006-031300}). Distinct from
     * {@link #versionMarker(Context)} only in purpose: this one exists so the
     * settings screen can show that the active runtime came from a file rather
     * than from the bundled assets.
     */
    public static File importMarker(Context context) {
        return new File(context.getFilesDir(), "imported-rootfs.version");
    }

    /**
     * Staging directory used while unpacking an imported archive. Separate from
     * {@link #stagingDir(Context)} because both a bundled install and an import
     * can be in progress across a restart, and sharing one directory would let
     * one delete the other's half-extracted tree.
     */
    public static File importStagingDir(Context context) {
        return new File(context.getFilesDir(), "rootfs-import-staging");
    }
}
