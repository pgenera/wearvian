package org.fivesevenfive.wearvian.tile

import androidx.concurrent.futures.ResolvableFuture
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.ColorFilter
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER
import androidx.wear.protolayout.LayoutElementBuilders.Image
import androidx.wear.protolayout.LayoutElementBuilders.Row
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.VERTICAL_ALIGN_CENTER
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.StateBuilders
import androidx.wear.protolayout.expression.AppDataKey
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicInt32
import androidx.wear.protolayout.expression.DynamicDataBuilders.DynamicDataValue
import androidx.wear.protolayout.ResourceBuilders.AndroidImageResourceByResId
import androidx.wear.protolayout.ResourceBuilders.ImageResource
import androidx.wear.protolayout.ResourceBuilders.Resources
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.protolayout.material.Button
import androidx.wear.protolayout.material.ButtonColors
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.fivesevenfive.wearvian.R
import org.fivesevenfive.wearvian.ble.ActiveCommandManager
import org.fivesevenfive.wearvian.ble.CommandBus
import org.fivesevenfive.wearvian.service.PresenceService
import org.fivesevenfive.wearvian.service.VehicleModel
import org.fivesevenfive.wearvian.service.VehicleStatus
import org.fivesevenfive.wearvian.crypto.KeyManager
import org.fivesevenfive.wearvian.protocol.ActiveCommandFrames.Cmd
import org.fivesevenfive.wearvian.store.EnrollmentStore
import org.fivesevenfive.wearvian.store.SettingsStore
import org.fivesevenfive.wearvian.ui.MainActivity
import org.fivesevenfive.wearvian.util.DebugLog
import org.fivesevenfive.wearvian.util.logi
import kotlin.random.Random

/**
 * A Wear Tile: the key in the center, ringed by six controls — unlock/lock, frunk open/close,
 * hatch open/close — laid out as a hexagon:
 *
 * ```
 *        unlock        lock
 *   frunk‹open›  KEY  frunk‹close›
 *        hatch‹open›  hatch‹close›
 * ```
 *
 * Tapping a control:
 *  - **Key** launches the full app (carrying [MainActivity.EXTRA_ACTIVATE_KEY] to arm the key).
 *  - **Any command** fires the BLE command in-process via a `LoadAction` — the tile reloads, the
 *    command runs in the background, and the app UI never opens.
 *
 * The controls carry over the app's vehicle-state treatment: the button that MATCHES the current
 * state for each pair (lock filled when locked, frunk-open filled when the frunk is open — like
 * Rivian's app) is filled — white when the state is live (a session is confirming it), gray when
 * it's the last-known (stale) state, and plain dark when state is unknown. State comes from the
 * in-process [VehicleStatus] (same process as the presence service / app), so the tile reflects
 * whatever the app would show.
 *
 * Command dispatch reuses [ActiveCommandManager] (the same path the app uses) on a
 * process-lifetime scope so it survives this service instance being recycled between
 * tile requests. See docs/passive-entry-protocol.md.
 */
class KeyTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<Tile> {
        // Rear closure depends on body style: R1T tailgate (open only) vs R1S hatch (open+close).
        // forceR1t is the debug test override (Settings page); honored here so the tile matches the app.
        val isTruck = VehicleModel.fromVin(EnrollmentStore(this).load()?.vin.orEmpty()).isTruck ||
            SettingsStore(this).forceR1t
        // A LoadAction reloads the tile and reports the tapped element as lastClickableId — but so
        // do REFRESHES (scroll-into-view, our TileRefresher.requestUpdate on 0x1c state changes),
        // and the platform re-delivers the LAST click's State on those. Dispatching straight off
        // lastClickableId therefore re-fires the last-tapped command on every refresh: if the last
        // tap was UNLOCK, a status refresh as the user reaches for LOCK re-sends UNLOCK — the truck
        // unlocks when lock was pressed. Guard with a per-render nonce (stamped on each command
        // clickable, below) so a given tap dispatches exactly once: act only when the tapped
        // clickable's nonce differs from the last one we consumed. Covers both platform behaviours —
        // if a refresh clears the state, clickNonce is null and we skip; if it re-delivers the stale
        // state, the nonce matches what we already handled and we skip.
        val settings = SettingsStore(this)
        val clickNonce = requestParams.currentState.keyToValueMapping[CLICK_NONCE_KEY]
            ?.takeIf { it.hasIntValue() }?.intValue
        val freshTap = clickNonce != null && clickNonce != settings.lastTileClickNonce
        // Diagnostic: if a refresh re-delivers a command clickable we've already handled (or with no
        // fresh nonce), we're seeing the replay bug in the wild — log it so the debug console proves
        // whether this path fires without a real tap. Genuine taps still log via dispatch().
        val clickedCmd = requestParams.currentState.lastClickableId.takeIf { it.startsWith("cmd_") }
        if (clickedCmd != null && !freshTap) {
            DebugLog.add("tile: ignored $clickedCmd — refresh replay (nonce=${clickNonce ?: "none"})")
        }
        if (freshTap) {
            settings.lastTileClickNonce = clickNonce!! // consume: refreshes replaying this tap now skip
            when (requestParams.currentState.lastClickableId) {
                ID_UNLOCK -> dispatch(Cmd.UNLOCK_ALL, "UNLOCK")
                ID_LOCK -> dispatch(Cmd.LOCK_ALL, "LOCK")
                ID_FRUNK_OPEN -> dispatch(Cmd.OPEN_FRUNK, "OPEN_FRUNK")
                ID_FRUNK_CLOSE -> dispatch(Cmd.CLOSE_FRUNK, "CLOSE_FRUNK")
                ID_HATCH_OPEN ->
                    if (isTruck) dispatch(Cmd.OPEN_TAILGATE, "OPEN_TAILGATE")
                    else dispatch(Cmd.OPEN_LIFTGATE, "OPEN_LIFTGATE")
                ID_HATCH_CLOSE -> dispatch(Cmd.CLOSE_LIFTGATE, "CLOSE_LIFTGATE") // R1S only (no truck close button)
            }
        }

