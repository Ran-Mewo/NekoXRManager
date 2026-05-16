package dev.lewds.ran.nekoxrmanager.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-lifetime scope for the patching pipeline. Survives Activity pause/resume cycles
 * caused by the system uninstall and install confirmation dialogs covering MainActivity —
 * a composition-scoped coroutine would be cancelled while those dialogs are foregrounded.
 */
val PatchScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
