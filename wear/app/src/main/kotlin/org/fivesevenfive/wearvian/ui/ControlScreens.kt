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
import androidx.compose.material.icons.filled.ToggleOff
import androidx.compose.material.icons.filled.ToggleOn
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Text
import kotlinx.coroutines.launch
import org.fivesevenfive.wearvian.protocol.ActiveCommandFrames.Cmd
import org.fivesevenfive.wearvian.service.VehicleStatus

private val GOLD = Color(0xFFFEDD5C)
private val DIM = Color(0xFF9A9A9A)
private val BTN_BG = Color(0xFF1C1C1C)
private val WARN = Color(0xFFFF6B6B)
private const val PAGES = 4

/** Closure-row open/close button diameter — trimmed so the label fits beside it on the round face. */
private val CLOSURE_BTN = 42.dp

/** Fixed width of the closure-row icon+label cluster so the open/close buttons align in columns. */
private val LABEL_W = 78.dp

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
    onProximityWakeChange: (Boolean) -> Unit,
    onStartPassive: () -> Unit,
) {
    val pager = rememberPagerState { PAGES }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var acc by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // Live vehicle state (lock/closures) from the PRIMARY 0x1c stream — drives icon state.
    val status by VehicleStatus.state.collectAsStateWithLifecycle()

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
                    0 -> KeyPage(state, status, onTogglePresence, onCommand)
                    1 -> ClosuresPage(state.inFlight, status, onCommand)
                    2 -> SignalPage(state.inFlight, onCommand)
                    else -> SettingsPage(state.proximityWakeEnabled, state.deviceSecure, onProximityWakeChange, onStartPassive)
                }
            }
        }
        PageDots(pager.currentPage, Modifier.align(Alignment.CenterEnd).padding(end = 3.dp))
    }
}

