package com.salat.bokl

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
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

    val textStyle = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 18.sp,
        textAlign = TextAlign.Justify,
        color = MaterialTheme.colorScheme.onSurface
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
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
            if (chunkCount % 2 == 0) yield()
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (totalPages == 0) "" else "${pagerState.currentPage + 1} / $totalPages",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
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
    val layout = measurer.measure(
        text = content.subSequence(offset, windowEnd),
        style = textStyle,
        constraints = Constraints(maxWidth = widthPx)
    )
    if (layout.lineCount == 0) return PageChunk(emptyList(), windowEnd)

    val windowText = layout.layoutInput.text
    val pages = mutableListOf<AnnotatedString>()
    var line = 0
    while (line < layout.lineCount) {
        val pageTop = layout.getLineTop(line)
        val startChar = layout.getLineStart(line)
        var endLine = line
        while (endLine < layout.lineCount && layout.getLineBottom(endLine) - pageTop <= heightPx) {
            endLine++
        }
        if (endLine <= line) endLine = line + 1

        if (endLine >= layout.lineCount) {
            if (windowEnd >= content.length) {
                pages.add(windowText.subSequence(startChar, windowText.length))
            }
            return PageChunk(pages, offset + startChar)
        }
        val endChar = layout.getLineStart(endLine)
        pages.add(windowText.subSequence(startChar, endChar))
        line = endLine
    }
    return PageChunk(pages, windowEnd)
}
