package com.example.omniroute;

import android.system.ErrnoException;
import android.system.Os;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Minimal tar reader for extracting the bundled rootfs.
 *
 * Android's toybox tar is not guaranteed to exist on every device and never
 * ships xz support, so the archive is a plain tar.gz extracted here instead of
 * by spawning a shell command. Handles the tar features GNU tar emits for a
 * Debian rootfs: ustar headers, GNU long names/links ({@code L}/{@code K}),
 * pax extended headers ({@code x}), plain directories, symlinks and hardlinks.
 * Device nodes and other special types are skipped because a non-root
 * extraction cannot create them.
 */
final class TarExtractor {
    private static final int BLOCK = 512;
    private static final long MAX_ENTRY_SIZE = 1L << 32;
    /**
     * How many entries to unpack between progress callbacks. A Debian rootfs
     * carries ~210k entries; reporting on every one would post a message per
     * entry and make the UI thread the bottleneck.
     */
    private static final int PROGRESS_EVERY = 500;

    /**
     * Reports extraction progress. {@code current} is null on the final call,
     * which signals that the stream has been fully consumed.
     */
    interface Progress {
        void onProgress(long entries, String current);
    }

    private TarExtractor() {}

    static void extract(File archive, File destination, Progress progress) throws IOException {
        InputStream raw = new BufferedInputStream(new java.io.FileInputStream(archive));
        raw.mark(2);
        boolean gzipped;
        if (raw.read() == 0x1f && raw.read() == 0x8b) {
            gzipped = true;
        } else {
            gzipped = false;
        }
        raw.reset();
        try (InputStream input = gzipped ? new GZIPInputStream(raw, 1 << 16) : raw) {
            readEntries(input, destination, progress);
        }
    }

    private static void readEntries(InputStream input, File root, Progress progress)
            throws IOException {
        // Directory modes are deferred to the end of the stream. A Debian rootfs
        // marks several directories read-only (0555) or owner-private (0700),
        // and applying that while entries are still arriving makes the next
        // write into that directory fail — the exact way a non-root extraction
        // breaks on a tree that unpacks fine as root. Extraction therefore
        // creates every directory writable and only narrows the mode once
        // nothing else needs to be written underneath it. The guest still sees
        // the recorded modes, because proot runs it with --root-id.
        Map<File, Integer> directoryModes = new HashMap<>();
        readEntriesInner(input, root, directoryModes, progress);
        for (Map.Entry<File, Integer> entry : directoryModes.entrySet()) {
            applyMode(entry.getKey(), entry.getValue());
        }
        if (progress != null) {
            // A null name marks the end of the stream, so the UI can stop
            // quoting a file name that no longer describes what is happening.
            progress.onProgress(0, null);
        }
    }

    private static void readEntriesInner(InputStream input, File root,
                                         Map<File, Integer> directoryModes,
                                         Progress progress) throws IOException {
        byte[] header = new byte[BLOCK];
        String pendingName = null;
        String pendingLink = null;
        Map<String, String> pax = new HashMap<>();
        // Counts every header consumed, including the metadata entries (long
        // name, long link, pax) that carry no payload of their own. It drives
        // the periodic progress callback so a long extraction stays visible
        // instead of looking hung.
        long entryCount = 0;

        while (true) {
            if (!readFully(input, header)) {
                return;
            }
            if (isZeroBlock(header)) {
                // GNU tar terminates the archive with two zero blocks; stop at
                // the first one because trailing padding is not meaningful.
                return;
            }

            // Counted here, before the metadata branches below, so long-name and
            // pax headers are included: on a 210k-entry rootfs the callback has
            // to keep firing even while the stream is still resolving names.
            entryCount++;
            if (progress != null && entryCount % PROGRESS_EVERY == 0) {
                progress.onProgress(entryCount, decode(header, 0, 100));
            }

            String name = decode(header, 0, 100);
            String mode = decode(header, 100, 8);
            String sizeField = decode(header, 124, 12);
            char type = (char) (header[156] & 0xFF);
            String linkName = decode(header, 157, 100);
            String prefix = decode(header, 345, 155);

            long size = parseNumeric(sizeField);
            if (size < 0 || size > MAX_ENTRY_SIZE) {
                throw new IOException("unsupported tar entry size: " + sizeField);
            }

            if (type == 'L') { // GNU long name
                pendingName = readStringPayload(input, size);
                continue;
            }
            if (type == 'K') { // GNU long link target
                pendingLink = readStringPayload(input, size);
                continue;
            }
            if (type == 'x' || type == 'g') { // pax extended header
                pax.putAll(parsePax(readStringPayload(input, size)));
                continue;
            }

            String resolvedName = paxName(pax, pendingName, prefix, name);
            String resolvedLink = paxLink(pax, pendingLink, linkName);
            pendingName = null;
            pendingLink = null;
            pax.clear();

            File target = safeResolve(root, resolvedName);
            switch (type) {
                case '5':
                    mkdir(target);
                    // Recorded, not applied: a 0555/0700 directory set now would
                    // block the entries still to be written underneath it. The
                    // recorded mode is applied once the whole stream is read.
                    directoryModes.put(target, parseMode(mode));
                    break;
                case '2':
                    createSymlink(target, resolvedLink);
                    break;
                case '1':
                    createHardLink(root, target, resolvedLink);
                    break;
                case '0':
                case '\0':
                case '7':
                    writeFile(input, target, size, parseMode(mode));
                    continue; // payload already consumed
                default:
                    // Device nodes, FIFOs and other special types cannot be
                    // created without root; skip just their payload.
                    break;
            }
            skipEntryPayload(input, size);
        }
    }

