package com.salat.bokl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubAnnotationsTest {

    private fun ch(path: String, html: String) = EpubChapter(path, html)

    @Test
    fun `extracts notes from anchor targets and strips the number paragraph`() {
        val body = ch(
            "OPS/ch1.xhtml",
            """<html><body>
                <p>Body with <a href="ch2.xhtml#n1">[1]</a> and <a href="ch2.xhtml#n2">[2]</a>.</p>
            </body></html>"""
        )
        val notes = ch(
            "OPS/ch2.xhtml",
            """<html><body>
                <h2>Примечания</h2>
                <span id="n1"><p>1</p><p>First note text.</p></span>
                <span id="n2"><p>2</p><p>Second note text.</p></span>
            </body></html>"""
        )

        val result = EpubAnnotations.extractNotes(listOf(body, notes))

        assertEquals(2, result.size)
        assertEquals("First note text.", result[1])
        assertEquals("Second note text.", result[2])
    }

    @Test
    fun `joins multi paragraph notes with spaces`() {
        val notes = ch(
            "OPS/notes.xhtml",
            """<html><body>
                <span id="n1"><p>1</p><p>First paragraph.</p><p>Second paragraph.</p></span>
            </body></html>"""
        )
        val body = ch("OPS/ch1.xhtml", """<a href="notes.xhtml#n1">[1]</a>""")

        val result = EpubAnnotations.extractNotes(listOf(body, notes))

        assertEquals("First paragraph. Second paragraph.", result[1])
    }

    @Test
    fun `strips an inline number marker without a separate paragraph`() {
        val notes = ch(
            "OPS/notes.xhtml",
            """<html><body>
                <span id="n1">1. Inline note text.</span>
            </body></html>"""
        )
        val body = ch("OPS/ch1.xhtml", """<a href="notes.xhtml#n1">[1]</a>""")

        val result = EpubAnnotations.extractNotes(listOf(body, notes))

        assertEquals("Inline note text.", result[1])
    }

    @Test
    fun `matches same chapter and cross chapter hrefs`() {
        val notes = ch("OPS/ch2.xhtml", """<html><body><p id="n5">5. Same chapter note.</p></body></html>""")
        val body = ch("OPS/ch1.xhtml", """<a href="ch2.xhtml#n5">[5]</a><a href="#n5">[6]</a>""")

        val result = EpubAnnotations.extractNotes(listOf(body, notes))

        assertEquals("Same chapter note.", result[5])
        assertEquals("Same chapter note.", result[6])
    }

    @Test
    fun `ignores links without a numeric text`() {
        val body = ch("OPS/ch1.xhtml", """<a href="notes.xhtml#n1">[1]</a><a href="notes.xhtml#n2">see note</a>""")
        val notes = ch("OPS/notes.xhtml", """<span id="n1">First.</span><span id="n2">Second.</span>""")

        val result = EpubAnnotations.extractNotes(listOf(body, notes))

        assertEquals(mapOf(1 to "First."), result)
    }

    @Test
    fun `skips targets that are not found in any chapter`() {
        val body = ch("OPS/ch1.xhtml", """<a href="notes.xhtml#n1">[1]</a>""")
        val notes = ch("OPS/ch2.xhtml", """<html><body><p>no notes here</p></body></html>""")

        val result = EpubAnnotations.extractNotes(listOf(body, notes))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `returns empty when there are no annotation links`() {
        val body = ch("OPS/ch1.xhtml", "<html><body><p>Plain text.</p></body></html>")

        val result = EpubAnnotations.extractNotes(listOf(body))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `resolves links in the chapter the href points to even when ids are reused`() {
        val body = ch(
            "OPS/ch1.xhtml",
            """<html><body>
                <span id="id3">BODY CONTAINER WITH THE WHOLE CHAPTER TEXT</span>
                <p>Text with <a href="ch2.xhtml#id3">[96]</a>.</p>
            </body></html>"""
        )
        val notes = ch(
            "OPS/ch2.xhtml",
            """<html><body>
                <span id="id3"><p>96</p><p>Real note 96 text.</p></span>
            </body></html>"""
        )

        val result = EpubAnnotations.extractNotes(listOf(body, notes))

        assertEquals(mapOf(96 to "Real note 96 text."), result)
    }
}
