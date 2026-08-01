package com.salat.bokl

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookReaderScreen(
    viewModel: ReaderViewModel,
    book: Book?,
    onBack: () -> Unit
) {
    LaunchedEffect(book) {
        if (book != null) {
            viewModel.loadBook(book)
        }
    }

    val state by viewModel.state.collectAsState()
    var showSettings by remember { mutableStateOf(false) }

    val background = state.background.background
    val textColor = state.background.textColor

    val textStyle = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 18.sp,
        textAlign = TextAlign.Justify,
        color = textColor
    )

    Scaffold(
        containerColor = background,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                        DropdownMenu(
                            expanded = showSettings,
                            onDismissRequest = { showSettings = false }
                        ) {
                            ReaderBackground.entries.forEach { option ->
                                BackgroundOptionRow(
                                    option = option,
                                    selected = option == state.background,
                                    onClick = {
                                        viewModel.setBackground(option)
                                        showSettings = false
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = background,
                    titleContentColor = textColor,
                    navigationIconContentColor = textColor,
                    actionIconContentColor = textColor
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                    PaginatedReader(
                        content = state.content,
                        coverImagePath = state.coverImagePath,
                        textStyle = textStyle,
                        pageCounterColor = textColor.copy(alpha = 0.6f),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp, vertical = 16.dp)
                    )
                }
                else -> {
                    Text(
                        text = state.content,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp, vertical = 16.dp)
                            .verticalScroll(rememberScrollState()),
                        style = textStyle
                    )
                }
            }
        }
    }
}

@Composable
private fun PaginatedReader(
    content: AnnotatedString,
    coverImagePath: String?,
    textStyle: TextStyle,
    pageCounterColor: Color,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    var readingSize by remember { mutableStateOf(IntSize.Zero) }
    val pages = remember(content) { mutableStateListOf<AnnotatedString>() }
    val coverOffset = if (coverImagePath != null) 1 else 0

    LaunchedEffect(content, textStyle, readingSize) {
        pages.clear()
        if (content.isEmpty()) return@LaunchedEffect
        if (readingSize.width <= 0 || readingSize.height <= 0) return@LaunchedEffect

        var offset = 0
        var chunkCount = 0
        while (offset < content.length) {
            val chunk = buildPageChunk(
                content = content,
                offset = offset,
                measurer = textMeasurer,
                textStyle = textStyle,
                widthPx = readingSize.width,
                heightPx = readingSize.height
            )
            pages.addAll(chunk.pages)
            if (chunk.nextOffset <= offset || chunk.pages.isEmpty()) {
                if (chunk.pages.isEmpty() && offset < content.length) {
                    pages.add(content.subSequence(offset, content.length))
                }
                break
            }
            offset = chunk.nextOffset
            chunkCount++
            yield()
            withFrameNanos { }
        }
    }

    val totalPages = pages.size + coverOffset
    val pageCount by rememberUpdatedState(totalPages)
    val pagerState = rememberPagerState(pageCount = { pageCount })

    LaunchedEffect(content) {
        pagerState.scrollToPage(0)
    }
    LaunchedEffect(totalPages) {
        if (pagerState.currentPage >= totalPages) {
            pagerState.scrollToPage((totalPages - 1).coerceAtLeast(0))
        }
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
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
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(totalPages) {
                                    val slop = viewConfiguration.touchSlop
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        val up = waitForUpOrCancellation()
                                        if (up != null &&
                                            !up.isConsumed &&
                                            (up.position - down.position).getDistance() < slop
                                        ) {
                                            val target = when {
                                                down.position.x < size.width / 3f -> pagerState.currentPage - 1
                                                down.position.x > size.width * 2f / 3f -> pagerState.currentPage + 1
                                                else -> null
                                            }
                                            if (target != null) {
                                                scope.launch {
                                                    pagerState.animateScrollToPage(
                                                        target.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                        ) {
                            if (coverImagePath != null && page == 0) {
                                CoverPage(
                                    imagePath = coverImagePath,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                SelectionContainer {
                                    Text(
                                        text = pages.getOrElse(page - coverOffset) { AnnotatedString("") },
                                        modifier = Modifier.fillMaxSize(),
                                        style = textStyle
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (totalPages == 0) "" else "${pagerState.currentPage + 1} / $totalPages",
                style = MaterialTheme.typography.labelMedium,
                color = pageCounterColor
            )
        }
    }
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

@Composable
private fun BackgroundOptionRow(
    option: ReaderBackground,
    selected: Boolean,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(option.background)
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = option.textColor
                    )
                }
            }
        },
        onClick = onClick
    )
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
            pages.add(
                if (isParagraphStart(content, pageStartOffset)) {
                    pageText
                } else {
                    stripFirstLineIndent(pageText)
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