    private static void writeFile(InputStream input, File target, long size, int mode)
            throws IOException {
        File parent = target.getParentFile();
        if (parent != null) {
            mkdir(parent);
        }
        try (OutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[1 << 16];
            long remaining = size;
            while (remaining > 0) {
                int want = (int) Math.min(buffer.length, remaining);
                int count = input.read(buffer, 0, want);
                if (count < 0) {
                    throw new EOFException("truncated tar payload for " + target);
                }
                output.write(buffer, 0, count);
                remaining -= count;
            }
        }
        skipFully(input, padding(size));
        applyMode(target, mode);
    }

    private static void createSymlink(File target, String linkName) throws IOException {
        File parent = target.getParentFile();
        if (parent != null) {
            mkdir(parent);
        }
        RootfsInstaller.deleteRecursively(target);
        try {
            Os.symlink(linkName, target.getAbsolutePath());
        } catch (ErrnoException exception) {
            // The archive holds 783 symlinks; one refused link must not throw
            // away the other 209k entries. The tree stays usable without it.
            android.util.Log.w("TarExtractor",
                    "symlink failed, continuing: " + target + " -> " + linkName, exception);
        }
    }

    private static void createHardLink(File root, File target, String linkName) throws IOException {
        File parent = target.getParentFile();
        if (parent != null) {
            mkdir(parent);
        }
        File source = safeResolve(root, linkName);
        if (!source.isFile()) {
            // Hardlink targets can appear later in the stream; a copy is a safe
            // fallback that keeps the tree usable.
            return;
        }
        RootfsInstaller.deleteRecursively(target);
        try {
            Os.link(source.getAbsolutePath(), target.getAbsolutePath());
        } catch (ErrnoException exception) {
            // link() is refused on some emulated filesystems; copying keeps the
            // file present instead of failing the whole import.
            copyFile(source, target);
        }
    }

    private static void copyFile(File source, File target) throws IOException {
        try (InputStream input = new java.io.FileInputStream(source);
             OutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[1 << 16];
            int count;
            while ((count = input.read(buffer)) > 0) {
                output.write(buffer, 0, count);
            }
        }
    }

