package org.fivesevenfive.wearvian.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.CurvedLayout
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.curvedText
import kotlinx.coroutines.launch
import org.fivesevenfive.wearvian.BuildConfig
import org.fivesevenfive.wearvian.util.DebugLog

/**
 * Black-field debug console reached by swiping left on the app. Shows the recent
 * BLE message exchange (and its semantic meaning) in small monospace text, newest
 * at the bottom. The build version is curved along the top bezel. The view pins to
 * the newest line (jumps to the bottom on open and follows new lines); the rotary
 * crown still scrolls between updates.
 */
@Composable
fun DebugScreen() {
    val lines by DebugLog.flow.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // Pin to the newest line: jump to the bottom on open and stay there as lines arrive.
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .onRotaryScrollEvent { e -> scope.launch { listState.scrollBy(e.verticalScrollPixels) }; true }
                .focusRequester(focus)
                .focusable()
                .padding(horizontal = 8.dp, vertical = 28.dp),
        ) {
            items(lines) { line ->
                Text(
                    text = line,
                    color = HEADER_GREEN,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    lineHeight = 11.sp,
                )
            }
        }
        // Build version, curved along the top bezel.
        CurvedLayout(modifier = Modifier.fillMaxSize(), anchor = 270f) {
            curvedText(
                text = "wearvian v${BuildConfig.VERSION_NAME}",
                style = CurvedTextStyle(fontSize = 11.sp, color = HEADER_GREEN),
            )
        }
    }
}

private val HEADER_GREEN = Color(0xFF8AE234)
