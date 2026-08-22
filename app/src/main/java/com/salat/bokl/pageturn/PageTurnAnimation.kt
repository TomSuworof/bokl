package com.salat.bokl.pageturn

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

interface PageTurnAnimation {
    /** 0 = idle; ±1 while a turn is in flight. Drives overlay composition + index selection. */
    val turnDirection: State<Int>

    fun startTapTurn(direction: Int, size: Size, onFinished: () -> Unit)
    fun dragStart(
        direction: Int,
        size: Size,
        down: Offset,
        current: Offset
    ): Float // returns drag progress

    fun dragUpdate(size: Size, down: Offset, current: Offset): Float
    fun settleDrag(direction: Int, size: Size, commit: Boolean, onFinished: () -> Unit)

    /** Renders the turning-page overlay; [content] draws the turning page itself. */
    @Composable
    fun TurningPageOverlay(paperColor: Color, content: @Composable () -> Unit)
}

@Composable
fun rememberPageTurnAnimation(): PageTurnAnimation {
    val scope = rememberCoroutineScope()
    return remember { CurlPageTurnAnimation(scope) }
}