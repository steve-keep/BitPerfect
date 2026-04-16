package com.bitperfect.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bitperfect.core.virtual.TestCdData

@Composable
fun SettingsScreen(
    isVirtualDriveEnabled: Boolean,
    onVirtualDriveToggle: (Boolean) -> Unit,
    selectedTestCdIndex: Int,
    onTestCdSelected: (Int) -> Unit,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            item {
                Text(
                    text = "Simulation",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            item {
                PreferenceSwitch(
                    title = "Virtual Drive",
                    description = "Simulate a connected USB optical drive",
                    checked = isVirtualDriveEnabled,
                    onCheckedChange = onVirtualDriveToggle
                )
            }

            if (isVirtualDriveEnabled) {
                item {
                    Text(
                        text = "Test CD Selection",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                items(TestCdData.CDs.size) { index ->
                    val cd = TestCdData.CDs[index]
                    PreferenceItem(
                        title = cd.product,
                        description = "${cd.vendor} - ${cd.tracks.size} tracks",
                        onClick = { onTestCdSelected(index) },
                        trailing = {
                            RadioButton(
                                selected = index == selectedTestCdIndex,
                                onClick = { onTestCdSelected(index) }
                            )
                        }
                    )
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = "About",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            item {
                PreferenceItem(
                    title = "Version",
                    description = "1.0.0-DEBUG"
                )
            }
        }
    }
}
