package org.fivesevenfive.wearvian.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/**
 * Per-session reconnect schedule for [VehicleSession.runForever]. Pure (no Android) so it is
 * unit-testable.
 *
 * - Backoff grows exponentially (with jitter) while attempts never connect, and resets once a
 *   session AUTHENTICATES and stays up for [MIN_STABLE_SESSION_MS]. One dropped sooner counts
 *   like a connected-but-unauthenticated attempt, so a link the car drops at once doesn't retry
 *   every 1.5 s forever.
 * - A CONNECTED outcome (connected, handshake failed) proves the module is awake and in range —
 *   typically the edge of range on the walk back to the car. Earlier NO_CONNECT failures (the car
 *   was away) then stop counting, and the first [QUICK_CONNECTED_RETRIES] consecutive connected-
 *   but-unauthenticated attempts retry at the base delay; after that the exponential growth
 *   resumes, so a permanently broken sensor still backs off.
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

    /** Consecutive attempts that never authenticated (NO_CONNECTs before a connect are forgotten). */
    var failures = 0
        private set

    /** Consecutive attempts that connected but did not authenticate. */
    var connectedFailures = 0
        private set

    /** Record an attempt's [outcome]; [authenticatedForMs] is how long an AUTHENTICATED one stayed up. */
    fun record(outcome: Outcome, authenticatedForMs: Long = Long.MAX_VALUE) {
        val stable = outcome == Outcome.AUTHENTICATED && authenticatedForMs >= MIN_STABLE_SESSION_MS
        when {
            outcome == Outcome.NO_CONNECT -> { connectFailures++; failures++; connectedFailures = 0 }
            stable -> { connectFailures = 0; failures = 0; connectedFailures = 0 }
            // CONNECTED, or authenticated but dropped almost at once: the module is awake and in range.
            else -> { connectFailures = 0; connectedFailures++; failures = connectedFailures }
        }
    }

    val useAutoConnect: Boolean
        get() = connectFailures >= if (primary) 1 else SENSOR_AUTOCONNECT_AFTER

    /** Delay before the next attempt. */
    fun nextDelayMs(): Long {
        if (failures == 0) return BASE_MS
        // Connected at the edge of range: retry quickly a few times, then grow from there.
        val steps = if (connectedFailures > 0) (connectedFailures - QUICK_CONNECTED_RETRIES).coerceAtLeast(0) else failures - 1
        val exp = BASE_MS shl steps.coerceAtMost(MAX_SHIFT)
        val capped = exp.coerceAtMost(MAX_MS)
        // ±25% jitter so sessions that failed together don't retry in lockstep; never below BASE.
        val jitter = (capped * JITTER * (2 * random.nextDouble() - 1)).toLong()
        return (capped + jitter).coerceIn(BASE_MS, MAX_MS)
    }

    companion object {
        const val BASE_MS = 1_500L
        const val MAX_MS = 60_000L
        const val SENSOR_AUTOCONNECT_AFTER = 3
        /** Connected-but-unauthenticated attempts retried at the base delay before backing off. */
        const val QUICK_CONNECTED_RETRIES = 4
        /** An authenticated session shorter than this doesn't reset the backoff. */
        const val MIN_STABLE_SESSION_MS = 10_000L
        private const val MAX_SHIFT = 10
        private const val JITTER = 0.25
    }
}

/**
 * "Reconnect now" signal for sessions sitting out a [ReconnectPolicy] backoff: a command submitted
 * to [CommandBus] or a watch-key burst starting means the wearer is at the car, so a waiting
 * session skips the rest of its delay. Process-wide; each [hurry] ends every wait in progress once.
 */
object ReconnectHurry {
    private val generation = MutableStateFlow(0L)

    fun hurry() {
        generation.value = generation.value + 1
    }

    /** Wait up to [ms]; returns true if [hurry] cut the wait short. */
    suspend fun delayUnlessHurried(ms: Long): Boolean {
        val start = generation.value
        return withTimeoutOrNull(ms) { generation.first { it != start } } != null
    }
}

/**
 * Which saved vehicle addresses ([org.fivesevenfive.wearvian.store.VehicleAddressStore], learned
 * from earlier sessions and already trusted for the proximity-wake filters) to open sensor sessions
 * for straight away when sensors are wanted, instead of waiting for a 6 s discovery scan to re-find
 * them. The scan still runs to find new ones. Pure, unit-tested.
 */
object SensorSeed {
    /**
     * [saved] minus the phone key ([pkAddress]) and anything already [started] (which holds the
     * running PRIMARY's address too), case-insensitively, in a stable order.
     */
    fun toStart(saved: Set<String>, pkAddress: String?, started: Set<String>): List<String> {
        val skip = (started + listOfNotNull(pkAddress)).map { it.uppercase() }.toSet()
        return saved.filter { it.uppercase() !in skip }.distinctBy { it.uppercase() }.sorted()
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
