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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

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
    var progress by remember { mutableFloatStateOf(0f) }
    var turnDirection by remember { mutableStateOf(0) }
    var currentPage by remember { mutableIntStateOf(0) }
    var initialized by remember { mutableStateOf(false) }
    var turnJob: Job? by remember { mutableStateOf(null) }

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

    fun commit(direction: Int) {
        val target = (currentPage + direction).coerceIn(0, latestTotalPages - 1)
        if (target != currentPage) {
            currentPage = target
        }
        turnDirection = 0
        progress = 0f
    }

    fun startTurn(direction: Int) {
        val target = currentPage + direction
        if (target < 0 || target >= latestTotalPages) return
        turnDirection = direction
        turnJob?.cancel()
        turnJob = scope.launch {
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(TurnDurationTapMillis, easing = PageTurnEasing)
            ) { value, _ -> progress = value }
            commit(direction)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dragStarted = false
                    var direction = 0
                    var cumulative = Offset.Zero
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
                                if (target < 0 || target >= latestTotalPages) {
                                    abandoned = true
                                    break
                                }
                                dragStarted = true
                                turnDirection = direction
                                turnJob?.cancel()
                                progress =
                                    ((abs(cumulative.x) - viewConfiguration.touchSlop) / size.width)
                                        .coerceIn(0f, 1f)
                            }
                        } else {
                            progress =
                                (abs(cumulative.x) / size.width).coerceIn(0f, 1f)
                            change.consume()
                        }
                    }

                    if (dragStarted) {
                        val flung = when (direction) {
                            1 -> velX < -FlingVelocityThreshold
                            -1 -> velX > FlingVelocityThreshold
                            else -> false
                        }
                        val complete = flung || progress >= 0.5f
                        turnJob?.cancel()
                        turnJob = scope.launch {
                            if (complete && !canceled && !abandoned && !upConsumed) {
                                animate(
                                    initialValue = progress,
                                    targetValue = 1f,
                                    animationSpec = tween(
                                        TurnDurationReleaseMillis,
                                        easing = PageTurnEasing
                                    )
                                ) { value, _ -> progress = value }
                                commit(direction)
                            } else {
                                animate(
                                    initialValue = progress,
                                    targetValue = 0f,
                                    animationSpec = tween(
                                        TurnDurationRevertMillis,
                                        easing = PageTurnEasing
                                    )
                                ) { value, _ -> progress = value }
                                turnDirection = 0
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
        val baseIndex = if (turnDirection != 0) {
            (currentPage + turnDirection).coerceIn(0, latestTotalPages - 1)
        } else {
            currentPage
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
            FoldShadow(
                direction = turnDirection,
                progress = progress,
                modifier = Modifier.fillMaxSize()
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationY = -turnDirection * 90f * progress
                        transformOrigin = TransformOrigin(
                            if (turnDirection == 1) 0f else 1f,
                            0.5f
                        )
                        cameraDistance = size.width.toFloat() * 1.2f
                    }
            ) {
                PageSurface(
                    index = currentPage,
                    pages = pages,
                    coverImagePath = coverImagePath,
                    textStyle = textStyle,
                    paperColor = paperColor,
                    totalPages = latestTotalPages,
                    topInset = topInset,
                    bottomInset = bottomInset
                )
                HingeShadow(
                    direction = turnDirection,
                    progress = progress,
                    modifier = Modifier.fillMaxSize()
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
                color = (textStyle.color ?: Color.Black).copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun FoldShadow(direction: Int, progress: Float, modifier: Modifier = Modifier) {
    val strength = sin(PI * progress.coerceIn(0f, 1f)).toFloat()
    val nearFold = 0.34f * strength
    val midFold = 0.16f * strength
    Box(
        modifier = modifier.drawBehind {
            val w = size.width
            val theta = (PI / 2.0 * progress.coerceIn(0f, 1f)).toFloat()
            val cosT = cos(theta.toDouble()).toFloat()
            val foldX = if (direction == 1) w * cosT else w * (1f - cosT)
            val shadowWidth = w * 0.45f
            val left = if (direction == 1) foldX else foldX - shadowWidth
            val stops = if (direction == 1) {
                arrayOf(
                    0f to Color.Black.copy(alpha = nearFold),
                    0.35f to Color.Black.copy(alpha = midFold),
                    1f to Color.Black.copy(alpha = 0f)
                )
            } else {
                arrayOf(
                    0f to Color.Black.copy(alpha = 0f),
                    0.65f to Color.Black.copy(alpha = midFold),
                    1f to Color.Black.copy(alpha = nearFold)
                )
            }
            drawRect(
                brush = Brush.horizontalGradient(
                    colorStops = stops,
                    startX = left,
                    endX = left + shadowWidth
                ),
                topLeft = Offset(left, 0f),
                size = Size(shadowWidth, size.height)
            )
        }
    )
}

@Composable
private fun HingeShadow(direction: Int, progress: Float, modifier: Modifier = Modifier) {
    val strength = sin(PI * progress.coerceIn(0f, 1f)).toFloat()
    val nearFold = 0.30f * strength
    val midFold = 0.14f * strength
    Box(
        modifier = modifier.drawBehind {
            val brush = if (direction == 1) {
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = nearFold),
                    0.10f to Color.Black.copy(alpha = midFold),
                    0.5f to Color.Black.copy(alpha = 0f)
                )
            } else {
                Brush.horizontalGradient(
                    0.5f to Color.Black.copy(alpha = 0f),
                    0.90f to Color.Black.copy(alpha = midFold),
                    1f to Color.Black.copy(alpha = nearFold)
                )
            }
            drawRect(brush = brush)
        }
    )
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
