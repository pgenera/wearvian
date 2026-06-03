package org.fivesevenfive.wearvian.ui

import android.Manifest
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.viewmodel.compose.viewModel
import org.fivesevenfive.wearvian.util.logi

class MainActivity : ComponentActivity() {

    private val requiredPermissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            logi("permissions result: $grants")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logi("MainActivity: onCreate; requesting permissions")
        permissionLauncher.launch(requiredPermissions)

        setContent {
            val vm: SetupViewModel = viewModel()
            val state by vm.state
            // Keep the screen on through the setup/pairing/companion dance so it
            // can't sleep mid-flow; allow it to sleep once bonded or on error.
            LaunchedEffect(state.phase) {
                val keepAwake = state.phase in setOf(
                    Phase.LOADING,
                    Phase.NEEDS_SETUP,
                    Phase.AWAITING_COMPANION,
                    Phase.ENROLLED,
                    Phase.PAIRING,
                )
                if (keepAwake) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            // Swipe left → BLE debug console; swipe right → back to the app.
            var showDebug by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        var dx = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dx = 0f },
                            onDragEnd = {
                                if (dx < -60f) showDebug = true
                                else if (dx > 60f) showDebug = false
                            },
                        ) { _, amount -> dx += amount }
                    },
            ) {
                if (showDebug) {
                    DebugScreen()
                } else {
                    WearvianApp(
                        state = state,
                        onRefresh = vm::refresh,
                        onSetup = vm::startEnrollment,
                        onPair = {
                            permissionLauncher.launch(requiredPermissions)
                            vm.startPairing()
                        },
                        onTogglePresence = vm::setPresence,
                        onCommand = vm::sendCommand,
                        onReset = vm::reset,
                    )
                }
            }
        }
    }
}
