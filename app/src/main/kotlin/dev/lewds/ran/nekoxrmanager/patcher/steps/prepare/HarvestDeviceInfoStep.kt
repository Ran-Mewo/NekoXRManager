package dev.lewds.ran.nekoxrmanager.patcher.steps.prepare

import android.os.Build
import dev.lewds.ran.nekoxrmanager.R
import dev.lewds.ran.nekoxrmanager.di.ServiceLocator
import dev.lewds.ran.nekoxrmanager.patcher.NekoPatchRunner
import dev.lewds.ran.nekoxrmanager.patcher.steps.StepGroup
import dev.lewds.ran.nekoxrmanager.patcher.steps.base.Step
import dev.lewds.ran.nekoxrmanager.patcher.util.DeviceProfileRegistry

/**
 * Looks the running device up in `assets/arcore/profiles/devices.json` and
 * populates `container.deviceProfile`.
 *
 * Fails loud when the device is not in the registry — the patcher cannot
 * proceed without per-device calibration values, and silently using stock
 * defaults would produce a profile that tracks badly or not at all.
 */
class HarvestDeviceInfoStep : Step() {
    override val group = StepGroup.Prepare
    override val localizedName = R.string.step_harvest_device

    override suspend fun execute(container: NekoPatchRunner) {
        if (container.mode != NekoPatchRunner.Mode.AR_CORE) { skip(); return }
        val log = ServiceLocator.log()
        val profile = DeviceProfileRegistry.lookupCurrent(container.appContext)
            ?: error(
                "No registry entry for ${Build.MANUFACTURER} ${Build.MODEL}. " +
                    "Run tools/harvest_device.py and add the result to " +
                    "app/src/main/assets/arcore/profiles/devices.json."
            )
        if (profile.fingerprint.isEmpty()) profile.fingerprint = Build.FINGERPRINT
        container.deviceProfile = profile
        log.info("harvest", "matched registry entry: $profile")
    }
}
