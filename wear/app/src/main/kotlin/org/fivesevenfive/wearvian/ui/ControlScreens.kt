package org.fivesevenfive.wearvian.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import kotlinx.coroutines.launch
import org.fivesevenfive.wearvian.protocol.ActiveCommandFrames.Cmd

private val GOLD = Color(0xFFFEDD5C)
private val DIM = Color(0xFF9A9A9A)
private val BTN_BG = Color(0xFF1C1C1C)
private const val PAGES = 3

/**
 * The BONDED control surface: a vertical pager of full-screen "cards", navigable by
 * swipe or the rotary crown. Pure-black OLED field, high-contrast white iconography
 * (Material vectors tinted white). Page 0 = key + lock/unlock; page 1 = closures;
 * page 2 = security & lights.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ControlScreens(
    state: SetupUiState,
    onTogglePresence: (Boolean) -> Unit,
    onCommand: (Int, String) -> Unit,
) {
    val pager = rememberPagerState { PAGES }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var acc by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VerticalPager(
            state = pager,
            modifier = Modifier
                .fillMaxSize()
                // Rotary crown pages between screens (accumulate to a detent threshold).
                .onRotaryScrollEvent { e ->
                    acc += e.verticalScrollPixels
                    when {
                        acc > ROTARY_STEP -> {
                            acc = 0f
                            scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(PAGES - 1)) }
                        }
                        acc < -ROTARY_STEP -> {
                            acc = 0f
                            scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) }
                        }
                    }
                    true
                }
                .focusRequester(focus)
                .focusable(),
        ) { page ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (page) {
                    0 -> KeyPage(state, onTogglePresence, onCommand)
                    1 -> ClosuresPage(onCommand)
                    else -> SignalPage(onCommand)
                }
            }
        }
        PageDots(pager.currentPage, Modifier.align(Alignment.CenterEnd).padding(end = 3.dp))
    }
}

@Composable
private fun KeyPage(
    state: SetupUiState,
    onTogglePresence: (Boolean) -> Unit,
    onCommand: (Int, String) -> Unit,
) {
    val active = state.presenceRunning
    Column(
        Modifier.fillMaxSize().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RoundIcon(
            icon = Icons.Filled.VpnKey,
            desc = if (active) "Deactivate key" else "Activate key",
            tint = if (active) GOLD else Color.White,
            diameter = 60.dp,
            onClick = { onTogglePresence(!active) },
        )
        Label(if (active) "Key active" else "Key off")
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            LabeledIcon(Icons.Filled.LockOpen, "Unlock") { onCommand(Cmd.UNLOCK_ALL, "UNLOCK") }
            LabeledIcon(Icons.Filled.Lock, "Lock") { onCommand(Cmd.LOCK_ALL, "LOCK") }
        }
    }
}

@Composable
private fun ClosuresPage(onCommand: (Int, String) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
    ) {
        Header("Closures")
        ClosureRow(Icons.Filled.Inventory2, "Frunk",
            { onCommand(Cmd.OPEN_FRUNK, "OPEN_FRUNK") }, { onCommand(Cmd.CLOSE_FRUNK, "CLOSE_FRUNK") })
        ClosureRow(Icons.Filled.Luggage, "Hatch",
            { onCommand(Cmd.OPEN_LIFTGATE, "OPEN_LIFTGATE") }, { onCommand(Cmd.CLOSE_LIFTGATE, "CLOSE_LIFTGATE") })
        ClosureRow(Icons.Filled.Bolt, "Charge",
            { onCommand(Cmd.OPEN_CHARGE_PORT, "OPEN_CHARGE_PORT") }, { onCommand(Cmd.CLOSE_CHARGE_PORT, "CLOSE_CHARGE_PORT") })
        ClosureRow(Icons.Filled.Window, "Windows",
            { onCommand(Cmd.OPEN_ALL_WINDOWS, "VENT_WINDOWS") }, { onCommand(Cmd.CLOSE_ALL_WINDOWS, "CLOSE_WINDOWS") })
    }
}

@Composable
private fun SignalPage(onCommand: (Int, String) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Header("Security & lights")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LabeledIcon(Icons.Filled.Shield, "Guard on", GOLD) { onCommand(Cmd.ENABLE_GEAR_GUARD, "GEAR_GUARD_ON") }
            LabeledIcon(Icons.Filled.Shield, "Guard off") { onCommand(Cmd.DISABLE_GEAR_GUARD, "GEAR_GUARD_OFF") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LabeledIcon(Icons.Filled.Lightbulb, "Lights") { onCommand(Cmd.FLASH_LIGHTS, "FLASH_LIGHTS") }
            LabeledIcon(Icons.Filled.VolumeUp, "Sound") { onCommand(Cmd.ACTIVATE_SOUND, "ACTIVATE_SOUND") }
        }
    }
}

// ---- building blocks ----

@Composable
private fun ClosureRow(icon: ImageVector, name: String, onOpen: () -> Unit, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(icon, name, Modifier.size(18.dp), colorFilter = ColorFilter.tint(Color.White))
        Spacer(Modifier.width(6.dp))
        Text(name, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        RoundIcon(Icons.Filled.KeyboardArrowUp, "Open $name", Color.White, 34.dp, onOpen)
        Spacer(Modifier.width(6.dp))
        RoundIcon(Icons.Filled.KeyboardArrowDown, "Close $name", Color.White, 34.dp, onClose)
    }
}

@Composable
private fun LabeledIcon(icon: ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RoundIcon(icon, label, tint, 48.dp, onClick)
        Spacer(Modifier.height(3.dp))
        Text(label, color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, desc: String, tint: Color, diameter: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(diameter).clip(CircleShape).background(BTN_BG).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(icon, desc, Modifier.size(diameter * 0.52f), colorFilter = ColorFilter.tint(tint))
    }
}

@Composable
private fun Header(text: String) =
    Text(text, color = DIM, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp), textAlign = TextAlign.Center)

@Composable
private fun Label(text: String) = Text(text, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center)

@Composable
private fun PageDots(current: Int, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(PAGES) { i ->
            Box(Modifier.size(5.dp).clip(CircleShape).background(if (i == current) Color.White else Color(0xFF444444)))
        }
    }
}

private const val ROTARY_STEP = 48f