    private static void mkdir(File dir) throws IOException {
        if (dir.isDirectory()) {
            return;
        }
        // A dangling symlink or a stale file left by an earlier entry is in the
        // way; the tar stream is authoritative, so replace it.
        if (dir.exists() || java.nio.file.Files.isSymbolicLink(dir.toPath())) {
            RootfsInstaller.deleteRecursively(dir);
        }
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("failed to create directory " + dir);
        }
    }

    private static File safeResolve(File root, String name) throws IOException {
        String cleaned = name.replace('\\', '/');
        while (cleaned.startsWith("./")) {
            cleaned = cleaned.substring(2);
        }
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.isEmpty()) {
            return root;
        }

        // Lexical only. getCanonicalPath() resolves against the real filesystem,
        // and a Debian rootfs ships absolute links such as
        // /usr/bin/ld.so -> /lib/ld-linux-aarch64.so.1. On Android those targets
        // exist on the host, so canonicalisation made legitimate entries look
        // like an escape and aborted the entire import.
        java.util.ArrayDeque<String> parts = new java.util.ArrayDeque<>();
        for (String segment : cleaned.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (parts.isEmpty()) {
                    throw new IOException("tar entry escapes rootfs: " + name);
                }
                parts.removeLast();
            } else {
                parts.addLast(segment);
            }
        }

        File resolved = root;
        for (String segment : parts) {
            resolved = new File(resolved, segment);
        }
        return resolved;
    }

    private static Map<String, String> parsePax(String payload) {
        Map<String, String> values = new HashMap<>();
        int index = 0;
        while (index < payload.length()) {
            int space = payload.indexOf(' ', index);
            if (space < 0) {
                break;
            }
            int length;
            try {
                length = Integer.parseInt(payload.substring(index, space).trim());
            } catch (NumberFormatException exception) {
                break;
            }
            if (length <= 0 || index + length > payload.length()) {
                break;
            }
            String record = payload.substring(space + 1, index + length);
            int equals = record.indexOf('=');
            if (equals > 0) {
                values.put(record.substring(0, equals), record.substring(equals + 1).trim());
            }
            index += length;
        }
        return values;
    }

    private static String paxName(Map<String, String> pax, String pending, String prefix, String name) {
        String value = pax.get("path");
        if (value != null && !value.isEmpty()) {
            return value;
        }
        if (pending != null && !pending.isEmpty()) {
            return pending;
        }
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }

    private static String paxLink(Map<String, String> pax, String pending, String linkName) {
        String value = pax.get("linkpath");
        if (value != null && !value.isEmpty()) {
            return value;
        }
        return pending != null && !pending.isEmpty() ? pending : linkName;
    }

    private static String readStringPayload(InputStream input, long size) throws IOException {
        byte[] payload = new byte[(int) size];
        if (!readFully(input, payload)) {
            throw new EOFException("truncated tar metadata entry");
        }
        skipFully(input, padding(size));
        return new String(payload, StandardCharsets.UTF_8).replace("\0", "").trim();
    }

    private static String decode(byte[] block, int offset, int length) {
        int end = offset;
        int limit = Math.min(offset + length, block.length);
        while (end < limit && block[end] != 0) {
            end++;
        }
        return new String(block, offset, end - offset, StandardCharsets.UTF_8).trim();
    }

    private static long parseNumeric(String field) {
        String value = field.trim();
        if (value.isEmpty()) {
            return 0;
        }
        if (value.equals("0")) {
            return 0;
        }
        // GNU base-256 encoding sets the high bit of the first byte.
        if (value.charAt(0) > 0x7F) {
            long result = value.charAt(0) & 0x7F;
            for (int index = 1; index < value.length(); index++) {
                result = (result << 8) | (value.charAt(index) & 0xFF);
            }
            return result;
        }
        try {
            return Long.parseLong(value.replace("\0", "").trim(), 8);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static int parseMode(String octal) {
        try {
            return Integer.parseInt(octal.trim(), 8) & 0777;
        } catch (NumberFormatException exception) {
            return 0644;
        }
    }

    private static void applyMode(File file, int mode) {
        // Os.chmod rather than File.setExecutable(): the latter reports
        // success but silently does nothing on some Android filesystems,
        // which left /usr/bin/dash non-executable and made proot abort
        // with `execve("/usr/bin/sh"): Permission denied`. Fall back to
        // the java.io API only if the syscall itself is refused.
        try {
            Os.chmod(file.getAbsolutePath(), mode & 0777);
            return;
        } catch (Exception refused) {
            android.util.Log.w("TarExtractor",
                    "chmod failed, falling back to File API: " + file, refused);
        }
        file.setReadable((mode & 0400) != 0, false);
        file.setWritable((mode & 0200) != 0, false);
        file.setExecutable((mode & 0111) != 0, false);
    }

    private static long padding(long size) {
        long remainder = size % BLOCK;
        return remainder == 0 ? 0 : BLOCK - remainder;
    }

    private static void skipEntryPayload(InputStream input, long size) throws IOException {
        skipFully(input, size + padding(size));
    }

    private static boolean readFully(InputStream input, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int count = input.read(buffer, offset, buffer.length - offset);
            if (count < 0) {
                return false;
            }
            offset += count;
        }
        return true;
    }

    private static void skipFully(InputStream input, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped <= 0) {
                if (input.read() < 0) {
                    return;
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte value : block) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }
}
