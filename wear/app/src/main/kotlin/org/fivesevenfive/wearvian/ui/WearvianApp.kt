package org.fivesevenfive.wearvian.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import org.fivesevenfive.wearvian.protocol.ActiveCommandFrames

/**
 * Single-screen Wear UI that reflects the current [SetupUiState]. Stateless and
 * driven entirely by callbacks so it can be previewed/tested independently of the
 * view model.
 */
@Composable
fun WearvianApp(
    state: SetupUiState,
    onRefresh: () -> Unit,
    onSetup: () -> Unit,
    onPair: () -> Unit,
    onTogglePresence: (Boolean) -> Unit,
    onCommand: (Int, String) -> Unit = { _, _ -> },
    onProximityWakeChange: (Boolean) -> Unit = {},
    onStartPassive: () -> Unit = {},
    onReset: () -> Unit,
) {
    // Refresh on every resume so state stays correct after the key is turned off from the
    // notification (or anything changes while the app was backgrounded), not just on launch.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { onRefresh() }

    // The BONDED control surface is its own full-screen vertical pager (ControlScreens.kt).
    if (state.phase == Phase.BONDED) {
        ControlScreens(state, onTogglePresence, onCommand, onProximityWakeChange, onStartPassive)
        return
    }

    Scaffold {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (state.phase) {
                Phase.LOADING, Phase.PAIRING -> Busy(state.detail)

                Phase.NEEDS_SETUP -> {
                    Title("wearvian")
                    Caption("Set up this watch as your Rivian phone key.")
                    Button(onClick = onSetup) { Text("Set up") }
                }

                Phase.AWAITING_COMPANION -> {
                    Title("Check your phone")
                    Caption(state.detail ?: "Open wearvian companion on your phone and sign in to Rivian.")
                    CircularProgressIndicator()
                }

                Phase.ENROLLED -> {
                    Title("Enrolled ✓")
                    Caption(state.detail?.let { "VIN $it" } ?: "Now pair over Bluetooth.")
                    Button(onClick = onPair) { Text("Pair with vehicle") }
                }

                Phase.BONDED -> Unit // handled by ControlScreens above

                Phase.ERROR -> {
                    Title("Something went wrong")
                    Caption(state.detail ?: "Please try again.")
                    Button(onClick = onRefresh) { Text("OK") }
                }
            }
        }
    }
}

@Composable
private fun Busy(detail: String?) {
    CircularProgressIndicator()
    Caption(detail ?: "Working…")
}

@Composable
private fun Title(text: String) {
    Text(text = text, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun Caption(text: String) {
    Text(text = text, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 8.dp))
}
