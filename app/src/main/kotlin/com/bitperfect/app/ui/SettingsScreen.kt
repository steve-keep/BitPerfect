package com.bitperfect.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    var isVirtualDriveEnabled by remember { mutableStateOf(settingsManager.isVirtualDriveEnabled) }
    var selectedTestCdIndex by remember { mutableStateOf(settingsManager.selectedTestCdIndex) }
    var outputFolderUri by remember { mutableStateOf(settingsManager.outputFolderUri) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(it, takeFlags)

            val uriString = it.toString()
            outputFolderUri = uriString
            settingsManager.outputFolderUri = uriString
        }
    }

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
                    text = "Storage",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(16.dp)
                )
            }

            item {
                PreferenceItem(
                    title = "Output Folder",
                    description = outputFolderUri ?: "Not set (using app private storage)",
                    onClick = { folderPickerLauncher.launch(null) }
                )
            }

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
                    checked = isVirtualDriveEnabled,
                    onCheckedChange = {
                        isVirtualDriveEnabled = it
                        settingsManager.isVirtualDriveEnabled = it
                    }
                )
            }

            if (isVirtualDriveEnabled) {
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
                            selected = selectedTestCdIndex == index,
                            onClick = {
                                selectedTestCdIndex = index
                                settingsManager.selectedTestCdIndex = index
                            }
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
