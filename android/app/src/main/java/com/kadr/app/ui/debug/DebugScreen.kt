package com.kadr.app.ui.debug

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kadr.app.R
import coil3.compose.AsyncImage
import com.kadr.app.data.local.AssetState
import com.kadr.app.data.local.LocalAsset
import com.kadr.app.data.repo.BackupPhase
import com.kadr.app.data.repo.BackupProgress
import com.kadr.app.ui.formatBytes
import com.kadr.app.ui.formatDuration
import com.kadr.app.ui.theme.KadrAmber
import com.kadr.app.ui.theme.KadrCoral
import com.kadr.app.ui.theme.KadrMuted
import java.util.Locale

/** API 34's "Select photos…", which grants this instead of the two below. */
private const val READ_MEDIA_VISUAL_USER_SELECTED =
    "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

private val mediaPermissions: Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.READ_MEDIA_IMAGES)
        add(Manifest.permission.READ_MEDIA_VIDEO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(READ_MEDIA_VISUAL_USER_SELECTED)
        }
        // The batch notification is what keeps a long upload alive (§10.6).
        add(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        @Suppress("DEPRECATION")
        add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}.toTypedArray()

/**
 * Whether MediaStore will hand this app anything at all.
 *
 * From API 34 the answer is not a single yes or no: "Select photos…" grants
 * READ_MEDIA_VISUAL_USER_SELECTED and *withholds* the two full-library
 * permissions, so insisting on those would report no access while the scanner
 * happily reads the photos the user picked — and the prompt would come back on
 * every visit. Partial access is access; the scan simply finds less.
 */
private fun hasMediaAccess(context: android.content.Context): Boolean {
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        @Suppress("DEPRECATION")
        return granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    val full = granted(Manifest.permission.READ_MEDIA_IMAGES) &&
        granted(Manifest.permission.READ_MEDIA_VIDEO)
    if (full) return true

    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        granted(READ_MEDIA_VISUAL_USER_SELECTED)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    onUnpaired: () -> Unit,
    onBack: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    viewModel: DebugViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val assets by viewModel.assets.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val scanProgress by viewModel.scanProgress.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val failures by viewModel.failures.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()

    var hasPermission by remember { mutableStateOf(hasMediaAccess(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // Asked of the system rather than read off the result map: on API 34 a
        // partial grant comes back with the full-library permissions marked
        // false, and only checkSelfPermission tells the whole story.
        hasPermission = hasMediaAccess(context)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    val verified = counts.firstOrNull { it.state == AssetState.VERIFIED }?.count ?: 0
    val failed = counts.firstOrNull { it.state == AssetState.FAILED }?.count ?: 0
    val skipped = counts.firstOrNull { it.state == AssetState.SKIPPED }?.count ?: 0

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.backup_title)) },
                    navigationIcon = {
                        onBack?.let {
                            IconButton(onClick = it) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.common_back),
                                )
                            }
                        }
                    },
                    actions = {
                        onOpenSettings?.let {
                            IconButton(onClick = it) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = stringResource(R.string.backup_settings),
                                )
                            }
                        } ?: TextButton(onClick = {
                            viewModel.unpair()
                            onUnpaired()
                        }) { Text(stringResource(R.string.backup_unpair)) }
                    },
                )
                // Backup as ambient feedback: a 2 dp hairline, never a dialog (§12).
                if (running) {
                    val fraction = progress?.overallFraction
                    if (fraction != null && fraction > 0f) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            color = KadrAmber,
                            modifier = Modifier.fillMaxWidth().height(2.dp),
                        )
                    } else {
                        LinearProgressIndicator(
                            color = KadrAmber,
                            modifier = Modifier.fillMaxWidth().height(2.dp),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {

            if (!hasPermission) {
                PermissionPrompt(onGrant = { permissionLauncher.launch(mediaPermissions) })
                return@Column
            }

            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    StatusCard(
                        serverUrl = settings.serverUrl,
                        total = assets.size,
                        verified = verified,
                        failed = failed,
                        skipped = skipped,
                        scanning = scanning,
                        scanProgress = scanProgress,
                        running = running,
                        progress = progress,
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (running) {
                            OutlinedButton(
                                onClick = viewModel::stopBackup,
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(R.string.backup_stop)) }
                        } else {
                            Button(
                                onClick = viewModel::backupNow,
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(R.string.backup_now)) }
                        }

                        OutlinedButton(
                            onClick = viewModel::scan,
                            enabled = !busy && !scanning,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                if (scanning) {
                                    stringResource(R.string.backup_scan_busy)
                                } else {
                                    stringResource(R.string.backup_scan)
                                },
                            )
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Toggle(
                            stringResource(R.string.backup_toggle_auto),
                            settings.autoBackup,
                            viewModel::setAutoBackup,
                        )
                        Toggle(
                            stringResource(R.string.backup_toggle_wifi),
                            settings.wifiOnly,
                            viewModel::setWifiOnly,
                        )
                        Toggle(
                            stringResource(R.string.backup_toggle_charging),
                            settings.chargingOnly,
                            viewModel::setChargingOnly,
                        )
                        Toggle(
                            stringResource(R.string.backup_toggle_videos),
                            settings.includeVideos,
                            viewModel::setIncludeVideos,
                        )
                    }
                }

                storage?.let { summary ->
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    stringResource(R.string.backup_storage),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    stringResource(
                                        R.string.backup_storage_reclaimable,
                                        formatBytes(summary.reclaimableBytes),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = KadrMuted,
                                )
                                if (summary.alreadyFreedBytes > 0) {
                                    Text(
                                        stringResource(
                                            R.string.backup_storage_freed,
                                            formatBytes(summary.alreadyFreedBytes),
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = KadrMuted,
                                    )
                                }
                                Text(
                                    stringResource(
                                        R.string.backup_storage_server,
                                        summary.serverAssets,
                                        summary.serverFreeBytes?.let { formatBytes(it) } ?: "?",
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = KadrMuted,
                                )
                            }
                        }
                    }
                }

                if (failures.isNotEmpty()) {
                    item {
                        FailureCard(
                            failures = failures,
                            onRetryAll = viewModel::retryFailed,
                            enabled = !busy,
                        )
                    }
                }

                item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

                if (assets.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.backup_nothing_indexed),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    items(assets, key = { it.id }) { asset ->
                        AssetRow(
                            asset = asset,
                            onUpload = { viewModel.upload(asset.id) },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = checked,
        onClick = { onChange(!checked) },
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = KadrAmber,
        ),
    )
}

