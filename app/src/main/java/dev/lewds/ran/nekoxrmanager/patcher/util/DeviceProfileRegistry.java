package dev.lewds.ran.nekoxrmanager.patcher.util;

import android.content.Context;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.lewds.ran.nekoxrmanager.patcher.DeviceProfile;

/**
 * Loads {@code assets/arcore/profiles/devices.json} and matches the running
 * device against its entries.
 *
 * <p>The {@code match} block on each entry holds {@code manufacturer} and
 * {@code model} substrings; both are tested case-insensitively against
 * {@link Build#MANUFACTURER} / {@link Build#MODEL}. The first matching entry
 * wins; lookup order is file order.</p>
 *
 * <p>Parsing is strict: required fields with no value in the JSON cause
 * {@link DeviceProfile#validate()} to throw at lookup time.</p>
 */
public final class DeviceProfileRegistry {

    public static final String ASSET_PATH = "arcore/profiles/devices.json";

    private DeviceProfileRegistry() {}

    /**
     * @return populated profile for the running device, or {@code null} if no
     *         registry entry matches.
     */
    @Nullable
    public static DeviceProfile lookupCurrent(@NonNull Context ctx) throws IOException, JSONException {
        return lookup(ctx, Build.MANUFACTURER, Build.MODEL);
    }

    @Nullable
    public static DeviceProfile lookup(@NonNull Context ctx,
                                       @NonNull String manufacturer,
                                       @NonNull String model) throws IOException, JSONException {
        JSONObject root = readJson(ctx);
        JSONArray devices = root.getJSONArray("devices");
        String mfg = manufacturer.toLowerCase(Locale.ROOT);
        String mdl = model.toLowerCase(Locale.ROOT);

        for (int i = 0; i < devices.length(); i++) {
            JSONObject entry = devices.getJSONObject(i);
            JSONObject match = entry.optJSONObject("match");
            if (match == null) continue;
            String wantMfg = match.optString("manufacturer", "").toLowerCase(Locale.ROOT);
            String wantMdl = match.optString("model", "").toLowerCase(Locale.ROOT);
            boolean mfgOk = wantMfg.isEmpty() || mfg.contains(wantMfg);
            boolean mdlOk = wantMdl.isEmpty() || mdl.contains(wantMdl);
            if (mfgOk && mdlOk) {
                DeviceProfile p = parse(entry);
                p.validate();
                return p;
            }
        }
        return null;
    }

    /** Returns every bundled profile; used by the device-picker UI. */
    @NonNull
    public static List<DeviceProfile> listAll(@NonNull Context ctx) throws IOException, JSONException {
        JSONObject root = readJson(ctx);
        JSONArray devices = root.getJSONArray("devices");
        List<DeviceProfile> out = new ArrayList<>(devices.length());
        for (int i = 0; i < devices.length(); i++) out.add(parse(devices.getJSONObject(i)));
        return out;
    }

    @NonNull
    private static JSONObject readJson(@NonNull Context ctx) throws IOException, JSONException {
        try (InputStream is = ctx.getAssets().open(ASSET_PATH);
             BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            return new JSONObject(sb.toString());
        }
    }

    @NonNull
    private static DeviceProfile parse(@NonNull JSONObject e) throws JSONException {
        DeviceProfile p = new DeviceProfile();
        p.name         = requireString(e, "name");
        JSONObject m   = e.getJSONObject("match");
        p.manufacturer = requireString(m, "manufacturer");
        p.model        = requireString(m, "model");
        p.fingerprint  = e.optString("fingerprint", "");
        p.soc          = e.optString("soc", "");

        p.cameraId      = requireString(e, "cameraId");
        p.captureWidth  = e.getInt("captureWidth");
        p.captureHeight = e.getInt("captureHeight");
        p.fps           = e.getInt("fps");

        p.focalX     = e.getDouble("focalX");
        p.focalY     = e.getDouble("focalY");
        p.principalX = e.getDouble("principalX");
        p.principalY = e.getDouble("principalY");
        p.k0         = e.getDouble("k0");
        p.k1         = e.getDouble("k1");
        p.k2         = e.getDouble("k2");

        p.gyroNoiseSigma  = e.getDouble("gyroNoiseSigma");
        p.gyroBiasSigma   = e.getDouble("gyroBiasSigma");
        p.accelNoiseSigma = e.getDouble("accelNoiseSigma");
        p.accelBiasSigma  = e.getDouble("accelBiasSigma");

        JSONArray q = e.getJSONArray("cameraExtrinsicQuat");
        if (q.length() != 4) {
            throw new JSONException("cameraExtrinsicQuat must have 4 elements, got " + q.length());
        }
        p.cameraExtrinsicQuat = new double[] {
                q.getDouble(0), q.getDouble(1), q.getDouble(2), q.getDouble(3)
        };

        JSONArray keys = e.getJSONArray("fingerprintHashKeys");
        for (int i = 0; i < keys.length(); i++) p.fingerprintHashKeys.add(keys.getString(i));
        return p;
    }

    @NonNull
    private static String requireString(@NonNull JSONObject o, @NonNull String field) throws JSONException {
        String v = o.getString(field);
        if (v.isEmpty()) throw new JSONException("empty value for required field: " + field);
        return v;
    }
}
