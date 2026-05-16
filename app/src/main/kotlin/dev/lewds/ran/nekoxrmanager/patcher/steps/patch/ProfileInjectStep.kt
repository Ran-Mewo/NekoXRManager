package dev.lewds.ran.nekoxrmanager.patcher.steps.patch

import com.github.diamondminer88.zip.ZipCompression
import dev.lewds.ran.nekoxrmanager.R
import dev.lewds.ran.nekoxrmanager.di.ServiceLocator
import dev.lewds.ran.nekoxrmanager.patcher.NekoPatchRunner
import dev.lewds.ran.nekoxrmanager.patcher.steps.StepGroup
import dev.lewds.ran.nekoxrmanager.patcher.steps.base.Step
import dev.lewds.ran.nekoxrmanager.patcher.util.DeviceProfileBypass
import dev.lewds.ran.nekoxrmanager.patcher.util.ZipUtil

private const val PACKED_DIR = "assets/packed_profiles/"
private const val TOC_ENTRY = PACKED_DIR + "profiles.toc"

/**
 * Renders the harvested [DeviceProfile] into a DeviceProfile binarypb, writes
 * it as a new `profiles_NNNNN.dat` slab inside the ARCore APK, and appends
 * TOC entries that point ARCore's native loader at it for every key variant
 * the lookup chain tries.
 */
class ProfileInjectStep : Step() {
    override val group = StepGroup.Patch
    override val localizedName = R.string.step_inject_profile

    override suspend fun execute(container: NekoPatchRunner) {
        if (container.mode != NekoPatchRunner.Mode.AR_CORE) { skip(); return }
        val apk = container.outputApk ?: error("no working APK")
        val profile = container.deviceProfile
        val log = ServiceLocator.log()

        val binarypb = DeviceProfileBypass.renderBinarypb(profile)

        val existing = ZipUtil.listEntries(apk, PACKED_DIR)
        val newIdx = DeviceProfileBypass.nextDatIndex(existing)
        val newDatEntry = "%sprofiles_%05d.dat".format(PACKED_DIR, newIdx)

        val toc = ZipUtil.readEntry(apk, TOC_ENTRY) ?: error("profiles.toc not found in APK")
        val keys = profile.buildAllProfileKeys(profile.fingerprint)
        val newToc = DeviceProfileBypass.appendTocEntries(toc, keys, newIdx, binarypb.size)

        ZipUtil.replaceEntry(apk, newDatEntry, binarypb, ZipCompression.DEFLATE)
        ZipUtil.replaceEntry(apk, TOC_ENTRY, newToc, ZipCompression.DEFLATE)

        log.info(
            "profile",
            "rendered ${binarypb.size}B binarypb → $newDatEntry; " +
                "registered ${keys.size} TOC keys: ${keys.joinToString()}"
        )
    }
}
