package com.salat.bokl

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.salat.bokl.pageturn.PageFooterHeight
import com.salat.bokl.pageturn.PageSidePadding
import com.salat.bokl.pageturn.PageTopPadding
import com.salat.bokl.pageturn.PageTurnReader
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun BookReaderScreen(
    viewModel: ReaderViewModel,
    settingsViewModel: ReaderSettingsViewModel,
    book: Book,
    onBack: () -> Unit
) {
    LaunchedEffect(book) {
        viewModel.loadBook(book)
    }

    val state by viewModel.state.collectAsState()
    val background by settingsViewModel.background.collectAsState()

    BackHandler(onBack = onBack)

    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(view) {
        val insetsController = (context as? Activity)?.let {
            WindowCompat.getInsetsController(it.window, view)
        }
        insetsController?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val paperColor = background.background
    val textColor = background.textColor

    val textStyle = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 18.sp,
        textAlign = TextAlign.Justify,
        color = textColor
    )

    val density = LocalDensity.current
    val topInset = with(density) { WindowInsets.safeDrawing.getTop(this).toDp() }
    val bottomInset = with(density) { WindowInsets.safeDrawing.getBottom(this).toDp() }

    var showSettings by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(false) }
    var controlsShownTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(controlsShownTick) {
        if (controlsShownTick > 0) {
            controlsVisible = true
            delay(ControlsTimeoutMillis.milliseconds)
            controlsVisible = false
        }
    }

    val systemBarsVisible = with(density) {
        WindowInsets.systemBars.getTop(this) > 0 ||
                WindowInsets.systemBars.getBottom(this) > 0
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(paperColor)
            .pointerInput(Unit) {
                val zonePx = with(density) { EdgeSwipeZone.roundToPx() }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val inZone = down.position.y <= zonePx ||
                            down.position.y >= size.height - zonePx
                    if (!inZone) {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.any { it.changedToUpIgnoreConsumed() }) break
                        }
                        return@awaitEachGesture
                    }
                    var vertical = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.changedToUpIgnoreConsumed()) break
                        vertical += change.positionChange().y
                    }
                    if (abs(vertical) > viewConfiguration.touchSlop) {
                        controlsShownTick++
                    }
                }
            }
    ) {
        when {
            state.isLoading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            state.error != null -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        state.error ?: "",
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onBack) {
                        Text("Go Back")
                    }
                }
            }

            book.format == BookFormat.EPUB -> {
                PaginatedReader(
                    content = state.content,
                    coverImagePath = state.coverImagePath,
                    textStyle = textStyle,
                    paperColor = paperColor,
                    initialPage = state.initialPage,
                    topInset = topInset,
                    bottomInset = bottomInset,
                    onPageChanged = { page, totalPages ->
                        viewModel.onPageChanged(page, totalPages)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            else -> {
                Text(
                    text = state.content,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            start = PageSidePadding,
                            end = PageSidePadding,
                            top = PageTopPadding + topInset,
                            bottom = PageFooterHeight + bottomInset
                        )
                        .verticalScroll(rememberScrollState()),
                    style = textStyle
                )
            }
        }

        if (showSettings) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showSettings = false }
            )
        }

        ReaderSettingsButton(
            visible = systemBarsVisible || controlsVisible || showSettings,
            expanded = showSettings,
            paperColor = paperColor,
            textColor = textColor,
            topInset = topInset,
            onExpand = { showSettings = !showSettings },
            selected = background,
            onSelect = { settingsViewModel.setBackground(it); showSettings = false },
            modifier = Modifier.align(Alignment.TopEnd)
        )
    }

    val activeAnnotation = state.activeAnnotation
    if (activeAnnotation != null) {
        AnnotationDialog(
            number = activeAnnotation,
            text = state.annotations[activeAnnotation].orEmpty(),
            paperColor = paperColor,
            textColor = textColor,
            onDismiss = viewModel::dismissAnnotation
        )
    }
}

