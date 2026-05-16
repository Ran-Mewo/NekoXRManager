package dev.lewds.ran.nekoxrmanager.manager;

import android.content.Context;
import androidx.annotation.NonNull;

import dev.lewds.ran.nekoxrmanager.installers.Installer;
import dev.lewds.ran.nekoxrmanager.installers.pm.PMInstaller;
import dev.lewds.ran.nekoxrmanager.installers.shizuku.ShizukuInstaller;
import dev.lewds.ran.nekoxrmanager.patcher.util.ShizukuShell;

/**
 * Picks {@link ShizukuInstaller} when available; falls back to {@link PMInstaller} (system
 * dialog) otherwise. The user pref {@code use_shizuku} is honored — if disabled, always use PM.
 */
public final class InstallerManager {

    private final Installer shizuku = new ShizukuInstaller();
    private final Installer pm;
    private final PreferencesManager prefs;

    public InstallerManager(Context appContext, PreferencesManager prefs) {
        this.pm = new PMInstaller(appContext);
        this.prefs = prefs;
    }

    @NonNull
    public Installer pick() {
        if (prefs.useShizuku() && ShizukuShell.isAvailable()) return shizuku;
        return pm;
    }

    public boolean shizukuActive() {
        return prefs.useShizuku() && ShizukuShell.isAvailable();
    }
}
