package dev.lewds.ran.nekoxrmanager.patcher.steps.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import dev.lewds.ran.nekoxrmanager.R
import dev.lewds.ran.nekoxrmanager.di.ServiceLocator
import dev.lewds.ran.nekoxrmanager.patcher.NekoPatchRunner
import dev.lewds.ran.nekoxrmanager.patcher.steps.StepGroup
import dev.lewds.ran.nekoxrmanager.patcher.steps.base.Step
import dev.lewds.ran.nekoxrmanager.patcher.util.ShizukuShell
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val UNINSTALL_TIMEOUT_MS = 120_000L

/**
 * Removes any pre-existing copy of [NekoPatchRunner.targetPackage] so the
 * patched APK (signed with our debug keystore) can be installed. The platform
 * refuses install-replace across signing certificates, so this step is
 * load-bearing for repeat patches — for both ARCore and consumer-app modes.
 *
 * Strategy:
 *   1. Not installed → skip.
 *   2. Shizuku available → silent `pm uninstall --user 0`.
 *   3. Otherwise → launch the system uninstall dialog and suspend on the
 *      `PACKAGE_FULLY_REMOVED` broadcast for the target package.
 */
class UninstallStockStep : Step() {
    override val group = StepGroup.Install
    override val localizedName = R.string.step_uninstall_stock

    override suspend fun execute(container: NekoPatchRunner) {
        val log = ServiceLocator.log()
        val ctx = container.appContext
        val pkg = container.targetPackage

        if (!isInstalled(ctx, pkg)) {
            log.info("uninstall", "no existing $pkg present")
            skip(); return
        }

        if (ShizukuShell.isAvailable()) {
            val r = ShizukuShell.sh("pm uninstall --user 0 $pkg", 10_000)
            if (r.ok()) {
                log.info("uninstall", "removed $pkg via Shizuku")
                return
            }
            if (r.stderr.contains("not installed", true) || r.stdout.contains("not installed", true)) {
                log.info("uninstall", "no $pkg present (per shell)")
                skip(); return
            }
            log.warn("uninstall", "Shizuku uninstall failed: exit=${r.exitCode} ${r.stderr.trim()}; falling back to user prompt")
        }

        log.info("uninstall", "prompting user to uninstall $pkg (signature differs from patched APK)")
        val removed = withTimeoutOrNull(UNINSTALL_TIMEOUT_MS) {
            awaitPackageRemoval(ctx, pkg)
        }
        if (removed != true) {
            error("user did not confirm uninstall of $pkg within ${UNINSTALL_TIMEOUT_MS / 1000}s")
        }
        log.info("uninstall", "user confirmed uninstall of $pkg")
    }

    private fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    private suspend fun awaitPackageRemoval(ctx: Context, pkg: String): Boolean =
        suspendCancellableCoroutine { cont ->
            val filter = IntentFilter(Intent.ACTION_PACKAGE_FULLY_REMOVED).apply {
                addDataScheme("package")
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.data?.schemeSpecificPart == pkg) {
                        runCatching { c.unregisterReceiver(this) }
                        if (cont.isActive) cont.resume(true)
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                ctx.registerReceiver(receiver, filter)
            }
            cont.invokeOnCancellation { runCatching { ctx.unregisterReceiver(receiver) } }

            val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ctx.startActivity(intent)
            } catch (t: Throwable) {
                runCatching { ctx.unregisterReceiver(receiver) }
                if (cont.isActive) cont.resumeWith(Result.failure(t))
            }
        }
}
