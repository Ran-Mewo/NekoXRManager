package dev.lewds.ran.nekoxrmanager.patcher.steps.prepare

import dev.lewds.ran.nekoxrmanager.R
import dev.lewds.ran.nekoxrmanager.patcher.NekoPatchRunner
import dev.lewds.ran.nekoxrmanager.patcher.steps.StepGroup
import dev.lewds.ran.nekoxrmanager.patcher.steps.base.Step

/**
 * Validates `container.deviceProfile` after harvest. Picked up here (rather than
 * inside [HarvestDeviceInfoStep]) so user-edited overrides from the device-profile
 * screen flow through the same gate before any patch step touches the APK.
 */
class LoadDeviceProfileStep : Step() {
    override val group = StepGroup.Prepare
    override val localizedName = R.string.step_load_profile

    override suspend fun execute(container: NekoPatchRunner) {
        if (container.mode != NekoPatchRunner.Mode.AR_CORE) { skip(); return }
        container.deviceProfile.validate()
    }
}
