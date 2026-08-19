package com.kadr.app.ui.settings

import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kadr.app.R
import com.kadr.app.ui.formatBytes
import com.kadr.app.ui.rememberHaptics
import com.kadr.app.ui.theme.KadrAmber
import com.kadr.app.ui.theme.KadrCoral
import com.kadr.app.ui.theme.KadrMuted

/** §12 screen 5: pairing, network rules, excluded folders, cache, trash, space. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenTrash: () -> Unit,
    onUnpaired: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val health by viewModel.health.collectAsStateWithLifecycle()
    val plan by viewModel.freeUpPlan.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val cacheBytes by viewModel.cacheBytes.collectAsStateWithLifecycle()

    val haptics = rememberHaptics()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    // Android shows its own confirmation for the deletion; this only reports
    // back so the rows can be marked freed.
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { plan?.let(viewModel::onFreeUpFinished) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(stringResource(R.string.settings_section_server))
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
                    Text(settings.serverUrl.ifBlank { stringResource(R.string.settings_not_paired) })
                    health?.let {
                        Text(
                            stringResource(
                                R.string.settings_server_summary,
                                it.version,
                                it.assetCount,
                                it.freeDiskBytes?.let(::formatBytes) ?: "?",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = KadrMuted,
                        )
                    }
                    TextButton(
                        onClick = {
                            haptics.select()
                            viewModel.unpair(onUnpaired)
                        },
                    ) {
                        Text(stringResource(R.string.settings_unpair), color = KadrCoral)
                    }
                }
            }

            SectionHeader(stringResource(R.string.settings_section_when))
            SettingSwitch(
                title = stringResource(R.string.settings_auto_title),
                subtitle = stringResource(R.string.settings_auto_subtitle),
                checked = settings.autoBackup,
                onChange = { haptics.select(); viewModel.setAutoBackup(it) },
            )
            SettingSwitch(
                title = stringResource(R.string.settings_wifi_title),
                subtitle = stringResource(R.string.settings_wifi_subtitle),
                checked = settings.wifiOnly,
                onChange = { haptics.select(); viewModel.setWifiOnly(it) },
            )
            SettingSwitch(
                title = stringResource(R.string.settings_charging_title),
                checked = settings.chargingOnly,
                onChange = { haptics.select(); viewModel.setChargingOnly(it) },
            )
            SettingSwitch(
                title = stringResource(R.string.settings_videos_title),
                checked = settings.includeVideos,
                onChange = { haptics.select(); viewModel.setIncludeVideos(it) },
            )

            SectionHeader(stringResource(R.string.settings_section_skipped))
            viewModel.knownFolders.forEach { folder ->
                SettingSwitch(
                    title = folder,
                    checked = folder in settings.excludedFolders,
                    onChange = { haptics.select(); viewModel.toggleExcludedFolder(folder) },
                )
            }

            SectionHeader(stringResource(R.string.settings_section_appearance))
            SettingSwitch(
                title = stringResource(R.string.settings_dynamic_title),
                subtitle = stringResource(R.string.settings_dynamic_subtitle),
                checked = settings.dynamicColor,
                enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                onChange = { haptics.select(); viewModel.setDynamicColor(it) },
            )

            SectionHeader(stringResource(R.string.settings_section_storage))
            SettingRow(
                title = stringResource(R.string.settings_cache_title),
                subtitle = stringResource(
                    R.string.settings_cache_subtitle,
                    formatBytes(cacheBytes),
                    settings.videoCacheMb,
                ),
                onClick = { haptics.select(); viewModel.clearMediaCache() },
                trailing = { Text(stringResource(R.string.settings_clear), color = KadrAmber) },
            )
            SettingRow(
                title = stringResource(R.string.settings_trash_title),
                subtitle = stringResource(R.string.settings_trash_subtitle),
                onClick = onOpenTrash,
            )
            SettingRow(
                title = stringResource(R.string.settings_free_title),
                subtitle = stringResource(R.string.settings_free_subtitle),
                enabled = !busy,
                onClick = { viewModel.prepareFreeUp() },
                trailing = {
                    Text(
                        if (busy) stringResource(R.string.settings_checking) else "",
                        color = KadrMuted,
                    )
                },
            )

            Text(
                text = stringResource(R.string.settings_footer),
                style = MaterialTheme.typography.bodySmall,
                color = KadrMuted,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

    plan?.let { current ->
        AlertDialog(
            onDismissRequest = viewModel::cancelFreeUp,
            title = {
                Text(
                    stringResource(
                        R.string.settings_free_dialog_title,
                        formatBytes(current.totalBytes),
                    ),
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(
                            R.string.settings_free_dialog_body,
                            current.assets.size,
                        ),
                    )
                    if (current.withheld > 0) {
                        Text(
                            stringResource(
                                R.string.settings_free_dialog_withheld,
                                current.withheld,
                            ),
                            color = KadrCoral,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    haptics.confirm()
                    val request = viewModel.deleteRequestFor(current)
                    if (request != null) {
                        deleteLauncher.launch(IntentSenderRequest.Builder(request).build())
                    } else {
                        viewModel.deleteWithoutSystemDialog(current)
                    }
                }) { Text(stringResource(R.string.common_continue)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelFreeUp) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = KadrAmber,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingSwitch(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // §12 accessibility: nothing tappable below 48 dp.
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = KadrMuted)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            // The row's own text already says what this is.
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

@Composable
private fun SettingRow(
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = KadrMuted)
            }
        }
        trailing?.invoke()
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = title,
                tint = KadrMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
