package com.bitperfect.app.ui.calibration

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import kotlinx.coroutines.delay

sealed class CalibrationStepState {
    data object WaitingForDisc : CalibrationStepState()
    data object Scanning : CalibrationStepState()
    data object Success : CalibrationStepState()
    data class Error(val message: String) : CalibrationStepState()
}

val CalibrationStepStateListSaver = listSaver<MutableList<CalibrationStepState>, String>(
    save = { stateList ->
        stateList.map { state ->
            when (state) {
                is CalibrationStepState.WaitingForDisc -> "WaitingForDisc"
                is CalibrationStepState.Scanning -> "Scanning"
                is CalibrationStepState.Success -> "Success"
                is CalibrationStepState.Error -> "Error:${state.message}"
            }
        }
    },
    restore = { savedList ->
        val stateList = mutableStateListOf<CalibrationStepState>()
        savedList.forEach { savedString ->
            stateList.add(
                when {
                    savedString == "WaitingForDisc" -> CalibrationStepState.WaitingForDisc
                    savedString == "Scanning" -> CalibrationStepState.Scanning
                    savedString == "Success" -> CalibrationStepState.Success
                    savedString.startsWith("Error:") -> CalibrationStepState.Error(savedString.substringAfter("Error:"))
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
    onStateChanged: (CalibrationStepState) -> Unit,
    onStartScan: () -> Unit
) {
    LaunchedEffect(state) {
        if (state is CalibrationStepState.Scanning) {
            delay(2000L)
            onStateChanged(CalibrationStepState.Success)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (state) {
            is CalibrationStepState.WaitingForDisc -> {
                Text(
                    text = "Insert disc $stepNumber of 3 and tap Start Scan",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
                Button(
                    onClick = {
                        onStateChanged(CalibrationStepState.Scanning)
                        onStartScan()
                    }
                ) {
                    Text("Start Scan")
                }
            }
            is CalibrationStepState.Scanning -> {
                CircularProgressIndicator(modifier = Modifier.padding(bottom = 24.dp))
                Text(
                    text = "Scanning disc…",
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
                    modifier = Modifier.padding(bottom = 24.dp)
                )
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
    onNavigateBack: () -> Unit
) {
    var currentStep by rememberSaveable { mutableIntStateOf(1) }
    val stepStates = rememberSaveable(saver = CalibrationStepStateListSaver) {
        mutableStateListOf(
            CalibrationStepState.WaitingForDisc,
            CalibrationStepState.WaitingForDisc,
            CalibrationStepState.WaitingForDisc
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calibrate Drive Offset") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
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
                    if (currentStep > 1) {
                        TextButton(onClick = { currentStep-- }) {
                            Text("Back")
                        }
                    } else {
                        Spacer(modifier = Modifier.width(64.dp))
                    }

                    Button(
                        onClick = {
                            if (currentStep < 3) {
                                currentStep++
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
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
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
                        stepStates[targetStep - 1] = newState
                    },
                    onStartScan = { /* stubbed out */ }
                )
            }
        }
    }
}
