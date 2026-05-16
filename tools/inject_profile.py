#!/usr/bin/env python3
"""inject_profile.py — append a rendered DeviceProfile to an apktool-decoded
ARCore tree.

Writes the binarypb as the next free `profiles_NNNNN.dat` and appends TOC
records to `assets/packed_profiles/profiles.toc` covering every TOC key
variant ARCore queries for the device's fingerprint:
  * each `fingerprintHashKeys` entry, with and without the ":<api>" suffix
  * the raw "<product>/<device>" and "<device>" substrings of
    `Build.FINGERPRINT`, with and without the ":<api>" suffix

TOC schema (verified via `protoc --decode_raw`):
  repeated message TocEntry {
    1: string key
    2: message Locator { 1: int file_idx, 2: int offset, 3: int length }
  }

Usage:
    tools/inject_profile.py --device "INMO Air 3" --apktool-dir /tmp/arcore_dex/smali
"""

import argparse
import json
import re
import struct
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DEFAULT_REGISTRY = REPO / "app/src/main/assets/arcore/profiles/devices.json"

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_profile  # noqa: E402


# ---------- protobuf encode for TOC entries (subset of build_profile helpers) ----------


def varint(n: int) -> bytes:
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


def sf(f: int, v: str) -> bytes:
    s = v.encode("utf-8")
    return tag(f, 2) + varint(len(s)) + s


def mf(f: int, body: bytes) -> bytes:
    return tag(f, 2) + varint(len(body)) + body


def build_toc_entry(key: str, file_idx: int, offset: int, length: int) -> bytes:
    locator = vf(1, file_idx) + vf(2, offset) + vf(3, length)
    entry = sf(1, key) + mf(2, locator)
    return mf(1, entry)


# ---------- key derivation ----------


def derive_keys(entry: dict, api: int, fingerprint: str) -> list[str]:
    keys: list[str] = []
    seen = set()

    def add(k: str) -> None:
        if k not in seen:
            seen.add(k)
            keys.append(k)

    for h in entry.get("fingerprintHashKeys", []):
        add(f"{h}:{api}")
        add(h)

    # Fingerprint: "<brand>/<product>/<device>:<release>/<id>/<incremental>:<type>/<tags>"
    m = re.match(r"^[^/]+/([^/]+)/([^:]+):", fingerprint or "")
    if m:
        product, device = m.group(1), m.group(2)
        add(f"{product}/{device}:{api}")
        add(f"{product}/{device}")
        add(f"{device}:{api}")
        add(device)

    return keys


def next_dat_index(packed_dir: Path) -> int:
    pat = re.compile(r"^profiles_(\d{5})\.dat$")
    indices = [int(pat.match(p.name).group(1)) for p in packed_dir.glob("profiles_*.dat") if pat.match(p.name)]
    return (max(indices) + 1) if indices else 0


# ---------- API level inference ----------


def infer_api_from_fingerprint(fingerprint: str) -> int:
    m = re.match(r"^[^/]+/[^/]+/[^:]+:(\d+)", fingerprint or "")
    return int(m.group(1)) if m else 0


def fingerprint_from_adb() -> str:
    out = subprocess.run(
        ["adb", "shell", "getprop", "ro.build.fingerprint"],
        capture_output=True, text=True,
    )
    return out.stdout.strip() if out.returncode == 0 else ""


# ---------- main ----------


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--device", required=True, help='entry `name` in the registry')
    ap.add_argument("--apktool-dir", required=True, type=Path,
                    help="apktool-decoded ARCore directory (contains AndroidManifest.xml + assets/)")
    ap.add_argument("--registry", type=Path, default=DEFAULT_REGISTRY)
    ap.add_argument("--api", type=int, default=0,
                    help="Android API level for :<api> key suffix; defaults to the value parsed from the entry's fingerprint")
    args = ap.parse_args()

    registry = json.loads(args.registry.read_text())
    entry = build_profile.find_entry(registry, args.device)

    packed = args.apktool_dir / "assets/packed_profiles"
    if not packed.is_dir():
        sys.exit(f"missing {packed} — pass the apktool-decoded ARCore root")

    fingerprint = entry.get("fingerprint", "") or fingerprint_from_adb()
    api = args.api or infer_api_from_fingerprint(fingerprint)
    if api == 0:
        sys.exit("could not infer Android API level from fingerprint; pass --api explicitly")

    keys = derive_keys(entry, api, fingerprint)
    if not keys:
        sys.exit("no TOC keys derived; entry needs `fingerprintHashKeys` and/or `fingerprint`")

    blob = build_profile.render(entry)
    idx = next_dat_index(packed)
    dat_path = packed / f"profiles_{idx:05d}.dat"
    dat_path.write_bytes(blob)

    toc_path = packed / "profiles.toc"
    toc = bytearray(toc_path.read_bytes())
    for key in keys:
        toc.extend(build_toc_entry(key, idx, 0, len(blob)))
    toc_path.write_bytes(bytes(toc))

    print(f"wrote {dat_path.relative_to(args.apktool_dir)} ({len(blob)} bytes)")
    print(f"appended {len(keys)} TOC keys: {', '.join(keys)}")


if __name__ == "__main__":
    main()
