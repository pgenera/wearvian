package org.fivesevenfive.wearvian.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text

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
    onReset: () -> Unit,
) {
    LaunchedEffect(Unit) { onRefresh() }

    Scaffold {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (state.phase) {
                Phase.LOADING, Phase.ENROLLING, Phase.PAIRING -> Busy(state.detail)

                Phase.NEEDS_SETUP -> {
                    Title("wearvian")
                    Caption("Set up this watch as your Rivian phone key.")
                    Button(onClick = onSetup) { Text("Set up") }
                }

                Phase.AWAITING_BROWSER -> {
                    state.qr?.let {
                        Image(bitmap = it, contentDescription = "Sign-in QR", modifier = Modifier.size(120.dp))
                    }
                    Caption(state.detail ?: "Scan to sign in")
                }

                Phase.ENROLLED -> {
                    Title("Enrolled ✓")
                    Caption(state.detail?.let { "VIN $it" } ?: "Now pair over Bluetooth.")
                    Button(onClick = onPair) { Text("Pair with vehicle") }
                }

                Phase.BONDED -> {
                    Title("Ready ✓")
                    Caption(
                        if (state.presenceRunning) "Phone key active — approach to unlock and drive."
                        else "Activate the phone key to be detected by the vehicle.",
                    )
                    Button(
                        onClick = { onTogglePresence(!state.presenceRunning) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (state.presenceRunning) "Deactivate" else "Activate phone key")
                    }
                    Button(onClick = onReset, modifier = Modifier.padding(top = 8.dp)) { Text("Reset") }
                }

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
