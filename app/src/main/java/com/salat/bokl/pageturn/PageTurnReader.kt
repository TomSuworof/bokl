package com.salat.bokl.pageturn

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max

internal val PageSidePadding = 20.dp
internal val PageTopPadding = 16.dp
internal val PageFooterHeight = 48.dp
internal val PageNumberBottomPadding = 14.dp

private const val TapLongPressMillis = 400L
private const val FlingVelocityThreshold = 700f

@Composable
fun PageTurnReader(
    pages: List<AnnotatedString>,
    coverImagePath: String?,
    textStyle: TextStyle,
    paperColor: Color,
    initialPage: Int,
    paginationComplete: Boolean,
    onPageChanged: (page: Int, totalPages: Int) -> Unit,
    modifier: Modifier = Modifier,
    animation: PageTurnAnimation = rememberPageTurnAnimation(),
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp
) {
    val coverOffset = if (coverImagePath != null) 1 else 0
    val totalPages = pages.size + coverOffset
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var turnStartPage by remember { mutableIntStateOf(0) }
    var currentPage by remember { mutableIntStateOf(0) }
    var initialized by remember { mutableStateOf(false) }

    fun currentSize(): Size =
        Size(max(boxSize.width, 1).toFloat(), max(boxSize.height, 1).toFloat())

    val latestTotalPages by rememberUpdatedState(totalPages)

    LaunchedEffect(totalPages, paginationComplete) {
        if (paginationComplete && totalPages > coverOffset) {
            if (!initialized) {
                currentPage = initialPage.coerceIn(0, totalPages - 1)
                initialized = true
            } else {
                currentPage = currentPage.coerceIn(0, totalPages - 1)
            }
        }
    }

    LaunchedEffect(currentPage, totalPages, initialized, paginationComplete) {
        if (initialized && paginationComplete && totalPages > 0) {
            onPageChanged(currentPage, totalPages)
        }
    }

    fun commitPage(direction: Int) {
        val target = (currentPage + direction).coerceIn(0, latestTotalPages - 1)
        if (target != currentPage) {
            currentPage = target
        }
    }

    fun startTurn(direction: Int) {
        val target = currentPage + direction
        if (target !in 0..<latestTotalPages) return
        turnStartPage = currentPage
        commitPage(direction)
        animation.startTapTurn(direction, currentSize()) {}
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dragStarted = false
                    var direction = 0
                    var cumulative = Offset.Zero
                    var dragValue = 0f
                    var lastX = down.position.x
                    var lastTime = down.uptimeMillis
                    var velX = 0f
                    var upPos: Offset? = null
                    var upConsumed = false
                    var canceled = false
                    var abandoned = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.changedToUpIgnoreConsumed()) {
                            upPos = change.position
                            upConsumed = change.isConsumed
                            break
                        }
                        if (event.changes.size > 1) {
                            canceled = true
                            break
                        }
                        if (change.isConsumed) {
                            abandoned = true
                            break
                        }

                        val delta = change.positionChange()
                        val now = change.uptimeMillis
                        val nowX = change.position.x
                        val dt = (now - lastTime).coerceAtLeast(1L)
                        velX = (nowX - lastX) / dt * 1000f
                        lastX = nowX
                        lastTime = now
                        cumulative += delta

                        if (!dragStarted) {
                            if (now - down.uptimeMillis > TapLongPressMillis) {
                                abandoned = true
                                break
                            }
                            if (abs(cumulative.x) > viewConfiguration.touchSlop) {
                                direction = if (cumulative.x < 0) 1 else -1
                                val target = currentPage + direction
                                if (target !in 0..<latestTotalPages) {
                                    abandoned = true
                                    break
                                }
                                dragStarted = true
                                turnStartPage = currentPage
                                dragValue = animation.dragStart(
                                    direction,
                                    currentSize(),
                                    down.position,
                                    change.position
                                )
                            }
                        } else {
                            dragValue =
                                animation.dragUpdate(currentSize(), down.position, change.position)
                            change.consume()
                        }
                    }

                    if (dragStarted) {
                        val flung = when (direction) {
                            1 -> velX < -FlingVelocityThreshold
                            -1 -> velX > FlingVelocityThreshold
                            else -> false
                        }
                        val complete = flung || dragValue >= 0.5f
                        val commit = complete && !canceled && !abandoned && !upConsumed
                        if (commit) {
                            commitPage(direction)
                        }
                        animation.settleDrag(direction, currentSize(), commit) {}
                    } else if (!abandoned && !canceled && upPos != null && !upConsumed) {
                        val moved = (upPos - down.position).getDistance()
                        if (moved < viewConfiguration.touchSlop) {
                            val x = down.position.x
                            val target = when {
                                x < size.width / 3f -> currentPage - 1
                                x > size.width * 2f / 3f -> currentPage + 1
                                else -> null
                            }
                            if (target != null) {
                                startTurn(target - currentPage)
                            }
                        }
                    }
                }
            }
    ) {
        val turning = animation.turnDirection.value
        val baseIndex = when (turning) {
            1 -> (turnStartPage + 1).coerceIn(0, latestTotalPages - 1)
            -1 -> turnStartPage.coerceIn(0, latestTotalPages - 1)
            else -> currentPage
        }
        val turningIndex = when (turning) {
            1 -> turnStartPage
            -1 -> (turnStartPage - 1).coerceAtLeast(0)
            else -> currentPage
        }

        PageSurface(
            index = baseIndex,
            pages = pages,
            coverImagePath = coverImagePath,
            textStyle = textStyle,
            paperColor = paperColor,
            totalPages = latestTotalPages,
            topInset = topInset,
            bottomInset = bottomInset
        )

        if (turning != 0) {
            animation.TurningPageOverlay(paperColor = paperColor) {
                PageSurface(
                    index = turningIndex,
                    pages = pages,
                    coverImagePath = coverImagePath,
                    textStyle = textStyle,
                    paperColor = paperColor,
                    totalPages = latestTotalPages,
                    topInset = topInset,
                    bottomInset = bottomInset
                )
            }
        }
    }
}
