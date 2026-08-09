package com.salat.bokl

import android.graphics.BitmapFactory
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal val PageSidePadding = 20.dp
internal val PageTopPadding = 16.dp
internal val PageFooterHeight = 48.dp
internal val PageNumberBottomPadding = 14.dp

private val PageTurnEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

private const val TapLongPressMillis = 400L
private const val FlingVelocityThreshold = 700f
private const val TurnDurationTapMillis = 480
private const val TurnDurationReleaseMillis = 320
private const val TurnDurationRevertMillis = 260

// The fold line passes through a diagonal pose at this progress (mirrors the keyframe
// sweep used by the reference page-curl implementation).
private const val MiddleCurlProgress = 1f / 3f

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
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp
) {
    val scope = rememberCoroutineScope()
    val coverOffset = if (coverImagePath != null) 1 else 0
    val totalPages = pages.size + coverOffset
    var fold by remember { mutableStateOf(Fold(Offset.Zero, Offset.Zero)) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var turnDirection by remember { mutableIntStateOf(0) }
    var turnStartPage by remember { mutableIntStateOf(0) }
    var currentPage by remember { mutableIntStateOf(0) }
    var initialized by remember { mutableStateOf(false) }
    var turnJob: Job? by remember { mutableStateOf(null) }
    val shadowBitmapCache = remember { BitmapCache() }

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

    fun animateFold(from: Fold, to: Fold, durationMillis: Int, then: () -> Unit) {
        turnJob?.cancel()
        turnJob = scope.launch {
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(durationMillis, easing = PageTurnEasing)
            ) { value, _ -> fold = Fold.lerp(from, to, value) }
            then()
        }
    }

    fun startTurn(direction: Int) {
        val target = currentPage + direction
        if (target !in 0..<latestTotalPages) return
        turnStartPage = currentPage
        commitPage(direction)
        turnDirection = direction
        fold = curlFold(currentSize(), direction, 0f)
        turnJob?.cancel()
        turnJob = scope.launch {
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(TurnDurationTapMillis, easing = PageTurnEasing)
            ) { value, _ -> fold = curlFold(currentSize(), direction, value) }
            turnDirection = 0
        }
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
                                dragValue = dragProgress(
                                    currentSize(),
                                    down.position,
                                    change.position,
                                    direction
                                )
                                turnStartPage = currentPage
                                turnDirection = direction
                                turnJob?.cancel()
                                fold = dragFold(
                                    currentSize(),
                                    down.position,
                                    change.position,
                                    direction
                                )
                            }
                        } else {
                            dragValue = dragProgress(
                                currentSize(),
                                down.position,
                                change.position,
                                direction
                            )
                            fold =
                                dragFold(currentSize(), down.position, change.position, direction)
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
                        turnJob?.cancel()
                        if (complete && !canceled && !abandoned && !upConsumed) {
                            commitPage(direction)
                            turnJob = scope.launch {
                                animateFold(
                                    from = fold,
                                    to = curlFold(currentSize(), direction, 1f),
                                    durationMillis = TurnDurationReleaseMillis
                                ) { turnDirection = 0 }
                            }
                        } else {
                            turnJob = scope.launch {
                                animateFold(
                                    from = fold,
                                    to = curlFold(currentSize(), direction, 0f),
                                    durationMillis = TurnDurationRevertMillis
                                ) { turnDirection = 0 }
                            }
                        }
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
        val baseIndex = when (turnDirection) {
            1 -> (turnStartPage + 1).coerceIn(0, latestTotalPages - 1)
            -1 -> turnStartPage.coerceIn(0, latestTotalPages - 1)
            else -> currentPage
        }
        val turningIndex = when (turnDirection) {
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

        if (turnDirection != 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawCurl(
                        posA = fold.top,
                        posB = fold.bottom,
                        backPageColor = paperColor,
                        shadowAlpha = CurlShadowAlpha,
                        shadowRadius = CurlShadowRadius,
                        shadowOffset = CurlShadowOffset,
                        shadowBitmapCache = shadowBitmapCache
                    )
            ) {
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

@Composable
private fun PageSurface(
    index: Int,
    pages: List<AnnotatedString>,
    coverImagePath: String?,
    textStyle: TextStyle,
    paperColor: Color,
    totalPages: Int,
    topInset: Dp,
    bottomInset: Dp,
    modifier: Modifier = Modifier
) {
    if (index < 0) return
    val coverOffset = if (coverImagePath != null) 1 else 0
    val isCover = coverImagePath != null && index == 0
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(paperColor)
    ) {
        if (isCover) {
            CoverPage(imagePath = coverImagePath, modifier = Modifier.fillMaxSize())
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = PageSidePadding,
                        end = PageSidePadding,
                        top = PageTopPadding + topInset,
                        bottom = PageFooterHeight + bottomInset
                    )
            ) {
                SelectionContainer {
                    Text(
                        text = pages.getOrElse(index - coverOffset) { AnnotatedString("") },
                        modifier = Modifier.fillMaxSize(),
                        style = textStyle
                    )
                }
            }
            Text(
                text = "${index + 1} / $totalPages",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = PageNumberBottomPadding + bottomInset),
                style = MaterialTheme.typography.labelMedium,
                color = textStyle.color.copy(alpha = 0.6f)
            )
        }
    }
}

