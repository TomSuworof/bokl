package com.salat.bokl

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
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
import kotlinx.coroutines.yield

@Composable
fun BookReaderScreen(
    viewModel: ReaderViewModel,
    settingsViewModel: ReaderSettingsViewModel,
    book: Book?,
    onBack: () -> Unit
) {
    LaunchedEffect(book) {
        if (book != null) {
            viewModel.loadBook(book)
        }
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(paperColor)
    ) {
        when {
            book == null -> {
                // Destination is being removed; render nothing so exiting is immediate.
            }
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
            book?.format == BookFormat.EPUB -> {
                key(book.id) {
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
    }
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
    var readingSize by remember { mutableStateOf(IntSize.Zero) }
    val pages = remember(content) { mutableStateListOf<AnnotatedString>() }
    var isPaginationComplete by remember { mutableStateOf(false) }
    val coverOffset = if (coverImagePath != null) 1 else 0

    val density = LocalDensity.current
    val sidePaddingPx = with(density) { PageSidePadding.roundToPx() }
    val topInsetPx = with(density) { topInset.roundToPx() }
    val bottomInsetPx = with(density) { bottomInset.roundToPx() }
    val textTopPaddingPx = with(density) { PageTopPadding.roundToPx() } + topInsetPx
    val footerHeightPx = with(density) { PageFooterHeight.roundToPx() } + bottomInsetPx

    LaunchedEffect(content, textStyle, readingSize) {
        isPaginationComplete = false
        pages.clear()
        if (content.isEmpty()) return@LaunchedEffect
        val widthPx = readingSize.width - sidePaddingPx * 2
        val heightPx = readingSize.height - textTopPaddingPx - footerHeightPx
        if (widthPx <= 0 || heightPx <= 0) return@LaunchedEffect

        var offset = 0
        while (offset < content.length) {
            val chunk = buildPageChunk(
                content = content,
                offset = offset,
                measurer = textMeasurer,
                textStyle = textStyle,
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
            yield()
            withFrameNanos { }
        }
        isPaginationComplete = true
    }

    val totalPages = pages.size + coverOffset

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

private data class PageChunk(
    val pages: List<AnnotatedString>,
    val nextOffset: Int
)

private fun buildPageChunk(
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
        text = if (isParagraphStart(content, offset)) {
            windowText
        } else {
            stripFirstLineIndent(windowText)
        },
        style = textStyle,
        constraints = Constraints(maxWidth = widthPx)
    )
    if (layout.lineCount == 0) return PageChunk(emptyList(), windowEnd)

    val measuredText = layout.layoutInput.text
    val pages = mutableListOf<AnnotatedString>()
    var line = 0
    while (line < layout.lineCount) {
        val pageTop = layout.getLineTop(line)
        val startChar = layout.getLineStart(line)
        val pageStartOffset = offset + startChar
        var endLine = line
        while (endLine < layout.lineCount && layout.getLineBottom(endLine) - pageTop <= heightPx) {
            endLine++
        }
        if (endLine <= line) endLine = line + 1

        val pageText = if (endLine >= layout.lineCount) {
            if (windowEnd >= content.length) {
                measuredText.subSequence(startChar, measuredText.length)
            } else {
                null
            }
        } else {
            measuredText.subSequence(startChar, layout.getLineStart(endLine))
        }

        if (pageText != null) {
            val page = if (isParagraphStart(content, pageStartOffset)) {
                pageText
            } else {
                stripFirstLineIndent(pageText)
            }
            val pageEndOffset = pageStartOffset + pageText.length
            pages.add(
                if (paragraphContinuesOnNextPage(content, pageEndOffset)) {
                    appendParagraphContinuationPad(page)
                } else {
                    page
                }
            )
        }
        if (endLine >= layout.lineCount) {
            return PageChunk(pages, offset + startChar)
        }
        line = endLine
    }
    return PageChunk(pages, windowEnd)
}

private fun isParagraphStart(content: AnnotatedString, offset: Int): Boolean {
    return offset <= 0 || content.text[offset - 1] == '\n'
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
        return AnnotatedString(
            padded.text,
            padded.spanStyles,
            listOf(AnnotatedString.Range(ParagraphStyle(), 0, padded.length))
        )
    }
    val styles = padded.paragraphStyles.toMutableList()
    val lastIndex = styles.size - 1
    val last = styles[lastIndex]
    styles[lastIndex] = AnnotatedString.Range(last.item, last.start, last.end + pad.length)
    return AnnotatedString(padded.text, padded.spanStyles, styles)
}

private fun stripFirstLineIndent(text: AnnotatedString): AnnotatedString {
    if (text.isEmpty() || text.paragraphStyles.isEmpty()) return text
    var changed = false
    val newStyles = mutableListOf<AnnotatedString.Range<ParagraphStyle>>()
    for (r in text.paragraphStyles) {
        if (r.start == 0) {
            val indent = r.item.textIndent
            if (indent != null && indent.firstLine.value > 0f) {
                newStyles.add(r.copy(item = r.item.copy(
                    textIndent = TextIndent(
                        firstLine = 0.em,
                        restLine = indent.restLine
                    )
                )))
                changed = true
                continue
            }
        }
        newStyles.add(r)
    }
    if (!changed) return text
    return AnnotatedString(text.text, text.spanStyles, newStyles)
}
