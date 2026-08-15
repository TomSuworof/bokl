package com.salat.bokl

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * Optional end-to-end test that assembles a real EPUB (given via the
 * BOKL_TEST_EPUB environment variable) through EpubStyler and verifies that
 * Annotations produces working links. The test embeds no book content and is
 * skipped when the variable is unset or the file is missing.
 */
class AnnotationsIntegrationTest {

    @Test
    fun `full epub produces working annotations`() {
        val epubPath = System.getenv("BOKL_TEST_EPUB") ?: ""
        assumeTrue("BOKL_TEST_EPUB is not set", epubPath.isNotBlank())
        assumeTrue("BOKL_TEST_EPUB file missing: $epubPath", File(epubPath).isFile)

        ZipFile(epubPath).use { zip ->
            val containerXml = zip.readEntry("META-INF/container.xml")
            val opfPath = Regex("""<rootfile[^>]*full-path="([^"]+)"""")
                .find(containerXml)?.groupValues?.get(1) ?: error("no opf path")
            val basePath = opfPath.substringBeforeLast("/", missingDelimiterValue = "")
            val opfXml = zip.readEntry(opfPath)

            val idToHref = Regex("""<item\b[^>]*>""")
                .findAll(opfXml)
                .mapNotNull { tag ->
                    val href = Regex("""href="([^"]+)"""").find(tag.value)?.groupValues?.get(1)
                        ?: return@mapNotNull null
                    val id = Regex("""\bid="([^"]+)"""").find(tag.value)?.groupValues?.get(1)
                        ?: return@mapNotNull null
                    val type = Regex("""media-type="([^"]+)"""").find(tag.value)?.groupValues?.get(1)
                    if (type != "application/xhtml+xml") return@mapNotNull null
                    id to href
                }
                .toMap()
            val hrefs = Regex("""<itemref\s+[^>]*idref="([^"]+)"""")
                .findAll(opfXml).map { idToHref.getValue(it.groupValues[1]) }.toList()

            val cssEntry = zip.entries().asSequence().firstOrNull { it.name.endsWith(".css") }
            val cssText = cssEntry?.let { zip.readEntry(it.name) }

            val chapters = mutableListOf<EpubChapter>()
            var book = AnnotatedString("")
            for (href in hrefs) {
                val path = resolve(basePath, href)
                val entry = zip.getEntry(path) ?: continue
                val html = zip.getInputStream(entry).bufferedReader().readText()
                chapters.add(EpubChapter(path, html))
                val chapter = EpubStyler.renderChapter(
                    html = html,
                    loadCss = { cssText },
                    isFirstChapter = book.isEmpty()
                )
                if (chapter.isNotEmpty()) book = concat(book, chapter)
            }

            val notes = EpubAnnotations.extractNotes(chapters)
            val info = Annotations.analyze(book, notes)
            assertTrue("expected some notes", notes.isNotEmpty())
            assertTrue("expected some references", info.references.isNotEmpty())
            assertTrue(info.references.all { it.number in info.notes })
            assertEquals(info.references.size, info.references.distinctBy { it.number }.size)
        }
    }

    private fun concat(a: AnnotatedString, b: AnnotatedString): AnnotatedString {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        return AnnotatedString(
            text = a.text + b.text,
            spanStyles = a.spanStyles + b.spanStyles.offsetBy(a.length),
            paragraphStyles = a.paragraphStyles + b.paragraphStyles.offsetBy(a.length)
        )
    }

    private fun <T> List<AnnotatedString.Range<T>>.offsetBy(n: Int) =
        map { AnnotatedString.Range(it.item, it.start + n, it.end + n) }

    private fun ZipFile.readEntry(name: String): String {
        val entry = getEntry(name) ?: error("missing zip entry $name")
        return getInputStream(entry).bufferedReader().use { it.readText() }
    }

    private fun resolve(basePath: String, href: String): String {
        val segments = (if (basePath.isEmpty()) href else "$basePath/$href").split("/")
        val result = mutableListOf<String>()
        for (segment in segments) {
            when (segment) {
                "", "." -> {}
                ".." -> if (result.isNotEmpty()) result.removeAt(result.size - 1)
                else -> result.add(segment)
            }
        }
        return result.joinToString("/")
    }
}