@Composable
private fun ReaderSettingsButton(
    visible: Boolean,
    expanded: Boolean,
    paperColor: Color,
    textColor: Color,
    topInset: Dp,
    onExpand: () -> Unit,
    selected: ReaderBackground,
    onSelect: (ReaderBackground) -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.padding(
            top = (topInset + 8.dp).coerceAtLeast(24.dp),
            end = 8.dp
        ),
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Column(horizontalAlignment = Alignment.End) {
            Box(
                modifier = Modifier
                    .shadow(elevation = 3.dp, shape = CircleShape, clip = false)
                    .clip(CircleShape)
                    .background(paperColor.copy(alpha = 0.95f))
                    .border(1.5.dp, textColor.copy(alpha = 0.75f), CircleShape)
            ) {
                IconButton(onClick = onExpand) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "Settings",
                        tint = textColor
                    )
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Row(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .shadow(elevation = 3.dp, shape = RoundedCornerShape(24.dp), clip = false)
                        .clip(RoundedCornerShape(24.dp))
                        .background(paperColor.copy(alpha = 0.95f))
                        .border(1.5.dp, textColor.copy(alpha = 0.75f), RoundedCornerShape(24.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    ReaderBackground.entries.forEach { option ->
                        BackgroundColorSwatch(
                            option = option,
                            selected = option == selected,
                            onClick = { onSelect(option) },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BackgroundColorSwatch(
    option: ReaderBackground,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(option.background)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = option.textColor
            )
        }
    }
}

@Composable
private fun AnnotationDialog(
    number: Int,
    text: String,
    paperColor: Color,
    textColor: Color,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = paperColor,
        titleContentColor = textColor,
        textContentColor = textColor,
        title = {
            Text(
                text = "Annotation $number",
                style = MaterialTheme.typography.titleMedium,
                color = textColor
            )
        },
        text = {
            Text(
                text = text,
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                style = TextStyle(
                    fontFamily = FontFamily.Serif,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    color = textColor
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = textColor)
            }
        }
    )
}

@Composable
private fun PaginatedReader(
    content: AnnotatedString,
    coverImagePath: String?,
    textStyle: TextStyle,
    paperColor: Color,
    initialPage: Int,
    topInset: Dp,
    bottomInset: Dp,
    onPageChanged: (page: Int, totalPages: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val layoutStyle = remember(textStyle) { textStyle.copy(color = Color.Unspecified) }
    var readingSize by remember { mutableStateOf(IntSize.Zero) }
    val pages = remember(content) { mutableStateListOf<AnnotatedString>() }
    var isPaginationComplete by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val sidePaddingPx = with(density) { PageSidePadding.roundToPx() }
    val topInsetPx = with(density) { topInset.roundToPx() }
    val bottomInsetPx = with(density) { bottomInset.roundToPx() }
    val textTopPaddingPx = with(density) { PageTopPadding.roundToPx() } + topInsetPx
    val footerHeightPx = with(density) { PageFooterHeight.roundToPx() } + bottomInsetPx

    LaunchedEffect(content, layoutStyle, readingSize) {
        isPaginationComplete = false
        pages.clear()
        if (content.isEmpty()) return@LaunchedEffect
        val widthPx = readingSize.width - sidePaddingPx * 2
        val heightPx = readingSize.height - textTopPaddingPx - footerHeightPx
        if (widthPx <= 0 || heightPx <= 0) return@LaunchedEffect

        var offset = 0
        var pagesThisFrame = 0
        while (offset < content.length) {
            val chunk = buildPage(
                content = content,
                offset = offset,
                measurer = textMeasurer,
                textStyle = layoutStyle,
                widthPx = widthPx,
                heightPx = heightPx
            )
            pages.addAll(chunk.pages)
            if (chunk.nextOffset <= offset || chunk.pages.isEmpty()) {
                if (chunk.pages.isEmpty() && offset < content.length) {
                    pages.add(content.subSequence(offset, content.length))
                }
                break
            }
            offset = chunk.nextOffset
            if (++pagesThisFrame >= PAGES_PER_FRAME) {
                pagesThisFrame = 0
                yield()
                withFrameNanos { }
            }
        }
        isPaginationComplete = true
    }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { readingSize = it }
        ) {
            when {
                coverImagePath == null && pages.isEmpty() && content.isBlank() -> {
                    Text(
                        text = "No readable content",
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyLarge,
                        color = textStyle.color
                    )
                }

                coverImagePath == null && pages.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                else -> {
                    PageTurnReader(
                        pages = pages,
                        coverImagePath = coverImagePath,
                        textStyle = textStyle,
                        paperColor = paperColor,
                        initialPage = initialPage,
                        paginationComplete = isPaginationComplete,
                        topInset = topInset,
                        bottomInset = bottomInset,
                        onPageChanged = { page, total ->
                            onPageChanged(page, total)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

private const val PAGINATION_WINDOW_CHARS = 10_000
private const val PAGES_PER_FRAME = 4

// How far from the top or bottom edge a swipe must start to reveal the settings gear.
private val EdgeSwipeZone = 72.dp

// How long the gear stays visible after an edge swipe, mirroring the transient system bars.
private const val ControlsTimeoutMillis = 3000L

private data class PageChunk(
    val pages: List<AnnotatedString>,
    val nextOffset: Int
)

private fun buildPage(
    content: AnnotatedString,
    offset: Int,
    measurer: TextMeasurer,
    textStyle: TextStyle,
    widthPx: Int,
    heightPx: Int
): PageChunk {
    if (offset >= content.length || heightPx <= 0) return PageChunk(emptyList(), offset)

    val windowEnd = minOf(offset + PAGINATION_WINDOW_CHARS, content.length)
    val windowText = content.subSequence(offset, windowEnd)
    val layout = measurer.measure(
        text = stripFirstLineIndent(windowText),
        style = textStyle,
        constraints = Constraints(maxWidth = widthPx)
    )
    if (layout.lineCount == 0) return PageChunk(emptyList(), windowEnd)

    var endLine = 0
    while (endLine < layout.lineCount && layout.getLineBottom(endLine) <= heightPx) {
        endLine++
    }
    if (endLine <= 0) endLine = 1

    val pageEndOffset: Int
    val pageText: AnnotatedString
    if (endLine >= layout.lineCount) {
        pageEndOffset = windowEnd
        pageText = windowText
    } else {
        pageEndOffset = offset + layout.getLineStart(endLine)
        pageText = windowText.subSequence(0, pageEndOffset - offset)
    }

    val page = stripFirstLineIndent(pageText)
    return PageChunk(
        listOf(
            if (paragraphContinuesOnNextPage(content, pageEndOffset)) {
                appendParagraphContinuationPad(page)
            } else {
                page
            }
        ),
        pageEndOffset
    )
}

private fun paragraphContinuesOnNextPage(content: AnnotatedString, offset: Int): Boolean {
    if (offset >= content.length) return false
    if (content.text[offset] == '\n') return false
    return content.paragraphStyles.none { it.start == offset }
}

private fun paragraphContinuationPad(): AnnotatedString =
    AnnotatedString(
        " " + "W".repeat(160),
        spanStyle = SpanStyle(color = Color.Transparent)
    )

private fun appendParagraphContinuationPad(page: AnnotatedString): AnnotatedString {
    val pad = paragraphContinuationPad()
    val padded = page + pad
    if (padded.paragraphStyles.isEmpty()) {
        val builder = AnnotatedString.Builder(padded.text)
        copySpanStylesAndLinks(padded, builder)
        builder.addStyle(ParagraphStyle(), 0, padded.length)
        return builder.toAnnotatedString()
    }
    val last = padded.paragraphStyles.maxByOrNull { it.end } ?: return padded
    return rebuildWithParagraphs(padded) { range ->
        if (range === last) {
            range.copy(end = range.end + pad.length)
        } else {
            range
        }
    }
}

private fun stripFirstLineIndent(text: AnnotatedString): AnnotatedString {
    if (text.isEmpty() || text.paragraphStyles.isEmpty()) return text
    val target = text.paragraphStyles.firstOrNull { it.start == 0 } ?: return text
    val indent = target.item.textIndent ?: return text
    if (indent.firstLine.value <= 0f) return text
    return rebuildWithParagraphs(text) { range ->
        if (range === target) {
            range.copy(
                item = range.item.copy(
                    textIndent = TextIndent(firstLine = 0.em, restLine = indent.restLine)
                )
            )
        } else {
            range
        }
    }
}

private fun rebuildWithParagraphs(
    text: AnnotatedString,
    transform: (AnnotatedString.Range<ParagraphStyle>) -> AnnotatedString.Range<ParagraphStyle>
): AnnotatedString {
    val builder = AnnotatedString.Builder(text.text)
    for (range in text.spanStyles) {
        builder.addStyle(range.item, range.start, range.end)
    }
    for (range in text.paragraphStyles) {
        val transformed = transform(range)
        builder.addStyle(transformed.item, transformed.start, transformed.end)
    }
    copyLinkAnnotations(text, builder)
    return builder.toAnnotatedString()
}

private fun copySpanStylesAndLinks(from: AnnotatedString, to: AnnotatedString.Builder) {
    for (range in from.spanStyles) {
        to.addStyle(range.item, range.start, range.end)
    }
    copyLinkAnnotations(from, to)
}

private fun copyLinkAnnotations(from: AnnotatedString, to: AnnotatedString.Builder) {
    for (range in from.getLinkAnnotations(0, from.length)) {
        when (val link = range.item) {
            is LinkAnnotation.Clickable -> to.addLink(link, range.start, range.end)
            is LinkAnnotation.Url -> to.addLink(link, range.start, range.end)
        }
    }
}
