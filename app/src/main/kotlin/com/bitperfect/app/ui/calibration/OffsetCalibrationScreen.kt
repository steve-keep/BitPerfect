package com.bitperfect.app.ui.calibration

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bitperfect.core.services.DriveOffsetRepository
import kotlinx.coroutines.delay

sealed class CalibrationStepState {
    data object WaitingForDisc : CalibrationStepState()
    data object CheckingDisc : CalibrationStepState()
    data class NotAKeyDisc(val discTitle: String?, val attemptedUrl: String? = null) : CalibrationStepState()
    data class KeyDiscConfirmed(val discTitle: String?) : CalibrationStepState()
    data class Scanning(val progress: Float, val phase: String) : CalibrationStepState()
    data object Success : CalibrationStepState()
    data class Error(val message: String, val attemptedUrl: String? = null) : CalibrationStepState()
}

val CalibrationStepStateListSaver = listSaver<MutableList<CalibrationStepState>, String>(
    save = { stateList ->
        stateList.map { state ->
            when (state) {
                is CalibrationStepState.WaitingForDisc -> "WaitingForDisc"
                is CalibrationStepState.CheckingDisc -> "CheckingDisc"
                is CalibrationStepState.NotAKeyDisc -> "NotAKeyDisc:${state.discTitle ?: ""}|||${state.attemptedUrl ?: ""}"
                is CalibrationStepState.KeyDiscConfirmed -> "KeyDiscConfirmed:${state.discTitle ?: ""}"
                is CalibrationStepState.Scanning -> "Scanning:${state.progress}|||${state.phase}"
                is CalibrationStepState.Success -> "Success"
                is CalibrationStepState.Error -> "Error:${state.message}|||${state.attemptedUrl ?: ""}"
            }
        }
    },
    restore = { savedList ->
        val stateList = mutableStateListOf<CalibrationStepState>()
        savedList.forEach { savedString ->
            stateList.add(
                when {
                    savedString == "WaitingForDisc" -> CalibrationStepState.WaitingForDisc
                    savedString == "CheckingDisc" -> CalibrationStepState.CheckingDisc
                    savedString.startsWith("NotAKeyDisc:") -> {
                        val parts = savedString.substringAfter("NotAKeyDisc:").split("|||", limit = 2)
                        CalibrationStepState.NotAKeyDisc(
                            discTitle = parts.getOrNull(0)?.takeIf { it.isNotEmpty() },
                            attemptedUrl = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
                        )
                    }
                    savedString.startsWith("KeyDiscConfirmed:") -> CalibrationStepState.KeyDiscConfirmed(savedString.substringAfter("KeyDiscConfirmed:").takeIf { it.isNotEmpty() })
                    savedString.startsWith("Scanning:") -> {
                        val parts = savedString.substringAfter("Scanning:").split("|||", limit = 2)
                        CalibrationStepState.Scanning(
                            progress = parts.getOrNull(0)?.toFloatOrNull() ?: 0f,
                            phase = parts.getOrElse(1) { "" }
                        )
                    }
                    savedString == "Scanning" -> CalibrationStepState.Scanning(0f, "Scanning disc...")
                    savedString == "Success" -> CalibrationStepState.Success
                    savedString.startsWith("Error:") -> {
                        val parts = savedString.substringAfter("Error:").split("|||", limit = 2)
                        CalibrationStepState.Error(
                            message = parts.getOrElse(0) { "" },
                            attemptedUrl = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
                        )
                    }
                    else -> CalibrationStepState.WaitingForDisc
                }
            )
        }
        stateList
    }
)

