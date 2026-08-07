package com.salat.bokl

import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val darkTheme = isSystemInDarkTheme()
            val colorScheme = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                }
                darkTheme -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    val pickerVM: BookPickerViewModel = viewModel()
                    val readerVM: ReaderViewModel = viewModel()
                    val settingsVM: ReaderSettingsViewModel = viewModel()
                    var selectedBook by rememberSaveable(stateSaver = BookSaver) { mutableStateOf<Book?>(null) }

                    val folderPickerLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocumentTree()
                    ) { uri: Uri? ->
                        if (uri != null) pickerVM.onFolderSelected(uri)
                    }
                    LaunchedEffect(Unit) {
                        pickerVM.events.collect { event ->
                            when (event) {
                                is BookPickerEvent.NeedsFolder -> folderPickerLauncher.launch(null)
                            }
                        }
                    }

                    val book = selectedBook
                    if (book == null) {
                        BookPickerScreen(
                            viewModel = pickerVM,
                            onBookSelected = { selectedBook = it }
                        )
                    } else {
                        BookReaderScreen(
                            viewModel = readerVM,
                            settingsViewModel = settingsVM,
                            book = book,
                            onBack = {
                                pickerVM.refreshProgress()
                                selectedBook = null
                            }
                        )
                    }
                }
            }
        }
    }
}

private val BookSaver = Saver<Book?, Any>(
    save = { it?.let { book -> listOf(book.id, book.title, book.uri.toString(), book.format.name) } },
    restore = { value ->
        val saved = value as List<*>
        Book(
            saved[0] as String,
            saved[1] as String,
            Uri.parse(saved[2] as String),
            BookFormat.valueOf(saved[3] as String)
        )
    }
)
