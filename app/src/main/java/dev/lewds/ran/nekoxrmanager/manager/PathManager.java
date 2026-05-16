package dev.lewds.ran.nekoxrmanager.manager;

import android.content.Context;

import java.io.File;

/** Working directories and well-known file paths for the patcher. */
public final class PathManager {

    private final File nekoDir;
    private final File workDir;
    private final File cacheDir;
    private final File keystoreFile;

    public PathManager(Context context) {
        // Use externalCacheDir for sideload-friendly paths; falls back to filesDir if external is null.
        File baseDir = context.getExternalCacheDir() != null
                ? context.getExternalCacheDir()
                : context.getCacheDir();
        nekoDir = new File(baseDir, "neko");
        workDir = new File(nekoDir, "work");
        cacheDir = new File(nekoDir, "cache");
        keystoreFile = new File(nekoDir, "neko.keystore");
        nekoDir.mkdirs();
        workDir.mkdirs();
        cacheDir.mkdirs();
    }

    public File getNekoDir() { return nekoDir; }
    public File getWorkDir() { return workDir; }
    public File getCacheDir() { return cacheDir; }
    public File getKeystoreFile() { return keystoreFile; }

    /** Per-job working subdirectory; cleared at start of each patch run. */
    public File freshJobDir() {
        File job = new File(workDir, "job-" + System.currentTimeMillis());
        job.mkdirs();
        return job;
    }

    /** Cached APK location for a given (versionCode, abi). */
    public File cachedArCoreApk(long versionCode, String abi) {
        return new File(cacheDir, "arcore_v" + versionCode + "_" + abi + ".apk");
    }
}
