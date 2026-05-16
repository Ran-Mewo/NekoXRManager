package dev.lewds.ran.nekoxrmanager.patcher.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Applies a {@link PatchSet} (byte signatures + replacements with {@code ??} wildcards) to a
 * native ELF library. Single-match-or-fail per pattern — drift is surfaced loud, never silently
 * skipped.
 *
 * <p>Patch sets are loaded from JSON matching the schema documented in
 * {@code app/src/main/assets/arcore/patches/generic_arm64.json}.</p>
 *
 * <p>Replacement convention: {@code ??} in a replacement byte means "preserve the original byte
 * at that position." Surgical 4-byte patches inside a 24-byte sig use this to keep anchor bytes
 * untouched.</p>
 */
public final class NativePatcher {

    private static final String TAG = "NativePatcher";
    private static final int WILDCARD = -1;

    public static final class BytePattern {
        public String sig;
        public String replacement;
    }
    public static final class FunctionPatch {
        public String name;
        public String symbol;
        @Nullable public String comment;
        public List<BytePattern> patterns;
    }
    public static final class PatchSet {
        public String id;
        @Nullable public String comment;
        public String abi;
        public String so;
        public List<FunctionPatch> patches;
    }

    public static final class PatchResult {
        public final String functionName;
        public final long matchOffset;
        public final int matchCount;
        public PatchResult(String fn, long off, int n) {
            this.functionName = fn; this.matchOffset = off; this.matchCount = n;
        }
    }

    public static final class PatchFailure extends IOException {
        public PatchFailure(String msg) { super(msg); }
    }

    private NativePatcher() {}

    public static PatchSet loadJson(byte[] json) throws IOException {
        Moshi moshi = new Moshi.Builder().build();
        JsonAdapter<PatchSet> adapter = moshi.adapter(PatchSet.class);
        PatchSet ps = adapter.fromJson(new String(json));
        if (ps == null) throw new IOException("patch-set JSON parsed to null");
        return ps;
    }

    /**
     * Apply every patch in {@code set} to {@code so}, in place. Logs each result to InstallLogManager.
     * Throws {@link PatchFailure} on any single-match check failure (caller should treat as fatal).
     *
     * <p>Strategy: read entire file into memory (35 MB worst case for libarcore_c.so — fine on
     * Android 14 / 8 GB), patch in-buffer, write back atomically via a sibling .tmp file.</p>
     */
    public static List<PatchResult> apply(@NonNull File so, @NonNull PatchSet set) throws IOException {
        dev.lewds.ran.nekoxrmanager.manager.InstallLogManager log =
                dev.lewds.ran.nekoxrmanager.di.ServiceLocator.log();
        log.info(TAG, "applying " + set.id + " to " + so.getName());

        byte[] before = Files.readAllBytes(so.toPath());
        byte[] beforeHash = sha256(before);
        log.debug(TAG, "  before sha256=" + hex(beforeHash) + "  size=" + before.length);

        List<PatchResult> results = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(so, "r")) {
            for (FunctionPatch fp : set.patches) {
                ElfReader.Symbol sym = ElfReader.findSymbol(raf, fp.symbol);
                if (sym == null) {
                    throw new PatchFailure("symbol not found: " + fp.symbol);
                }
                int rangeStart = (int) sym.fileOffset;
                int rangeEnd = (int) (sym.fileOffset + sym.size);

                for (BytePattern bp : fp.patterns) {
                    int[] sig = parseHexPattern(bp.sig);
                    int[] rep = parseHexPattern(bp.replacement);
                    if (sig.length != rep.length) {
                        throw new PatchFailure(fp.symbol + ": sig and replacement length differ ("
                                + sig.length + " vs " + rep.length + ")");
                    }
                    long match = scanUnique(before, rangeStart, rangeEnd, sig);
                    int count = match == -2 ? 2 : (match < 0 ? 0 : 1);
                    results.add(new PatchResult(fp.symbol, match, count));
                    if (count != 1) {
                        throw new PatchFailure(fp.symbol + ": expected 1 match, got " + count
                                + " in [0x" + Long.toHexString(rangeStart) + ", 0x"
                                + Long.toHexString(rangeEnd) + ")");
                    }
                    log.info(TAG, String.format(Locale.US,
                            "  ✓ %s @ 0x%08x", fp.name != null ? fp.name : fp.symbol, match));
                    applyReplacement(before, (int) match, rep);
                }
            }
        }

        File tmp = new File(so.getParentFile(), so.getName() + ".tmp");
        Files.write(tmp.toPath(), before);
        Files.move(tmp.toPath(), so.toPath(), StandardCopyOption.REPLACE_EXISTING);
        log.debug(TAG, "  after sha256=" + hex(sha256(before)));
        return results;
    }

    private static int[] parseHexPattern(String s) {
        String[] toks = s.trim().split("\\s+");
        int[] out = new int[toks.length];
        for (int i = 0; i < toks.length; i++) {
            String t = toks[i];
            if ("??".equals(t)) {
                out[i] = WILDCARD;
            } else {
                if (t.length() != 2) throw new IllegalArgumentException("bad hex token: " + t);
                out[i] = Integer.parseInt(t, 16);
            }
        }
        return out;
    }

    /** Returns -1 for 0 matches, -2 for >1, else the file offset of the unique match. */
    private static long scanUnique(byte[] hay, int from, int to, int[] sig) {
        int plen = sig.length;
        long firstHit = -1;
        for (int i = from; i + plen <= to; i++) {
            boolean ok = true;
            for (int j = 0; j < plen; j++) {
                int s = sig[j];
                if (s == WILDCARD) continue;
                if ((hay[i + j] & 0xFF) != s) { ok = false; break; }
            }
            if (ok) {
                if (firstHit >= 0) return -2;
                firstHit = i;
            }
        }
        return firstHit;
    }

    private static void applyReplacement(byte[] buf, int offset, int[] rep) {
        for (int j = 0; j < rep.length; j++) {
            int r = rep[j];
            if (r == WILDCARD) continue; // preserve original
            buf[offset + j] = (byte) (r & 0xFF);
        }
    }

    private static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
