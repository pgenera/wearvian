package org.fivesevenfive.wearvian.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Text
import org.fivesevenfive.wearvian.util.DebugLog

/**
 * Black-field debug console reached by swiping left on the app. Shows the recent
 * BLE message exchange (and its semantic meaning) in small monospace text, newest
 * at the bottom, auto-scrolling.
 */
@Composable
fun DebugScreen() {
    val lines by DebugLog.flow.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 8.dp, vertical = 24.dp),
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
}

private val HEADER_GREEN = Color(0xFF8AE234)