@Composable
fun CalibrationStepContent(
    stepNumber: Int,
    state: CalibrationStepState,
    onStateChanged: (CalibrationStepState) -> Unit
) {
    // Note: the delayed state change is moved to the ViewModel where scanning will occur

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (state) {
            is CalibrationStepState.WaitingForDisc -> {
                Text(
                    text = "Insert disc $stepNumber of 3",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
                // "Start Scan" will appear when it transitions to KeyDiscConfirmed
            }
            is CalibrationStepState.CheckingDisc -> {
                CircularProgressIndicator(modifier = Modifier.padding(bottom = 24.dp))
                Text(
                    text = "Checking disc…",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            is CalibrationStepState.NotAKeyDisc -> {
                val uriHandler = LocalUriHandler.current
                Icon(
                    imageVector = Icons.Outlined.Warning,
                    contentDescription = "Warning",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .size(64.dp)
                        .padding(bottom = 16.dp)
                )
                if (state.discTitle != null) {
                    Text(
                        text = state.discTitle,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                Text(
                    text = "This disc isn't in the AccurateRip database. Please insert a different disc.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = if (state.attemptedUrl != null) 8.dp else 24.dp)
                )
                if (state.attemptedUrl != null) {
                    Text(
                        text = state.attemptedUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .padding(bottom = 24.dp, start = 16.dp, end = 16.dp)
                            .clickable {
                                try {
                                    uriHandler.openUri(state.attemptedUrl)
                                } catch (e: Exception) {
                                    // Ignore if no handler available
                                }
                            }
                    )
                }
                Button(onClick = { onStateChanged(CalibrationStepState.WaitingForDisc) }) {
                    Text("Retry")
                }
            }
            is CalibrationStepState.KeyDiscConfirmed -> {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = "Key Disc Confirmed",
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier
                        .size(64.dp)
                        .padding(bottom = 16.dp)
                )
                if (state.discTitle != null) {
                    Text(
                        text = state.discTitle,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                } else {
                    Text(
                        text = "Key Disc Confirmed",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                }
            }
            is CalibrationStepState.Scanning -> {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(bottom = 24.dp)) {
                    CircularProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.size(80.dp),
                        strokeWidth = 6.dp
                    )
                    Text(
                        text = "${(state.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    text = state.phase,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            is CalibrationStepState.Success -> {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = "Success",
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier
                        .size(64.dp)
                        .padding(bottom = 16.dp)
                )
                Text(
                    text = "Disc scanned successfully",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            is CalibrationStepState.Error -> {
                val uriHandler = LocalUriHandler.current
                Icon(
                    imageVector = Icons.Outlined.Warning,
                    contentDescription = "Error",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .size(64.dp)
                        .padding(bottom = 16.dp)
                )
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = if (state.attemptedUrl != null) 8.dp else 24.dp)
                )
                if (state.attemptedUrl != null) {
                    Text(
                        text = state.attemptedUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .padding(bottom = 24.dp, start = 16.dp, end = 16.dp)
                            .clickable {
                                try {
                                    uriHandler.openUri(state.attemptedUrl)
                                } catch (e: Exception) {
                                    // Ignore
                                }
                            }
                    )
                }
                Button(onClick = { onStateChanged(CalibrationStepState.WaitingForDisc) }) {
                    Text("Retry")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OffsetCalibrationScreen(
    driveOffsetRepository: DriveOffsetRepository,
    onNavigateBack: () -> Unit,
    viewModel: OffsetCalibrationViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return OffsetCalibrationViewModel(
                    driveOffsetRepository = driveOffsetRepository
                ) as T
            }
        }
    )
) {
    val uiState by viewModel.uiState.collectAsState()
    val currentStep = uiState.activeStepIndex + 1
    val stepStates = uiState.steps
    val calibrationResult = uiState.calibrationResult
    val saveState = uiState.saveState

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(saveState) {
        when (saveState) {
            is SaveState.Finished -> {
                onNavigateBack()
            }
            is SaveState.Error -> {
                snackbarHostState.showSnackbar(saveState.message)
                viewModel.resetSaveState()
            }
            else -> {}
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text("Calibrate Drive Offset") },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close"
                            )
                        }
                    }
                )
            },
            bottomBar = {
            BottomAppBar {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (calibrationResult != null) {
                        Spacer(modifier = Modifier.weight(1f))
                        Button(
                            onClick = { viewModel.saveOffset(calibrationResult.offset) },
                            enabled = saveState !is SaveState.Saving
                        ) {
                            if (saveState is SaveState.Saving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp).padding(end = 8.dp),
                                    strokeWidth = 2.dp
                                )
                            }
                            Text("Finish")
                        }
                    } else {
                        if (currentStep > 1) {
                            TextButton(onClick = { viewModel.setActiveStepIndex(currentStep - 2) }) {
                                Text("Back")
                            }
                        } else {
                            Spacer(modifier = Modifier.width(64.dp))
                        }

                        Button(
                            onClick = {
                                if (currentStep < 3) {
                                    viewModel.setActiveStepIndex(currentStep)
                                } else {
                                    onNavigateBack()
                                }
                            },
                            enabled = stepStates[currentStep - 1] is CalibrationStepState.Success
                        ) {
                            Text(if (currentStep < 3) "Next" else "Finish")
                        }
                    }
                }
            }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            if (calibrationResult != null) {
                // Summary Screen
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (calibrationResult.passed) {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = "Passed",
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(80.dp).padding(bottom = 16.dp)
                        )
                        Text(
                            text = "Confidence: Pass",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 24.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Warning,
                            contentDescription = "Warning",
                            tint = Color(0xFFFFC107),
                            modifier = Modifier.size(80.dp).padding(bottom = 16.dp)
                        )
                        Text(
                            text = "Confidence: Low — consider re-running",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 24.dp)
                        )
                    }

                    Text(
                        text = "Offset: ${if (calibrationResult.offset > 0) "+" else ""}${calibrationResult.offset} samples",
                        style = MaterialTheme.typography.headlineMedium
                    )
                }
            } else {
                // Step indicator
                Text(
                    text = "Step $currentStep of 3",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )

                // Content area
                AnimatedContent(
                    targetState = currentStep,
                    transitionSpec = {
                        if (targetState > initialState) {
                            (slideInHorizontally { width -> width } + fadeIn()).togetherWith(
                                slideOutHorizontally { width -> -width } + fadeOut()
                            )
                        } else {
                            (slideInHorizontally { width -> -width } + fadeIn()).togetherWith(
                                slideOutHorizontally { width -> width } + fadeOut()
                            )
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { targetStep ->
                    CalibrationStepContent(
                        stepNumber = targetStep,
                        state = stepStates[targetStep - 1],
                        onStateChanged = { newState ->
                            if (newState is CalibrationStepState.WaitingForDisc) {
                                viewModel.resetStep(targetStep - 1)
                            }
                        }
                    )
                    }
                }
            }
        }
    }
}
