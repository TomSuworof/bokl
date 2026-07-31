package com.salat.bokl

import android.app.Application
import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ReaderBackground(
    val background: Color,
    val textColor: Color
) {
    White(
        background = Color(0xFFFFFFFF),
        textColor = Color(0xFF212121)
    ),
    Brown(
        background = Color(0xFFECE1C9),
        textColor = Color(0xFF634F31)
    ),
    Black(
        background = Color(0xFF000000),
        textColor = Color(0xFFD3D3D3)
    )
}

data class ReaderState(
    val title: String = "",
    val content: AnnotatedString = AnnotatedString(""),
    val coverImagePath: String? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val background: ReaderBackground = ReaderBackground.White
)

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = BookRepository(application)
    private val prefs = application.getSharedPreferences("reader_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        ReaderState(background = loadBackground())
    )
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    fun setBackground(background: ReaderBackground) {
        prefs.edit().putString("background", background.name).apply()
        _state.value = _state.value.copy(background = background)
    }

    fun loadBook(book: Book) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                title = book.title,
                content = AnnotatedString(""),
                coverImagePath = null,
                isLoading = true,
                error = null
            )
            try {
                val result = withContext(Dispatchers.IO) {
                    repository.readBookContent(book)
                }
                _state.value = _state.value.copy(
                    content = result.text,
                    coverImagePath = result.coverImagePath,
                    isLoading = false
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "Error loading book: ${e.message}"
                )
            }
        }
    }

    private fun loadBackground(): ReaderBackground {
        return prefs.getString("background", null)
            ?.let { name -> ReaderBackground.entries.firstOrNull { it.name == name } }
            ?: ReaderBackground.White
    }
}
