package dev.lewds.ran.nekoxrmanager.patcher

import android.content.Context
import android.net.Uri
import dev.lewds.ran.nekoxrmanager.patcher.steps.base.Step
import dev.lewds.ran.nekoxrmanager.patcher.steps.install.InstallStep
import dev.lewds.ran.nekoxrmanager.patcher.steps.install.RepackageStep
import dev.lewds.ran.nekoxrmanager.patcher.steps.install.SignStep
import dev.lewds.ran.nekoxrmanager.patcher.steps.install.UninstallStockStep
import dev.lewds.ran.nekoxrmanager.patcher.steps.patch.BypassConsumerSigStep
import dev.lewds.ran.nekoxrmanager.patcher.steps.patch.ManifestPatchStep
import dev.lewds.ran.nekoxrmanager.patcher.steps.patch.NativePatchStep
import dev.lewds.ran.nekoxrmanager.patcher.steps.patch.ProfileInjectStep
import dev.lewds.ran.nekoxrmanager.patcher.steps.patch.SaveMetadataStep
import dev.lewds.ran.nekoxrmanager.patcher.steps.prepare.CheckShizukuStep
import dev.lewds.ran.nekoxrmanager.patcher.steps.prepare.DownloadArCoreStep
import dev.lewds.ran.nekoxrmanager.patcher.steps.prepare.ExtractApkStep
import dev.lewds.ran.nekoxrmanager.patcher.steps.prepare.FetchManifestStep
import dev.lewds.ran.nekoxrmanager.patcher.steps.prepare.HarvestDeviceInfoStep
import dev.lewds.ran.nekoxrmanager.patcher.steps.prepare.LoadDeviceProfileStep
import dev.lewds.ran.nekoxrmanager.patcher.steps.prepare.LoadPatchSetStep
import dev.lewds.ran.nekoxrmanager.patcher.steps.prepare.PickApkStep
import dev.lewds.ran.nekoxrmanager.patcher.steps.verify.VerifyServiceStep
import java.io.File

private const val PKG_ARCORE = "com.google.ar.core"

/**
 * Concrete pipeline. Drives two flows from one step list:
 *
 *  * [Mode.AR_CORE] — patches a stock `com.google.ar.core` APK so it runs on
 *    INMO Air 3 (download/profile/native byte-patches/manifest/install).
 *  * [Mode.CONSUMER_APP] — re-signs an installed AR-using app and NOPs its
 *    `SessionCreateJniHelper.checkApkSignature` so it accepts our re-signed
 *    `com.google.ar.core`.
 *
 * Each step that's specific to one flow early-exits with `skip()` in the other.
 * Sharing a single runner keeps the StepRunner contract simple and the UI's
 * progress display consistent.
 */
class NekoPatchRunner(
    val appContext: Context,
    val mode: Mode = Mode.AR_CORE,
    /** Package the patched APK installs as. Drives [UninstallStockStep] and verify. */
    val targetPackage: String = PKG_ARCORE,
    /** SAF Uri to a user-supplied source APK. Required in [Mode.CONSUMER_APP]. */
    val userApkOverride: Uri? = null,
) : StepRunner() {

    enum class Mode { AR_CORE, CONSUMER_APP }

    /** Source APK after download / SAF-pick / package-info-pick. */
    var sourceApk: File? = null

    /** Decided ARCore versionCode (AR_CORE mode only). */
    var arCoreVersionCode: Long = 0

    /** Working dir into which the source APK is extracted. */
    var extractedDir: File? = null

    /** Selected native patch set (AR_CORE mode only). */
    var patchSet: dev.lewds.ran.nekoxrmanager.patcher.util.NativePatcher.PatchSet? = null

    /** Harvested + clamped intrinsics (AR_CORE mode only). */
    var deviceProfile: DeviceProfile = DeviceProfile()

    /** Final patched + signed APK on disk. */
    var outputApk: File? = null

    /** True if [InstallStep] used Shizuku. */
    var installedSilently: Boolean = false

    override val steps: List<Step> = listOf(
        FetchManifestStep(),
        DownloadArCoreStep(),
        PickApkStep(),
        CheckShizukuStep(),
        HarvestDeviceInfoStep(),
        ExtractApkStep(),
        LoadPatchSetStep(),
        LoadDeviceProfileStep(),
        NativePatchStep(),
        ProfileInjectStep(),
        ManifestPatchStep(),
        BypassConsumerSigStep(),
        SaveMetadataStep(),
        RepackageStep(),
        SignStep(),
        UninstallStockStep(),
        InstallStep(),
        VerifyServiceStep(),
    )
}
