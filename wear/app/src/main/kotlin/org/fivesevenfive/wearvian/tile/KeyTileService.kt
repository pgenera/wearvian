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
import org.fivesevenfive.wearvian.service.VehicleStatus
import org.fivesevenfive.wearvian.crypto.KeyManager
import org.fivesevenfive.wearvian.protocol.ActiveCommandFrames.Cmd
import org.fivesevenfive.wearvian.store.EnrollmentStore
import org.fivesevenfive.wearvian.store.SettingsStore
import org.fivesevenfive.wearvian.ui.MainActivity
import org.fivesevenfive.wearvian.util.DebugLog
import org.fivesevenfive.wearvian.util.logi

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
 * The controls carry over the app's vehicle-state treatment: the *actionable* button for each
 * pair (the one whose press would change state) is filled — white when the state is live
 * (a session is confirming it), gray when it's the last-known (stale) state, and plain dark
 * when state is unknown. State comes from the in-process [VehicleStatus] (same process as the
 * presence service / app), so the tile reflects whatever the app would show.
 *
 * Command dispatch reuses [ActiveCommandManager] (the same path the app uses) on a
 * process-lifetime scope so it survives this service instance being recycled between
 * tile requests. See docs/passive-entry-protocol.md.
 */
class KeyTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<Tile> {
        // A LoadAction reloads the tile and reports the tapped element here.
        when (requestParams.currentState.lastClickableId) {
            ID_UNLOCK -> dispatch(Cmd.UNLOCK_ALL, "UNLOCK")
            ID_LOCK -> dispatch(Cmd.LOCK_ALL, "LOCK")
            ID_FRUNK_OPEN -> dispatch(Cmd.OPEN_FRUNK, "OPEN_FRUNK")
            ID_FRUNK_CLOSE -> dispatch(Cmd.CLOSE_FRUNK, "CLOSE_FRUNK")
            ID_HATCH_OPEN -> dispatch(Cmd.OPEN_LIFTGATE, "OPEN_LIFTGATE")
            ID_HATCH_CLOSE -> dispatch(Cmd.CLOSE_LIFTGATE, "CLOSE_LIFTGATE")
        }

        // Gold when the key is armed (active OR passively power-saving), not only while running.
        val keyArmed = SettingsStore(this).keyArmed
        // Vehicle state for the actionable-button fill (same source/semantics as the app).
        val st = VehicleStatus.state.value
        // A button is actionable when pressing it would change the current state. Only meaningful
        // once we have state; the fill is white when live, gray when stale (last-known).
        fun actionable(changes: Boolean) = st.valid && changes

        val grid = Column.Builder()
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            // top: unlock / lock
            .addContent(
                Row.Builder()
                    .addContent(commandButton(ID_UNLOCK, ICON_UNLOCK, actionable(st.locked), st.live))
                    .addContent(Spacer.Builder().setWidth(dp(TOP_GAP)).build())
                    .addContent(commandButton(ID_LOCK, ICON_LOCK, actionable(!st.locked), st.live))
                    .build(),
            )
            .addContent(Spacer.Builder().setHeight(dp(ROW_GAP)).build())
            // middle: frunk open / KEY / frunk close
            .addContent(
                Row.Builder()
                    .setVerticalAlignment(VERTICAL_ALIGN_CENTER)
                    .addContent(commandButton(ID_FRUNK_OPEN, ICON_FRUNK_OPEN, actionable(!st.frunkOpen), st.live))
                    .addContent(Spacer.Builder().setWidth(dp(MID_GAP)).build())
                    .addContent(keyButton(keyArmed))
                    .addContent(Spacer.Builder().setWidth(dp(MID_GAP)).build())
                    .addContent(commandButton(ID_FRUNK_CLOSE, ICON_FRUNK_CLOSE, actionable(st.frunkOpen), st.live))
                    .build(),
            )
            .addContent(Spacer.Builder().setHeight(dp(ROW_GAP)).build())
            // bottom: hatch open / hatch close
            .addContent(
                Row.Builder()
                    .addContent(commandButton(ID_HATCH_OPEN, ICON_HATCH_OPEN, actionable(!st.liftgateOpen), st.live))
                    .addContent(Spacer.Builder().setWidth(dp(TOP_GAP)).build())
                    .addContent(commandButton(ID_HATCH_CLOSE, ICON_HATCH_CLOSE, actionable(st.liftgateOpen), st.live))
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
    private fun keyButton(armed: Boolean): Button {
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
            .setCustomContent(iconElement(ICON_KEY, KEY_ICON_DP, glyph))
            .setSize(dp(KEY_BTN_DP))
            .setButtonColors(ButtonColors(bg, glyph))
            .build()
    }

    /**
     * A command control: fires its command in-process via LoadAction (no app UI). When
     * [actionable] (its press would change the vehicle's current state) it's filled — white if
     * [live], gray if stale — with a dark glyph, exactly like the app's closure buttons.
     */
    private fun commandButton(id: String, icon: String, actionable: Boolean, live: Boolean): Button {
        val click = Clickable.Builder()
            .setId(id)
            .setOnClick(ActionBuilders.LoadAction.Builder().build())
            .build()
        val fill = when {
            !actionable -> BTN_BG
            live -> WHITE
            else -> STALE
        }
        val glyph = if (actionable) BLACK else WHITE
        return Button.Builder(this, click)
            .setCustomContent(iconElement(icon, CMD_ICON_DP, glyph))
            .setSize(dp(CMD_BTN_DP))
            .setButtonColors(ButtonColors(fill, glyph))
            .build()
    }

    /** A tinted icon sized explicitly (Material's default tile-button glyph is too small). */
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
        // If the presence session is up, ride it (running counter + presence-gated
        // commands) instead of opening a second GATT connection to the same device.
        if (PresenceService.isRunning) {
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
        const val RESOURCES_VERSION = "2"
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
        // Button + glyph sizing. Larger ring buttons (like Home Assistant's 7-circle tile),
        // still fitting inside the round face — the top/bottom pair are the limiting corners.
        const val KEY_BTN_DP = 60f
        const val CMD_BTN_DP = 50f
        const val KEY_ICON_DP = 36f
        const val CMD_ICON_DP = 30f
        // Inter-button gaps: tight all round so the six outer buttons pack close to the key on a
        // hex ring (a small top/bottom gap keeps the pairs from touching).
        const val MID_GAP = 4f
        const val TOP_GAP = 10f
        const val ROW_GAP = 2f

        // Process-lifetime scope so a queued command survives the TileService instance
        // being torn down between requests (BLE send takes a few seconds).
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
