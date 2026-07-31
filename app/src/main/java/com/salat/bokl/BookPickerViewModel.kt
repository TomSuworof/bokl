package com.salat.bokl

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BookPickerState(
    val books: List<Book> = emptyList(),
    val isLoading: Boolean = true,
    val folderUri: Uri? = null,
    val isFirstLaunch: Boolean = false,
    val error: String? = null
)

sealed class BookPickerEvent {
    data class FolderSelected(val uri: Uri) : BookPickerEvent()
    data object NeedsFolder : BookPickerEvent()
}

class BookPickerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = BookRepository(application)
    private val prefs = application.getSharedPreferences("bokl", 0)
    private val _state = MutableStateFlow(BookPickerState())
    val state: StateFlow<BookPickerState> = _state.asStateFlow()

    private val _events = MutableStateFlow<BookPickerEvent?>(null)
    val events: StateFlow<BookPickerEvent?> = _events.asStateFlow()

    private val folderKey = "folder_uri"
    private val takeFlagsKey = "folder_flags"

    init {
        checkFolder()
    }

    private fun checkFolder() {
        val uriString = prefs.getString(folderKey, null)
        if (uriString == null) {
            _state.value = _state.value.copy(isFirstLaunch = true, isLoading = false)
            _events.value = BookPickerEvent.NeedsFolder
        } else {
            val uri = Uri.parse(uriString)
            persistPermissions(uri)
            _state.value = _state.value.copy(folderUri = uri)
            loadBooks(uri)
        }
    }

    fun onFolderSelected(uri: Uri) {
        prefs.edit()
            .putString(folderKey, uri.toString())
            .putInt(takeFlagsKey, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply()
        persistPermissions(uri)
        _state.value = _state.value.copy(folderUri = uri, isFirstLaunch = false)
        loadBooks(uri)
    }

    fun retry() {
        _state.value = _state.value.copy(error = null)
        checkFolder()
    }

    private fun persistPermissions(uri: Uri) {
        val context = getApplication<Application>()
        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, takeFlags)
    }

    private fun loadBooks(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val books = withContext(Dispatchers.IO) {
                    repository.listBooks(uri)
                }
                _state.value = _state.value.copy(books = books, isLoading = false)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "Failed to load books: ${e.message}"
                )
            }
        }
    }

    fun clearEvent() {
        _events.value = null
    }

    suspend fun loadCover(book: Book): String? {
        return withContext(Dispatchers.IO) {
            repository.loadCover(book)
        }
    }
}
