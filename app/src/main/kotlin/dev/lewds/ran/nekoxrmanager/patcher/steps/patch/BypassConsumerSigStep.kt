package dev.lewds.ran.nekoxrmanager.patcher.steps.patch

import dev.lewds.ran.nekoxrmanager.R
import dev.lewds.ran.nekoxrmanager.di.ServiceLocator
import dev.lewds.ran.nekoxrmanager.patcher.NekoPatchRunner
import dev.lewds.ran.nekoxrmanager.patcher.steps.StepGroup
import dev.lewds.ran.nekoxrmanager.patcher.steps.base.Step
import dev.lewds.ran.nekoxrmanager.patcher.util.ConsumerSigBypass

/**
 * Rewrites `com.google.ar.core.SessionCreateJniHelper.checkApkSignature(Context):Z` in
 * the extracted APK's `classes*.dex` so it returns `true` unconditionally. Without this,
 * a consumer app refuses to start an AR session against our re-signed `com.google.ar.core`
 * (logcat tag `ARCore-SessionCreateJniHelper: Signature mismatch`).
 *
 * No-op in [NekoPatchRunner.Mode.AR_CORE].
 */
class BypassConsumerSigStep : Step() {
    override val group = StepGroup.Patch
    override val localizedName = R.string.step_bypass_consumer_sig

    override suspend fun execute(container: NekoPatchRunner) {
        if (container.mode != NekoPatchRunner.Mode.CONSUMER_APP) { skip(); return }
        val apk = container.outputApk ?: error("no working APK")
        val log = ServiceLocator.log()
        val r = ConsumerSigBypass.patchApk(apk)
        log.info(
            "sig-bypass",
            "rewrote ${r.patchedDexEntry} (was ${r.instructionsBefore} instructions)"
        )
    }
}
