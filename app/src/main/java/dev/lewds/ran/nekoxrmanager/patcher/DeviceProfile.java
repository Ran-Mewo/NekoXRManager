package dev.lewds.ran.nekoxrmanager.patcher;

import android.os.Build;
import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Per-device ARCore calibration values, loaded from the bundled registry.
 *
 * <p>All fields must be populated from {@code assets/arcore/profiles/devices.json};
 * none have meaningful defaults. {@link #validate()} fails loud if the registry
 * entry is missing required values.</p>
 *
 * <p>Capture resolution must be clamped to {@code <= 3MP} (lightweight glasses
 * thermal limit).</p>
 */
public final class DeviceProfile {

    /** Human-readable label, e.g. "INMO Air 3". */
    public String name = "";

    public String manufacturer = "";
    public String model = "";
    public String fingerprint = "";
    public String soc = "";

    /** Camera2 logical id of the world-facing camera. */
    public String cameraId = "";

    /** Capture frame size — must satisfy {@code capture_w * capture_h <= 3_000_000}. */
    public int captureWidth;
    public int captureHeight;
    public int fps;

    /** Focal length in pixels at the captured resolution. */
    public double focalX;
    public double focalY;

    /** Principal point in pixels. */
    public double principalX;
    public double principalY;

    /** Brown-Conrady poly3 distortion coefficients (k1, k2, k3 in OpenCV terms). */
    public double k0;
    public double k1;
    public double k2;

    /** IMU noise/bias sigmas. */
    public double gyroNoiseSigma;
    public double gyroBiasSigma;
    public double accelNoiseSigma;
    public double accelBiasSigma;

    /**
     * Camera-to-IMU extrinsic quaternion {@code (w, x, y, z)}.
     *
     * <p>Determined by where the camera is physically mounted relative to the IMU,
     * which Android exposes via {@code CameraCharacteristics.SENSOR_ORIENTATION}:
     * <ul>
     *   <li>90° mount (phone portrait): {@code (0, -√½, +√½, 0)}</li>
     *   <li>270° mount (e.g. temple-side glasses): {@code (0, -√½, -√½, 0)}</li>
     *   <li>180° mount (upside-down): {@code (0, 0, 0, 1)}</li>
     * </ul>
     * Symptom of a wrong value: AR view is rotated/mirrored and VIO never
     * converges because gyro and image motion disagree.</p>
     */
    public double[] cameraExtrinsicQuat = new double[0];

    /**
     * 6-character base64url keys ARCore looks up in {@code profiles.toc}.
     *
     * <p>ARCore hashes a normalized {@code MANUFACTURER/BRAND/PRODUCT/...} string
     * into a key plus {@code :<api>} suffix. The hash algorithm is not publicly
     * documented; per-device values are harvested by running stock ARCore and
     * scraping {@code device_profile_database_helpers.cc Considering ...} lines
     * from logcat.</p>
     */
    public final List<String> fingerprintHashKeys = new ArrayList<>();

    public DeviceProfile() {}

    @NonNull
    @Override
    public String toString() {
        return "DeviceProfile{" + name + " (" + manufacturer + " " + model + ")"
                + ", capture=" + captureWidth + "x" + captureHeight + "@" + fps
                + ", f=(" + focalX + "," + focalY + ")"
                + ", c=(" + principalX + "," + principalY + ")"
                + ", k=(" + k0 + "," + k1 + "," + k2 + ")}";
    }

    /** Returns true if {@code capture_w * capture_h <= 3_000_000}. */
    public boolean withinThermalCap() {
        return ((long) captureWidth) * captureHeight <= 3_000_000L;
    }

    /** Throws {@link IllegalStateException} if any required field is unset. */
    public void validate() {
        require(!name.isEmpty(),         "name");
        require(!manufacturer.isEmpty(), "manufacturer");
        require(!model.isEmpty(),        "model");
        require(!cameraId.isEmpty(),     "cameraId");
        require(captureWidth > 0,        "captureWidth");
        require(captureHeight > 0,       "captureHeight");
        require(fps > 0,                 "fps");
        require(focalX > 0,              "focalX");
        require(focalY > 0,              "focalY");
        require(principalX > 0,          "principalX");
        require(principalY > 0,          "principalY");
        require(gyroNoiseSigma > 0,      "gyroNoiseSigma");
        require(gyroBiasSigma > 0,       "gyroBiasSigma");
        require(accelNoiseSigma > 0,     "accelNoiseSigma");
        require(accelBiasSigma > 0,      "accelBiasSigma");
        require(cameraExtrinsicQuat.length == 4, "cameraExtrinsicQuat (4-element array)");
        require(!fingerprintHashKeys.isEmpty(),  "fingerprintHashKeys (>= 1 entry)");
        if (!withinThermalCap()) {
            throw new IllegalStateException(
                    "capture " + captureWidth + "x" + captureHeight + " exceeds 3MP thermal cap");
        }
    }

    private static void require(boolean cond, @NonNull String field) {
        if (!cond) throw new IllegalStateException("DeviceProfile." + field + " not set");
    }

    /**
     * Returns every TOC key variant ARCore is known to query for {@code fingerprint},
     * combining {@link #fingerprintHashKeys} with the raw {@code <product>/<device>}
     * and {@code <device>} substrings extracted from the fingerprint. Each variant is
     * emitted with and without the {@code :<api>} suffix. Order is preserved,
     * duplicates suppressed.
     */
    @NonNull
    public List<String> buildAllProfileKeys(@NonNull String fingerprint) {
        int api = Build.VERSION.SDK_INT;
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String h : fingerprintHashKeys) {
            out.add(h + ":" + api);
            out.add(h);
        }
        // Fingerprint layout: "<brand>/<product>/<device>:<release>/<id>/<incremental>:<type>/<tags>"
        if (fingerprint != null && !fingerprint.isEmpty()) {
            int slash1 = fingerprint.indexOf('/');
            int slash2 = slash1 >= 0 ? fingerprint.indexOf('/', slash1 + 1) : -1;
            int colon  = slash2 >= 0 ? fingerprint.indexOf(':', slash2 + 1) : -1;
            if (slash2 > slash1 && colon > slash2) {
                String product = fingerprint.substring(slash1 + 1, slash2);
                String device  = fingerprint.substring(slash2 + 1, colon);
                String prodDev = product + "/" + device;
                out.add(prodDev + ":" + api);
                out.add(prodDev);
                out.add(device + ":" + api);
                out.add(device);
            }
        }
        return new ArrayList<>(out);
    }
}
