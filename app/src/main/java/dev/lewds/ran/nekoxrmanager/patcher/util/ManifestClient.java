package dev.lewds.ran.nekoxrmanager.patcher.util;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import dev.lewds.ran.nekoxrmanager.patcher.ReleaseManifest;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

/**
 * Fetches the release manifest and downloads the matching ARCore APK with SHA-256 verification.
 *
 * <p>HTTP: OkHttp (java-friendly). JSON: Moshi reflective. Cache: {@code PathManager.cacheDir}.</p>
 */
public final class ManifestClient {

    /** Reads the bundled {@code arcore/manifest_url.txt} and trims whitespace/newlines. */
    @NonNull
    public static String readBundledManifestUrl(@NonNull Context ctx) throws IOException {
        try (InputStream in = ctx.getAssets().open("arcore/manifest_url.txt");
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString().trim();
        }
    }

    /** GETs the manifest URL and parses it. */
    @NonNull
    public static ReleaseManifest fetch(@NonNull OkHttpClient client, @NonNull String url) throws IOException {
        Request req = new Request.Builder().url(url).get().build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new IOException("manifest HTTP " + resp.code() + " from " + url);
            ResponseBody body = resp.body();
            if (body == null) throw new IOException("empty manifest body");
            String json = body.string();
            Moshi moshi = new Moshi.Builder().build();
            JsonAdapter<ReleaseManifest> adapter = moshi.adapter(ReleaseManifest.class).lenient();
            ReleaseManifest m = adapter.fromJson(json);
            if (m == null) throw new IOException("manifest JSON parsed to null");
            return m;
        }
    }

    public interface ProgressListener {
        void onProgress(long bytesRead, long contentLength);
    }

    /**
     * Downloads {@code url} to {@code dest}, verifying SHA-256 (hex, lowercase) on the fly.
     * If {@code expectedSha256} is null/empty, no verification (use only when we trust the source).
     * Atomic write via {@code .part} sibling.
     */
    public static void download(@NonNull OkHttpClient client,
                                @NonNull String url,
                                @NonNull File dest,
                                @Nullable String expectedSha256,
                                @Nullable ProgressListener progress) throws IOException {
        Request req = new Request.Builder().url(url).get().build();
        File part = new File(dest.getParentFile(), dest.getName() + ".part");
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }

        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new IOException("download HTTP " + resp.code() + " from " + url);
            ResponseBody body = resp.body();
            if (body == null) throw new IOException("empty body for " + url);
            long total = body.contentLength();

            try (BufferedSource src = body.source();
                 java.io.OutputStream out = Files.newOutputStream(part.toPath())) {
                byte[] buf = new byte[64 * 1024];
                long read = 0;
                int n;
                while ((n = src.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    md.update(buf, 0, n);
                    read += n;
                    if (progress != null) progress.onProgress(read, total);
                }
            }
        }

        String got = hex(md.digest());
        if (expectedSha256 != null && !expectedSha256.isEmpty()
                && !expectedSha256.equalsIgnoreCase(got)) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new IOException("sha256 mismatch: expected " + expectedSha256 + " got " + got);
        }
        Files.move(part.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /** Slurps an asset to bytes. Used for fallback patch-set loading. */
    @NonNull
    public static byte[] readAsset(@NonNull Context ctx, @NonNull String path) throws IOException {
        try (InputStream in = ctx.getAssets().open(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            byte[] tmp = new byte[8192];
            int n;
            while ((n = in.read(tmp)) > 0) out.write(tmp, 0, n);
            return out.toByteArray();
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private ManifestClient() {}
}
