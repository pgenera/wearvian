package org.fivesevenfive.wearvian.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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

/** Closure-row open/close button diameter — large touch target (room freed by hiding Windows). */
private val CLOSURE_BTN = 50.dp

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
                    1 -> ClosuresPage(state.inFlight, onCommand)
                    else -> SignalPage(state.inFlight, onCommand)
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
            LabeledIcon(Icons.Filled.LockOpen, "Unlock", busy = Cmd.UNLOCK_ALL in state.inFlight) {
                onCommand(Cmd.UNLOCK_ALL, "UNLOCK")
            }
            LabeledIcon(Icons.Filled.Lock, "Lock", busy = Cmd.LOCK_ALL in state.inFlight) {
                onCommand(Cmd.LOCK_ALL, "LOCK")
            }
        }
    }
}

@Composable
private fun ClosuresPage(inFlight: Set<Int>, onCommand: (Int, String) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Header("Closures")
        ClosureRow(Icons.Filled.Inventory2, "Frunk", Cmd.OPEN_FRUNK, Cmd.CLOSE_FRUNK,
            "OPEN_FRUNK", "CLOSE_FRUNK", inFlight, onCommand)
        ClosureRow(Icons.Filled.Luggage, "Hatch", Cmd.OPEN_LIFTGATE, Cmd.CLOSE_LIFTGATE,
            "OPEN_LIFTGATE", "CLOSE_LIFTGATE", inFlight, onCommand)
        ClosureRow(Icons.Filled.Bolt, "Charge", Cmd.OPEN_CHARGE_PORT, Cmd.CLOSE_CHARGE_PORT,
            "OPEN_CHARGE_PORT", "CLOSE_CHARGE_PORT", inFlight, onCommand)
        // Windows hidden until confirmed working on-vehicle (0x15/0x16 currently no-op).
    }
}

@Composable
private fun SignalPage(inFlight: Set<Int>, onCommand: (Int, String) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Header("Security & lights")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LabeledIcon(Icons.Filled.Shield, "Guard on", GOLD, Cmd.ENABLE_GEAR_GUARD in inFlight) {
                onCommand(Cmd.ENABLE_GEAR_GUARD, "GEAR_GUARD_ON")
            }
            LabeledIcon(Icons.Filled.Shield, "Guard off", busy = Cmd.DISABLE_GEAR_GUARD in inFlight) {
                onCommand(Cmd.DISABLE_GEAR_GUARD, "GEAR_GUARD_OFF")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LabeledIcon(Icons.Filled.Lightbulb, "Lights", busy = Cmd.FLASH_LIGHTS in inFlight) {
                onCommand(Cmd.FLASH_LIGHTS, "FLASH_LIGHTS")
            }
            LabeledIcon(Icons.Filled.VolumeUp, "Sound", busy = Cmd.ACTIVATE_SOUND in inFlight) {
                onCommand(Cmd.ACTIVATE_SOUND, "ACTIVATE_SOUND")
            }
        }
    }
}

// ---- building blocks ----

@Composable
private fun ClosureRow(
    icon: ImageVector,
    name: String,
    openCode: Int,
    closeCode: Int,
    openLabel: String,
    closeLabel: String,
    inFlight: Set<Int>,
    onCommand: (Int, String) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(icon, name, Modifier.size(20.dp), colorFilter = ColorFilter.tint(Color.White))
        // Push the label + buttons together to the right so the name sits next to them.
        Spacer(Modifier.weight(1f))
        Text(name, color = Color.White, fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        RoundIcon(Icons.Filled.KeyboardArrowUp, "Open $name", Color.White, CLOSURE_BTN, openCode in inFlight) {
            onCommand(openCode, openLabel)
        }
        Spacer(Modifier.width(6.dp))
        RoundIcon(Icons.Filled.KeyboardArrowDown, "Close $name", Color.White, CLOSURE_BTN, closeCode in inFlight) {
            onCommand(closeCode, closeLabel)
        }
    }
}

@Composable
private fun LabeledIcon(
    icon: ImageVector,
    label: String,
    tint: Color = Color.White,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RoundIcon(icon, label, tint, 48.dp, busy, onClick)
        Spacer(Modifier.height(3.dp))
        Text(label, color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center)
    }
}

/**
 * A circular icon button. When [busy] (its command is in flight) it throbs — the icon
 * pulses its alpha — and taps are disabled until the command completes or times out,
 * then it returns to the steady idle graphic.
 */
@Composable
private fun RoundIcon(
    icon: ImageVector,
    desc: String,
    tint: Color,
    diameter: androidx.compose.ui.unit.Dp,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    val alpha = if (busy) {
        val transition = rememberInfiniteTransition(label = "throb")
        val a by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(tween(550), RepeatMode.Reverse),
            label = "throbAlpha",
        )
        a
    } else {
        1f
    }
    Box(
        Modifier.size(diameter).clip(CircleShape).background(BTN_BG)
            .clickable(enabled = !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(icon, desc, Modifier.size(diameter * 0.52f), alpha = alpha, colorFilter = ColorFilter.tint(tint))
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

// Rotary crown travel required to flip one page — larger = less twitchy paging.
private const val ROTARY_STEP = 110f
