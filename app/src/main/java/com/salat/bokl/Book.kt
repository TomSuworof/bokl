package com.salat.bokl

import android.net.Uri

data class Book(
    val id: String,
    val title: String,
    val uri: Uri,
    val format: BookFormat
)

enum class BookFormat {
    TXT, EPUB, UNSUPPORTED
}
