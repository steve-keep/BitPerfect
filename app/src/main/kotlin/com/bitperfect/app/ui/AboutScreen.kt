package com.bitperfect.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bitperfect.app.BuildConfig
import com.bitperfect.app.OpenTelemetryProvider
import com.bitperfect.core.services.DriveOffsetRepository
import java.time.Instant
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

@Composable
fun AboutScreen(driveOffsetRepository: DriveOffsetRepository) {
    val generatedAt by driveOffsetRepository.generatedAt.collectAsState()

    val relativeTimeText = remember(generatedAt) {
        if (generatedAt == null) {
            "offset not downloaded"
        } else {
            try {
                val instant = Instant.parse(generatedAt)
                val now = Instant.now()
                val days = ChronoUnit.DAYS.between(instant, now)
                if (days == 0L) {
                    "offset downloaded today"
                } else if (days == 1L) {
                    "offset downloaded 1 day ago"
                } else {
                    "offset downloaded $days days ago"
                }
            } catch (e: DateTimeParseException) {
                "offset downloaded at $generatedAt"
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "BitPerfect",
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Version ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = relativeTimeText,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = if (OpenTelemetryProvider.isEnabled) "Telemetry: Active" else "Telemetry: Disabled",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
