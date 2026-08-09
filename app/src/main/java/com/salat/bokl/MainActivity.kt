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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.net.toUri

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val darkTheme = isSystemInDarkTheme()
            val colorScheme = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(
                        context
                    )
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
                    var selectedBook by rememberSaveable(stateSaver = BookSaver) {
                        mutableStateOf(
                            null
                        )
                    }

                    val folderPickerLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocumentTree()
                    ) { uri: Uri? ->
                        if (uri != null) pickerVM.onFolderSelected(uri)
                    }
                    val pickerState by pickerVM.state.collectAsState()
                    LaunchedEffect(pickerState.isFirstLaunch) {
                        if (pickerState.isFirstLaunch) folderPickerLauncher.launch(null)
                    }

                    val book = selectedBook
                    if (book == null) {
                        BookPickerScreen(
                            viewModel = pickerVM,
                            onBookSelected = { selectedBook = it },
                            onChooseFolder = { folderPickerLauncher.launch(null) }
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
    save = {
        it?.let { book ->
            listOf(
                book.id,
                book.title,
                book.uri.toString(),
                book.format.name
            )
        }
    },
    restore = { value ->
        val saved = value as List<*>
        Book(
            saved[0] as String,
            saved[1] as String,
            (saved[2] as String).toUri(),
            BookFormat.valueOf(saved[3] as String)
        )
    }
)
