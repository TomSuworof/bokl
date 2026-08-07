package com.salat.bokl

import android.app.Application
import android.content.Context
import android.content.SharedPreferences

class BoklApplication : Application() {

    val bookRepository: BookRepository by lazy { BookRepository(this) }

    val progressStore: ReadingProgressStore by lazy {
        ReadingProgressStore(getSharedPreferences("reading_progress", Context.MODE_PRIVATE))
    }

    val pickerPrefs: SharedPreferences by lazy {
        getSharedPreferences("bokl", Context.MODE_PRIVATE)
    }

    val readerSettingsPrefs: SharedPreferences by lazy {
        getSharedPreferences("reader_settings", Context.MODE_PRIVATE)
    }
}
