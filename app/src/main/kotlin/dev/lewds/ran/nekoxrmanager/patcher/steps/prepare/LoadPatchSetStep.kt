package dev.lewds.ran.nekoxrmanager.patcher.steps.prepare

import dev.lewds.ran.nekoxrmanager.R
import dev.lewds.ran.nekoxrmanager.di.ServiceLocator
import dev.lewds.ran.nekoxrmanager.patcher.NekoPatchRunner
import dev.lewds.ran.nekoxrmanager.patcher.steps.StepGroup
import dev.lewds.ran.nekoxrmanager.patcher.steps.base.Step
import dev.lewds.ran.nekoxrmanager.patcher.util.ManifestClient
import dev.lewds.ran.nekoxrmanager.patcher.util.NativePatcher

/**
 * Picks the patch-set asset name from the (optional) remote manifest. Override paths
 * keyed by the ARCore versionCode take priority; otherwise the per-ABI default is used.
 * Patch-set assets are bundled under {@code assets/arcore/patches/}.
 */
class LoadPatchSetStep : Step() {
    override val group = StepGroup.Prepare
    override val localizedName = R.string.step_load_patchset

    override suspend fun execute(container: NekoPatchRunner) {
        if (container.mode != NekoPatchRunner.Mode.AR_CORE) { skip(); return }
        val log = ServiceLocator.log()
        val fetch = container.getStepOrNull<FetchManifestStep>()
        val manifest = fetch?.manifest
        val abi = fetch?.primaryAbi ?: "arm64-v8a"

        val assetName = manifest?.patchSets?.let { ps ->
            val vc = container.arCoreVersionCode.toString()
            ps.overrides?.get(vc)
                ?: when (abi) {
                    "arm64-v8a" -> ps.defaultArm64
                    "armeabi-v7a" -> ps.defaultArm32
                    else -> null
                }
        } ?: when (abi) {
            "arm64-v8a" -> "generic_arm64.json"
            "armeabi-v7a" -> "generic_arm32.json"
            else -> "generic_arm64.json"
        }

        val assetPath = "arcore/patches/$assetName"
        val bytes = try {
            ManifestClient.readAsset(container.appContext, assetPath)
        } catch (t: Throwable) {
            error("patch-set asset missing: $assetPath (${t.message})")
        }
        container.patchSet = NativePatcher.loadJson(bytes)
        log.info("patchset", "loaded ${container.patchSet?.id} from $assetName")
    }
}
