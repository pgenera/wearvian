package org.fivesevenfive.wearvian.ble

import kotlin.random.Random

/**
 * Per-session reconnect schedule for [VehicleSession.runForever]. Pure (no Android) so it is
 * unit-testable.
 *
 * - Backoff grows exponentially (with jitter) while attempts keep failing, and resets only once a
 *   session AUTHENTICATES — a link that connects and then fails the handshake is still failing.
 * - The PRIMARY falls back to autoConnect after one failed connect (it goes dormant when parked and
 *   a direct connect keeps missing its rare advertisements). Sensors follow after a few failed
 *   connects, so an unreachable sensor stops occupying the one-at-a-time direct-connect slot for
 *   35 s per attempt and delaying the links that matter.
 * - A handshake failure after a successful connect does NOT count as a connect failure (the module
 *   is evidently awake), so it doesn't push the PRIMARY onto the slower autoConnect path.
 */
class ReconnectPolicy(
    private val primary: Boolean,
    private val random: Random = Random.Default,
) {
    enum class Outcome {
        /** The GATT connection was never established. */
        NO_CONNECT,
        /** Connected, but the session ended before authenticating. */
        CONNECTED,
        /** The session authenticated (it may have run for a long time before ending). */
        AUTHENTICATED,
    }

    /** Consecutive attempts that never connected. */
    var connectFailures = 0
        private set

    /** Consecutive attempts that never authenticated. */
    var failures = 0
        private set

    fun record(outcome: Outcome) {
        when (outcome) {
            Outcome.NO_CONNECT -> { connectFailures++; failures++ }
            Outcome.CONNECTED -> { connectFailures = 0; failures++ }
            Outcome.AUTHENTICATED -> { connectFailures = 0; failures = 0 }
        }
    }

    val useAutoConnect: Boolean
        get() = connectFailures >= if (primary) 1 else SENSOR_AUTOCONNECT_AFTER

    /** Delay before the next attempt. */
    fun nextDelayMs(): Long {
        if (failures == 0) return BASE_MS
        val exp = BASE_MS shl (failures - 1).coerceAtMost(MAX_SHIFT)
        val capped = exp.coerceAtMost(MAX_MS)
        // ±25% jitter so sessions that failed together don't retry in lockstep; never below BASE.
        val jitter = (capped * JITTER * (2 * random.nextDouble() - 1)).toLong()
        return (capped + jitter).coerceIn(BASE_MS, MAX_MS)
    }

    companion object {
        const val BASE_MS = 1_500L
        const val MAX_MS = 60_000L
        const val SENSOR_AUTOCONNECT_AFTER = 3
        private const val MAX_SHIFT = 10
        private const val JITTER = 0.25
    }
}

/**
 * Sensor-discovery cadence for the presence loop. Every scan is a 6 s LOW_LATENCY window, so once
 * nothing new turns up the interval backs off (the sessions already launched reconnect on their
 * own); any new sensor snaps it back to the minimum. A failed scan (null) keeps the interval.
 */
object RescanSchedule {
    const val MIN_MS = 15_000L
    const val MAX_MS = 120_000L

    fun next(currentMs: Long, foundNew: Boolean?): Long = when (foundNew) {
        true -> MIN_MS
        false -> (currentMs * 2).coerceIn(MIN_MS, MAX_MS)
        null -> currentMs.coerceIn(MIN_MS, MAX_MS)
    }
}

/**
 * Session labels key [org.fivesevenfive.wearvian.service.PresenceStatus]'s per-link map, so two
 * sessions must never share one: a down sensor would overwrite an UP one's entry and the service
 * could read "no link" while a link is actually up.
 */
object SessionLabels {
    /** Label from the advertised name: "Rivian Sensor 3" → "S3"; nameless → "S?"; other → "PK2". */
    fun base(name: String?): String = when {
        name == null -> "S?"
        name.startsWith("Rivian Sensor ") -> "S" + name.removePrefix("Rivian Sensor ").trim()
        else -> "PK2"
    }

    /** [base], made unique against [used] (and added to it) with the address tail when needed. */
    fun unique(name: String?, address: String, used: MutableSet<String>): String {
        val base = base(name)
        val tail = address.replace(":", "").takeLast(4)
        var label = if (base == "S?" || base in used) "$base-$tail" else base
        var n = 2
        while (label in used) label = "$base-$tail-${n++}"
        used.add(label)
        return label
    }
}
