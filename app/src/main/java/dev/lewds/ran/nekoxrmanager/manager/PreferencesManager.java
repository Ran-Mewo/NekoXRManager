package dev.lewds.ran.nekoxrmanager.manager;

import android.content.Context;
import android.content.SharedPreferences;

/** SharedPreferences wrapper for patcher options. */
public final class PreferencesManager {

    private static final String PREFS_NAME = "neko_prefs";
    private static final String KEY_DEBUGGABLE = "debuggable";
    private static final String KEY_USE_SHIZUKU = "use_shizuku";
    private static final String KEY_USER_APK_OVERRIDE = "user_apk_override";

    private final SharedPreferences prefs;

    public PreferencesManager(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public boolean isDebuggable() { return prefs.getBoolean(KEY_DEBUGGABLE, false); }
    public void setDebuggable(boolean v) { prefs.edit().putBoolean(KEY_DEBUGGABLE, v).apply(); }

    public boolean useShizuku() { return prefs.getBoolean(KEY_USE_SHIZUKU, true); }
    public void setUseShizuku(boolean v) { prefs.edit().putBoolean(KEY_USE_SHIZUKU, v).apply(); }

    public String getUserApkOverride() { return prefs.getString(KEY_USER_APK_OVERRIDE, null); }
    public void setUserApkOverride(String uri) { prefs.edit().putString(KEY_USER_APK_OVERRIDE, uri).apply(); }
}
