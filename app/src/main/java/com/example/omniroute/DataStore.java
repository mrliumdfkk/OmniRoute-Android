package com.example.omniroute;

import android.content.Context;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class DataStore {
    private DataStore() {}

    public static File exportData(Context context) {
        File source = AppPaths.dataDir(context);
        if (!source.isDirectory()) {
            throw new IllegalStateException("no data directory to export");
        }
        File exportDir = AppPaths.exportDir(context);
        if (!exportDir.isDirectory() && !exportDir.mkdirs()) {
            throw new IllegalStateException("failed to create export directory");
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        File target = new File(exportDir, "omniroute-data-" + stamp + ".zip");

        try (ZipOutputStream zip = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(target)))) {
            zipDirectory(source, source, zip);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to export data", exception);
        }
        return target;
    }

    public static void importData(Context context, File archive) {
        File target = AppPaths.dataDir(context);
        RootfsInstaller.deleteRecursively(target);
        if (!target.mkdirs()) {
            throw new IllegalStateException("failed to recreate data directory");
        }

        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File destination = new File(target, entry.getName());
                if (!destination.getCanonicalPath().startsWith(target.getCanonicalPath())) {
                    throw new IllegalStateException("archive entry escapes data directory");
                }
                if (entry.isDirectory()) {
                    destination.mkdirs();
                    continue;
                }
                File parent = destination.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                try (OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
                    copy(zip, output);
                }
            }
        } catch (Exception exception) {
            throw new IllegalStateException("failed to import data", exception);
        }
    }

    private static void zipDirectory(File root, File current, ZipOutputStream zip) throws Exception {
        File[] children = current.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            String name = root.toURI().relativize(child.toURI()).getPath();
            if (child.isDirectory()) {
                zip.putNextEntry(new ZipEntry(name));
                zip.closeEntry();
                zipDirectory(root, child, zip);
            } else {
                zip.putNextEntry(new ZipEntry(name));
                try (InputStream input = new BufferedInputStream(new FileInputStream(child))) {
                    copy(input, zip);
                }
                zip.closeEntry();
            }
        }
    }

    private static void copy(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[1 << 16];
        int count;
        while ((count = input.read(buffer)) > 0) {
            output.write(buffer, 0, count);
        }
    }
}
