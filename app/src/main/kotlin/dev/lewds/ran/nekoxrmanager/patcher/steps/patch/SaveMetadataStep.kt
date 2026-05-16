package dev.lewds.ran.nekoxrmanager.patcher.steps.patch

import com.github.diamondminer88.zip.ZipCompression
import dev.lewds.ran.nekoxrmanager.BuildConfig
import dev.lewds.ran.nekoxrmanager.R
import dev.lewds.ran.nekoxrmanager.di.ServiceLocator
import dev.lewds.ran.nekoxrmanager.patcher.NekoPatchRunner
import dev.lewds.ran.nekoxrmanager.patcher.steps.StepGroup
import dev.lewds.ran.nekoxrmanager.patcher.steps.base.Step
import dev.lewds.ran.nekoxrmanager.patcher.util.ZipUtil

private const val META_ENTRY = "assets/neko_install.json"

/**
 * Stamps a small JSON manifest into the patched APK so that future runs (or support
 * triage) can identify which patcher build / patch-set / ARCore version produced it.
 */
class SaveMetadataStep : Step() {
    override val group = StepGroup.Patch
    override val localizedName = R.string.step_save_metadata

    override suspend fun execute(container: NekoPatchRunner) {
        if (container.mode != NekoPatchRunner.Mode.AR_CORE) { skip(); return }
        val apk = container.outputApk ?: error("no working APK")
        val patchSet = container.patchSet
        val profile = container.deviceProfile
        val now = System.currentTimeMillis()

        val json = buildString {
            append('{')
            append("\"patcher\":\"NekoXRManager ${BuildConfig.VERSION_NAME}\",")
            append("\"patcher_version_code\":${BuildConfig.VERSION_CODE},")
            append("\"timestamp_ms\":$now,")
            append("\"arcore_version_code\":${container.arCoreVersionCode},")
            append("\"patch_set_id\":\"${patchSet?.id ?: ""}\",")
            append("\"capture\":\"${profile.captureWidth}x${profile.captureHeight}@${profile.fps}\",")
            append("\"manufacturer\":${jsonStr(profile.manufacturer)},")
            append("\"model\":${jsonStr(profile.model)}")
            append('}')
        }
        ZipUtil.replaceEntry(apk, META_ENTRY, json.toByteArray(Charsets.UTF_8), ZipCompression.DEFLATE)
        ServiceLocator.log().info("meta", "wrote $META_ENTRY (${json.length} B)")
    }

    private fun jsonStr(s: String?): String {
        if (s == null) return "null"
        val esc = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$esc\""
    }
}
