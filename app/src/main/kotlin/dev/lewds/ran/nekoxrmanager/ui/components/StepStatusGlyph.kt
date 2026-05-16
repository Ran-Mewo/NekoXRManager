package dev.lewds.ran.nekoxrmanager.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.lewds.ran.nekoxrmanager.R
import dev.lewds.ran.nekoxrmanager.patcher.steps.base.StepState

@Composable
fun StepStatusGlyph(state: StepState, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            StepState.Pending -> Text(
                text = "○",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )

            StepState.Running -> CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
            )

            StepState.Success -> Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = Color(0xFF2EA86C),
                modifier = Modifier.size(18.dp),
            )

            StepState.Skipped -> Text(
                text = "—",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            )

            StepState.Error -> Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
