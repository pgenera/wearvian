package org.fivesevenfive.wearvian.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.fivesevenfive.wearvian.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small in-memory ring buffer of BLE/debug events, surfaced on the swipe-left debug
 * screen so on-vehicle BLE exchanges (and their semantic meaning) are visible without
 * a logcat tether. Also mirrors to logcat. Thread-safe; bounded to [MAX] lines.
 *
 * Cheap on the hot path: [add] only appends to the deque and bumps a version counter;
 * the list copy is made lazily, when a [flow] collector (the debug screen) actually reads
 * it. Builds without the debug console ([BuildConfig.DEBUG_CONSOLE] false) skip the
 * buffer entirely and only mirror to logcat.
 */
object DebugLog {
    private const val MAX = 200
    private val lines = ArrayDeque<String>()
    /** Bumped on every change; [flow] maps it to a snapshot only when collected. */
    private val version = MutableStateFlow(0L)
    private val ts = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** Whether lines are kept for the debug console. Not a const so tests can flip it. */
    @Volatile internal var bufferEnabled: Boolean = BuildConfig.DEBUG_CONSOLE

    /**
     * The buffer's lines, re-emitted on change. Conflated (backed by a StateFlow), so a slow
     * collector snapshots only the latest state. Collect with an initial [snapshot].
     */
    val flow: Flow<List<String>> = version.map { snapshot() }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    fun add(line: String) {
        if (bufferEnabled) {
            synchronized(this) {
                lines.addLast("${ts.format(Date())}  $line")
                while (lines.size > MAX) lines.removeFirst()
                version.value++
            }
        }
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
        version.value++
    }
}
