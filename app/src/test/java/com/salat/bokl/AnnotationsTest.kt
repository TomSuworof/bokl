package com.salat.bokl

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationsTest {

    @Test
    fun `finds references with matching notes`() {
        val book = AnnotatedString("Body [1] and [2].")
        val notes = mapOf(1 to "First note.", 2 to "Second note.")
        val info = Annotations.analyze(book, notes)

        assertEquals(setOf(1, 2), info.notes.keys)
        assertEquals(2, info.references.size)
        val body = "Body [1] and [2]."
        assertEquals(body.indexOf("[1]"), info.references[0].start)
        assertEquals(body.indexOf("[2]"), info.references[1].start)
    }

    @Test
    fun `ignores references without a matching note`() {
        val book = AnnotatedString("Body [1] and [99].")
        val info = Annotations.analyze(book, mapOf(1 to "Only note."))

        assertEquals(1, info.references.size)
        assertEquals(1, info.references[0].number)
    }

    @Test
    fun `returns no references when there are no notes`() {
        val book = AnnotatedString("Body [1].")
        val info = Annotations.analyze(book, emptyMap())

        assertTrue(info.notes.isEmpty())
        assertTrue(info.references.isEmpty())
    }

    @Test
    fun `matches references with surrounding whitespace`() {
        val book = AnnotatedString("Body [ 1 ] and [2].")
        val info = Annotations.analyze(book, mapOf(1 to "Note one.", 2 to "Note two."))

        assertEquals(2, info.references.size)
        assertEquals(1, info.references[0].number)
        assertEquals(2, info.references[1].number)
    }

    @Test
    fun `does not link bare numbers without brackets`() {
        val book = AnnotatedString("Body 1 and 2.")
        val info = Annotations.analyze(book, mapOf(1 to "Note.", 2 to "Note."))

        assertTrue(info.references.isEmpty())
    }

    @Test
    fun `applyLinks adds clickable links at reference offsets`() {
        val book = AnnotatedString("Body [1] and [2].")
        val info = Annotations.analyze(book, mapOf(1 to "Note one.", 2 to "Note two."))
        var opened: Int? = null
        val linked = Annotations.applyLinks(book, info) { opened = it }

        val links = linked.getLinkAnnotations(0, linked.length)
        assertEquals(2, links.size)
        val body = "Body [1] and [2]."
        assertEquals(body.indexOf("[1]"), links[0].start)
        assertEquals(body.indexOf("[2]"), links[1].start)
        assertEquals("[1]", linked.text.substring(links[0].start, links[0].end))
        assertEquals("[2]", linked.text.substring(links[1].start, links[1].end))

        val first = links[0].item as LinkAnnotation.Clickable
        first.linkInteractionListener?.onClick(first)
        assertEquals(1, opened)
    }

    @Test
    fun `applyLinks returns text unchanged when there are no references`() {
        val book = AnnotatedString("No references.")
        val linked = Annotations.applyLinks(book, AnnotationInfo(emptyMap(), emptyList())) {}

        assertEquals(book, linked)
    }
}
