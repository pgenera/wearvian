package org.fivesevenfive.wearvian.ble

import android.app.KeyguardManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fivesevenfive.wearvian.crypto.KeyManager
import org.fivesevenfive.wearvian.crypto.SharedSecretCache
import org.fivesevenfive.wearvian.protocol.ActiveCommandFrames
import org.fivesevenfive.wearvian.service.PresenceService
import org.fivesevenfive.wearvian.store.Enrollment
import org.fivesevenfive.wearvian.util.DebugLog

/** What happened to a tapped command. */
sealed interface CommandOutcome {
    data object Sent : CommandOutcome
    data class Failed(val failure: CommandFailure) : CommandOutcome
}

/**
 * The one place the app and the tile send an active command, so both route the same way and both
 * get an outcome to show the user:
 *  - watch locked → blocked outright (anti-theft; never queued to fire after unlock);
 *  - presence service running and not passive → ride the live session via [CommandBus] and wait
 *    for the frame write (or the drop). The session owns the PK link in this state — even while
 *    it is still (re)connecting — so a one-shot here would run a second handshake on the same
 *    peripheral; the bus's TTL bounds the wait instead;
 *  - otherwise (key off, or passive) → one-shot [ActiveCommandManager]. While parked nearby an
 *    UNLOCK also re-activates the key, started only AFTER the one-shot has closed its GATT client.
 */
object CommandDispatcher {

    enum class Route { BLOCKED_LOCKED, LIVE_SESSION, ONE_SHOT }

    /** Pure routing decision (unit-tested). */
    fun route(watchLocked: Boolean, serviceRunning: Boolean, passive: Boolean): Route = when {
        watchLocked -> Route.BLOCKED_LOCKED
        serviceRunning && !passive -> Route.LIVE_SESSION
        else -> Route.ONE_SHOT
    }

    /** Whether a one-shot of [commandCode] should wake the key to active once it has finished. */
    fun wakesKeyAfterOneShot(commandCode: Int, parkedNearby: Boolean): Boolean =
        parkedNearby && commandCode == ActiveCommandFrames.Cmd.UNLOCK_ALL

    suspend fun send(
        context: Context, enrollment: Enrollment, keyManager: KeyManager, commandCode: Int, label: String,
    ): CommandOutcome {
        val app = context.applicationContext
        val locked = app.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
        return when (route(locked, PresenceService.isRunning, PresenceService.passive.value)) {
            Route.BLOCKED_LOCKED -> {
                SharedSecretCache.clear() // a fresh locked reading: drop the secret too
                DebugLog.add("cmd $label blocked — watch locked")
                CommandOutcome.Failed(CommandFailure.WATCH_LOCKED)
            }
            Route.LIVE_SESSION -> {
                val cmd = CommandBus.submit(commandCode, label)
                if (CommandBus.awaitOutcome(cmd)) CommandOutcome.Sent
                else CommandOutcome.Failed(CommandFailure.NOT_CONNECTED)
            }
            Route.ONE_SHOT -> {
                // Read before sending: the one-shot's own connection can wake passive (autoConnect).
                val wake = wakesKeyAfterOneShot(commandCode, PresenceService.parkedNearby.value)
                val result = withContext(Dispatchers.IO) {
                    ActiveCommandManager(app, keyManager).sendCommand(enrollment, commandCode, label)
                }
                if (wake) PresenceService.start(app) // after the one-shot closed its GATT client
                result.fold(
                    onSuccess = { CommandOutcome.Sent },
                    onFailure = { CommandOutcome.Failed(ActiveCommandManager.failureOf(it)) },
                )
            }
        }
    }
}
