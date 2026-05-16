# UPDATE_ARCORE.md — maintainer runbook

For future: "Google released a new ARCore. What do I do?" Run through this top-to-bottom; no detour into Ghidra unless step 4.3 tells you to.

---

## 1. When to ship an update

- A user reports `NativePatchStep` failing with `match_count != 1` — the byte-sigs against `libarcore_c.so` no longer apply.
- A user reports `ProfileInjectStep` failing — the packed-profiles TOC schema in `assets/packed_profiles/profiles.toc` has shifted (rare; the format has been stable).
- A user reports `BypassConsumerSigStep` failing on a fresh AR app — Google moved or renamed `com.google.ar.core.SessionCreateJniHelper.checkApkSignature(Context)`. New AR apps ship with the latest ARCore SDK AAR, so this tracks ARCore releases too.
- A user reports Live View / general AR apps showing `Tracking state: NOT_TRACKING` despite the spoofer install succeeding.
- Routine: every ~2–3 ARCore releases on apkmirror.com (~quarterly). Pre-emptive validation prevents user reports.

## 2. Tools (one-time install)

All from pacman or AUR on Arch:

```sh
sudo pacman -S radare2 binutils ripgrep jq python python-lief unzip
# Optional: rizin (radare2 fork, slightly cleaner CLI)
sudo pacman -S rizin
```

Why each:
- **radare2** (`r2`) — primary RE tool. Symbol resolution, xref walking, function disassembly, byte-pattern search. Runs headless; scriptable via `r2 -q -c "...; ..."`.
- **binutils** — `objdump`, `readelf`, `nm` for fast first-look ELF inspection.
- **ripgrep** (`rg`) — search rodata strings inside extracted APK trees.
- **jq** — edit/validate manifest and patch-set JSON.
- **python-lief** — Python ELF library; used by `tools/sigtest.py` to validate patterns programmatically without spinning up r2.
- **unzip** — extract APKs.

Reference builds: previous versions that *did* work, kept under `research/ARCore/<vc>/` for diffing.

## 3. Inputs needed

- The new ARCore APK (per-ABI). Source: APKMirror `https://www.apkmirror.com/apk/google-inc/google-play-services-for-ar/`. Pick the specific `arm64-v8a + armeabi-v7a` non-bundle variant for max-compatibility — matches what we mirror.

## 4. Step-by-step

### 4.0. Prereq

`tools/sigtest.py` exists in the repo and uses the venv at `tools/.venv/`. If missing:

```sh
python -m venv tools/.venv
tools/.venv/bin/pip install lief==0.17.6
```

### 4.1. Acquire and verify

```sh
mkdir -p /tmp/arcore_<vc> && cd /tmp/arcore_<vc>
# Download manually from APKMirror or via aria2c
sha256sum *.apk
apksigner verify --print-certs *.apk | grep "Subject:"   # must match Google's known cert
aapt2 dump badging *.apk | grep -E "package:|sdkVersion"
```

Record: SHA-256 per APK, `versionCode`, `versionName`.

### 4.2. Extract

```sh
unzip -q new.apk -d extracted/
ls extracted/lib/arm64-v8a/libarcore_c.so extracted/lib/armeabi-v7a/libarcore_c.so
```

### 4.3. Sigtest the existing generic patch-set

```sh
tools/.venv/bin/python tools/sigtest.py \
    extracted/lib/arm64-v8a/libarcore_c.so \
    app/src/main/assets/arcore/patches/generic_arm64.json
```

- All matches `== 1` → new version is compatible. Skip to **4.7**.
- Any `0` or `>1` → continue to **4.4** to re-derive the broken targets.

### 4.4. Re-derive sigs via radare2

Methodology: **anchor on a stable rodata string or exported symbol, walk to the function, extract a wildcarded sig from the patch site, validate uniqueness across multiple builds.**

**(a) Find the rodata anchor.**

