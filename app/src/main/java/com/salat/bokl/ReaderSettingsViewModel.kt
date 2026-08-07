package com.salat.bokl

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

class ReaderSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = getApplication<BoklApplication>().readerSettingsPrefs
    private val _background = MutableStateFlow(loadBackground())
    val background: StateFlow<ReaderBackground> = _background.asStateFlow()

    fun setBackground(background: ReaderBackground) {
        prefs.edit().putString("background", background.name).apply()
        _background.value = background
    }

    private fun loadBackground(): ReaderBackground {
        return prefs.getString("background", null)
            ?.let { name -> ReaderBackground.entries.firstOrNull { it.name == name } }
            ?: ReaderBackground.White
    }
}
