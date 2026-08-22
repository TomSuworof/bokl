package com.salat.bokl.pageturn

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min

private val PageTurnEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

private const val TurnDurationTapMillis = 480
private const val TurnDurationReleaseMillis = 320
private const val TurnDurationRevertMillis = 260

// The fold line passes through a diagonal pose at this progress (mirrors the keyframe
// sweep used by the reference page-curl implementation).
private const val MiddleCurlProgress = 1f / 3f

class CurlPageTurnAnimation : PageTurnAnimation {
    private val _turnDirection = mutableIntStateOf(0)
    override val turnDirection: State<Int>
        get() = _turnDirection

    private var fold by mutableStateOf(Fold(Offset.Zero, Offset.Zero))
    private var activeDirection = 0
    private var turnJob: Job? = null
    private val shadowBitmapCache = BitmapCache()

    private val scope: CoroutineScope

    constructor(scope: CoroutineScope) {
        this.scope = scope
    }

    override fun startTapTurn(
        direction: Int,
        size: Size,
        onFinished: () -> Unit
    ) {
        activeDirection = direction
        _turnDirection.intValue = direction
        fold = curlFold(size, direction, 0f)
        turnJob?.cancel()
        turnJob = scope.launch {
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(TurnDurationTapMillis, easing = PageTurnEasing)
            ) { value, _ -> fold = curlFold(size, direction, value) }
            _turnDirection.intValue = 0
            onFinished()
        }
    }

    override fun dragStart(
        direction: Int,
        size: Size,
        down: Offset,
        current: Offset
    ): Float {
        activeDirection = direction
        _turnDirection.intValue = direction
        turnJob?.cancel()
        fold = dragFold(size, down, current, direction)
        return dragProgress(size, down, current, direction)
    }

    override fun dragUpdate(
        size: Size,
        down: Offset,
        current: Offset
    ): Float {
        // Direction comes from dragStart; the gesture loop guarantees ordering.
        val progress = dragProgress(size, down, current, activeDirection)
        fold = dragFold(size, down, current, activeDirection)
        return progress
    }

    override fun settleDrag(
        direction: Int,
        size: Size,
        commit: Boolean,
        onFinished: () -> Unit
    ) {
        val from = fold
        val to = curlFold(size, direction, if (commit) 1f else 0f)
        val duration = if (commit) TurnDurationReleaseMillis else TurnDurationRevertMillis
        turnJob?.cancel()
        turnJob = scope.launch {
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(duration, easing = PageTurnEasing)
            ) { value, _ -> fold = Fold.lerp(from, to, value) }
            _turnDirection.intValue = 0
            onFinished()
        }
    }

    @Composable
    override fun TurningPageOverlay(
        paperColor: Color,
        content: @Composable (() -> Unit)
    ) {
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
            content()
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