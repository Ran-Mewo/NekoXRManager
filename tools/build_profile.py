#!/usr/bin/env python3
"""build_profile.py — render a device entry from devices.json into ARCore's
DeviceProfile protobuf wire-format.

This is the Python counterpart of `DeviceProfileBypass.renderBinarypb` in the
Android patcher; the two implementations encode the same schema. Use this for
offline / CLI patching of an apktool-decoded ARCore tree.

DeviceProfile schema (recovered via `protoc --decode_raw` on stock dat slabs):

  Top-level DeviceProfile {
    1  cameras             repeated message
    2  imus                repeated map-entry { int32 key; IMUInfo value }
    3  camera_extrinsics   repeated Extrinsic
    4  general_extrinsics  repeated Extrinsic
  }

  Camera {
    1  camera_id                                  string
    2  camera_direction                           enum  REAR=1 FRONT=2
    3  rolling_shutter_readout_time_ns            int64
    4  rolling_shutter_direction                  enum  POS_X=2 POS_Y=3
    5  camera_timestamp_alignment_ns              int64
    6  calibrated_width                           int32
    7  calibrated_height                          int32
    8  calibrated_focal_length                    { 1:fx 2:fy } doubles
    9  calibrated_principal_point                 { 1:cx 2:cy } doubles
    11 distortion_poly3                           { 1:k0 2:k1 3:k2 } doubles
    14 exposure_timestamp_meaning                 enum  BEGINNING_OF_EXPOSURE=1
    16 stream_groups                              StreamGroup
    17 camera_timebase_trust_level                enum  RUNTIME_CHECK_REQUIRED=1
    18 rolling_shutter_data_source                enum  DEVICE_PROFILE=1
    27 depth_api_support_mode                     enum  UNSUPPORTED=2
  }

  StreamGroup {
    1  streams                                    repeated Stream
    3  supported_frame_rates                      { 1:min 2:max }
  }

  Stream {
    1  stream_types                               { 1:usage 2:ordinal }
    2  format                                     int32   YUV_420_888=35 PRIVATE=34
    3  supported_resolutions                      { 1:w 2:h }
  }

  IMUInfo {
    1  gyro_scale          { 1:x 2:y 3:z } doubles
    2  gyro_misalignment   { 1:x 2:y 3:z } doubles
    3  accel_scale         { 1:x 2:y 3:z } doubles
    4  accel_misalignment  { 1:x 2:y 3:z } doubles
    5  gyro_q_accel        { 1:w 2:x 3:y 4:z } doubles
    6..9  gyro/accel noise/bias sigmas              double
    10 default_gyro_bias   { 1:x 2:y 3:z } doubles
    11 default_accel_bias  { 1:x 2:y 3:z } doubles
    14 gyro_timestamp_alignment_ns                int
    15 accel_timestamp_alignment_ns               int
    16 gyro_type                                  enum  GYROSCOPE_CALIBRATED=2
  }

  Extrinsic {
    1  frame_id / frame_a_id                       enum (or imu key int)
    2  camera_id / frame_b_id                      string or enum
    3  frame_t_camera / a_t_b                      { 1:p{x,y,z} 2:q{w,x,y,z} }
  }

Frame-id enum values for extrinsic records:
  IMU_0=100, ANDROID_FRAME=40, OPENGL=42, CAMERA_PRIMARY=40
"""

import argparse
import json
import struct
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DEFAULT_REGISTRY = REPO / "app/src/main/assets/arcore/profiles/devices.json"


# ---------- protobuf wire-format encoder ----------


def varint(n: int) -> bytes:
    if n < 0:
        n &= (1 << 64) - 1
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def tag(field: int, wire: int) -> bytes:
    return varint((field << 3) | wire)


def vf(f: int, v: int) -> bytes:
    return tag(f, 0) + varint(v)


def df(f: int, v: float) -> bytes:
    return tag(f, 1) + struct.pack("<d", v)


def sf(f: int, v: str) -> bytes:
    s = v.encode("utf-8")
    return tag(f, 2) + varint(len(s)) + s


def mf(f: int, body: bytes) -> bytes:
    return tag(f, 2) + varint(len(body)) + body


def xyz(x: float, y: float, z: float) -> bytes:
    return df(1, x) + df(2, y) + df(3, z)


def xy(x: float, y: float) -> bytes:
    return df(1, x) + df(2, y)


def quat(w: float, x: float, y: float, z: float) -> bytes:
    return df(1, w) + df(2, x) + df(3, y) + df(4, z)


# ---------- DeviceProfile schema constants ----------

REAR_FACING = 1
POS_Y_READOUT = 3
BEGINNING_OF_EXPOSURE = 1
RUNTIME_CHECK_REQUIRED = 1
DEVICE_PROFILE_TIMEBASE = 1
DEPTH_API_UNSUPPORTED = 2

USAGE_FEATURE_TRACKING = 1
USAGE_AR_PASSTHROUGH = 2
USAGE_AUXILIARY_CV = 5
FORMAT_YUV_420_888 = 35
FORMAT_PRIVATE = 34

