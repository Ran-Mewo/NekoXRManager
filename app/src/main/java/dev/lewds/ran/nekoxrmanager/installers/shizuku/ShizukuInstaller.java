package dev.lewds.ran.nekoxrmanager.installers.shizuku;

import androidx.annotation.NonNull;

import java.io.File;

import dev.lewds.ran.nekoxrmanager.di.ServiceLocator;
import dev.lewds.ran.nekoxrmanager.installers.InstallResult;
import dev.lewds.ran.nekoxrmanager.installers.Installer;
import dev.lewds.ran.nekoxrmanager.patcher.util.ShizukuShell;

/**
 * Installer that drives {@code pm install} / {@code pm uninstall} via Shizuku's elevated shell.
 * No BroadcastReceiver dance — we read pm's exit code directly.
 */
public final class ShizukuInstaller implements Installer {

    private static final String TAG = "ShizukuInstaller";
    private static final long INSTALL_TIMEOUT_MS = 120_000L;
    private static final long UNINSTALL_TIMEOUT_MS = 30_000L;

    @NonNull
    @Override
    public InstallResult install(@NonNull File apk) throws Exception {
        if (!apk.isFile()) return InstallResult.failed("apk not found: " + apk);
        String cmd = "pm install -r -t -d --user 0 " + shellQuote(apk.getAbsolutePath());
        ServiceLocator.log().info(TAG, "$ " + cmd);
        ShizukuShell.Result r = ShizukuShell.sh(cmd, INSTALL_TIMEOUT_MS);
        if (r.exitCode == 0 && (r.stdout.contains("Success") || r.stdout.trim().isEmpty())) {
            return InstallResult.success();
        }
        return InstallResult.failed("pm install exit=" + r.exitCode + ": " + r.stderr.trim() + " " + r.stdout.trim());
    }

    @NonNull
    @Override
    public InstallResult uninstall(@NonNull String packageName) throws Exception {
        String cmd = "pm uninstall --user 0 " + shellQuote(packageName);
        ServiceLocator.log().info(TAG, "$ " + cmd);
        ShizukuShell.Result r = ShizukuShell.sh(cmd, UNINSTALL_TIMEOUT_MS);
        // pm uninstall is idempotent for our purposes: "not installed" is fine.
        if (r.exitCode == 0 || r.stderr.contains("not installed for") || r.stdout.contains("Success")) {
            return InstallResult.success();
        }
        return InstallResult.failed("pm uninstall exit=" + r.exitCode + ": " + r.stderr.trim());
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
