package org.fivesevenfive.wearvian.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import org.fivesevenfive.wearvian.service.PresenceService
import org.fivesevenfive.wearvian.service.PresenceStatus
import org.fivesevenfive.wearvian.service.VehicleStatus
import org.fivesevenfive.wearvian.util.Units
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Custom always-on (ambient) idle screen. Replaces the system's blur-with-clock fallback with a
 * low-fidelity echo of the interactive KEY page ([ControlScreens]): the same key glyph, status
 * label, telemetry flanks, and lock row — but drawn as thin OUTLINES with nothing filled in, so
 * far fewer pixels are lit.
 *
 * Burn-in safety: black background, outline glyphs, thin fonts, no filled/gold areas, and the whole
 * layout is nudged a few pixels per ambient update ([tick]) so no pixel stays lit in one spot. In
 * low-bit ambient the panel can't render alpha/anti-aliasing, so we drop to pure white only (no
 * dimmed grays).
 */
@Composable
fun AmbientScreen(
    burnInProtection: Boolean,
    lowBit: Boolean,
    tick: Int,
) {
    val running by PresenceService.running.collectAsStateWithLifecycle()
    val passive by PresenceService.passive.collectAsStateWithLifecycle()
    val connected by PresenceStatus.connected.collectAsStateWithLifecycle()
    val status by VehicleStatus.state.collectAsStateWithLifecycle()

    // Low-bit panels can't dim, so secondary detail must be pure white too; otherwise a muted gray.
    val primary = Color.White
    val secondary = if (lowBit) Color.White else Color(0xFF9E9E9E)

    // Nudge the whole layout within a small box each ambient update so no pixel burns in.
    val range = if (burnInProtection) 3 else 0
    val dx = (tick % (range * 2 + 1) - range).dp
    val dy = ((tick / 3) % (range * 2 + 1) - range).dp

    // Same tri-state as KeyPage: off → not armed; passive → armed but idling; active → armed + up.
    val armed = running
    val keyLabel = when {
        !armed -> "Key off"
        passive -> "Key passive"
        else -> "Key active"
    }
    val keyTint = if (armed) primary else secondary

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().offset(dx, dy).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = LocalTime.now().format(TIME_FMT),
                color = secondary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Light,
            )

            Icon(
                imageVector = Icons.Outlined.VpnKey,
                contentDescription = null,
                tint = keyTint,
                modifier = Modifier.size(44.dp),
            )

            // Key status, flanked by range (left) and cabin temp (right) — mirrors KeyPage.
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        status.rangeKm?.takeIf { status.valid }?.let {
                            FlankStat(Icons.Outlined.Route, Units.range(it), secondary)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        status.cabinTempC?.takeIf { status.valid }?.let {
                            FlankStat(Icons.Outlined.Thermostat, Units.temp(it), secondary)
                        }
                    }
                }
                Text(keyLabel, color = primary, fontSize = 17.sp, fontWeight = FontWeight.Light)
            }

            // Lock/unlock glyphs, OUTLINE only — nothing filled in (the "fewer pixels" ask). The
            // current lock state is spelled out below, colorblind-safe like the interactive screen.
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Icon(Icons.Outlined.LockOpen, null, tint = secondary, modifier = Modifier.size(22.dp))
                Icon(Icons.Outlined.Lock, null, tint = secondary, modifier = Modifier.size(22.dp))
            }

            if (status.valid) {
                val inGear = status.gear != VehicleStatus.Gear.PARK && status.gear != VehicleStatus.Gear.UNKNOWN
                FlankStat(
                    icon = when {
                        inGear -> Icons.Outlined.DirectionsCar
                        status.locked -> Icons.Outlined.Lock
                        else -> Icons.Outlined.LockOpen
                    },
                    text = when {
                        inGear -> when (status.gear) {
                            VehicleStatus.Gear.REVERSE -> "Reverse"
                            VehicleStatus.Gear.NEUTRAL -> "Neutral"
                            else -> "Driving"
                        }
                        status.locked -> "Locked"
                        else -> "Unlocked"
                    },
                    tint = secondary,
                )
            }
        }
    }
}

@Composable
private fun FlankStat(icon: ImageVector, text: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(text = text, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Light)
    }
}

private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm")
