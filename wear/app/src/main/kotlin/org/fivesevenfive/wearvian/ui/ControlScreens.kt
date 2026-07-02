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
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.ToggleOff
import androidx.compose.material.icons.filled.ToggleOn
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
import androidx.wear.compose.material.TimeText
import kotlinx.coroutines.launch
import org.fivesevenfive.wearvian.BuildConfig
import org.fivesevenfive.wearvian.protocol.ActiveCommandFrames.Cmd
import org.fivesevenfive.wearvian.service.VehicleStatus
import org.fivesevenfive.wearvian.util.Units

private val GOLD = Color(0xFFFEDD5C)
private val DIM = Color(0xFF9A9A9A)
private val BTN_BG = Color(0xFF1C1C1C)
private val WARN = Color(0xFFFF6B6B)

/** Control-surface pages, in order. SETTINGS is development-only; everything else ships.
 *  Climate lives on the CHARGE card; ALARM is the last shipping page. */
private enum class Page { KEY, CLOSURES, CHARGE, ALARM, SETTINGS }

/** Closure-row open/close button diameter — trimmed so the label fits beside it on the round face. */
private val CLOSURE_BTN = 42.dp

/** Fixed width of the closure-row icon+label cluster so the open/close buttons align in columns. */
private val LABEL_W = 78.dp

