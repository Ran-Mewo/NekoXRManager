#!/usr/bin/env python3
"""
Auto-refresh the ARCore release manifest.

Resolves the latest Google Play Services for AR (ARCore) release from APKMirror,
downloads the arm64-v8a APK, uploads it to a GitHub Release (stable per-versionCode
asset URL), and rewrites dist/manifest.json so the patcher fetches the new build on
its next run. Idempotent: if the manifest already points at the latest versionCode,
the script exits 0 without touching anything.

Designed to run from CI; depends only on stdlib + `requests` and the `gh` CLI.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Optional

import requests

APKMIRROR_FEED = "https://www.apkmirror.com/apk/google-inc/arcore/feed/"
APKMIRROR_ROOT = "https://www.apkmirror.com"

UA = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"
)
HEADERS = {"User-Agent": UA, "Accept-Language": "en-US,en;q=0.9"}

# Stable asset name pattern. Release tag is arcore-v<vc>.
RELEASE_TAG = "arcore-v{vc}"
ASSET_NAME = "arcore_v{vc}_arm64-v8a.apk"

REPO_ROOT = Path(__file__).resolve().parent.parent
MANIFEST_PATH = REPO_ROOT / "dist" / "manifest.json"


def log(msg: str) -> None:
    print(f"[refresh] {msg}", flush=True)


def http_get(url: str, *, stream: bool = False) -> requests.Response:
    r = requests.get(url, headers=HEADERS, stream=stream, timeout=60, allow_redirects=True)
    r.raise_for_status()
    return r


def latest_release_page() -> str:
    """Parse the APKMirror RSS feed for ARCore and return the URL of the newest non-beta release page."""
    body = http_get(APKMIRROR_FEED).text
    # RSS <item><link>https://www.apkmirror.com/apk/google-inc/arcore/.../google-play-services-for-ar-X-release/</link>
    candidates = re.findall(r"<link>(https://www\.apkmirror\.com/apk/google-inc/arcore/[^<]+-release/)</link>", body)
    if not candidates:
        raise RuntimeError("no release links found in APKMirror RSS")
    for link in candidates:
        if "beta" in link.lower() or "alpha" in link.lower():
            continue
        return link
    return candidates[0]


def find_arm64_variant_page(release_url: str) -> str:
    """On a release page, locate the arm64-v8a non-bundle nodpi variant link.

    APKMirror lays out variants as a structured table — one `<div class="table-row">`
    per (versionCode × arch × min-Android × DPI × signature) row. Cells in order:
    Variant (with APK/BUNDLE badge), Architecture, Minimum Version, Screen DPI, Download.
    """
    body = http_get(release_url).text
    rows = re.findall(
        r'<div class="table-row[^"]*">(.*?)(?=<div class="table-row|<div class="listWidget)',
        body,
        re.DOTALL,
    )
    for row in rows:
        # Architecture cell may be "arm64-v8a" or a fat combo like "arm64-v8a + armeabi-v7a".
        if "arm64-v8a" not in row:
            continue
        if re.search(r'<span class="apkm-badge">BUNDLE</span>', row):
            continue
        if not re.search(r'<span class="apkm-badge">APK</span>', row):
            continue
        if not re.search(r'>\s*nodpi\s*<', row):
            continue
        m = re.search(
            r'href="(/apk/google-inc/arcore/[^"]+-android-apk-download/)"',
            row,
        )
        if m:
            return APKMIRROR_ROOT + m.group(1)
    raise RuntimeError(f"no arm64-v8a APK nodpi variant found on {release_url}")


def follow_to_apk_download_link(variant_url: str) -> str:
    """Variant page → 'Download APK' button → page with final download link → final URL."""
    body = http_get(variant_url).text
    m = re.search(r'href="(/apk/google-inc/arcore/[^"]+/download/[^"]*)"', body)
    if not m:
        raise RuntimeError(f"no /download/ link on variant page {variant_url}")
    dl_page = APKMIRROR_ROOT + m.group(1)

    body2 = http_get(dl_page).text
    # Final page contains a "click here" anchor pointing at the verify-token URL.
    m2 = re.search(r'href="(https://download\.apkmirror\.com/[^"]+\.apk\?verify=[^"]+)"', body2)
    if not m2:
        # Fallback: relative path on apkmirror.com that 302s to download.apkmirror.com.
        m2 = re.search(r'href="(/wp-content/themes/APKMirror/download\.php\?id=[^"]+)"', body2)
        if not m2:
            raise RuntimeError(f"no final apk URL on {dl_page}")
        return APKMIRROR_ROOT + m2.group(1)
    return m2.group(1)


def extract_version_code(variant_url: str) -> int:
    """The variant slug encodes the real 9-digit versionCode.

    e.g. .../google-play-services-for-ar-1-54-260890493-android-apk-download/ → 260890493
    A `-2-`/`-3-` sub-variant suffix may follow the versionCode for x86/duplicates.
    """
    m = re.search(r"-(\d{9,})(?:-\d+)?-android-apk-download/?$", variant_url)
    if not m:
        raise RuntimeError(f"cannot parse versionCode from {variant_url}")
    return int(m.group(1))


def extract_version_name(variant_url: str) -> str:
    """e.g. .../google-play-services-for-ar-1-54-260890493-android-apk-download/ → 1.54.260890493"""
    m = re.search(r"google-play-services-for-ar-(\d+(?:-\d+){2,})(?:-\d+)?-android-apk-download/?$", variant_url)
    if not m:
        return "unknown"
    return m.group(1).replace("-", ".")


def download(url: str, dest: Path) -> tuple[str, int]:
    """Stream-download to dest, return (sha256_hex, size)."""
    h = hashlib.sha256()
    size = 0
    with http_get(url, stream=True) as r:
        with dest.open("wb") as f:
            for chunk in r.iter_content(chunk_size=1 << 16):
                if not chunk:
                    continue
                f.write(chunk)
                h.update(chunk)
                size += len(chunk)
    return h.hexdigest(), size


def gh(*args: str, capture: bool = False) -> Optional[str]:
    cmd = ["gh", *args]
    log("$ " + " ".join(cmd))
    if capture:
        return subprocess.run(cmd, check=True, capture_output=True, text=True).stdout
    subprocess.run(cmd, check=True)
    return None


def release_exists(tag: str, repo: str) -> bool:
    try:
        gh("release", "view", tag, "--repo", repo, capture=True)
        return True
    except subprocess.CalledProcessError:
        return False


def upload_release(tag: str, repo: str, apk_path: Path, version_name: str, sha256: str) -> str:
    """Create or update the release with the APK as an asset. Returns the asset URL."""
    if not release_exists(tag, repo):
        notes = f"ARCore {version_name}\n\nsha256: `{sha256}`"
        gh("release", "create", tag, str(apk_path),
           "--repo", repo, "--title", f"ARCore {version_name}", "--notes", notes)
    else:
        # Replace asset with --clobber so re-runs are safe.
        gh("release", "upload", tag, str(apk_path), "--repo", repo, "--clobber")
    return f"https://github.com/{repo}/releases/download/{tag}/{apk_path.name}"


def read_manifest() -> dict:
    if MANIFEST_PATH.exists():
        return json.loads(MANIFEST_PATH.read_text())
    return {}


def write_manifest(m: dict) -> None:
    MANIFEST_PATH.parent.mkdir(parents=True, exist_ok=True)
    MANIFEST_PATH.write_text(json.dumps(m, indent=2) + "\n")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", "Ran-Mewo/NekoXRManager"))
    ap.add_argument("--dry-run", action="store_true", help="Resolve + download but don't upload or rewrite the manifest.")
    args = ap.parse_args()

    log(f"repo = {args.repo}")
    release_url = latest_release_page()
    log(f"latest release page: {release_url}")

    variant = find_arm64_variant_page(release_url)
    log(f"arm64-v8a variant: {variant}")
    vc = extract_version_code(variant)
    vn = extract_version_name(variant)
    log(f"versionCode = {vc}, versionName = {vn}")

    existing = read_manifest()
    existing_vc = (existing.get("latest") or {}).get("version_code")
    if existing_vc == vc:
        log(f"manifest already points at vc {vc} — nothing to do")
        return 0

    final_apk_url = follow_to_apk_download_link(variant)
    log(f"final APK URL resolved")

    with tempfile.TemporaryDirectory() as td:
        local = Path(td) / ASSET_NAME.format(vc=vc)
        log(f"downloading → {local}")
        sha256, size = download(final_apk_url, local)
        log(f"sha256 = {sha256}, size = {size}")

        if args.dry_run:
            log("dry-run: not uploading or rewriting manifest")
            return 0

        tag = RELEASE_TAG.format(vc=vc)
        asset_url = upload_release(tag, args.repo, local, vn, sha256)
        log(f"asset uploaded: {asset_url}")

    # Rewrite manifest preserving any fields we don't manage (patch_sets, min_patcher_version).
    new_manifest = dict(existing) if existing else {}
    new_manifest["schema_version"] = 1
    new_manifest["latest"] = {
        "version_code": vc,
        "version_name": vn,
        "min_sdk": (existing.get("latest") or {}).get("min_sdk", 31),
        "abis": {
            "arm64-v8a": {
                "url": asset_url,
                "sha256": sha256,
                "size": size,
            }
        },
    }
    compat = set(existing.get("compatible_versions") or [])
    compat.add(vc)
    new_manifest["compatible_versions"] = sorted(compat, reverse=True)
    new_manifest.setdefault("patch_sets", {"default_arm64": "generic_arm64.json", "overrides": {}})
    new_manifest.setdefault("min_patcher_version", 1)
    new_manifest.pop("_comment", None)

    write_manifest(new_manifest)
    log(f"manifest rewritten: {MANIFEST_PATH}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