        // Fresh nonce stamped on every command clickable in THIS render; a tap echoes it back in
        // currentState so the guard above can tell a genuine press from a replayed refresh. Random
        // (not sequential) so it survives this service being recycled between requests without a
        // shared counter; a 1-in-2^32 collision with the stored nonce only costs one missed tap.
        val renderNonce = Random.nextInt()

        // Gold when the key is armed (active OR passively power-saving), not only while running.
        val keyArmed = SettingsStore(this).keyArmed
        // Vehicle state for the state-matching button fill (same source/semantics as the app).
        val st = VehicleStatus.state.value
        // Fill the button that MATCHES the current state (like Rivian's app: the lock glyph fills
        // when locked), NOT the press target. Only meaningful once we have state; the fill is white
        // when live, gray when stale (last-known).
        fun inState(matches: Boolean) = st.valid && matches

        // Size the whole hex to the actual face so it fills the watch (fixed dp looked small on
        // larger screens). Fractions are tuned so the top/bottom pair — the limiting corners —
        // stay inside the round face, while the key↔ring gap is preserved (not crowded).
        val cfg = requestParams.deviceConfiguration
        val dim = minOf(cfg.screenWidthDp, cfg.screenHeightDp).toFloat().let { if (it > 0f) it else FALLBACK_DIM }
        val cmdBtn = dim * CMD_BTN_FRAC
        val keyBtn = dim * KEY_BTN_FRAC
        val cmdIcon = cmdBtn * CMD_ICON_FRAC
        val keyIcon = keyBtn * KEY_ICON_FRAC
        val midGap = dim * MID_GAP_FRAC
        val topGap = dim * TOP_GAP_FRAC
        val rowGap = dim * ROW_GAP_FRAC

        val grid = Column.Builder()
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            // top: unlock / lock
            .addContent(
                Row.Builder()
                    .addContent(commandButton(ID_UNLOCK, ICON_UNLOCK, inState(!st.locked), st.live, cmdBtn, cmdIcon, renderNonce))
                    .addContent(Spacer.Builder().setWidth(dp(topGap)).build())
                    .addContent(commandButton(ID_LOCK, ICON_LOCK, inState(st.locked), st.live, cmdBtn, cmdIcon, renderNonce))
                    .build(),
            )
            .addContent(Spacer.Builder().setHeight(dp(rowGap)).build())
            // middle: frunk open / KEY / frunk close
            .addContent(
                Row.Builder()
                    .setVerticalAlignment(VERTICAL_ALIGN_CENTER)
                    .addContent(commandButton(ID_FRUNK_OPEN, ICON_FRUNK_OPEN, inState(st.frunkOpen), st.live, cmdBtn, cmdIcon, renderNonce))
                    .addContent(Spacer.Builder().setWidth(dp(midGap)).build())
                    .addContent(keyButton(keyArmed, keyBtn, keyIcon))
                    .addContent(Spacer.Builder().setWidth(dp(midGap)).build())
                    .addContent(commandButton(ID_FRUNK_CLOSE, ICON_FRUNK_CLOSE, inState(!st.frunkOpen), st.live, cmdBtn, cmdIcon, renderNonce))
                    .build(),
            )
            .addContent(Spacer.Builder().setHeight(dp(rowGap)).build())
            // bottom: rear closure. R1T tailgate = open only (no tailgate-close command exists);
            // R1S hatch = open + close. liftgateOpen is the rear-closure state for both bodies.
            .addContent(
                Row.Builder()
                    .addContent(commandButton(ID_HATCH_OPEN, ICON_HATCH_OPEN, inState(st.liftgateOpen), st.live, cmdBtn, cmdIcon, renderNonce))
                    .apply {
                        if (!isTruck) {
                            addContent(Spacer.Builder().setWidth(dp(topGap)).build())
                            addContent(commandButton(ID_HATCH_CLOSE, ICON_HATCH_CLOSE, inState(!st.liftgateOpen), st.live, cmdBtn, cmdIcon, renderNonce))
                        }
                    }
                    .build(),
            )
            .build()

