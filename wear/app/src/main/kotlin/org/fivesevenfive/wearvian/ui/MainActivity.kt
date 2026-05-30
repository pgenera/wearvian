package org.fivesevenfive.wearvian.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    private val requiredPermissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher.launch(requiredPermissions)

        setContent {
            val vm: SetupViewModel = viewModel()
            val state by vm.state
            WearvianApp(
                state = state,
                onRefresh = vm::refresh,
                onSetup = vm::startEnrollment,
                onPair = {
                    permissionLauncher.launch(requiredPermissions)
                    vm.startPairing()
                },
                onTogglePresence = vm::setPresence,
                onReset = vm::reset,
            )
        }
    }
}
