package com.salat.bokl

import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

class MainActivity : ComponentActivity() {

    private var pendingFolderPicker = false
    private var pickerViewModel: BookPickerViewModel? = null

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            pendingFolderPicker = false
            pickerViewModel?.onFolderSelected(uri)
        }
    }

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
                    val navController = rememberNavController()
                    val pickerVM: BookPickerViewModel = viewModel()
                    val readerVM: ReaderViewModel = viewModel()
                    val settingsVM: ReaderSettingsViewModel = viewModel()
                    var selectedBook by remember { mutableStateOf<Book?>(null) }

                    LaunchedEffect(Unit) {
                        pickerViewModel = pickerVM
                    }

                    val pickerEvent by pickerVM.events.collectAsState()
                    LaunchedEffect(pickerEvent) {
                        when (pickerEvent) {
                            is BookPickerEvent.NeedsFolder -> {
                                if (!pendingFolderPicker) {
                                    pendingFolderPicker = true
                                    folderPickerLauncher.launch(null)
                                }
                                pickerVM.clearEvent()
                            }
                            else -> {}
                        }
                    }

                    NavHost(
                        navController = navController,
                        startDestination = "picker"
                    ) {
                        composable("picker") {
                            BookPickerScreen(
                                viewModel = pickerVM,
                                onBookSelected = { book ->
                                    selectedBook = book
                                    navController.navigate("reader")
                                }
                            )
                        }
                        composable("reader") {
                            BookReaderScreen(
                                viewModel = readerVM,
                                settingsViewModel = settingsVM,
                                book = selectedBook,
                                onBack = {
                                    pickerVM.refreshProgress()
                                    selectedBook = null
                                    navController.popBackStack()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
