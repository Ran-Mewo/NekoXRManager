package dev.lewds.ran.nekoxrmanager.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.lewds.ran.nekoxrmanager.patcher.NekoPatchRunner
import dev.lewds.ran.nekoxrmanager.ui.screens.home.HomeScreen
import dev.lewds.ran.nekoxrmanager.ui.screens.log.LogScreen
import dev.lewds.ran.nekoxrmanager.ui.screens.patching.PatchingScreen
import java.io.File

enum class Screen { Home, Patching, Log }

@Composable
fun AppRoot() {
    var screen by rememberSaveable { mutableStateOf(Screen.Home) }
    var runner by remember { mutableStateOf<NekoPatchRunner?>(null) }

    when (screen) {
        Screen.Home -> HomeScreen(
            onStartArCore = { ctx, uri: Uri? ->
                runner = NekoPatchRunner(ctx.applicationContext, userApkOverride = uri)
                screen = Screen.Patching
            },
            onStartConsumer = { ctx, pkg, apkPath: File ->
                runner = NekoPatchRunner(
                    ctx.applicationContext,
                    mode = NekoPatchRunner.Mode.CONSUMER_APP,
                    targetPackage = pkg,
                ).also { it.sourceApk = apkPath }
                screen = Screen.Patching
            },
            onShowLog = { screen = Screen.Log },
        )

        Screen.Patching -> PatchingScreen(
            runner = runner!!,
            onBack = { screen = Screen.Home },
            onShowLog = { screen = Screen.Log },
        )

        Screen.Log -> LogScreen(
            onBack = { screen = if (runner == null) Screen.Home else Screen.Patching },
        )
    }
}
