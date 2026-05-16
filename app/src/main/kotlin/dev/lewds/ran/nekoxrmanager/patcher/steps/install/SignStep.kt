package dev.lewds.ran.nekoxrmanager.patcher.steps.install

import dev.lewds.ran.nekoxrmanager.R
import dev.lewds.ran.nekoxrmanager.di.ServiceLocator
import dev.lewds.ran.nekoxrmanager.patcher.NekoPatchRunner
import dev.lewds.ran.nekoxrmanager.patcher.steps.StepGroup
import dev.lewds.ran.nekoxrmanager.patcher.steps.base.Step
import dev.lewds.ran.nekoxrmanager.patcher.util.Signer

/**
 * Signs the patched APK in-place with V2+V3 (no V1 — min SDK 28). The signing key is a
 * self-signed RSA-2048 cert generated on first use and persisted at
 * {@code PathManager.keystoreFile}.
 */
class SignStep : Step() {
    override val group = StepGroup.Sign
    override val localizedName = R.string.step_sign

    override suspend fun execute(container: NekoPatchRunner) {
        val apk = container.outputApk ?: error("no working APK")
        val signer = Signer(ServiceLocator.paths().keystoreFile)
        signer.signApk(apk)
        ServiceLocator.log().info("sign", "signed ${apk.name} (${apk.length()} B)")
    }
}
