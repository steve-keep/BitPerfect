package com.bitperfect.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bitperfect.core.utils.SettingsManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsManager: SettingsManager,
    onBack: () -> Unit,
    onCopyDebugReport: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            item {
                Text(
                    text = "Development & Testing",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(16.dp)
                )
            }

            item {
                PreferenceSwitch(
                    title = "Enable Virtual Drive",
                    description = "Simulate a CD drive for UI testing",
                    checked = settingsManager.isVirtualDriveEnabled,
                    onCheckedChange = { settingsManager.isVirtualDriveEnabled = it }
                )
            }

            if (settingsManager.isVirtualDriveEnabled) {
                item {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    Text(
                        text = "Selected Test CD",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
                    )
                }

                itemsIndexed(settingsManager.testCds) { index, cd ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = settingsManager.selectedTestCdIndex == index,
                            onClick = { settingsManager.selectedTestCdIndex = index }
                        )
                        Column(
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .weight(1f)
                        ) {
                            Text(text = cd.album, style = MaterialTheme.typography.bodyLarge)
                            Text(text = cd.artist, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                Text(
                    text = "Support & Debug",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(16.dp)
                )
            }

            item {
                PreferenceItem(
                    title = "Copy Debug Report",
                    description = "Copy system info, logs, and crash reports to clipboard",
                    onClick = onCopyDebugReport
                )
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
                PreferencesHintCard(
                    title = "About Virtual Drive",
                    description = "The virtual drive uses deterministic mock data to simulate successful rips and error scenarios without physical hardware."
                )
            }
        }
    }
}
