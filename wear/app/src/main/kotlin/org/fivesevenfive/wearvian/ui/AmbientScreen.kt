package org.fivesevenfive.wearvian.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import org.fivesevenfive.wearvian.service.PresenceService
import org.fivesevenfive.wearvian.service.PresenceStatus
import org.fivesevenfive.wearvian.service.VehicleStatus
import org.fivesevenfive.wearvian.util.Units
import java.util.Date

private val DIM = Color(0xFF9A9A9A)

/**
 * Custom always-on (ambient) idle screen. Mirrors the interactive KEY page ([ControlScreens]) as
 * closely as makes sense — same column geometry (18dp pad, 10dp spacing), same status label with
 * range/temp flanks, same lock/unlock row and lock-state line, so elements sit where the live UI
 * puts them. Differences for always-on: the round key button is dropped for a large localized clock,
 * and everything is thin OUTLINES with nothing filled in, so far fewer pixels are lit.
 *
 * Burn-in safety: black background, outline glyphs, no filled areas, and the whole layout is nudged
 * a few pixels per ambient update ([tick]). In low-bit ambient the panel can't dim, so secondary
 * detail is drawn pure white instead of gray.
 */
@Composable
fun AmbientScreen(
    burnInProtection: Boolean,
    lowBit: Boolean,
    tick: Int,
) {
    val running by PresenceService.running.collectAsStateWithLifecycle()
    val passive by PresenceService.passive.collectAsStateWithLifecycle()
    val status by VehicleStatus.state.collectAsStateWithLifecycle()
    @Suppress("UNUSED_VARIABLE") // collected so the label recomputes as links come/go
    val connected by PresenceStatus.connected.collectAsStateWithLifecycle()

    // Low-bit panels can't dim, so secondary detail must be pure white too; otherwise the app's gray.
    val dim = if (lowBit) Color.White else DIM

    // Respect the watch's 12/24h setting and locale (java's DateFormat, no seconds). Keyed on [tick].
    val context = LocalContext.current
    val timeText = remember(tick) { DateFormat.getTimeFormat(context).format(Date()) }

    // Nudge the whole layout within a small box each ambient update so no pixel burns in.
    val r = if (burnInProtection) 3 else 0
    val dx = (tick % (r * 2 + 1) - r).dp
    val dy = ((tick / 3) % (r * 2 + 1) - r).dp

    val keyLabel = when {
        !running -> "Key off"
        passive -> "Key passive"
        else -> "Key active"
    }
    val inGear = status.gear != VehicleStatus.Gear.PARK && status.gear != VehicleStatus.Gear.UNKNOWN

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            Modifier.fillMaxSize().offset(dx, dy).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Large clock in the slot the round key button occupies on the live screen.
            Text(timeText, color = Color.White, fontSize = 46.sp, fontWeight = FontWeight.Thin)

            // Key status flanked by range (left) / cabin temp (right) — same geometry as KeyPage.
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        status.rangeKm?.takeIf { status.valid }?.let {
                            FlankStat(Icons.Outlined.Route, Units.range(it), dim)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        status.cabinTempC?.takeIf { status.valid }?.let {
                            FlankStat(Icons.Outlined.Thermostat, Units.temp(it), dim)
                        }
                    }
                }
                Text(keyLabel, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center)
            }

            // Unlock / Lock in the same slots as the live buttons, but OUTLINE only (no fills). The
            // glyph matching the current lock state is lit white; the other stays dim. While in gear
            // both stay dim — the auto-lock makes a lit padlock noise, not signal.
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                OutlineButton(Icons.Outlined.LockOpen, "Unlock", lit = status.valid && !inGear && !status.locked, dim = dim)
                OutlineButton(Icons.Outlined.Lock, "Lock", lit = status.valid && !inGear && status.locked, dim = dim)
            }

            // Lock/drive state line — same glyph+word as KeyPage.
            if (status.valid) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when {
                            inGear -> Icons.Outlined.DirectionsCar
                            status.locked -> Icons.Outlined.Lock
                            else -> Icons.Outlined.LockOpen
                        },
                        null, tint = dim, modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        when {
                            inGear -> when (status.gear) {
                                VehicleStatus.Gear.REVERSE -> "Reverse"
                                VehicleStatus.Gear.NEUTRAL -> "Neutral"
                                else -> "Driving"
                            }
                            status.locked -> "Locked"
                            else -> "Unlocked"
                        },
                        color = dim, fontSize = 11.sp,
                    )
                }
            }

            if (status.valid && (status.anyDoorOpen || status.anyWindowOpen)) {
                Text(
                    listOfNotNull(
                        if (status.anyDoorOpen) "door open" else null,
                        if (status.anyWindowOpen) "window open" else null,
                    ).joinToString(" · "),
                    color = dim, fontSize = 10.sp, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Outline stand-in for the live LabeledIcon: same footprint so the glyph + label land in exactly
 * the same spot across ambient/active. KeyPage's button is a 48dp RoundIcon holding a `48*0.52`
 * glyph; we reproduce that 48dp box and glyph size but skip the filled circle.
 */
@Composable
private fun OutlineButton(icon: ImageVector, label: String, lit: Boolean, dim: Color) {
    val tint = if (lit) Color.White else dim
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(48.dp * 0.52f))
        }
        Spacer(Modifier.height(3.dp))
        Text(label, color = tint, fontSize = 11.sp, textAlign = TextAlign.Center)
    }
}

/** Vertical icon-over-value flank, mirroring KeyPage's FlankStat (outline glyph, no fill). */
@Composable
private fun FlankStat(icon: ImageVector, text: String, tint: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(12.dp))
        Spacer(Modifier.height(2.dp))
        Text(text, color = tint, fontSize = 10.sp, maxLines = 1, softWrap = false)
    }
}
