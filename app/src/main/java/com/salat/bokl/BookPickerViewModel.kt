package com.salat.bokl

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
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
    val progress: Map<String, ReadingProgress> = emptyMap(),
    val isLoading: Boolean = true,
    val folderUri: Uri? = null,
    val isFirstLaunch: Boolean = false,
    val error: String? = null
)

class BookPickerViewModel(application: Application) : AndroidViewModel(application) {
    private val app = getApplication<BoklApplication>()
    private val repository = app.bookRepository
    private val prefs = app.pickerPrefs
    private val progressStore = app.progressStore
    private val _state = MutableStateFlow(BookPickerState())
    val state: StateFlow<BookPickerState> = _state.asStateFlow()

    private val folderKey = "folder_uri"
    private val takeFlagsKey = "folder_flags"

    init {
        checkFolder()
    }

    private fun checkFolder() {
        val uriString = prefs.getString(folderKey, null)
        if (uriString == null) {
            _state.value = _state.value.copy(isFirstLaunch = true, isLoading = false)
        } else {
            val uri = uriString.toUri()
            if (isGrantPersisted(uri)) {
                _state.value = _state.value.copy(folderUri = uri)
                loadBooks(uri)
            } else {
                prefs.edit { remove(folderKey) }
                _state.value = _state.value.copy(isFirstLaunch = true, isLoading = false)
            }
        }
    }

    fun onFolderSelected(uri: Uri) {
        val persisted = persistPermissions(uri)
        prefs.edit {
            remove(folderKey)
            if (persisted) {
                putString(folderKey, uri.toString())
                    .putInt(takeFlagsKey, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        _state.value = _state.value.copy(folderUri = uri, isFirstLaunch = false)
        loadBooks(uri)
    }

    fun retry() {
        _state.value = _state.value.copy(error = null)
        checkFolder()
    }

    private fun persistPermissions(uri: Uri): Boolean {
        val context = getApplication<Application>()
        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        return try {
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun isGrantPersisted(uri: Uri): Boolean {
        val context = getApplication<Application>()
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
    }

    private fun loadBooks(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val books = withContext(Dispatchers.IO) {
                    repository.listBooks(uri)
                }
                val progress = books.mapNotNull { book ->
                    progressStore.load(book.id)?.let { book.id to it }
                }.toMap()
                _state.value =
                    _state.value.copy(books = books, progress = progress, isLoading = false)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "Failed to load books: ${e.message}"
                )
            }
        }
    }

    fun refreshProgress() {
        val books = _state.value.books
        if (books.isEmpty()) return
        val progress = books.mapNotNull { book ->
            progressStore.load(book.id)?.let { book.id to it }
        }.toMap()
        _state.value = _state.value.copy(progress = progress)
    }

    suspend fun loadCover(book: Book): String? {
        return withContext(Dispatchers.IO) {
            repository.loadCover(book)
        }
    }
}
