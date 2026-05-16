package dev.lewds.ran.nekoxrmanager.patcher.steps

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.lewds.ran.nekoxrmanager.R

@Immutable
enum class StepGroup(
    @get:StringRes val localizedName: Int,
) {
    Prepare(R.string.step_group_prepare),
    Patch(R.string.step_group_patch),
    Sign(R.string.step_group_sign),
    Install(R.string.step_group_install),
    Verify(R.string.step_group_verify),
}
