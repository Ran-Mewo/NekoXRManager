# ADD_DEVICE.md — adding support for a new device

For "a new pair of glasses / headset / phone needs a calibration entry."

The patcher reads `app/src/main/assets/arcore/profiles/devices.json` at runtime. Each entry maps an Android `Build.MANUFACTURER` + `Build.MODEL` pair to a populated `DeviceProfile`. Adding an entry is the only code path needed for a new device — no Java, Kotlin, or smali edits.

---

## 1. Connect the device

USB or wireless `adb`. Sanity:

```sh
adb get-state                          # expect "device"
adb shell getprop ro.build.fingerprint  # expect a non-empty string
```

## 2. Make stock ARCore log its lookup keys

ARCore hashes the fingerprint to a 6-char base64url key (algorithm not publicly documented) and logs every key it tries as `device_profile_database_helpers.cc:N] Considering X:Y` before failing. Harvest those lines from logcat.

Easiest path:

1. Install stock ARCore (`com.google.ar.core`) from APKMirror or a CDN mirror.
2. Run any ARCore-dependent app — Google Maps Live View, JustALine, etc.
3. The "Considering" lines will be in the device's logcat ring buffer.

If `harvest_device.py` reports zero hash keys, repeat step 2 just before re-running it.

## 3. Run the harvester

```sh
python tools/harvest_device.py --name "<Device Name>" --write
```

This pulls everything in one shot:

- Build properties (`manufacturer`, `model`, `fingerprint`, `soc`).
- Camera2 intrinsics from `dumpsys media.camera`, isotropically scaled to a ≤ 3MP capture mode.
- `cameraExtrinsicQuat` derived from `SENSOR_ORIENTATION` (90° / 180° / 270° / 0°).
- IMU hardware name from `dumpsys sensorservice` (left in `notes`); sigmas default to conservative ARCore max-compat values.
- `fingerprintHashKeys` scraped from the current logcat.

Add `--replace` to overwrite an existing entry (e.g. after re-calibrating).

## 4. Verify the entry

The harvester prints to `stderr` what it wrote. Sanity-check:

- `cameraExtrinsicQuat` matches your physical mount (90° = phone portrait, 270° = sideways temple-mount, 180° = upside-down).
- `captureWidth * captureHeight <= 3_000_000`.
- `focalX`, `focalY` are positive, close to each other (square pixels).
- `principalX ≈ captureWidth / 2`, `principalY ≈ captureHeight / 2`.
- `fingerprintHashKeys` is non-empty — if not, go back to step 2.

## 5. Verify the entry end-to-end

Render and inject offline against an apktool-decoded ARCore tree to confirm the toolchain agrees with itself before invoking the on-device patcher:

```sh
python tools/build_profile.py --device "<Device Name>" --out /tmp/profile.binarypb
protoc --decode_raw < /tmp/profile.binarypb | head -30   # sanity-check the wire format

apktool d arcore.apk -o /tmp/arcore_extracted
python tools/inject_profile.py --device "<Device Name>" --apktool-dir /tmp/arcore_extracted
apktool b /tmp/arcore_extracted -o /tmp/arcore_patched.apk
zipalign -p -f 4 /tmp/arcore_patched.apk /tmp/arcore_aligned.apk
apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android \
    --key-pass pass:android --out /tmp/arcore_signed.apk /tmp/arcore_aligned.apk

adb shell pm uninstall com.google.ar.core
adb install /tmp/arcore_signed.apk
adb shell am start -n com.arexperiments.justaline/.DrawARActivity
adb logcat | grep -E "Device profile for|VisualInertialState"
```

You want to see:

- `device_profile_database_helpers.cc] Device profile for <fingerprint>: <hash>:<api>` — lookup succeeded.
- `VisualInertialState is kNotTracking. Wait.` flipping to tracking once the device moves — VIO converged on your intrinsics.

If tracking is rotated/mirrored or VIO never converges: `cameraExtrinsicQuat` is wrong for the mount. Try the other quat from the table in `DeviceProfile.java`.

## 6. Commit

```sh
git add app/src/main/assets/arcore/profiles/devices.json
git commit -m "devices: add <Device Name> (<manufacturer> <model>)"
```
