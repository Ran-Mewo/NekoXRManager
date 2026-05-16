package dev.lewds.ran.nekoxrmanager.ui.screens.patching

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lewds.ran.nekoxrmanager.R
import dev.lewds.ran.nekoxrmanager.patcher.NekoPatchRunner
import dev.lewds.ran.nekoxrmanager.patcher.steps.StepGroup
import dev.lewds.ran.nekoxrmanager.patcher.steps.base.Step
import dev.lewds.ran.nekoxrmanager.patcher.steps.base.StepState
import dev.lewds.ran.nekoxrmanager.ui.PatchScope
import dev.lewds.ran.nekoxrmanager.ui.components.StepStatusGlyph
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatchingScreen(
    runner: NekoPatchRunner,
    onBack: () -> Unit,
    onShowLog: () -> Unit,
) {
    var done by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(runner) {
        PatchScope.launch {
            val err = runner.executeAll()
            error = err?.message
            done = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when {
                            error != null -> "Patching failed"
                            done -> "Patching complete"
                            else -> "Patching…"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    if (done) {
                        IconButton(onClick = onBack) {
                            Icon(
                                painter = painterResource(R.drawable.ic_back),
                                contentDescription = "Back to home",
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onShowLog) {
                        Icon(
                            painter = painterResource(R.drawable.ic_log),
                            contentDescription = "Open install log",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize()) {
            val grouped = remember(runner) { runner.steps.groupBy { it.group } }
            val groups = remember(grouped) { grouped.keys.toList() }

            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(groups, key = { it.name }) { group ->
                    StepGroupCard(group, grouped[group].orEmpty())
                }
            }

            ResultFooter(
                done = done,
                error = error,
                onBack = onBack,
                onShowLog = onShowLog,
                bottomInset = padding.calculateBottomPadding(),
            )
        }
    }
}

@Composable
private fun StepGroupCard(group: StepGroup, steps: List<Step>) {
    val anyRunning = steps.any { it.state == StepState.Running }
    val anyError = steps.any { it.state == StepState.Error }
    val allDone = steps.all { it.state == StepState.Success || it.state == StepState.Skipped }
    val statusTint = when {
        anyError -> MaterialTheme.colorScheme.error
        anyRunning -> MaterialTheme.colorScheme.primary
        allDone -> Color(0xFF2EA86C)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    ElevatedCard(
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                androidx.compose.foundation.Canvas(modifier = Modifier.size(10.dp)) {
                    drawCircle(color = statusTint)
                }
                Text(
                    text = stringResource(group.localizedName),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(10.dp))
            steps.forEach { StepRow(it) }
        }
    }
}

@Composable
private fun StepRow(step: Step) {
    val state = step.state
    val rowAlpha = if (state == StepState.Pending) 0.55f else 1f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(rowAlpha)
            .padding(vertical = 4.dp),
    ) {
        StepStatusGlyph(state = state)
        Text(
            text = stringResource(step.localizedName),
            style = MaterialTheme.typography.bodyMedium,
            color = when (state) {
                StepState.Error -> MaterialTheme.colorScheme.error
                StepState.Success -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        val duration = step.getDuration()
        if (state == StepState.Success && duration > 0) {
            Text(
                text = formatDuration(duration),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun ResultFooter(
    done: Boolean,
    error: String?,
    onBack: () -> Unit,
    onShowLog: () -> Unit,
    bottomInset: androidx.compose.ui.unit.Dp,
) {
    AnimatedVisibility(
        visible = !done,
        enter = fadeIn(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }

    AnimatedVisibility(
        visible = done,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .padding(bottom = bottomInset),
        ) {
            if (error == null) {
                Text(
                    text = "All steps finished successfully.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                ) { Text("Back") }
                OutlinedButton(
                    onClick = onShowLog,
                    modifier = Modifier.weight(1f),
                ) { Text("View log") }
            }
        }
    }
}

private fun formatDuration(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> "%.1fs".format(ms / 1000.0)
    else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
}
