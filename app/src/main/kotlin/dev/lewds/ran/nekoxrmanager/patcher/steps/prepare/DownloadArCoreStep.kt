package dev.lewds.ran.nekoxrmanager.patcher.steps.prepare

import dev.lewds.ran.nekoxrmanager.R
import dev.lewds.ran.nekoxrmanager.di.ServiceLocator
import dev.lewds.ran.nekoxrmanager.patcher.NekoPatchRunner
import dev.lewds.ran.nekoxrmanager.patcher.steps.StepGroup
import dev.lewds.ran.nekoxrmanager.patcher.steps.base.Step
import dev.lewds.ran.nekoxrmanager.patcher.util.ManifestClient
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Downloads the ARCore APK matching this device's ABI via the manifest entry. Verifies
 * SHA-256. Caches under {@code PathManager.cacheDir} so re-runs are instant. If no
 * manifest is available (FetchManifestStep skipped) or no compatible ABI: skip and let
 * [PickApkStep] handle a user-supplied APK.
 */
class DownloadArCoreStep : Step() {
    override val group = StepGroup.Prepare
    override val localizedName = R.string.step_download_arcore

    override suspend fun execute(container: NekoPatchRunner) {
        if (container.mode != NekoPatchRunner.Mode.AR_CORE) { skip(); return }
        if (container.userApkOverride != null) {
            skip(); return
        }
        val fetch = container.getStepOrNull<FetchManifestStep>()
        val manifest = fetch?.manifest
        if (manifest?.latest == null) { skip(); return }

        val abi = fetch.primaryAbi
        val asset = manifest.latest.abis?.get(abi)
        if (asset == null || asset.url.isNullOrBlank()) {
            ServiceLocator.log().warn("download", "manifest has no asset for $abi; skipping")
            skip(); return
        }

        val cached = ServiceLocator.paths().cachedArCoreApk(manifest.latest.versionCode, abi)
        if (cached.exists() && cached.length() == asset.size) {
            ServiceLocator.log().info("download", "cache hit ${cached.name} (${cached.length()} B)")
            container.sourceApk = cached
            container.arCoreVersionCode = manifest.latest.versionCode
            return
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
        ManifestClient.download(client, asset.url, cached, asset.sha256) { read, total ->
            if (total > 0) progress(read.toFloat() / total.toFloat())
        }
        container.sourceApk = cached
        container.arCoreVersionCode = manifest.latest.versionCode
        ServiceLocator.log().info("download", "downloaded ${cached.name} (${cached.length()} B)")
    }
}