@Composable
private fun PermissionPrompt(onGrant: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.backup_permission_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.backup_permission_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        Button(onClick = onGrant) { Text(stringResource(R.string.backup_grant)) }
    }
}

@Composable
private fun StatusCard(
    serverUrl: String,
    total: Int,
    verified: Int,
    failed: Int,
    skipped: Int,
    scanning: Boolean,
    scanProgress: Int,
    running: Boolean,
    progress: BackupProgress?,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = serverUrl.ifBlank { stringResource(R.string.backup_no_server) },
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            val counts = stringResource(R.string.backup_counts, total, verified)
            val failedLabel = stringResource(R.string.backup_counts_failed, failed)
            val skippedLabel = stringResource(R.string.backup_counts_skipped, skipped)
            Text(
                text = buildString {
                    append(counts)
                    if (failed > 0) append(failedLabel)
                    if (skipped > 0) append(skippedLabel)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (scanning) {
                Text(
                    stringResource(R.string.backup_reading_mediastore, scanProgress),
                    style = MaterialTheme.typography.bodySmall,
                    color = KadrAmber,
                )
            }

            if (running && progress != null) {
                Text(
                    text = phaseLabel(progress),
                    style = MaterialTheme.typography.bodySmall,
                    color = KadrAmber,
                )
                if (progress.phase == BackupPhase.UPLOADING && progress.fileBytes > 0) {
                    LinearProgressIndicator(
                        progress = { progress.fileFraction },
                        color = KadrAmber,
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun phaseLabel(progress: BackupProgress): String = when (progress.phase) {
    BackupPhase.IDLE -> stringResource(R.string.backup_phase_idle)
    BackupPhase.SCANNING -> stringResource(R.string.backup_phase_scanning)
    BackupPhase.HASHING -> stringResource(R.string.backup_phase_hashing)
    BackupPhase.CHECKING -> stringResource(R.string.backup_phase_checking)
    BackupPhase.UPLOADING -> {
        val head = stringResource(
            R.string.backup_phase_uploading,
            (progress.done + 1).coerceAtMost(maxOf(progress.total, 1)),
            maxOf(progress.total, 1),
        )
        buildString {
        append(head)
        progress.filename?.let { append(" · $it") }
        if (progress.bytesPerSecond > 0) {
            val mb = progress.bytesPerSecond / (1024.0 * 1024.0)
            append(" · ")
            append(
                if (mb >= 1.0) String.format(Locale.US, "%.1f MB/s", mb)
                else String.format(Locale.US, "%.0f KB/s", progress.bytesPerSecond / 1024.0),
            )
        }
        }
    }
}

@Composable
private fun FailureCard(failures: List<LocalAsset>, onRetryAll: () -> Unit, enabled: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.backup_failed_count, failures.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = KadrCoral,
                )
                TextButton(onClick = onRetryAll, enabled = enabled) {
                    Text(stringResource(R.string.backup_retry_all))
                }
            }
            // Never silent (§10.4): the actual error is on screen.
            failures.take(3).forEach { asset ->
                Text(
                    stringResource(
                        R.string.backup_failure_line,
                        asset.filename,
                        asset.lastError ?: stringResource(R.string.common_unknown_error),
                        asset.attemptCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AssetRow(asset: LocalAsset, onUpload: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        AsyncImage(
            model = asset.contentUri,
            contentDescription = asset.filename,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = asset.filename,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(formatBytes(asset.sizeBytes))
                    asset.durationMs?.let { append(" · ").append(formatDuration(it)) }
                    asset.sha256?.let { append(" · ").append(it.take(8)) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = KadrMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            asset.lastError?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = KadrCoral,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        StateChip(asset.state)

        if (asset.state != AssetState.VERIFIED) {
            TextButton(onClick = onUpload) { Text(stringResource(R.string.backup_send)) }
        }
    }
}

@Composable
private fun StateChip(state: AssetState) {
    val color = when (state) {
        AssetState.VERIFIED -> Color(0xFF6BCB8B)
        AssetState.FAILED -> KadrCoral
        AssetState.UPLOADING -> KadrAmber
        else -> KadrMuted
    }
    Text(
        text = stringResource(
            when (state) {
                AssetState.DISCOVERED -> R.string.state_discovered
                AssetState.HASHED -> R.string.state_hashed
                AssetState.CHECKED -> R.string.state_checked
                AssetState.UPLOADING -> R.string.state_uploading
                AssetState.VERIFIED -> R.string.state_verified
                AssetState.LOCAL_FREED -> R.string.state_local_freed
                AssetState.SKIPPED -> R.string.state_skipped
                AssetState.FAILED -> R.string.state_failed
            },
        ),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.Medium,
    )
}