```sh
readelf -s --wide extracted/lib/arm64-v8a/libarcore_c.so \
    | rg 'containsMatchingProfile|geoar_device_blocklist|imu_based_6dof_allowlist'

strings -td extracted/lib/arm64-v8a/libarcore_c.so \
    | rg -i 'unsupported device|geoar_device_blocklist|containsMatchingProfile'
```

Record symbol addresses (for JNI-exported targets) or rodata string VAs (for phenotype targets).

**(b) Walk to the owning function via radare2.**

```sh
r2 -q -c '
aaa
axt @ sym.Java_com_google_ar_core_services_CalibrationContentResolver_containsMatchingProfile
pdf @ sym.Java_com_google_ar_core_services_CalibrationContentResolver_containsMatchingProfile | head -60
' extracted/lib/arm64-v8a/libarcore_c.so
```

For string-anchored targets:

```sh
r2 -q -c '
aaa
/ Unsupported device
axt @ <address from search>
pdf @ <function_addr> | head -80
' extracted/lib/arm64-v8a/libarcore_c.so
```

**(c) Identify the patch site.** Usually the function prologue (for "force return true"), or a specific compare/branch (for phenotype always-allow). Note 16–32 instruction bytes covering it.

**(d) Build the wildcarded sig.**

```sh
r2 -q -c 'aaa; s <patch_site_addr>; p8 32' extracted/lib/arm64-v8a/libarcore_c.so
```

Replace PC-relative immediates with `??`. AArch64 heuristic: in `B`/`BL`/`ADR`/`ADRP`/`LDR (literal)`, wildcard the low 3 bytes (displacement bits). Keep opcode bits and constant `MOV` immediates verbatim.

**(e) Validate uniqueness.**

```sh
r2 -q -c '/x AA BB ?? ?? CC DD ...' extracted/lib/arm64-v8a/libarcore_c.so
# Must return exactly one hit.
```

**(f) Cross-build validation.**

```sh
for vc in <prev_vc_1> <prev_vc_2>; do
    echo "=== $vc ==="
    r2 -q -c '/x AA BB ?? ?? CC DD ...' research/ARCore/$vc/lib/arm64-v8a/libarcore_c.so
done
```

The pattern must produce exactly one hit on each prior known-good build. If any fail, broaden wildcards or anchor on a different rodata string and restart from (a).

**(g) Replacement bytes.**

Same length as patch site. `??` in the replacement = "preserve original byte at that offset" — use this for anchor bytes you don't want to clobber.

```
AArch64 "return true":  20 00 80 52 C0 03 5F D6     (mov w0,#1; ret)
AArch64 NOP padding:    1F 20 03 D5
ARMv7   "return true":  01 00 A0 E3 1E FF 2F E1
ARMv7   NOP padding:    00 F0 20 E3
```

**(h) ARMv7 pass.** Repeat (a)–(g) for `lib/armeabi-v7a/libarcore_c.so`. Sigs and replacements differ.

### 4.5. Update the patch-set JSON

Path: `app/src/main/assets/arcore/patches/`.

- New sigs match the previous ≥2 builds AND the new build → update `generic_arm64.json` / `generic_arm32.json` in place; append the new versionCode to the doc comment.
- New sigs only match the new build → write `overrides/v<vc>_arm64.json` instead, leave the generic set alone.

Schema (see `NativePatcher.PatchSet`):

```json
{
  "id": "generic-arm64-v1",
  "comment": "Validated against ARCore 1.50/1.51/1.52 (vc 252900200/253020401/253140493).",
  "abi": "arm64-v8a",
  "so": "lib/arm64-v8a/libarcore_c.so",
  "patches": [
    {
      "name": "containsMatchingProfile -> true",
      "symbol": "Java_com_google_ar_core_services_CalibrationContentResolver_containsMatchingProfile",
      "anchor": { "kind": "symbol", "name": "Java_com_google_ar_core_services_CalibrationContentResolver_containsMatchingProfile" },
      "patterns": [
        {
          "comment": "Anchored on the b.ne before the return-true cleanup; patches AND→MOV w0,#1.",
          "sig":         "?? ?? ?? 54 60 02 00 12 ...",
          "replacement": "?? ?? ?? ?? 20 00 80 52 ..."
        }
      ]
    }
  ]
}
```

