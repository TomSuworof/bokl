package com.salat.bokl

import android.app.Application
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReaderState(
    val title: String = "",
    val content: AnnotatedString = AnnotatedString(""),
    val coverImagePath: String? = null,
    val isLoading: Boolean = true,
    val error: String? = null
)

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = BookRepository(application)
    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    fun loadBook(book: Book) {
        viewModelScope.launch {
            _state.value = ReaderState(title = book.title, isLoading = true)
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
}