/**
 * The BONDED control surface: a vertical pager of full-screen "cards", navigable by
 * swipe or the rotary crown. Pure-black OLED field, high-contrast white iconography
 * (Material vectors tinted white). Page 0 = key + lock/unlock; page 1 = closures;
 * page 2 = charge/range + climate; page 3 = alarm; page 4 = settings (debug).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ControlScreens(
    state: SetupUiState,
    onTogglePresence: (Boolean) -> Unit,
    onCommand: (Int, String) -> Unit,
    onStartPassive: () -> Unit,
    onForceR1tChange: (Boolean) -> Unit,
    onForceWatchChange: (Boolean) -> Unit,
) {
    // Production shows the working cards; Settings stays debug-only (auto power-save defaults on,
    // so there's nothing to toggle in production). BuildConfig.PRODUCTION is constant.
    val pages = remember {
        buildList {
            add(Page.KEY); add(Page.CLOSURES); add(Page.CHARGE); add(Page.ALARM)
            if (!BuildConfig.PRODUCTION) add(Page.SETTINGS)
        }
    }
    val pager = rememberPagerState { pages.size }
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
                            scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(pages.size - 1)) }
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
        ) { index ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (pages[index]) {
                    Page.KEY -> KeyPage(state, status, onTogglePresence, onCommand)
                    Page.CLOSURES -> ClosuresPage(state.inFlight, status, state.isTruck, onCommand)
                    Page.CHARGE -> ChargeStatusPage(state.inFlight, status, onCommand)
                    Page.ALARM -> AlarmPage(state.inFlight, onCommand)
                    Page.SETTINGS -> SettingsPage(state, onStartPassive, onForceR1tChange, onForceWatchChange)
                }
            }
        }
        // Curved hours:minutes along the top of the home screen, like Wear fitness apps. The
        // default time source follows the watch's 12h/24h system setting. Home page only.
        if (pages[pager.currentPage] == Page.KEY) TimeText()
        PageDots(pager.currentPage, pages.size, Modifier.align(Alignment.CenterEnd).padding(end = 3.dp))
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
        // Key status text, flanked by small telemetry: estimated range on the left, cabin temp on
        // the right. The flanks live in the OUTER thirds of three equal-weight cells (empty middle),
        // so each sits at a FIXED sixth-point — out near the bezel, ~a third closer to the edge than
        // the half-cell quarter-points — and does NOT shift when the status text changes width; the
        // status text is overlaid, centred on top and free to be full width. Nothing shows on a side
        // we have no state for (localized °F/°C, mi/km; dimmed gray when stale).
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    status.rangeKm?.takeIf { status.valid }?.let { km ->
                        FlankStat(Icons.Filled.Route, Units.range(km), status.live)
                    }
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    status.cabinTempC?.takeIf { status.valid }?.let { c ->
                        FlankStat(Icons.Filled.Thermostat, Units.temp(c), status.live)
                    }
                }
            }
            // Tri-state: off → not armed; passive → armed but idling (service alive, BLE down,
            // watching for the car's approach); active → armed + sessions up.
            Label(
                when {
                    !armed -> "Key off"
                    state.presencePassive -> "Key passive"
                    state.presenceRunning -> "Key active"
                    else -> "Key passive"
                },
            )
        }
        // Don't allow unlocking a moving vehicle: while in gear the Unlock button is disabled.
        val driving = status.gear != VehicleStatus.Gear.PARK && status.gear != VehicleStatus.Gear.UNKNOWN
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            // Lit button MATCHES the current lock state (like Rivian's app): Unlock fills when
            // unlocked, Lock fills when locked — white if live, gray (DIM) if stale/last-known.
            LabeledIcon(
                Icons.Filled.LockOpen, "Unlock", busy = Cmd.UNLOCK_ALL in state.inFlight,
                active = status.valid && !status.locked,
                activeFill = if (status.live) Color.White else DIM,
                enabled = !driving,
            ) {
                onCommand(Cmd.UNLOCK_ALL, "UNLOCK")
            }
            LabeledIcon(
                Icons.Filled.Lock, "Lock", busy = Cmd.LOCK_ALL in state.inFlight,
                active = status.valid && status.locked,
                activeFill = if (status.live) Color.White else DIM,
            ) {
                onCommand(Cmd.LOCK_ALL, "LOCK")
            }
        }
        // Drive/lock state from the 0x1c stream, shown colorblind-safe: the GLYPH differs AND it's
        // spelled out — no reliance on color. While in gear we show the gear (the car auto-locks
        // anyway, so lock matters less mid-drive); parked, the padlock GLYPH + word. A stale
        // (persisted, not currently confirmed) state renders gray instead of white.
        if (status.valid) {
            val stateColor = if (status.live) Color.White else DIM
            val inGear = status.gear != VehicleStatus.Gear.PARK && status.gear != VehicleStatus.Gear.UNKNOWN
            // Parked + actively charging: show "Charging" in place of the lock word (can't charge in gear).
            val charging = !inGear && status.chargeState == VehicleStatus.ChargeState.CHARGING
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    when {
                        inGear -> Icons.Filled.DirectionsCar
                        charging -> Icons.Filled.Bolt
                        status.locked -> Icons.Filled.Lock
                        else -> Icons.Filled.LockOpen
                    },
                    null, Modifier.size(14.dp), colorFilter = ColorFilter.tint(stateColor),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    when {
                        inGear -> when (status.gear) {
                            VehicleStatus.Gear.REVERSE -> "Reverse"
                            VehicleStatus.Gear.NEUTRAL -> "Neutral"
                            else -> "Driving"
                        }
                        charging -> "Charging"
                        status.locked -> "Locked"
                        else -> "Unlocked"
                    },
                    color = stateColor, fontSize = 11.sp,
                )
            }
        }
        // Surface genuinely useful state when we have it (text, not color). Live = red warning;
        // stale = gray (last known, not confirmed), matching the dimmed affordances elsewhere.
        if (status.valid && (status.anyDoorOpen || status.anyWindowOpen)) {
            Text(
                listOfNotNull(
                    if (status.anyDoorOpen) "door open" else null,
                    if (status.anyWindowOpen) "window open" else null,
                ).joinToString(" · "),
                color = if (status.live) WARN else DIM, fontSize = 10.sp, textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ClosuresPage(
    inFlight: Set<Int>,
    status: VehicleStatus.State,
    isTruck: Boolean,
    onCommand: (Int, String) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Header("Closures")
        // Frunk/hatch/window open-state come from the 0x1c stream. Charge-port door state
        // is NOT in that frame (cloud-only), so it has no live indicator. A stale (persisted)
        // state still lights the state-matching button, just dimmed (gray, via [stale]).
        val stale = status.valid && !status.live
        ClosureRow(Icons.Filled.Inventory2, "Frunk", Cmd.OPEN_FRUNK, Cmd.CLOSE_FRUNK,
            "OPEN_FRUNK", "CLOSE_FRUNK", inFlight, onCommand,
            open = status.frunkOpen, stateKnown = status.valid, stale = stale)
        // Rear closure: R1S liftgate (open + close) vs R1T tailgate (open only — there is no
        // tailgate-close command). The 0x1c rear-closure bit is model-agnostic, so liftgateOpen
        // reflects the tailgate state on an R1T too. See VehicleModel / ActiveCommandFrames.Cmd.
        if (isTruck) {
            ClosureRow(Icons.Filled.Luggage, "Tailgate", Cmd.OPEN_TAILGATE, null,
                "OPEN_TAILGATE", null, inFlight, onCommand,
                open = status.liftgateOpen, stateKnown = status.valid, stale = stale)
        } else {
            ClosureRow(Icons.Filled.Luggage, "Hatch", Cmd.OPEN_LIFTGATE, Cmd.CLOSE_LIFTGATE,
                "OPEN_LIFTGATE", "CLOSE_LIFTGATE", inFlight, onCommand,
                open = status.liftgateOpen, stateKnown = status.valid, stale = stale)
        }
        // Windows: OPEN_ALL_WINDOWS (0x15) vents/opens all, CLOSE (0x16) closes. Re-enabled
        // now that commands ride the live session (the old one-shot path no-op'd them).
        ClosureRow(Icons.Filled.Window, "Windows", Cmd.OPEN_ALL_WINDOWS, Cmd.CLOSE_ALL_WINDOWS,
            "OPEN_ALL_WINDOWS", "CLOSE_ALL_WINDOWS", inFlight, onCommand,
            open = status.anyWindowOpen, stateKnown = status.valid, stale = stale, upOpens = false)
        // Charge-port door state is NOT in the 0x1c frame (cloud-only) — no live indicator.
        ClosureRow(Icons.Filled.Bolt, "Charge", Cmd.OPEN_CHARGE_PORT, Cmd.CLOSE_CHARGE_PORT,
            "OPEN_CHARGE_PORT", "CLOSE_CHARGE_PORT", inFlight, onCommand)
    }
}

/** Round charge-ETA seconds to a compact "Xh Ym" / "Ym" string (the field's ~16 s/count → ~1-min res). */
private fun formatEta(seconds: Int): String {
    val totalMin = (seconds + 30) / 60
    return if (totalMin >= 60) "${totalMin / 60}h ${totalMin % 60}m" else "${totalMin}m"
}

