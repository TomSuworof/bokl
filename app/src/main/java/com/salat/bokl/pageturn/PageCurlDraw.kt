package com.salat.bokl.pageturn

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotateRad
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

// How strongly the mirror of the page reads as a "back page" (0 = fully covered).
private const val BackPageContentAlpha = 0.12f
internal const val CurlShadowAlpha = 0.2f
internal val CurlShadowRadius = 15.dp
internal val CurlShadowOffset = DpOffset((-5).dp, 0.dp)

/**
 * Draws the page as a curled sheet: the content left of the fold line is shown as-is,
 * and the part right of the fold line is mirrored and rotated to form the back-page
 * with a drop shadow. Ported from the Apache-2.0 "pagecurl" library (CurlDraw.kt).
 */
internal fun Modifier.drawCurl(
    posA: Offset,
    posB: Offset,
    backPageColor: Color,
    shadowAlpha: Float,
    shadowRadius: Dp,
    shadowOffset: DpOffset,
    shadowBitmapCache: BitmapCache,
): Modifier = drawWithCache {
    // Fully turned: the fold line sits at the left edge, so the page is not visible.
    if (posA.x <= 0.5f && posB.x <= 0.5f) {
        return@drawWithCache onDrawWithContent { /* Empty */ }
    }
    // Not started: the fold line sits at the right edge, so draw the whole page.
    if (posA.x >= size.width - 0.5f && posB.x >= size.width - 0.5f) {
        return@drawWithCache onDrawWithContent { drawContent() }
    }

    // Find the intersection of the fold line with the top and bottom sides so that the
    // content may be clipped and mirrored correctly.
    val topIntersection = lineLineIntersection(
        Offset(0f, 0f), Offset(size.width, 0f),
        posA, posB
    )
    val bottomIntersection = lineLineIntersection(
        Offset(0f, size.height), Offset(size.width, size.height),
        posA, posB
    )
    // Should not really happen (horizontal fold line), but draw the full content then.
    if (topIntersection == null || bottomIntersection == null) {
        return@drawWithCache onDrawWithContent { drawContent() }
    }

    // Clamp x coordinates to 0 so the page does not look torn from the book.
    val topCurlOffset = Offset(max(0f, topIntersection.x), topIntersection.y)
    val bottomCurlOffset = Offset(max(0f, bottomIntersection.x), bottomIntersection.y)

    val drawClippedContent = prepareClippedContent(topCurlOffset, bottomCurlOffset)
    val drawCurl = prepareCurl(
        backPageColor = backPageColor,
        shadowAlpha = shadowAlpha,
        shadowRadius = shadowRadius,
        shadowOffset = shadowOffset,
        topCurlOffset = topCurlOffset,
        bottomCurlOffset = bottomCurlOffset,
        bitmapCache = shadowBitmapCache
    )

    onDrawWithContent {
        drawClippedContent()
        drawCurl()
    }
}

private fun CacheDrawScope.prepareClippedContent(
    topCurlOffset: Offset,
    bottomCurlOffset: Offset,
): ContentDrawScope.() -> Unit {
    // A quadrilateral from the left side of the page to the intersection points.
    val path = Path()
    path.lineTo(topCurlOffset.x, topCurlOffset.y)
    path.lineTo(bottomCurlOffset.x, bottomCurlOffset.y)
    path.lineTo(0f, size.height)

    return result@{
        clipPath(path) {
            this@result.drawContent()
        }
    }
}

private fun CacheDrawScope.prepareCurl(
    backPageColor: Color,
    shadowAlpha: Float,
    shadowRadius: Dp,
    shadowOffset: DpOffset,
    topCurlOffset: Offset,
    bottomCurlOffset: Offset,
    bitmapCache: BitmapCache,
): ContentDrawScope.() -> Unit {
    // A quadrilateral of the part of the page which should be mirrored as the back-page.
    // Always keep 4 points, even when the back-page is only a small "corner" (3 points),
    // otherwise a visual artifact appears when switching between 3 and 4 points.
    val polygon = Polygon(
        sequence {
            suspend fun SequenceScope<Offset>.yieldEndSideInterception() {
                val offset = lineLineIntersection(
                    topCurlOffset, bottomCurlOffset,
                    Offset(size.width, 0f), Offset(size.width, size.height)
                ) ?: return
                yield(offset)
                yield(offset)
            }

            // Take 2 points from the top side when it intersects the fold, otherwise the
            // interception with the right side.
            if (topCurlOffset.x < size.width) {
                yield(topCurlOffset)
                yield(Offset(size.width, topCurlOffset.y))
            } else {
                yieldEndSideInterception()
            }

            // Take 2 points from the bottom side when it intersects the fold, otherwise the
            // interception with the right side.
            if (bottomCurlOffset.x < size.width) {
                yield(Offset(size.width, size.height))
                yield(bottomCurlOffset)
            } else {
                yieldEndSideInterception()
            }
        }.toList()
    )

    // The angle between the X axis and the fold line, used to rotate the mirrored content
    // into the place of the curled back-page.
    val lineVector = topCurlOffset - bottomCurlOffset
    val angle = PI.toFloat() - atan2(lineVector.y, lineVector.x) * 2

    val drawShadow =
        prepareShadow(shadowAlpha, shadowRadius, shadowOffset, polygon, angle, bitmapCache)

    return result@{
        withTransform({
            // Mirror in X axis as the back-page should be mirrored
            scale(-1f, 1f, pivot = bottomCurlOffset)
            // Rotate the drawing according to the fold line
            rotateRad(angle, pivot = bottomCurlOffset)
        }) {
            // Draw the shadow first
            this@result.drawShadow()

            // And finally the back-page with a dim overlay
            clipPath(polygon.toPath()) {
                this@result.drawContent()
                drawRect(backPageColor.copy(alpha = 1f - BackPageContentAlpha))
            }
        }
    }
}

