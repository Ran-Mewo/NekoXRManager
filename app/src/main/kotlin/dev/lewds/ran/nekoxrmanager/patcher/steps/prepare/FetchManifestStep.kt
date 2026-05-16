package dev.lewds.ran.nekoxrmanager.patcher.steps.prepare

import android.os.Build
import dev.lewds.ran.nekoxrmanager.di.ServiceLocator
import dev.lewds.ran.nekoxrmanager.patcher.NekoPatchRunner
import dev.lewds.ran.nekoxrmanager.patcher.ReleaseManifest
import dev.lewds.ran.nekoxrmanager.patcher.steps.StepGroup
import dev.lewds.ran.nekoxrmanager.patcher.steps.base.Step
import dev.lewds.ran.nekoxrmanager.patcher.util.ManifestClient
import dev.lewds.ran.nekoxrmanager.R
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Reads the bundled manifest URL, GETs it via OkHttp, picks the entry matching this
 * device's primary ABI. On no-network or HTTP failure: log and skip — [PickApkStep]
 * picks up the slack via SAF.
 */
class FetchManifestStep : Step() {
    override val group = StepGroup.Prepare
    override val localizedName = R.string.step_fetch_manifest

    var manifest: ReleaseManifest? = null
    var primaryAbi: String = preferredAbi()

    override suspend fun execute(container: NekoPatchRunner) {
        if (container.mode != NekoPatchRunner.Mode.AR_CORE) { skip(); return }
        val log = ServiceLocator.log()
        val url = ManifestClient.readBundledManifestUrl(container.appContext)
        if (url.isEmpty() || url.startsWith("#")) {
            log.warn("manifest", "no manifest URL configured; skipping fetch")
            skip()
            return
        }
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            manifest = ManifestClient.fetch(client, url)
            log.info("manifest", "fetched: latest vc=${manifest?.latest?.versionCode}")
        } catch (t: Throwable) {
            log.warn("manifest", "fetch failed: ${t.message}; falling back to user-picked APK")
            skip()
        }
    }

    private fun preferredAbi(): String =
        Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" }
            ?: Build.SUPPORTED_ABIS.firstOrNull { it == "armeabi-v7a" }
            ?: (Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a")
}
