package com.kadr.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kadr.app.R
import com.kadr.app.ui.formatBytes
import com.kadr.app.ui.rememberHaptics
import com.kadr.app.ui.theme.KadrAmber
import com.kadr.app.ui.theme.KadrCoral
import com.kadr.app.ui.theme.KadrMuted
import java.util.concurrent.TimeUnit

/**
 * Trash (§7, §12 screen 5).
 *
 * Nothing here can be destroyed by hand — the server purges on its own schedule
 * and this screen says how long each item has left. "Never lose a file" (§2)
 * reads better as a countdown than as a delete button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val trash by viewModel.trash.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.loadTrash() }
    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.trash_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                trash.loading -> CircularProgressIndicator(
                    color = KadrAmber,
                    modifier = Modifier.align(Alignment.Center),
                )

                trash.error != null -> Text(
                    text = stringResource(R.string.trash_error, trash.error.orEmpty()),
                    color = KadrCoral,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )

                trash.items.isEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.trash_empty),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        stringResource(R.string.trash_empty_detail, trash.retentionDays),
                        style = MaterialTheme.typography.bodyMedium,
                        color = KadrMuted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                else -> LazyColumn(
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(trash.items, key = { it.id }) { item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .padding(horizontal = 16.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.filename.ifBlank { item.id },
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.trash_item_line,
                                        formatBytes(item.sizeBytes),
                                        remaining(item.purgesInMs),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = KadrMuted,
                                )
                            }
                            TextButton(onClick = {
                                haptics.confirm()
                                viewModel.restore(item.id)
                            }) {
                                Text(stringResource(R.string.trash_restore), color = KadrAmber)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun remaining(millis: Long): String {
    if (millis <= 0) return stringResource(R.string.trash_purging_soon)
    val days = TimeUnit.MILLISECONDS.toDays(millis)
    if (days >= 1) return pluralStringResource(R.plurals.trash_days_left, days.toInt(), days)
    val hours = TimeUnit.MILLISECONDS.toHours(millis)
    if (hours >= 1) return pluralStringResource(R.plurals.trash_hours_left, hours.toInt(), hours)
    return stringResource(R.string.trash_less_than_hour)
}
