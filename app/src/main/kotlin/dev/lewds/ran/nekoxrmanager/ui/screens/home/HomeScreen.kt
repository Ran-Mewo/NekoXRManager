package dev.lewds.ran.nekoxrmanager.ui.screens.home

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lewds.ran.nekoxrmanager.R
import dev.lewds.ran.nekoxrmanager.ui.components.AppListItem
import dev.lewds.ran.nekoxrmanager.ui.components.MainActionButton
import dev.lewds.ran.nekoxrmanager.ui.components.ProjectHeader
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onStartArCore: (Context, Uri?) -> Unit,
    onStartConsumer: (Context, String, File) -> Unit,
    onShowLog: () -> Unit,
) {
    val ctx = LocalContext.current
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    val pickApk = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        pickedUri = uri
    }

    var candidates by remember { mutableStateOf<List<ConsumerCandidate>?>(null) }
    var scanning by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (candidates == null) {
            scanning = true
            candidates = findArConsumerCandidates(ctx)
            scanning = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "NekoXRManager",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                actions = {
                    IconButton(onClick = onShowLog) {
                        Icon(
                            painter = painterResource(R.drawable.ic_log),
                            contentDescription = "Open install log",
                        )
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "HEADER") {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 12.dp),
                ) {
                    ProjectHeader()
                }
            }

            item(key = "PRIMARY_ACTION") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    MainActionButton(
                        text = "Install & patch ARCore",
                        icon = painterResource(R.drawable.ic_download),
                        onClick = { onStartArCore(ctx, pickedUri) },
                    )

                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        TextButton(
                            onClick = {
                                if (pickedUri == null) pickApk.launch("application/vnd.android.package-archive")
                                else pickedUri = null
                            }
                        ) {
                            Text(
                                text = if (pickedUri == null) "Use a local ARCore APK instead"
                                else "Clear picked APK",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }

            item(key = "CONSUMER_HEADER") {
                Spacer(Modifier.height(4.dp))
                SectionHeader(
                    title = "Patch an installed app",
                    subtitle = "Re-signs the app and disables its embedded ARCore signature check.",
                )
            }

            when {
                scanning -> item(key = "SCANNING") {
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(96.dp),
                        ) {
                            Text(
                                text = "Scanning installed apps…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                candidates.isNullOrEmpty() -> item(key = "EMPTY") {
                    EmptyConsumerState(onRescan = {
                        scanning = true
                        candidates = null
                    })
                }

                else -> items(candidates!!, key = { it.packageName }) { candidate ->
                    AppListItem(
                        label = candidate.label,
                        packageName = candidate.packageName,
                        iconLoader = { loadAppIcon(ctx, candidate.packageName) },
                        onClick = { onStartConsumer(ctx, candidate.packageName, candidate.apkFile) },
                    )
                }
            }

            if (!candidates.isNullOrEmpty()) {
                item(key = "RESCAN") {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    ) {
                        TextButton(onClick = {
                            scanning = true
                            candidates = null
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_refresh),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(16.dp)
                                    .padding(end = 4.dp),
                            )
                            Text("Rescan", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(scanning, candidates) {
        if (scanning && candidates == null) {
            candidates = findArConsumerCandidates(ctx)
            scanning = false
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alpha(0.85f),
        )
    }
}

@Composable
private fun EmptyConsumerState(onRescan: () -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Text(
                text = "No ARCore-using apps installed",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Install an app that bundles the ARCore SDK and rescan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onRescan) {
                Icon(
                    painter = painterResource(R.drawable.ic_refresh),
                    contentDescription = null,
                    modifier = Modifier
                        .size(16.dp)
                        .padding(end = 4.dp),
                )
                Text("Rescan")
            }
        }
    }
}
