package org.fivesevenfive.wearvian.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.SwipeToDismissValue
import androidx.wear.compose.foundation.rememberSwipeToDismissBoxState
import androidx.wear.compose.material.SwipeToDismissBox
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

    /** Set when launched from the tile's key control; consumed once we reach BONDED. */
    private val activateKeyRequest = mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_ACTIVATE_KEY, false)) activateKeyRequest.value = true
        // Consume it so a later config-change/recreate can't re-trigger activation.
        intent.removeExtra(EXTRA_ACTIVATE_KEY)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logi("MainActivity: onCreate; requesting permissions")
        permissionLauncher.launch(requiredPermissions)
        activateKeyRequest.value = intent?.getBooleanExtra(EXTRA_ACTIVATE_KEY, false) == true
        intent?.removeExtra(EXTRA_ACTIVATE_KEY) // consume — don't re-activate on recreate

        setContent {
            val vm: SetupViewModel = viewModel()
            val state by vm.state
            // The tile's key control launches us with EXTRA_ACTIVATE_KEY to also start
            // the mobile key. Activate once we're bonded (setPresence is idempotent).
            val activate by activateKeyRequest
            LaunchedEffect(activate, state.phase) {
                if (activate && state.phase == Phase.BONDED) {
                    if (!state.presenceRunning) vm.setPresence(true)
                    activateKeyRequest.value = false
                }
            }
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
            // Swipe left → BLE debug console; native Wear swipe-right → back.
            var showDebug by remember { mutableStateOf(false) }
            if (showDebug) {
                val dismiss = rememberSwipeToDismissBoxState()
                LaunchedEffect(dismiss.currentValue) {
                    if (dismiss.currentValue == SwipeToDismissValue.Dismissed) {
                        showDebug = false
                        dismiss.snapTo(SwipeToDismissValue.Default)
                    }
                }
                SwipeToDismissBox(state = dismiss) { isBackground ->
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        if (!isBackground) DebugScreen()
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            var dx = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { dx = 0f },
                                onDragEnd = { if (dx < -60f) showDebug = true },
                            ) { _, amount -> dx += amount }
                        },
                ) {
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
                        onProximityWakeChange = vm::setProximityWake,
                        onReset = vm::reset,
                    )
                }
            }
        }
    }

    companion object {
        /** Boolean intent extra: when true (set by the tile's key control), activate the mobile key on launch. */
        const val EXTRA_ACTIVATE_KEY = "org.fivesevenfive.wearvian.ACTIVATE_KEY"
    }
}
