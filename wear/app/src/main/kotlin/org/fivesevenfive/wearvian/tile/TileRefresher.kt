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
 */
object TileRefresher {
    fun refresh(context: Context) {
        if (AppForeground.inForeground) return
        runCatching {
            TileService.getUpdater(context.applicationContext).requestUpdate(KeyTileService::class.java)
        }.onFailure { DebugLog.add("tile: requestUpdate failed — ${it.message}") }
    }
}