/** The fold line defined by its [top] and [bottom] points. */
private data class Fold(val top: Offset, val bottom: Offset) {
    companion object {
        fun lerp(a: Fold, b: Fold, t: Float): Fold =
            Fold(
                Offset(a.top.x + (b.top.x - a.top.x) * t, a.top.y + (b.top.y - a.top.y) * t),
                Offset(
                    a.bottom.x + (b.bottom.x - a.bottom.x) * t,
                    a.bottom.y + (b.bottom.y - a.bottom.y) * t
                )
            )
    }
}

/**
 * Maps the turn [progress] to the fold line.
 * Forward turns sweep the fold from the right edge to the left edge (the page curls away),
 * backward turns sweep it from the left edge to the right edge (the page curls in).
 */
private fun curlFold(size: Size, direction: Int, progress: Float): Fold {
    val w = size.width
    val h = size.height
    val t = progress.coerceIn(0f, 1f)
    val mid = MiddleCurlProgress
    val right = Fold(Offset(w, 0f), Offset(w, h))
    val left = Fold(Offset(0f, 0f), Offset(0f, h))
    val diagonal = Fold(Offset(w, h / 2f), Offset(w / 2f, h))
    return if (direction == 1) {
        when {
            t <= 0f -> right
            t >= 1f -> left
            t <= mid -> Fold.lerp(right, diagonal, t / mid)
            else -> Fold.lerp(diagonal, left, (t - mid) / (1f - mid))
        }
    } else {
        when {
            t <= 0f -> left
            t >= 1f -> right
            t <= mid -> Fold.lerp(left, diagonal, t / mid)
            else -> Fold.lerp(diagonal, right, (t - mid) / (1f - mid))
        }
    }
}

/**
 * Builds the fold line anchored to the finger: it passes through the [current] position and
 * is perpendicular to the direction towards the page edge the turn starts from. This way the
 * curl follows the finger while dragging, as in Play Books / iBooks.
 *
 * The fold is additionally constrained so that its line always exits the page through the top
 * and bottom edges within the page bounds. Without this, when the finger drifts vertically the
 * fold line tilts until one of its edge intersections slides past the right side of the page,
 * which flips the mirrored back-page polygon and makes the page flicker around 3/4 of the drag.
 */
private fun fingerFold(size: Size, start: Offset, current: Offset, direction: Int): Fold {
    val w = size.width
    val h = size.height
    val cx = current.x
    val cy = current.y
    val anchor = if (direction == 1) {
        Offset(w, start.y)
    } else {
        Offset(0f, start.y)
    }
    val rotated = (anchor - Offset(cx, cy)).rotate(PI.toFloat() / 2f)

    // The fold line through the finger must hit the top edge at an x in [0, w] and the bottom
    // edge at an x in [0, w]. Expressing the direction as a cotangent, both constraints bound
    // it, so clamp the perpendicular direction into the intersection of those ranges.
    val topMin = (cx - w) / max(cy, 1f)
    val topMax = cx / max(cy, 1f)
    val bottomMin = -cx / max(h - cy, 1f)
    val bottomMax = (w - cx) / max(h - cy, 1f)
    val minCot = max(topMin, bottomMin)
    val maxCot = min(topMax, bottomMax)

    val desiredCot = if (rotated.y == 0f) {
        if (rotated.x > 0f) Float.POSITIVE_INFINITY else Float.NEGATIVE_INFINITY
    } else {
        rotated.x / rotated.y
    }
    val cot = if (maxCot >= minCot) desiredCot.coerceIn(minCot, maxCot) else 0f

    return Fold(
        Offset(cx - cy * cot, 0f),
        Offset(cx + (h - cy) * cot, h)
    )
}

/**
 * The drag progress from 0 to 1, based on how far the finger has moved towards the opposite
 * page edge. This is what determines whether the release commits or reverts the turn.
 */
private fun dragProgress(size: Size, down: Offset, current: Offset, direction: Int): Float {
    return if (direction == 1) {
        ((down.x - current.x) / size.width).coerceIn(0f, 1f)
    } else {
        ((current.x - down.x) / size.width).coerceIn(0f, 1f)
    }
}

/**
 * The fold used while dragging. It starts at the rest edge (the page fully closed) and moves
 * towards the finger-anchored fold as the drag progresses, so the curl grows from the edge
 * instead of jumping to the finger position.
 */
private fun dragFold(size: Size, down: Offset, current: Offset, direction: Int): Fold {
    val rest = if (direction == 1) {
        Fold(Offset(size.width, 0f), Offset(size.width, size.height))
    } else {
        Fold(Offset(0f, 0f), Offset(0f, size.height))
    }
    val finger = fingerFold(size, down, current, direction)
    return Fold.lerp(rest, finger, dragProgress(size, down, current, direction))
}

@Composable
private fun CoverPage(imagePath: String, modifier: Modifier = Modifier) {
    val image by produceState<ImageBitmap?>(initialValue = null, imagePath) {
        value = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(imagePath)?.asImageBitmap()
        }
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            CircularProgressIndicator()
        }
    }
}
