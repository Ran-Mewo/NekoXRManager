# dist/cdn

ARCore APK mirror. Naming: `arcore_v<version_code>_<abi>.apk`.

Only the latest ARCore version is kept here — older builds are pruned at release time to keep the repo from bloating. If you need a historical APK, look in the git history (`git log --all -- dist/cdn/`).

URL pattern (used by the patcher's `dist/manifest.json`):

```
https://raw.githubusercontent.com/Ran-Mewo/NekoXRManager/main/dist/cdn/arcore_v<vc>_<abi>.apk
```

GitHub `raw.githubusercontent.com` caps individual file size at 100 MB; ARCore APKs are well under that. See `UPDATE_ARCORE.md` for the full release workflow.

## Currently shipping

- `arcore_v253140493_arm64-v8a.apk` — ARCore 1.52 (vc 253140493). Currently we only ship arm64. To add 32-bit support: mirror the matching `armeabi-v7a` APK from APKMirror, derive a `generic_arm32.json` patch-set against its `lib/armeabi-v7a/libarcore_c.so`, and add an `armeabi-v7a` entry to `dist/manifest.json`.