FRAME_IMU_0 = 100
FRAME_ANDROID_FRAME = 40
FRAME_OPENGL = 42
FRAME_CAMERA_PRIMARY = 40
GYROSCOPE_CALIBRATED = 2

ZERO_P = (0.0, 0.0, 0.0)
Q_GL = (0.70710678118654757, 0.0, 0.0, 0.70710678118654746)
Q_ID = (1.0, 0.0, 0.0, 0.0)


def stream(usage: int, ordinal: int, fmt: int, w: int, h: int) -> bytes:
    types = vf(1, usage) + vf(2, ordinal)
    res = vf(1, w) + vf(2, h)
    return mf(1, types) + vf(2, fmt) + mf(3, res)


def transform(p: tuple, q: tuple) -> bytes:
    return mf(1, xyz(*p)) + mf(2, quat(*q))


def render(entry: dict) -> bytes:
    cam_id = str(entry["cameraId"])
    w = int(entry["captureWidth"])
    h = int(entry["captureHeight"])
    fps = int(entry["fps"])
    q_cam = tuple(entry["cameraExtrinsicQuat"])

    cam = (
        sf(1, cam_id)
        + vf(2, REAR_FACING)
        + vf(3, 33_333_333)
        + vf(4, POS_Y_READOUT)
        + vf(5, 0)
        + vf(6, w)
        + vf(7, h)
        + mf(8, xy(float(entry["focalX"]), float(entry["focalY"])))
        + mf(9, xy(float(entry["principalX"]), float(entry["principalY"])))
        + mf(11, xyz(float(entry["k0"]), float(entry["k1"]), float(entry["k2"])))
        + vf(14, BEGINNING_OF_EXPOSURE)
    )
    sg = (
        mf(1, stream(USAGE_FEATURE_TRACKING, 0, FORMAT_YUV_420_888, w, h))
        + mf(1, stream(USAGE_AUXILIARY_CV,    0, FORMAT_YUV_420_888, w, h))
        + mf(1, stream(USAGE_AR_PASSTHROUGH,  0, FORMAT_PRIVATE,     w, h))
        + mf(3, vf(1, fps) + vf(2, fps))
    )
    cam += mf(16, sg)
    cam += vf(17, RUNTIME_CHECK_REQUIRED)
    cam += vf(18, DEVICE_PROFILE_TIMEBASE)
    cam += vf(27, DEPTH_API_UNSUPPORTED)

    imu_info = (
        mf(1, xyz(1.0, 1.0, 1.0))
        + mf(2, xyz(0.0, 0.0, 0.0))
        + mf(3, xyz(1.0, 1.0, 1.0))
        + mf(4, xyz(0.0, 0.0, 0.0))
        + mf(5, quat(1.0, 0.0, 0.0, 0.0))
        + df(6, float(entry["gyroNoiseSigma"]))
        + df(7, float(entry["gyroBiasSigma"]))
        + df(8, float(entry["accelNoiseSigma"]))
        + df(9, float(entry["accelBiasSigma"]))
        + mf(10, xyz(0.0, 0.0, 0.0))
        + mf(11, xyz(0.0, 0.0, 0.0))
        + vf(14, 0)
        + vf(15, 0)
        + vf(16, GYROSCOPE_CALIBRATED)
    )
    imu_entry = vf(1, FRAME_IMU_0) + mf(2, imu_info)

    cam_ext = vf(1, FRAME_IMU_0) + sf(2, cam_id) + mf(3, transform(ZERO_P, q_cam))
    gen1 = vf(1, FRAME_IMU_0) + vf(2, FRAME_CAMERA_PRIMARY) + mf(3, transform(ZERO_P, q_cam))
    gen2 = vf(1, FRAME_ANDROID_FRAME) + vf(2, FRAME_OPENGL) + mf(3, transform(ZERO_P, Q_GL))
    gen3 = vf(1, FRAME_IMU_0) + vf(2, FRAME_ANDROID_FRAME) + mf(3, transform(ZERO_P, Q_ID))

    return (
        mf(1, cam) + mf(2, imu_entry) + mf(3, cam_ext)
        + mf(4, gen1) + mf(4, gen2) + mf(4, gen3)
    )


# ---------- entry lookup ----------


def find_entry(registry: dict, name: str) -> dict:
    for d in registry.get("devices", []):
        if d.get("name") == name:
            return d
    sys.exit(f"no entry named '{name}' in registry; available: "
             + ", ".join(d.get("name", "?") for d in registry.get("devices", [])))


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--device", required=True, help='entry `name` to look up in the registry, e.g. "INMO Air 3"')
    ap.add_argument("--registry", type=Path, default=DEFAULT_REGISTRY)
    ap.add_argument("--out", type=Path, help="output binarypb path; default: stdout")
    args = ap.parse_args()

    registry = json.loads(args.registry.read_text())
    entry = find_entry(registry, args.device)
    blob = render(entry)

    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_bytes(blob)
        print(f"wrote {args.out} ({len(blob)} bytes)", file=sys.stderr)
    else:
        sys.stdout.buffer.write(blob)


if __name__ == "__main__":
    main()
