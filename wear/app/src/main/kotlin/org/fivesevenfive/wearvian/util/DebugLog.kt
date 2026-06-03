package org.fivesevenfive.wearvian.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small in-memory ring buffer of BLE/debug events, surfaced on the swipe-left debug
 * screen so on-vehicle BLE exchanges (and their semantic meaning) are visible without
 * a logcat tether. Also mirrors to logcat. Thread-safe; bounded to [MAX] lines.
 */
object DebugLog {
    private const val MAX = 200
    private val lines = ArrayDeque<String>()
    private val _flow = MutableStateFlow<List<String>>(emptyList())
    val flow: StateFlow<List<String>> = _flow
    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun add(line: String) {
        lines.addLast("${ts.format(Date())}  $line")
        while (lines.size > MAX) lines.removeFirst()
        _flow.value = lines.toList()
        logi("dbg: $line")
    }

    /**
     * A BLE frame event. [dir] is "→" (to vehicle) or "←" (from vehicle), [char] a
     * short characteristic id (e.g. "0x20"), [summary] the semantic meaning, and
     * [bytes] the frame length.
     */
    fun ble(dir: String, char: String, summary: String, bytes: Int? = null) =
        add("$dir $char  $summary${bytes?.let { "  ${it}B" } ?: ""}")

    @Synchronized
    fun clear() {
        lines.clear()
        _flow.value = emptyList()
    }
}
