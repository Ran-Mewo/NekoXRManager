package dev.lewds.ran.nekoxrmanager.installers.pm;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;

import java.io.File;

import dev.lewds.ran.nekoxrmanager.installers.InstallResult;
import dev.lewds.ran.nekoxrmanager.installers.Installer;

/**
 * Fallback installer that launches the system's package-installer activity. Returns
 * {@link InstallResult.Kind#PENDING_USER_CONFIRM} since we can't synchronously block on the
 * user dismissing the system dialog without a result-callback contract.
 *
 * <p>Useful when Shizuku is unavailable. The user must manually confirm install for each APK.</p>
 */
public final class PMInstaller implements Installer {

    private final Context appContext;

    public PMInstaller(Context appContext) {
        this.appContext = appContext.getApplicationContext();
    }

    @NonNull
    @Override
    public InstallResult install(@NonNull File apk) {
        if (!apk.isFile()) return InstallResult.failed("apk not found: " + apk);
        Uri uri = FileProvider.getUriForFile(
                appContext,
                appContext.getPackageName() + ".fileprovider",
                apk
        );
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            appContext.startActivity(intent);
            return InstallResult.pendingUserConfirm();
        } catch (Exception e) {
            return InstallResult.failed("startActivity: " + e.getMessage());
        }
    }

    @NonNull
    @Override
    public InstallResult uninstall(@NonNull String packageName) {
        Intent intent = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            appContext.startActivity(intent);
            return InstallResult.pendingUserConfirm();
        } catch (Exception e) {
            return InstallResult.failed("startActivity: " + e.getMessage());
        }
    }
}
