#!/usr/bin/env python3
"""harvest_device.py — adb-probe a connected device and emit a devices.json entry.

Pulls every value the patcher needs to support a new device:
  * Build properties (manufacturer / model / fingerprint / soc)
  * Camera intrinsics from `dumpsys media.camera` (fx, fy, cx, cy, distortion,
    active array), scaled to a ≤ 3MP capture resolution
  * IMU sigmas from sensor datasheet defaults plus the hardware part name
    grepped from `dumpsys sensorservice` (left in the `notes` field)
  * Camera-to-IMU extrinsic quaternion, derived from
    `CameraCharacteristics.SENSOR_ORIENTATION`
  * Fingerprint hash keys ARCore queries, scraped from logcat lines emitted by
    `device_profile_database_helpers.cc` when any AR session is created

Usage:
    tools/harvest_device.py --name "INMO Air 3"
    tools/harvest_device.py --name "INMO Air 3" --write   # merges into devices.json
    tools/harvest_device.py --name "INMO Air 3" --write --replace   # overwrite existing entry

Prerequisite for harvesting the hash keys: any ARCore-dependent app (Google
Maps Live View, JustALine, etc.) must have been launched on the device since
the last reboot, so the "Considering X:Y" lines are in logcat. The script
prints an explicit hint if none are found.
"""

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DEFAULT_REGISTRY = REPO / "app/src/main/assets/arcore/profiles/devices.json"


# ---------- adb helpers ----------


def adb(*args: str, check: bool = True) -> str:
    out = subprocess.run(["adb", *args], capture_output=True, text=True)
    if check and out.returncode != 0:
        sys.exit(f"adb {' '.join(args)} failed: {out.stderr.strip()}")
    return out.stdout


def getprop(name: str) -> str:
    return adb("shell", "getprop", name).strip()


def require_device() -> None:
    state = subprocess.run(["adb", "get-state"], capture_output=True, text=True)
    if state.returncode != 0:
        sys.exit("no adb device connected; plug it in or run `adb connect <host>`")


# ---------- Camera2 intrinsics ----------


def parse_camera_dumpsys(text: str) -> dict:
    """Picks the first per-camera block out of `dumpsys media.camera` and pulls intrinsics."""

    def grab(key: str) -> list[float]:
        for i, line in enumerate(text.splitlines()):
            if key in line and i + 1 < len(text.splitlines()):
                nxt = text.splitlines()[i + 1].strip()
                m = re.search(r"\[([^\]]+)\]", nxt)
                if not m:
                    continue
                return [float(x) for x in m.group(1).split()]
        return []

    intr = grab("android.lens.intrinsicCalibration")  # [fx fy cx cy skew]
    dist = grab("android.lens.distortion")            # [k1 k2 k3 ...]
    active = grab("android.sensor.info.activeArraySize")  # [l t r b] usually [0 0 W H]
    orient = grab("android.sensor.orientation")       # [degrees]

    if len(intr) < 4 or len(active) < 4:
        sys.exit("could not parse Camera2 intrinsics from `dumpsys media.camera`")

    return {
        "fx": intr[0],
        "fy": intr[1],
        "cx_native": intr[2],
        "cy_native": intr[3],
        "k0": dist[0] if len(dist) > 0 else 0.0,
        "k1": dist[1] if len(dist) > 1 else 0.0,
        "k2": dist[2] if len(dist) > 2 else 0.0,
        "active_w": int(active[2]),
        "active_h": int(active[3]),
        "sensor_orientation": int(orient[0]) if orient else 0,
    }


def pick_capture_resolution(dumpsys_text: str, active_w: int, active_h: int) -> tuple[int, int]:
    """Largest stream-config size satisfying w*h ≤ 3_000_000, preferring 1920x1080 if advertised."""
    # YUV_420_888 outputs are listed as `[35 W H OUTPUT ...]`.
    if "[35 1920 1080 OUTPUT" in dumpsys_text:
        return 1920, 1080
    candidates = re.findall(r"\[35 (\d+) (\d+) OUTPUT", dumpsys_text)
    sizes = [(int(w), int(h)) for w, h in candidates if int(w) * int(h) <= 3_000_000]
    if not sizes:
        # Fall back to scaling the active array down isotropically until under cap.
        scale = (3_000_000 / (active_w * active_h)) ** 0.5
        return int(active_w * scale), int(active_h * scale)
    sizes.sort(key=lambda wh: wh[0] * wh[1], reverse=True)
    return sizes[0]


def extrinsic_quat_for_orientation(degrees: int) -> list[float]:
    """Camera-to-IMU rotation matching SENSOR_ORIENTATION ∈ {0, 90, 180, 270}."""
    sqrt_half = 0.70710678118654746
    if degrees == 90:
        return [0.0, -sqrt_half, sqrt_half, 0.0]
    if degrees == 270:
        return [0.0, -sqrt_half, -sqrt_half, 0.0]
    if degrees == 180:
        return [0.0, 0.0, 0.0, 1.0]
    # 0° or anything else: identity. Verify by hand if the AR view looks wrong.
    return [1.0, 0.0, 0.0, 0.0]


# ---------- IMU ----------


def parse_imu_part(dumpsys_sensors: str) -> str:
    m = re.search(
        r"\)\s+(\S+(?:\s\S+)*?)\s+Gyroscope\s+Non-wakeup",
        dumpsys_sensors,
    )
    return m.group(1).strip() if m else "unknown"