        val layout = Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(VERTICAL_ALIGN_CENTER)
            .addContent(grid)
            .build()

        val tile = Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(Timeline.fromLayoutElement(layout))
            .build()
        return immediate(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<Resources> {
        val res = Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .addIdToImageMapping(ICON_KEY, drawable(R.drawable.ic_tile_key))
            .addIdToImageMapping(ICON_UNLOCK, drawable(R.drawable.ic_tile_unlock))
            .addIdToImageMapping(ICON_LOCK, drawable(R.drawable.ic_tile_lock))
            .addIdToImageMapping(ICON_FRUNK_OPEN, drawable(R.drawable.ic_tile_frunk_open))
            .addIdToImageMapping(ICON_FRUNK_CLOSE, drawable(R.drawable.ic_tile_frunk_close))
            .addIdToImageMapping(ICON_HATCH_OPEN, drawable(R.drawable.ic_tile_hatch_open))
            .addIdToImageMapping(ICON_HATCH_CLOSE, drawable(R.drawable.ic_tile_hatch_close))
            .build()
        return immediate(res)
    }

    /**
     * The key control: launches the app **and** activates the mobile key (the launch
     * intent carries [MainActivity.EXTRA_ACTIVATE_KEY], which the app acts on). Gold
     * when the key is already armed.
     */
    private fun keyButton(armed: Boolean, sizeDp: Float, iconDp: Float): Button {
        val launch = Clickable.Builder()
            .setId(ID_KEY)
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MainActivity::class.java.name)
                            .addKeyToExtraMapping(
                                MainActivity.EXTRA_ACTIVATE_KEY,
                                ActionBuilders.AndroidBooleanExtra.Builder().setValue(true).build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .build()
        val bg = if (armed) GOLD else BTN_BG
        val glyph = if (armed) BLACK else WHITE // gold circle → black glyph for contrast
        return Button.Builder(this, launch)
            .setCustomContent(iconElement(ICON_KEY, iconDp, glyph))
            .setSize(dp(sizeDp))
            .setButtonColors(ButtonColors(bg, glyph))
            .build()
    }

    /**
     * A command control: fires its command in-process via LoadAction (no app UI). When [filled]
     * (this button MATCHES the vehicle's current state — e.g. the lock button when locked) it's
     * filled — white if [live], gray if stale — with a dark glyph, like the app's controls.
     */
    private fun commandButton(
        id: String, icon: String, filled: Boolean, live: Boolean, sizeDp: Float, iconDp: Float, nonce: Int,
    ): Button {
        // Stamp this render's nonce onto the LoadAction so a tap echoes it back in currentState —
        // that's what lets onTileRequest distinguish a genuine press from a replayed refresh.
        val requestState = StateBuilders.State.Builder()
            .addKeyToValueMapping(CLICK_NONCE_KEY, DynamicDataValue.fromInt(nonce))
            .build()
        val click = Clickable.Builder()
            .setId(id)
            .setOnClick(ActionBuilders.LoadAction.Builder().setRequestState(requestState).build())
            .build()
        val fill = when {
            !filled -> BTN_BG
            live -> WHITE
            else -> STALE
        }
        val glyph = if (filled) BLACK else WHITE
        return Button.Builder(this, click)
            .setCustomContent(iconElement(icon, iconDp, glyph))
            .setSize(dp(sizeDp))
            .setButtonColors(ButtonColors(fill, glyph))
            .build()
    }

    /**
     * A tinted icon sized explicitly (Material's default tile-button glyph is too small).
     *
     * Uses the string-resource-id model (`Image.Builder().setResourceId(id)` + the id→image map in
     * [onTileResourcesRequest]), which protolayout 1.4 deprecates in favor of `ProtoLayoutScope` +
     * `setImageResource(ImageResource)` (resources collected inline). That model unifies layout and
     * resources, but this `TileService` serves them from two independent callbacks with the instance
     * recycled between — so adopting the scope would mean caching `collectResources()` by version
     * across callbacks. Not worth that risk on a working tile; suppress until we migrate the whole
     * resource flow (or the Material components we build on move off the id model too).
     */
    @Suppress("DEPRECATION")
    private fun iconElement(iconId: String, sizeDp: Float, tintArgb: Int): Image =
        Image.Builder()
            .setResourceId(iconId)
            .setWidth(dp(sizeDp))
            .setHeight(dp(sizeDp))
            .setColorFilter(ColorFilter.Builder().setTint(argb(tintArgb)).build())
            .build()

    private fun dispatch(code: Int, label: String) {
        val enrollment = EnrollmentStore(this).load() ?: run {
            DebugLog.add("tile: $label ignored — not enrolled"); return
        }
        logi("tile: dispatch $label")
        DebugLog.add("tile: $label tapped")
        // If a LIVE session is up, ride it (running counter + presence-gated commands) instead of
        // opening a second GATT connection. But NOT while passive: the PRIMARY session is torn down
        // then, so the bus has no drainer and the command would sit queued until the car returns and
        // fire stale — so fall through to a one-shot connect (matches SetupViewModel.sendCommand).
        if (PresenceService.isRunning && !PresenceService.passive.value) {
            CommandBus.submit(code, label)
            return
        }
        val ctx = applicationContext
        scope.launch {
            ActiveCommandManager(ctx, KeyManager()).sendCommand(enrollment, code, label)
        }
    }

    private fun drawable(resId: Int): ImageResource =
        ImageResource.Builder()
            .setAndroidResourceByResId(
                AndroidImageResourceByResId.Builder().setResourceId(resId).build(),
            )
            .build()

    private fun <T> immediate(value: T): ListenableFuture<T> =
        ResolvableFuture.create<T>().apply { set(value) }

    private companion object {
        // Bumped when the resource (icon) set changes so the system refreshes the tile images.
        // "3": frunk/hatch drawables replaced with the car-silhouette art (0.9.1) — the renderer
        // caches resources by this version, so the swap only shows once the version changes.
        const val RESOURCES_VERSION = "3"
        // Per-render tap nonce carried in each command clickable's LoadAction requestState, read
        // back from currentState to fire each tap exactly once (see onTileRequest). Same instance
        // for write and read so AppDataKey equality matches.
        val CLICK_NONCE_KEY = AppDataKey<DynamicInt32>("wv_tile_click_nonce")
        const val ID_KEY = "key"
        const val ID_UNLOCK = "cmd_unlock"
        const val ID_LOCK = "cmd_lock"
        const val ID_FRUNK_OPEN = "cmd_frunk_open"
        const val ID_FRUNK_CLOSE = "cmd_frunk_close"
        const val ID_HATCH_OPEN = "cmd_hatch_open"
        const val ID_HATCH_CLOSE = "cmd_hatch_close"
        const val ICON_KEY = "ic_key"
        const val ICON_UNLOCK = "ic_unlock"
        const val ICON_LOCK = "ic_lock"
        const val ICON_FRUNK_OPEN = "ic_frunk_open"
        const val ICON_FRUNK_CLOSE = "ic_frunk_close"
        const val ICON_HATCH_OPEN = "ic_hatch_open"
        const val ICON_HATCH_CLOSE = "ic_hatch_close"
        const val GOLD = 0xFFFEDD5C.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BTN_BG = 0xFF1C1C1C.toInt()
        const val STALE = 0xFF9A9A9A.toInt() // app DIM gray — last-known (not live) state
        // Sizing as fractions of the face's min dimension, so the hex fills any watch (fixed dp
        // looked small on larger screens). Tuned so the top/bottom pair — the limiting corners —
        // stay inside the circle; see the geometry note in onTileRequest. The key↔ring gap
        // (MID_GAP_FRAC) is kept comfortable so the ring grows outward, not into the key.
        const val FALLBACK_DIM = 200f // if the device reports no screen size
        const val CMD_BTN_FRAC = 0.27f
        const val KEY_BTN_FRAC = 0.30f
        const val CMD_ICON_FRAC = 0.62f // of the button
        const val KEY_ICON_FRAC = 0.60f
        const val MID_GAP_FRAC = 0.03f
        const val TOP_GAP_FRAC = 0.018f
        const val ROW_GAP_FRAC = 0.012f

        // Process-lifetime scope so a queued command survives the TileService instance
        // being torn down between requests (BLE send takes a few seconds).
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
