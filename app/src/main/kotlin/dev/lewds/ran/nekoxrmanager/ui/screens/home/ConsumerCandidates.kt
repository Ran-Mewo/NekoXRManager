package dev.lewds.ran.nekoxrmanager.ui.screens.home

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import dev.lewds.ran.nekoxrmanager.patcher.util.ConsumerSigBypass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ConsumerCandidate(
    val packageName: String,
    val label: String,
    val apkFile: File,
)

/**
 * Walks the user-installed package list and returns the entries whose APKs bundle the
 * ARCore client SDK (lib/&lt;abi&gt;/libarcore_sdk_c.so). These are the apps whose embedded
 * signature check needs the [ConsumerSigBypass] rewrite to accept our re-signed ARCore.
 *
 * System apps are skipped: INMO ships preinstalled bundles we don't want to re-sign.
 */
suspend fun findArConsumerCandidates(ctx: Context): List<ConsumerCandidate> = withContext(Dispatchers.IO) {
    val pm = ctx.packageManager
    val out = mutableListOf<ConsumerCandidate>()
    for (pkg in pm.getInstalledPackages(0)) {
        val info = pkg.applicationInfo ?: continue
        if ((info.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
        val src = info.sourceDir ?: continue
        val apk = File(src)
        if (!apk.exists()) continue
        if (!ConsumerSigBypass.apkContainsArCoreSdk(apk)) continue
        val label = pm.getApplicationLabel(info)?.toString() ?: pkg.packageName
        out.add(ConsumerCandidate(pkg.packageName, label, apk))
    }
    out.sortBy { it.label.lowercase() }
    out
}

fun loadAppIcon(ctx: Context, packageName: String): Drawable? = runCatching {
    ctx.packageManager.getApplicationIcon(packageName)
}.getOrNull()
