package org.fivesevenfive.wearvian.tile

import android.content.Context
import androidx.wear.tiles.TileService
import org.fivesevenfive.wearvian.util.AppForeground
import org.fivesevenfive.wearvian.util.DebugLog

/**
 * Asks the system to re-render [KeyTileService]. A tile is otherwise only re-requested when it
 * scrolls into view, so without this the tile's vehicle-state shading never updates live.
 *
 * Skipped while the app is in the foreground ([AppForeground]) — the tile is hidden behind the
 * app then, and the app already shows live state, so refreshing would be wasted work. Callers
 * that fire on rapidly-changing state (e.g. the 0x1c stream) must debounce; this only forwards
 * the request (the platform also rate-limits tile updates).
 *
 * A refresh skipped while foreground is remembered and replayed by [flushPending] when the app
 * leaves the foreground (MainActivity.onStop) — otherwise a state change made inside the app
 * (e.g. unlock) never reaches the tile, since the status-change flow is distinctUntilChanged.
 */
object TileRefresher {
    @Volatile private var pending = false

    fun refresh(context: Context) {
        if (AppForeground.inForeground) {
            pending = true
            return
        }
        request(context)
    }

    /** Replay a refresh that was skipped while the app was in the foreground. */
    fun flushPending(context: Context) {
        if (!pending || AppForeground.inForeground) return
        request(context)
    }

    private fun request(context: Context) {
        pending = false
        runCatching {
            TileService.getUpdater(context.applicationContext).requestUpdate(KeyTileService::class.java)
        }.onFailure { DebugLog.add("tile: requestUpdate failed — ${it.message}") }
    }
}
