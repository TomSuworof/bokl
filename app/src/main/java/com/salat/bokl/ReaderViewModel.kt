package com.salat.bokl

import android.app.Application
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReaderState(
    val title: String = "",
    val bookId: String? = null,
    val content: AnnotatedString = AnnotatedString(""),
    val coverImagePath: String? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val initialPage: Int = 0,
    val currentPage: Int = 0,
    val totalPages: Int = 0
)

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val app = getApplication<BoklApplication>()
    private val repository = app.bookRepository
    private val progressStore = app.progressStore
    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private var saveJob: Job? = null

    fun loadBook(book: Book) {
        viewModelScope.launch {
            val progress = progressStore.load(book.id)
            _state.value = _state.value.copy(
                bookId = book.id,
                title = book.title,
                content = AnnotatedString(""),
                coverImagePath = null,
                isLoading = true,
                error = null,
                initialPage = progress?.page ?: 0,
                currentPage = 0,
                totalPages = 0
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

    fun onPageChanged(page: Int, totalPages: Int) {
        val bookId = _state.value.bookId ?: return
        _state.value = _state.value.copy(currentPage = page, totalPages = totalPages)
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(PROGRESS_SAVE_DELAY_MILLIS)
            progressStore.save(bookId, page, totalPages)
        }
    }

    override fun onCleared() {
        saveJob?.cancel()
        val state = _state.value
        if (state.totalPages > 0) {
            state.bookId?.let { progressStore.save(it, state.currentPage, state.totalPages) }
        }
        super.onCleared()
    }

    private companion object {
        const val PROGRESS_SAVE_DELAY_MILLIS = 500L
    }
}
