package dev.lewds.ran.nekoxrmanager.patcher.util;

import androidx.annotation.NonNull;

import com.github.diamondminer88.zip.ZipCompression;
import com.github.diamondminer88.zip.ZipEntry;
import com.github.diamondminer88.zip.ZipReader;
import com.github.diamondminer88.zip.ZipWriter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Convenience wrappers around zip-android. The patcher's edit primitives:
 * read an entry to a byte[] / File, replace the entry, list ABI .so paths.
 */
public final class ZipUtil {

    private ZipUtil() {}

    /** Reads a single entry's bytes; returns null if the entry doesn't exist. */
    public static byte[] readEntry(@NonNull File apk, @NonNull String name) throws IOException {
        try (ZipReader r = new ZipReader(apk)) {
            ZipEntry e = r.openEntry(name);
            if (e == null) return null;
            return e.read();
        }
    }

    /**
     * Replaces (or adds) an entry. Compression is set to {@code compress} —
     * use {@link ZipCompression#NONE} for native libraries to keep them mmap-friendly.
     */
    public static void replaceEntry(@NonNull File apk, @NonNull String name,
                                    @NonNull byte[] data, @NonNull ZipCompression compress) throws IOException {
        boolean exists;
        try (ZipReader r = new ZipReader(apk)) {
            exists = r.openEntry(name) != null;
        }
        try (ZipWriter w = new ZipWriter(apk, /* append */ true)) {
            if (exists) w.deleteEntry(name);
            w.writeEntry(name, data, compress);
        }
    }

    /** Lists every entry under {@code prefix} (e.g. {@code "lib/arm64-v8a/"}). */
    @NonNull
    public static List<String> listEntries(@NonNull File apk, @NonNull String prefix) throws IOException {
        List<String> out = new ArrayList<>();
        try (ZipReader r = new ZipReader(apk)) {
            for (String n : r.getEntryNames()) {
                if (n.startsWith(prefix)) out.add(n);
            }
        }
        Collections.sort(out);
        return out;
    }

    /** Copies an APK to {@code dst}, overwriting if present. */
    public static void copyApk(@NonNull File src, @NonNull File dst) throws IOException {
        if (dst.exists() && !dst.delete()) throw new IOException("cannot delete " + dst);
        Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /** Extracts a single entry to a file on disk. */
    public static void extractEntry(@NonNull File apk, @NonNull String name, @NonNull File outFile) throws IOException {
        byte[] data = readEntry(apk, name);
        if (data == null) throw new IOException("entry not in apk: " + name);
        outFile.getParentFile().mkdirs();
        Files.write(outFile.toPath(), data);
    }

    /**
     * Streams every entry from {@code apk} into a new ZIP and atomically replaces
     * the original. Used to clean up after multiple in-place entry replacements,
     * which can leave the central directory inconsistent with on-disk local file
     * headers — apksig refuses to sign such APKs.
     *
     * <p>Compression is preserved per entry — STORED stays STORED so
     * {@code classes.dex} keeps its mmap-friendly alignment.</p>
     */
    public static void rebuildClean(@NonNull File apk) throws IOException {
        File tmp = new File(apk.getParentFile(), apk.getName() + ".rebuild");
        if (tmp.exists() && !tmp.delete()) throw new IOException("cannot delete " + tmp);
        try (ZipReader r = new ZipReader(apk);
             ZipWriter w = new ZipWriter(tmp)) {
            for (String name : r.getEntryNames()) {
                ZipEntry e = r.openEntry(name);
                if (e == null) continue;
                byte[] data = e.read();
                ZipCompression compression = e.getCompression() == ZipCompression.NONE
                        ? ZipCompression.NONE : ZipCompression.DEFLATE;
                w.writeEntry(name, data, compression);
            }
        }
        if (!apk.delete() || !tmp.renameTo(apk)) {
            throw new IOException("could not replace " + apk + " with rebuilt copy");
        }
    }
}