`anchor.kind` is `symbol` (exported symbol name) or `string` (rodata literal); the runtime resolver narrows the search window before sigscanning. `??` in `replacement` = "preserve the original byte at that offset" — use it for anchor bytes you want to keep intact.

Validate:

```sh
jq . app/src/main/assets/arcore/patches/generic_arm64.json   # parses
for vc in <vc_new> <prev_vc_1> <prev_vc_2>; do
    tools/.venv/bin/python tools/sigtest.py \
        research/ARCore/$vc/lib/arm64-v8a/libarcore_c.so \
        app/src/main/assets/arcore/patches/generic_arm64.json
done
```

### 4.6. Compute and pin SHA-256

```sh
sha256sum *.apk
```

Both ABIs — they go into the remote manifest in 4.8.

### 4.7. Mirror the APK

APKs live in this repo under `dist/cdn/`. Naming: `arcore_v<version_code>_<abi>.apk` — stable so old manifest entries keep working.

We only keep the **latest** ARCore version in `dist/cdn/` to avoid bloating the repo; older builds remain reachable through git history (`git log --all -- dist/cdn/`). When shipping a new version, delete the previous APKs from `dist/cdn/` in the same commit that adds the new ones.

```sh
# from /tmp/arcore_<vc>:
git -C ~/Code/NekoXRManager rm dist/cdn/arcore_v*.apk 2>/dev/null || true
cp arcore_v<vc>_arm64-v8a.apk   ~/Code/NekoXRManager/dist/cdn/
cp arcore_v<vc>_armeabi-v7a.apk ~/Code/NekoXRManager/dist/cdn/
```

URL pattern resolved by the patcher (served by `raw.githubusercontent.com`, no auth):

```
https://raw.githubusercontent.com/Ran-Mewo/NekoXRManager/main/dist/cdn/arcore_v<vc>_<abi>.apk
```

### 4.8. Update the remote manifest

The manifest lives at `dist/manifest.json` in this repo and is fetched at runtime via the URL in `app/src/main/assets/arcore/manifest_url.txt` (raw.githubusercontent.com on `main`).

```sh
cd ~/Code/NekoXRManager
SIZE64=$(stat -c%s dist/cdn/arcore_v<vc>_arm64-v8a.apk)
SIZE32=$(stat -c%s dist/cdn/arcore_v<vc>_armeabi-v7a.apk)
SHA64=$(sha256sum dist/cdn/arcore_v<vc>_arm64-v8a.apk   | awk '{print $1}')
SHA32=$(sha256sum dist/cdn/arcore_v<vc>_armeabi-v7a.apk | awk '{print $1}')
BASE='https://raw.githubusercontent.com/Ran-Mewo/NekoXRManager/main/dist/cdn'

jq --argjson vc <vc> --arg vname "<version_name>" \
   --arg sha64 "$SHA64" --arg sha32 "$SHA32" \
   --argjson size64 "$SIZE64" --argjson size32 "$SIZE32" \
   --arg base "$BASE" '
  .latest = {
    version_code: $vc,
    version_name: $vname,
    min_sdk: 31,
    abis: {
      "arm64-v8a":   { url: ($base + "/arcore_v" + ($vc|tostring) + "_arm64-v8a.apk"),   sha256: $sha64, size: $size64 },
      "armeabi-v7a": { url: ($base + "/arcore_v" + ($vc|tostring) + "_armeabi-v7a.apk"), sha256: $sha32, size: $size32 }
    }
  }
  | .compatible_versions = ((.compatible_versions // []) + [$vc] | unique)
' dist/manifest.json > /tmp/m && mv /tmp/m dist/manifest.json
```

If a version-override was written in 4.5, also patch `patch_sets.overrides`:

```sh
jq --argjson vc <vc> '.patch_sets.overrides[($vc|tostring)] = ("overrides/v" + ($vc|tostring) + "_arm64.json")' \
    dist/manifest.json > /tmp/m && mv /tmp/m dist/manifest.json
```

Validate + push:

```sh
jq . dist/manifest.json > /dev/null
git add app/src/main/assets/arcore/patches/ dist/manifest.json dist/cdn/
git commit -m "arcore: support v<version_name> (vc <vc>)"
git tag arcore-<version_name>
git push --tags origin main
```

Only bump `min_patcher_version` if the new manifest schema needs parser logic older builds lack (rare).

### 4.9. Smoke-test

- On a test INMO Air 3 (or any device with the patcher): clear cache, run patcher in **ARCore mode**. Confirm `FetchManifestStep` picks up the new entry, `DownloadArCoreStep` verifies the new SHA-256, `NativePatchStep` reports all `match_count == 1`, `ProfileInjectStep` writes a new `profiles_NNNNN.dat`, install succeeds.
- Then run the patcher in **CONSUMER_APP mode** against a real AR app (e.g. JustALine — bundles the latest ARCore SDK AAR). Confirm `BypassConsumerSigStep` rewrites `classes*.dex`, install succeeds, and opening the app does not log `ARCore-SessionCreateJniHelper: Signature mismatch`. If it does, see §4.10.
- Launch Google Maps Live View on the glasses; verify it loads.
- If any of the above fails: rollback the manifest (`latest` → previous version) and debug.

### 4.10. When `BypassConsumerSigStep` breaks

The bypass NOPs `com.google.ar.core.SessionCreateJniHelper.checkApkSignature(Landroid/content/Context;)Z` to `return true`. Failure modes:

- **Class not found**: Google renamed `SessionCreateJniHelper` or moved it. Find the new owner by greping a fresh ARCore SDK consumer APK:

```sh
unzip -p justaline.apk classes.dex classes2.dex classes3.dex 2>/dev/null \
  | strings | rg -i 'SessionCreate|checkApkSignature|Signature mismatch'
```

  The log tag (`ARCore-SessionCreateJniHelper`) usually still names the class. Update `ConsumerSigBypass.TARGET_CLASS_DESCRIPTOR` / `TARGET_METHOD_NAME`.

- **Method signature changed**: e.g. `checkApkSignature(Context, ...)` gained an argument. Update `TARGET_METHOD_PARAMS`.

- **Native side calls a different Java entry point**: rare but possible. Confirm with logcat — a passing bypass still produces `ARCore-SessionCreateJniHelper: Signature mismatch` means the native shim is now calling a different method. RE `libarcore_sdk_c.so` for `RegisterNatives` / `GetMethodID` strings.

## 5. Things that should never change without thinking carefully

- Manifest schema is forward-compatible (new fields ignored by old patchers). Do not rename existing fields without bumping `schema_version` and updating the patcher's parser.
- Generic-set IDs (`generic-v1-arm64`, `generic-v2-arm64`, …) are append-only — old `neko_install.json` metadata references them.
- Mirror URL pattern is load-bearing for old patcher builds. Don't relocate without first shipping a new `manifest_url.txt` in the patcher binary itself.

## 6. Quick checklist per release

- [ ] `research/ARCore/<vc>/` populated with extracted APK.
- [ ] `app/src/main/assets/arcore/patches/generic_arm64.json` updated *or* `overrides/v<vc>_arm64.json` added.
- [ ] `tools/sigtest.py` passes against the new build *and* prior 2 reference builds.
- [ ] APKs placed in `dist/cdn/` under `arcore_v<vc>_<abi>.apk` (and previous versions removed in the same commit).
- [ ] `dist/manifest.json` updated, validated by `jq`, pushed to `main` so `raw.githubusercontent.com` serves it.
- [ ] Patcher repo commit + tag pushed.
- [ ] Smoke test on physical device — ARCore mode AND consumer-app mode against a recently-updated AR app — passed.
