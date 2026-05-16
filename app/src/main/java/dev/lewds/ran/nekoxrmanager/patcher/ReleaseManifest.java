package dev.lewds.ran.nekoxrmanager.patcher;

import com.squareup.moshi.Json;

import java.util.List;
import java.util.Map;

/**
 * Schema for the remote release manifest fetched per {@code arcore/manifest_url.txt}.
 * Forward-compatible — parser tolerates unknown fields.
 *
 * <p>Mutable POJOs so Moshi's reflective adapter can populate them without a code-gen step.</p>
 */
public final class ReleaseManifest {

    @Json(name = "schema_version") public int schemaVersion = 1;
    @Json(name = "latest") public Release latest;
    @Json(name = "compatible_versions") public List<Long> compatibleVersions;
    @Json(name = "patch_sets") public PatchSets patchSets;
    @Json(name = "min_patcher_version") public int minPatcherVersion = 1;

    public static final class Release {
        @Json(name = "version_code") public long versionCode;
        @Json(name = "version_name") public String versionName;
        @Json(name = "min_sdk") public int minSdk;
        public Map<String, AbiAsset> abis;
    }

    public static final class AbiAsset {
        public String url;
        public String sha256;
        public long size;
    }

    public static final class PatchSets {
        /** Default patch-set asset name for arm64 — looked up under {@code assets/arcore/patches/}. */
        @Json(name = "default_arm64") public String defaultArm64 = "generic_arm64.json";
        /** Default patch-set asset name for armv7 — optional. */
        @Json(name = "default_arm32") public String defaultArm32;
        /** Per-version overrides keyed by stringified versionCode → asset relative path. */
        public Map<String, String> overrides;
    }
}