@Composable
private fun KeyPage(
    state: SetupUiState,
    status: VehicleStatus.State,
    onTogglePresence: (Boolean) -> Unit,
    onCommand: (Int, String) -> Unit,
) {
    val armed = state.keyArmed
    Column(
        Modifier.fillMaxSize().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RoundIcon(
            icon = Icons.Filled.VpnKey,
            desc = if (armed) "Deactivate key" else "Activate key",
            tint = if (armed) GOLD else Color.White,
            diameter = 60.dp,
            onClick = { onTogglePresence(!armed) },
        )
        // Tri-state: off → not armed; active → armed + service running; passive → armed
        // but auto power-save dropped the service (still armed, wakes on approach).
        Label(
            when {
                !armed -> "Key off"
                state.presenceRunning -> "Key active"
                else -> "Key passive"
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            LabeledIcon(Icons.Filled.LockOpen, "Unlock", busy = Cmd.UNLOCK_ALL in state.inFlight) {
                onCommand(Cmd.UNLOCK_ALL, "UNLOCK")
            }
            LabeledIcon(Icons.Filled.Lock, "Lock", busy = Cmd.LOCK_ALL in state.inFlight) {
                onCommand(Cmd.LOCK_ALL, "LOCK")
            }
        }
        // Live lock state from the 0x1c stream, shown colorblind-safe: the padlock GLYPH
        // differs (open vs closed shackle) AND it's spelled out — no reliance on color.
        if (status.valid) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    if (status.locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    null, Modifier.size(14.dp), colorFilter = ColorFilter.tint(Color.White),
                )
                Spacer(Modifier.width(4.dp))
                Text(if (status.locked) "Locked" else "Unlocked", color = Color.White, fontSize = 11.sp)
            }
        }
        // Surface genuinely useful live state when we have it (text, not color).
        if (status.valid && (status.anyDoorOpen || status.anyWindowOpen)) {
            Text(
                listOfNotNull(
                    if (status.anyDoorOpen) "door open" else null,
                    if (status.anyWindowOpen) "window open" else null,
                ).joinToString(" · "),
                color = WARN, fontSize = 10.sp, textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ClosuresPage(inFlight: Set<Int>, status: VehicleStatus.State, onCommand: (Int, String) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Header("Closures")
        // Frunk/hatch/window open-state come from the 0x1c stream. Charge-port door state
        // is NOT in that frame (cloud-only), so it has no live indicator.
        ClosureRow(Icons.Filled.Inventory2, "Frunk", Cmd.OPEN_FRUNK, Cmd.CLOSE_FRUNK,
            "OPEN_FRUNK", "CLOSE_FRUNK", inFlight, onCommand,
            open = status.frunkOpen, stateKnown = status.valid)
        ClosureRow(Icons.Filled.Luggage, "Hatch", Cmd.OPEN_LIFTGATE, Cmd.CLOSE_LIFTGATE,
            "OPEN_LIFTGATE", "CLOSE_LIFTGATE", inFlight, onCommand,
            open = status.liftgateOpen, stateKnown = status.valid)
        // Windows: OPEN_ALL_WINDOWS (0x15) vents/opens all, CLOSE (0x16) closes. Re-enabled
        // now that commands ride the live session (the old one-shot path no-op'd them).
        ClosureRow(Icons.Filled.Window, "Windows", Cmd.OPEN_ALL_WINDOWS, Cmd.CLOSE_ALL_WINDOWS,
            "OPEN_ALL_WINDOWS", "CLOSE_ALL_WINDOWS", inFlight, onCommand,
            open = status.anyWindowOpen, stateKnown = status.valid)
        // Charge-port door state is NOT in the 0x1c frame (cloud-only) — no live indicator.
        ClosureRow(Icons.Filled.Bolt, "Charge", Cmd.OPEN_CHARGE_PORT, Cmd.CLOSE_CHARGE_PORT,
            "OPEN_CHARGE_PORT", "CLOSE_CHARGE_PORT", inFlight, onCommand)
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

@Composable
private fun SettingsPage(
    proximityWakeOn: Boolean,
    deviceSecure: Boolean,
    onChange: (Boolean) -> Unit,
    onStartPassive: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Header("Settings")
        if (!deviceSecure) {
            Text(
                "⚠ No watch lock set — remove-from-wrist protection is OFF",
                color = WARN,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
            )
        }
        Image(
            imageVector = if (proximityWakeOn) Icons.Filled.ToggleOn else Icons.Filled.ToggleOff,
            contentDescription = if (proximityWakeOn) "Auto power-save on" else "Auto power-save off",
            modifier = Modifier.width(64.dp).height(40.dp).clickable { onChange(!proximityWakeOn) },
            colorFilter = ColorFilter.tint(if (proximityWakeOn) GOLD else DIM),
        )
        Label("Auto power-save")
        Text(
            "Start passive mode",
            color = GOLD,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.clickable { onStartPassive() }.padding(top = 4.dp),
        )
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
    open: Boolean = false,
    stateKnown: Boolean = false,
) {
    // Category icon + label, then open/close. The leading cluster has a FIXED width so the
    // open (▲) buttons line up in one vertical column and the close (▼) buttons in another
    // across every row. Current state (when known) lights the ACTIONABLE button — the one whose
    // press would change state: down filled when currently open (you can close it), up filled
    // when currently closed (you can open it). The fill is a luminance cue, so colorblind-safe.
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.width(LABEL_W), verticalAlignment = Alignment.CenterVertically) {
            Image(icon, name, Modifier.size(20.dp), colorFilter = ColorFilter.tint(Color.White))
            Spacer(Modifier.width(5.dp))
            Text(name, color = Color.White, fontSize = 13.sp)
        }
        Spacer(Modifier.width(10.dp))
        RoundIcon(Icons.Filled.KeyboardArrowUp, "Open $name", Color.White, CLOSURE_BTN,
            busy = openCode in inFlight, active = stateKnown && !open) {
            onCommand(openCode, openLabel)
        }
        Spacer(Modifier.width(8.dp))
        RoundIcon(Icons.Filled.KeyboardArrowDown, "Close $name", Color.White, CLOSURE_BTN,
            busy = closeCode in inFlight, active = stateKnown && open) {
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
        RoundIcon(icon, label, tint, 48.dp, busy = busy, onClick = onClick)
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
    active: Boolean = false,
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
    // [active] = this button is the actionable one for the closure's current state: invert to a
    // bright filled circle with a dark glyph. The fill is a luminance/contrast change (not a hue),
    // so it reads regardless of color vision and highlights the press that will change state.
    Box(
        Modifier.size(diameter).clip(CircleShape).background(if (active) Color.White else BTN_BG)
            .clickable(enabled = !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            icon, desc, Modifier.size(diameter * 0.52f), alpha = alpha,
            colorFilter = ColorFilter.tint(if (active) Color.Black else tint),
        )
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
