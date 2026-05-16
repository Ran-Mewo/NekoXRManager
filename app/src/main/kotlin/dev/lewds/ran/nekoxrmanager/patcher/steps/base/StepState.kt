package dev.lewds.ran.nekoxrmanager.patcher.steps.base

enum class StepState {
    Pending,
    Running,
    Success,
    Error,
    Skipped;

    val isFinished: Boolean
        get() = this == Success || this == Skipped || this == Error
}
