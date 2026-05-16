package dev.lewds.ran.nekoxrmanager.di;

import android.content.Context;

import dev.lewds.ran.nekoxrmanager.manager.InstallLogManager;
import dev.lewds.ran.nekoxrmanager.manager.PathManager;
import dev.lewds.ran.nekoxrmanager.manager.PreferencesManager;

/**
 * Static-init singleton holder for Java-side managers.
 * The Kotlin/UI layer wires Koin separately and pulls these via {@link #paths()}, etc.
 *
 * <p>Initialised exactly once from {@link dev.lewds.ran.nekoxrmanager.NekoApplication#onCreate()}.</p>
 */
public final class ServiceLocator {

    private static volatile PathManager paths;
    private static volatile PreferencesManager prefs;
    private static volatile InstallLogManager log;

    private ServiceLocator() {}

    public static synchronized void init(Context appContext) {
        if (paths != null) return;
        paths = new PathManager(appContext);
        prefs = new PreferencesManager(appContext);
        log = new InstallLogManager();
    }

    public static PathManager paths() { return require(paths); }
    public static PreferencesManager prefs() { return require(prefs); }
    public static InstallLogManager log() { return require(log); }

    private static <T> T require(T t) {
        if (t == null) throw new IllegalStateException("ServiceLocator not initialized — call init() in Application.onCreate()");
        return t;
    }
}