/**
 * Charge + climate. Top half is read-only from the 0x1c stream — SoC, charge state, and the
 * time-to-limit ETA when plugged. Bottom half is cabin preconditioning: fire-and-forget Start/Off
 * (the frame has no climate-active bit, so no toggle), with the live cabin temperature as the
 * footer stat beside range. Climate codes confirmed against the decompiled command registry:
 * CABIN_PRECONDITION_ENABLE 0x19 / DISABLE 0x1a. Stale (persisted) readings dim to gray.
 */
@Composable
private fun ChargeStatusPage(inFlight: Set<Int>, status: VehicleStatus.State, onCommand: (Int, String) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Header("Charge & climate")
        val live = status.live
        val primary = if (live) Color.White else DIM
        val charging = status.chargeState == VehicleStatus.ChargeState.CHARGING
        if (status.valid && status.socPercent != null) {
            // SoC hero — battery icon bolts + golds while charging.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    if (charging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryFull,
                    null, Modifier.size(24.dp),
                    colorFilter = ColorFilter.tint(if (charging && live) GOLD else primary),
                )
                Spacer(Modifier.width(6.dp))
                Text("${status.socPercent}%", color = primary, fontSize = 28.sp)
            }
            // Charge state. Gold while charging, red on a fault, dim otherwise. The frame carries
            // time-to-limit (not power — the BLE frame has no charge rate), so the ETA is the
            // headline: "Charging · 5h 18m left". "left" disambiguates remaining from elapsed.
            val (line, color) = when (status.chargeState) {
                VehicleStatus.ChargeState.CHARGING -> {
                    val eta = status.chargeEtaSeconds?.takeIf { it > 0 }?.let { " · ${formatEta(it)} left" } ?: ""
                    "Charging$eta" to (if (live) GOLD else DIM)
                }
                VehicleStatus.ChargeState.PLUGGED_IDLE -> "Plugged in" to primary
                VehicleStatus.ChargeState.STARTING -> "Starting…" to primary
                VehicleStatus.ChargeState.FAULT -> "Check charger" to (if (live) WARN else DIM)
                VehicleStatus.ChargeState.UNPLUGGED -> "Unplugged" to DIM
                VehicleStatus.ChargeState.UNKNOWN -> null to primary
            }
            line?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (charging) {
                        Image(Icons.Filled.Bolt, null, Modifier.size(12.dp), colorFilter = ColorFilter.tint(color))
                        Spacer(Modifier.width(2.dp))
                    }
                    Text(it, color = color, fontSize = 12.sp, textAlign = TextAlign.Center)
                }
            }
        } else {
            Text("No vehicle data", color = DIM, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
        // Climate: cabin preconditioning. status[4] tells us if it's running, so the Climate
        // button fills (gold live / gray stale) while on — the same state-matching highlight the
        // lock/closures now use — and a status line confirms it in words. Off stops it.
        Spacer(Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            LabeledIcon(
                Icons.Filled.PlayArrow, "Climate", GOLD,
                busy = Cmd.CABIN_PRECONDITION_ENABLE in inFlight,
                active = status.valid && status.climateOn,
                activeFill = if (live) GOLD else DIM,
            ) { onCommand(Cmd.CABIN_PRECONDITION_ENABLE, "CLIMATE_ON") }
            LabeledIcon(Icons.Filled.Stop, "Off", busy = Cmd.CABIN_PRECONDITION_DISABLE in inFlight) {
                onCommand(Cmd.CABIN_PRECONDITION_DISABLE, "CLIMATE_OFF")
            }
        }
        if (status.valid && status.climateOn) {
            Text("Preconditioning", color = if (live) GOLD else DIM, fontSize = 10.sp, textAlign = TextAlign.Center)
        }
        // Footer mini-stats: estimated range + live cabin temp (each hidden when unknown).
        if (status.valid && (status.rangeKm != null || status.cabinTempC != null)) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                status.rangeKm?.let { FlankStat(Icons.Filled.Route, Units.range(it), live) }
                status.cabinTempC?.let { FlankStat(Icons.Filled.Thermostat, Units.temp(it), live) }
            }
        }
    }
}