# Conservative ARCore max-compat defaults. Override per-device if a datasheet is available.
DEFAULT_IMU_SIGMAS = {
    "gyroNoiseSigma":  0.0005309,
    "gyroBiasSigma":   0.0001413,
    "accelNoiseSigma": 0.0048836,
    "accelBiasSigma":  0.0125893,
}


# ---------- fingerprint hash keys (from logcat) ----------


HASH_KEY_LINE = re.compile(
    r"device_profile_database_helpers\.cc:\d+\] Considering ([A-Za-z0-9\-_+/]{4,8}):\d+"
)


def harvest_hash_keys() -> list[str]:
    text = adb("logcat", "-d", "-t", "5000")
    keys = []
    seen = set()
    for m in HASH_KEY_LINE.finditer(text):
        k = m.group(1)
        if k not in seen:
            seen.add(k)
            keys.append(k)
    return keys


# ---------- entry assembly ----------


def build_entry(name: str) -> dict:
    require_device()
    cam_text = adb("shell", "dumpsys", "media.camera", check=False)
    sensors_text = adb("shell", "dumpsys", "sensorservice", check=False)

    cam = parse_camera_dumpsys(cam_text)
    capture_w, capture_h = pick_capture_resolution(cam_text, cam["active_w"], cam["active_h"])

    # Capture cropping is isotropic — Camera2's standard letterbox/pillarbox to a
    # specific aspect ratio crops one axis of the active array, then scales both
    # axes by the same factor. Pick that factor from whichever axis is bound.
    active_aspect = cam["active_w"] / cam["active_h"]
    capture_aspect = capture_w / capture_h
    if capture_aspect >= active_aspect:
        # capture is wider: bound by width, crop height of source
        scale = capture_w / cam["active_w"]
    else:
        scale = capture_h / cam["active_h"]

    fingerprint = getprop("ro.build.fingerprint")
    manufacturer = getprop("ro.product.manufacturer")
    model = getprop("ro.product.model")
    soc = getprop("ro.boot.hardware.platform") or getprop("ro.hardware")

    hash_keys = harvest_hash_keys()
    if not hash_keys:
        print(
            "warning: no `Considering X:Y` lines found in logcat. Launch an AR app "
            "(Maps Live View / JustALine / any ARCore client) on the device to make "
            "stock ARCore emit them, then re-run this script.",
            file=sys.stderr,
        )

    entry = {
        "name": name,
        "match": {"manufacturer": manufacturer, "model": model},
        "fingerprint": fingerprint,
        "soc": soc,
        "fingerprintHashKeys": hash_keys,
        "cameraId": "0",
        "captureWidth": capture_w,
        "captureHeight": capture_h,
        "fps": 30,
        "focalX": round(cam["fx"] * scale, 4),
        "focalY": round(cam["fy"] * scale, 4),
        "principalX": round(capture_w / 2, 4),
        "principalY": round(capture_h / 2, 4),
        "k0": cam["k0"],
        "k1": cam["k1"],
        "k2": cam["k2"],
        **DEFAULT_IMU_SIGMAS,
        "cameraExtrinsicQuat": extrinsic_quat_for_orientation(cam["sensor_orientation"]),
        "notes": (
            f"SENSOR_ORIENTATION={cam['sensor_orientation']}. "
            f"IMU: {parse_imu_part(sensors_text)}. "
            f"Capture {capture_w}x{capture_h} from active array "
            f"{cam['active_w']}x{cam['active_h']} (isotropic scale {scale:.4f})."
        ),
    }
    return entry


# ---------- registry I/O ----------


def merge_into_registry(entry: dict, registry_path: Path, replace: bool) -> None:
    data = json.loads(registry_path.read_text())
    devices = data.setdefault("devices", [])
    existing_idx = next(
        (i for i, d in enumerate(devices)
         if d.get("match", {}).get("manufacturer", "").lower() == entry["match"]["manufacturer"].lower()
         and d.get("match", {}).get("model", "").lower() == entry["match"]["model"].lower()),
        None,
    )
    if existing_idx is not None:
        if not replace:
            sys.exit(
                f"entry for {entry['match']['manufacturer']}/{entry['match']['model']} "
                f"already exists at index {existing_idx}; pass --replace to overwrite"
            )
        devices[existing_idx] = entry
    else:
        devices.append(entry)
    registry_path.write_text(json.dumps(data, indent=2) + "\n")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--name", required=True, help='human-readable device name, e.g. "INMO Air 3"')
    ap.add_argument("--write", action="store_true",
                    help=f"merge into {DEFAULT_REGISTRY.relative_to(REPO)} instead of printing to stdout")
    ap.add_argument("--replace", action="store_true",
                    help="replace any existing entry with the same manufacturer+model match")
    ap.add_argument("--registry", type=Path, default=DEFAULT_REGISTRY,
                    help="registry file to read/write (default: bundled assets/arcore/profiles/devices.json)")
    args = ap.parse_args()

    entry = build_entry(args.name)
    if args.write:
        merge_into_registry(entry, args.registry, args.replace)
        print(f"wrote entry for {args.name} → {args.registry.relative_to(REPO)}", file=sys.stderr)
    else:
        print(json.dumps(entry, indent=2))


if __name__ == "__main__":
    main()
