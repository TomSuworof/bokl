package com.salat.bokl.pageturn

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun PageSurface(
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