package com.salat.bokl

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** A spine chapter with its resolved path inside the EPUB archive (e.g. `OPS/ch2.xhtml`). */
data class EpubChapter(
    val path: String,
    val html: String
)

/**
 * Extracts the note texts from an EPUB's original chapter markup, before it is
 * flattened into the rendered text. EPUBs express annotations as links in the
 * body (`<a href="notes.xhtml#id107">[1]</a>`) whose targets (`<span id="id107">`)
 * contain the full note text, so the mapping number -> text can be read directly
 * from the DOM instead of being inferred from the flattened text.
 *
 * Each link is resolved in the chapter its href points to, because ids are only
 * unique per document; body chapters can reuse the same low ids for large section
 * containers that must not be mistaken for notes.
 */
internal object EpubAnnotations {

    private val NUMBER_REGEX = Regex("""\d+""")
    private val NUMBER_ONLY = Regex("""\d+[.)]?""")
    private val INLINE_NUMBER_PREFIX = Regex("""^\d+[.)]\s*""")

    fun extractNotes(chapters: List<EpubChapter>): Map<Int, String> {
        if (chapters.isEmpty()) return emptyMap()
        val docs = chapters.associate { it.path to Jsoup.parse(it.html) }

        val targetToNumber = mutableMapOf<String, Int>()
        for (chapter in chapters) {
            val doc = docs.getValue(chapter.path)
            for (a in doc.select("a[href]")) {
                val href = a.attr("href")
                val hash = href.indexOf('#')
                if (hash < 0) continue
                val id = href.substring(hash + 1)
                if (id.isEmpty()) continue
                val number = NUMBER_REGEX.find(a.text())?.value?.toIntOrNull() ?: continue
                val pathPart = href.substring(0, hash)
                val targetPath = if (pathPart.isEmpty()) {
                    chapter.path
                } else {
                    resolveEpubPath(chapter.path.substringBeforeLast("/", missingDelimiterValue = ""), pathPart)
                }
                targetToNumber["$targetPath#$id"] = number
            }
        }
        if (targetToNumber.isEmpty()) return emptyMap()

        val notes = sortedMapOf<Int, String>()
        for ((key, number) in targetToNumber) {
            val hash = key.lastIndexOf('#')
            val path = key.substring(0, hash)
            val id = key.substring(hash + 1)
            val element = docs[path]?.getElementById(id)
                ?: docs.values.firstNotNullOfOrNull { it.getElementById(id) }
                ?: continue
            val text = noteText(element) ?: continue
            notes[number] = text
        }
        return notes
    }

    private fun noteText(element: Element): String? {
        val clone = element.clone()
        clone.children().firstOrNull { it.text().trim().matches(NUMBER_ONLY) }?.remove()
        val text = clone.text().trim()
            .replaceFirst(INLINE_NUMBER_PREFIX, "")
            .trim()
        return text.ifEmpty { null }
    }
}