private fun CacheDrawScope.prepareShadow(
    shadowAlpha: Float,
    shadowRadius: Dp,
    shadowOffset: DpOffset,
    polygon: Polygon,
    angle: Float,
    bitmapCache: BitmapCache,
): ContentDrawScope.() -> Unit {
    if (shadowAlpha == 0f || shadowRadius == 0.dp) {
        return { /* No shadow is requested */ }
    }

    val radius = shadowRadius.toPx()
    val shadowColor = Color.Black.copy(alpha = shadowAlpha).toArgb()
    val transparent = Color.Black.copy(alpha = 0f).toArgb()
    val shadowOffsetPx = Offset(-shadowOffset.x.toPx(), shadowOffset.y.toPx())
        .rotate(2 * PI.toFloat() - angle)

    val paint = Paint().apply {
        val frameworkPaint = asFrameworkPaint()
        frameworkPaint.color = transparent
        frameworkPaint.setShadowLayer(
            shadowRadius.toPx(),
            shadowOffsetPx.x,
            shadowOffsetPx.y,
            shadowColor
        )
    }

    // Hardware acceleration supports setShadowLayer() only on API 28 and above, so for
    // older versions draw the shadow into a bitmap instead.
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        prepareShadowApi28(radius, paint, polygon)
    } else {
        prepareShadowImage(radius, paint, polygon, bitmapCache)
    }
}

private fun prepareShadowApi28(
    radius: Float,
    paint: Paint,
    polygon: Polygon,
): ContentDrawScope.() -> Unit = {
    drawIntoCanvas {
        it.nativeCanvas.drawPath(
            polygon.offset(radius).toPath().asAndroidPath(),
            paint.asFrameworkPaint()
        )
    }
}

/** Caches a bitmap so that the API < 28 shadow does not allocate a full-screen bitmap every frame. */
internal class BitmapCache {
    private var cached: Bitmap? = null

    fun obtain(width: Int, height: Int): Bitmap {
        val current = cached
        if (current != null && !current.isRecycled && current.width == width && current.height == height) {
            return current
        }
        return createBitmap(width, height).also { cached = it }
    }
}

private fun CacheDrawScope.prepareShadowImage(
    radius: Float,
    paint: Paint,
    polygon: Polygon,
    bitmapCache: BitmapCache,
): ContentDrawScope.() -> Unit {
    // Increase the size a little so that the shadow is not clipped.
    val bitmap = bitmapCache.obtain(
        (size.width + radius * 4).toInt(),
        (size.height + radius * 4).toInt()
    )
    Canvas(bitmap).apply {
        drawPath(
            polygon
                // As the bitmap is bigger, translate the polygon so the shadow stays centered.
                .translate(Offset(2 * radius, 2 * radius))
                .offset(radius)
                .toPath()
                .asAndroidPath(),
            paint.asFrameworkPaint()
        )
    }

    return {
        drawIntoCanvas {
            it.nativeCanvas.drawBitmap(bitmap, -2 * radius, -2 * radius, null)
        }
    }
}

private data class Polygon(val vertices: List<Offset>) {

    private val size: Int = vertices.size

    fun translate(offset: Offset): Polygon =
        Polygon(vertices.map { it + offset })

    fun offset(value: Float): Polygon {
        val edgeNormals = List(size) {
            val edge = vertices[index(it + 1)] - vertices[index(it)]
            Offset(edge.y, -edge.x).normalized()
        }

        val vertexNormals = List(size) {
            (edgeNormals[index(it - 1)] + edgeNormals[index(it)]).normalized()
        }

        return Polygon(
            vertices.mapIndexed { index, vertex ->
                vertex + vertexNormals[index] * value
            }
        )
    }

    fun toPath(): Path =
        Path().apply {
            vertices.forEachIndexed { index, vertex ->
                if (index == 0) {
                    moveTo(vertex.x, vertex.y)
                } else {
                    lineTo(vertex.x, vertex.y)
                }
            }
        }

    private fun index(i: Int) = ((i % size) + size) % size
}

private fun Offset.normalized(): Offset {
    val distance = getDistance()
    return if (distance != 0f) this / distance else this
}

internal fun Offset.rotate(angle: Float): Offset {
    val s = sin(angle)
    val c = cos(angle)
    return Offset(x * c - y * s, x * s + y * c)
}

private fun lineLineIntersection(
    line1a: Offset,
    line1b: Offset,
    line2a: Offset,
    line2b: Offset,
): Offset? {
    val denominator =
        (line1a.x - line1b.x) * (line2a.y - line2b.y) - (line1a.y - line1b.y) * (line2a.x - line2b.x)
    if (denominator == 0f) return null

    val x1 = (line1a.x * line1b.y - line1a.y * line1b.x) * (line2a.x - line2b.x)
    val x2 = (line1a.x - line1b.x) * (line2a.x * line2b.y - line2a.y * line2b.x)
    val x = (x1 - x2) / denominator

    val y1 = (line1a.x * line1b.y - line1a.y * line1b.x) * (line2a.y - line2b.y)
    val y2 = (line1a.y - line1b.y) * (line2a.x * line2b.y - line2a.y * line2b.x)
    val y = (y1 - y2) / denominator
    return Offset(x, y)
}