/**
 * Panic alarm — sound or silence the vehicle's anti-theft alarm (horn + lights). Two deliberate
 * buttons (no toggle: there's no alarm-state bit in the frame, and you have to swipe here to reach
 * it, so it won't fire by accident). Codes confirmed against the decompiled registry:
 * PANIC_ON 0x07 / PANIC_OFF 0x34.
 */
@Composable
private fun AlarmPage(inFlight: Set<Int>, onCommand: (Int, String) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Header("Alarm")
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            LabeledIcon(Icons.Filled.Campaign, "Sound", WARN, Cmd.PANIC_ON in inFlight) {
                onCommand(Cmd.PANIC_ON, "PANIC_ON")
            }
            LabeledIcon(Icons.Filled.NotificationsOff, "Silence", busy = Cmd.PANIC_OFF in inFlight) {
                onCommand(Cmd.PANIC_OFF, "PANIC_OFF")
            }
        }
        Text("Vehicle alarm — horn & lights", color = DIM, fontSize = 10.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SettingsPage(
    state: SetupUiState,
    onStartPassive: () -> Unit,
    onForceR1tChange: (Boolean) -> Unit,
    onForceWatchChange: (Boolean) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Header("Settings")
        if (!state.deviceSecure) {
            Text(
                "⚠ No watch lock set — remove-from-wrist protection is OFF",
                color = WARN,
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
            )
        }
        // Debug: force the R1T (tailgate) UI on a non-R1T vehicle, to test the truck layout.
        val r1t = state.forceR1t
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                imageVector = if (r1t) Icons.Filled.ToggleOn else Icons.Filled.ToggleOff,
                contentDescription = if (r1t) "Force R1T on" else "Force R1T off",
                modifier = Modifier.width(64.dp).height(40.dp).clickable { onForceR1tChange(!r1t) },
                colorFilter = ColorFilter.tint(if (r1t) GOLD else DIM),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Force R1T",
                color = Color.White,
                fontSize = 13.sp,
                maxLines = 1,
                softWrap = false,
            )
        }
        // Debug: force WATCH-key behavior (burst presence, no passive lock/unlock) regardless of the
        // enrolled device type. TRANSIENT — never persisted (DebugOverrides); gone on app restart.
        val waak = state.forceWatch
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                imageVector = if (waak) Icons.Filled.ToggleOn else Icons.Filled.ToggleOff,
                contentDescription = if (waak) "Force WaaK on" else "Force WaaK off",
                modifier = Modifier.width(64.dp).height(40.dp).clickable { onForceWatchChange(!waak) },
                colorFilter = ColorFilter.tint(if (waak) GOLD else DIM),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Force WaaK",
                color = Color.White,
                fontSize = 13.sp,
                maxLines = 1,
                softWrap = false,
            )
        }
        Text(
            "Start passive mode",
            color = GOLD,
            fontSize = 12.sp,
            maxLines = 1,
            softWrap = false,
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
    closeCode: Int?,
    openLabel: String,
    closeLabel: String?,
    inFlight: Set<Int>,
    onCommand: (Int, String) -> Unit,
    open: Boolean = false,
    stateKnown: Boolean = false,
    stale: Boolean = false,
    upOpens: Boolean = true,
) {
    // Category icon + label, then open/close. The leading cluster has a FIXED width so the
    // open (▲) buttons line up in one vertical column and the close (▼) buttons in another
    // across every row. Current state (when known) lights the button MATCHING that state (like
    // Rivian's app) — the OPEN button when currently open, the CLOSE button when currently closed
    // — NOT the press target. The fill is a luminance cue, so colorblind-safe.
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
        // One arrow button, by the role it fires (open vs close). The lit button is the one whose
        // ROLE matches the current state: `open == opens` is true exactly for the open button when
        // open and the close button when closed. Windows invert via [upOpens], so it's the role —
        // not the physical arrow — that's matched (down=open lights when a window is open).
        @Composable
        fun arrow(glyph: ImageVector, opens: Boolean) {
            val code = if (opens) openCode else closeCode
            val label = if (opens) openLabel else closeLabel
            // A role with no command (e.g. an R1T tailgate has no close) renders an empty slot of
            // the same size, so the open (▲) buttons stay column-aligned with the other rows.
            if (code == null || label == null) {
                Spacer(Modifier.size(CLOSURE_BTN))
                return
            }
            RoundIcon(glyph, if (opens) "Open $name" else "Close $name", Color.White, CLOSURE_BTN,
                busy = code in inFlight, active = stateKnown && open == opens,
                activeFill = if (stale) DIM else Color.White) {
                onCommand(code, label)
            }
        }
        // Normally up=open / down=close; windows are inverted ([upOpens]=false) because raising a
        // window closes it — so up=close, down=open.
        Spacer(Modifier.width(10.dp))
        arrow(Icons.Filled.KeyboardArrowUp, opens = upOpens)
        Spacer(Modifier.width(8.dp))
        arrow(Icons.Filled.KeyboardArrowDown, opens = !upOpens)
    }
}

