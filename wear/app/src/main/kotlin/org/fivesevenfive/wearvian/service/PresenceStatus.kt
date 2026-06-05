package org.fivesevenfive.wearvian.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.fivesevenfive.wearvian.util.DebugLog
import java.util.concurrent.ConcurrentHashMap

/**
 * Aggregates the per-connection state of every [org.fivesevenfive.wearvian.ble.VehicleSession]
 * into a single human-readable summary for the foreground notification — mirroring the
 * official app's persistent "vehicle connected / disconnected" notification.
 *
 * Each session reports transitions via [set]; [PresenceService] observes [summary] and
 * pushes it to the ongoing notification. Every change is mirrored to [DebugLog] → logcat
 * so the connection state is verifiable from `adb logcat -s wearvian`.
 */
object PresenceStatus {

    enum class Link { CONNECTING, CONNECTED, UP, DOWN }

    /** label ("PK","S1"…) → latest link state. */
    private val links = ConcurrentHashMap<String, Link>()

    private val _summary = MutableStateFlow("Starting…")
    val summary: StateFlow<String> get() = _summary

    /** True once at least one link is authenticated (UP). */
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> get() = _connected

    fun set(label: String, state: Link) {
        links[label] = state
        recompute()
        DebugLog.add("status: $label=$state → \"${_summary.value}\"")
    }

    /** Clear all state (presence stopped). */
    fun reset() {
        links.clear()
        _connected.value = false
        _summary.value = "Stopped"
        DebugLog.add("status: reset")
    }

    /** Snapshot for logging/UI: e.g. "PK=UP S1=UP S2=CONNECTING". */
    fun snapshot(): String =
        links.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value}" }

    private fun recompute() {
        val up = links.values.count { it == Link.UP }
        val connecting = links.values.count { it == Link.CONNECTING || it == Link.CONNECTED }
        _connected.value = up > 0
        _summary.value = when {
            up > 0 -> "Vehicle connected · $up link${if (up == 1) "" else "s"}"
            connecting > 0 -> "Connecting to vehicle…"
            links.isEmpty() -> "Searching for vehicle…"
            else -> "Vehicle disconnected"
        }
    }
}
