package dev.lewds.ran.nekoxrmanager.patcher.util;

import androidx.annotation.NonNull;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * Thin wrapper around {@link Shizuku#newProcess(String[], String[], String)} that returns
 * captured stdout/stderr and an exit code.
 *
 * <p>Used for harvesting device info (getprop, dumpsys, cmd ...) and for installing APKs
 * via {@code pm install}. Requires the user to have granted Shizuku to this app.</p>
 *
 * <p>If Shizuku is unavailable, every call throws {@link ShizukuUnavailableException}; the
 * caller decides whether to degrade or abort.</p>
 */
public final class ShizukuShell {

    public static final class Result {
        public final int exitCode;
        public final String stdout;
        public final String stderr;
        public Result(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
        public boolean ok() { return exitCode == 0; }
    }

    public static final class ShizukuUnavailableException extends IOException {
        public ShizukuUnavailableException(String msg) { super(msg); }
    }

    private ShizukuShell() {}

    private static volatile Method newProcessMethod;

    static {
        try {
            HiddenApiBypass.addHiddenApiExemptions("L");
        } catch (Throwable ignored) {
            // pre-Pie devices don't need the exemption
        }
    }

    private static Method resolveNewProcess() throws IOException {
        Method m = newProcessMethod;
        if (m != null) return m;
        try {
            Method found = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            found.setAccessible(true);
            newProcessMethod = found;
            return found;
        } catch (NoSuchMethodException e) {
            throw new IOException("Shizuku.newProcess not present in this Shizuku build", e);
        }
    }

    /** Returns true if Shizuku is alive and this app holds the permission. */
    public static boolean isAvailable() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Runs a /system/bin/sh command via Shizuku and returns captured output.
     * Times out after {@code timeoutMs} ms (process is destroyed; result has whatever stdout/stderr was buffered).
     */
    public static Result sh(@NonNull String shellCommand, long timeoutMs) throws IOException, InterruptedException {
        if (!isAvailable()) {
            throw new ShizukuUnavailableException("Shizuku not available or permission not granted");
        }
        String[] cmd = { "sh", "-c", shellCommand };
        Process p;
        try {
            p = (Process) resolveNewProcess().invoke(null, cmd, null, null);
        } catch (Throwable t) {
            throw new IOException("Shizuku.newProcess failed: " + t.getMessage(), t);
        }
        if (p == null) throw new IOException("Shizuku.newProcess returned null");

        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Thread tOut = drain(p.getInputStream(), out);
        Thread tErr = drain(p.getErrorStream(), err);

        boolean done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!done) {
            p.destroy();
            tOut.join(500);
            tErr.join(500);
            return new Result(-1, out.toString(), err.toString() + "\n[timed out after " + timeoutMs + "ms]");
        }
        tOut.join();
        tErr.join();
        return new Result(p.exitValue(), out.toString(), err.toString());
    }

    private static Thread drain(InputStream in, StringBuilder out) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                }
            } catch (IOException ignored) {}
        }, "shizuku-stream");
        t.setDaemon(true);
        t.start();
        return t;
    }
}
