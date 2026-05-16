package dev.lewds.ran.nekoxrmanager.installers;

import androidx.annotation.NonNull;

import java.io.File;

public interface Installer {
    /** Install the given APK. Blocks until the operation finishes (or until user dismisses any dialog). */
    @NonNull
    InstallResult install(@NonNull File apk) throws Exception;

    /** Uninstall a package by name. Returns {@code SUCCESS} even if the package wasn't installed. */
    @NonNull
    InstallResult uninstall(@NonNull String packageName) throws Exception;
}
