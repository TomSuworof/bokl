package com.salat.bokl

import android.content.SharedPreferences
import kotlin.math.roundToInt

data class ReadingProgress(
    val page: Int,
    val totalPages: Int
) {
    val fraction: Float
        get() = if (totalPages <= 0) 0f
        else (page + 1).coerceIn(1, totalPages).toFloat() / totalPages

    val percent: Int
        get() = (fraction * 100).roundToInt().coerceIn(0, 100)
}

class ReadingProgressStore(private val prefs: SharedPreferences) {

    fun save(bookId: String, page: Int, totalPages: Int) {
        prefs.edit()
            .putInt(pageKey(bookId), page)
            .putInt(totalKey(bookId), totalPages)
            .apply()
    }

    fun load(bookId: String): ReadingProgress? {
        val pageKey = pageKey(bookId)
        val totalKey = totalKey(bookId)
        if (!prefs.contains(pageKey) || !prefs.contains(totalKey)) return null
        return ReadingProgress(
            page = prefs.getInt(pageKey, 0),
            totalPages = prefs.getInt(totalKey, 0)
        )
    }

    private fun pageKey(bookId: String) = "progress_page_$bookId"
    private fun totalKey(bookId: String) = "progress_total_$bookId"
}