/** A tiny stacked icon-over-value stat used to flank the key (range, cabin temp). The label
 *  text whites when [live], grays when stale; the glyph stays dim so it reads as a quiet caption. */
@Composable
private fun FlankStat(icon: ImageVector, text: String, live: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(icon, null, Modifier.size(12.dp), colorFilter = ColorFilter.tint(DIM))
        Spacer(Modifier.height(2.dp))
        Text(text, color = if (live) Color.White else DIM, fontSize = 10.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun LabeledIcon(
    icon: ImageVector,
    label: String,
    tint: Color = Color.White,
    busy: Boolean = false,
    active: Boolean = false,
    activeFill: Color = Color.White,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RoundIcon(icon, label, tint, 48.dp, busy = busy, active = active, activeFill = activeFill, enabled = enabled, onClick = onClick)
        Spacer(Modifier.height(3.dp))
        Text(label, color = if (enabled) Color.White else DIM, fontSize = 11.sp, textAlign = TextAlign.Center)
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
    /** Fill used when [active]; white for live state, gray for a stale (persisted) one. */
    activeFill: Color = Color.White,
    /** When false the button is non-tappable and dimmed (e.g. Unlock while the car is in gear). */
    enabled: Boolean = true,
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
    // [active] = this button MATCHES the vehicle's current state (like Rivian's app: the lock
    // button when locked): invert to a filled circle with a dark glyph. The fill is a luminance/
    // contrast change (not a hue), so it reads regardless of color vision. A stale (persisted)
    // state fills gray instead of white, so it reads as "last known, not confirmed".
    Box(
        Modifier.size(diameter).clip(CircleShape).background(if (active) activeFill else BTN_BG)
            .clickable(enabled = !busy && enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            icon, desc, Modifier.size(diameter * 0.52f), alpha = if (enabled) alpha else 0.3f,
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
private fun PageDots(current: Int, count: Int, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(count) { i ->
            Box(Modifier.size(5.dp).clip(CircleShape).background(if (i == current) Color.White else Color(0xFF444444)))
        }
    }
}

// Rotary crown travel required to flip one page — larger = less twitchy paging.
private const val ROTARY_STEP = 165f
