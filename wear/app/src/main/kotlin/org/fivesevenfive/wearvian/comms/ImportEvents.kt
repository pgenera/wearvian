package org.fivesevenfive.wearvian.comms

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide signal that an imported key + enrollment just landed (via
 * [WearImportListenerService]). A foregrounded [org.fivesevenfive.wearvian.ui.SetupViewModel]
 * observes this and refreshes to "ready to pair" without the user relaunching the app — the
 * import is phone-initiated, so the watch UI otherwise has no idea its state just changed.
 * (If the app isn't open when the import lands, the normal on-resume refresh covers it.)
 */
object ImportEvents {
    private val _landed = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val landed: SharedFlow<String> = _landed.asSharedFlow()

    fun signal(vin: String) {
        _landed.tryEmit(vin)
    }
}
