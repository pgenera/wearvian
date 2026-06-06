package org.fivesevenfive.wearvian.tile

import androidx.concurrent.futures.ResolvableFuture
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders.ColorFilter
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER
import androidx.wear.protolayout.LayoutElementBuilders.Image
import androidx.wear.protolayout.LayoutElementBuilders.Row
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ResourceBuilders.AndroidImageResourceByResId
import androidx.wear.protolayout.ResourceBuilders.ImageResource
import androidx.wear.protolayout.ResourceBuilders.Resources
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.protolayout.material.Button
import androidx.wear.protolayout.material.ButtonColors
import androidx.wear.protolayout.material.ButtonDefaults
import androidx.wear.protolayout.material.layouts.PrimaryLayout
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
import org.fivesevenfive.wearvian.crypto.KeyManager
import org.fivesevenfive.wearvian.protocol.ActiveCommandFrames.Cmd
import org.fivesevenfive.wearvian.store.EnrollmentStore
import org.fivesevenfive.wearvian.store.SettingsStore
import org.fivesevenfive.wearvian.ui.MainActivity
import org.fivesevenfive.wearvian.util.DebugLog
import org.fivesevenfive.wearvian.util.logi

/**
 * A Wear Tile mirroring page 0 of the app (key + unlock + lock). Tapping a control:
 *  - **Key** launches the full app (to activate/deactivate the mobile key).
 *  - **Unlock / Lock** fire the BLE command in-process via a `LoadAction` — the tile
 *    reloads, the command runs in the background, and the app UI never opens.
 *
 * Lock/unlock dispatch reuses [ActiveCommandManager] (the same path the app uses) on a
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
        }

        // Gold when the key is armed (active OR passively power-saving), not only while
        // the service is currently running.
        val keyActive = SettingsStore(this).keyArmed
        val layout = PrimaryLayout.Builder(requestParams.deviceConfiguration)
            .setContent(
                Column.Builder()
                    .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
                    .addContent(keyButton(keyActive))
                    .addContent(Spacer.Builder().setHeight(dp(10f)).build())
                    .addContent(
                        Row.Builder()
                            .addContent(commandButton(ID_UNLOCK, ICON_UNLOCK))
                            .addContent(Spacer.Builder().setWidth(dp(12f)).build())
                            .addContent(commandButton(ID_LOCK, ICON_LOCK))
                            .build(),
                    )
                    .build(),
            )
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
            .build()
        return immediate(res)
    }

    /**
     * The key control: launches the app **and** activates the mobile key (the launch
     * intent carries [MainActivity.EXTRA_ACTIVATE_KEY], which the app acts on). Gold
     * when the key is already active.
     */
    private fun keyButton(active: Boolean): Button {
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
        val bg = if (active) GOLD else BTN_BG
        val glyph = if (active) BLACK else WHITE // gold circle → black glyph for contrast
        return Button.Builder(this, launch)
            .setCustomContent(iconElement(ICON_KEY, KEY_ICON_DP, glyph))
            .setSize(ButtonDefaults.LARGE_SIZE)
            .setButtonColors(ButtonColors(bg, glyph))
            .build()
    }

    /** A lock/unlock control: fires the command in-process via LoadAction (no app UI). */
    private fun commandButton(id: String, icon: String): Button {
        val click = Clickable.Builder()
            .setId(id)
            .setOnClick(ActionBuilders.LoadAction.Builder().build())
            .build()
        return Button.Builder(this, click)
            .setCustomContent(iconElement(icon, CMD_ICON_DP, WHITE))
            .setSize(ButtonDefaults.LARGE_SIZE)
            .setButtonColors(ButtonColors(BTN_BG, WHITE))
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
        const val RESOURCES_VERSION = "1"
        const val ID_KEY = "key"
        const val ID_UNLOCK = "cmd_unlock"
        const val ID_LOCK = "cmd_lock"
        const val ICON_KEY = "ic_key"
        const val ICON_UNLOCK = "ic_unlock"
        const val ICON_LOCK = "ic_lock"
        const val GOLD = 0xFFFEDD5C.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BTN_BG = 0xFF1C1C1C.toInt()
        // Explicit glyph sizes — bigger than Material's default tile-button icon.
        const val KEY_ICON_DP = 38f
        const val CMD_ICON_DP = 34f

        // Process-lifetime scope so a queued command survives the TileService instance
        // being torn down between requests (BLE send takes a few seconds).
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
