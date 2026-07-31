package com.salat.bokl

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubStylerTest {

    private fun render(html: String): androidx.compose.ui.text.AnnotatedString {
        return EpubStyler.renderChapter(html) { null }
    }

    @Test
    fun `semantic tags produce bold and italic spans`() {
        val result = render("<p>Normal <b>bold</b> and <i>italic</i> text.</p>")

        assertTrue(result.text.contains("bold"))
        assertTrue(result.text.contains("italic"))

        val bold = result.spanStyles.firstOrNull {
            it.item.fontWeight == FontWeight.Bold
        }
        val italic = result.spanStyles.firstOrNull {
            it.item.fontStyle == FontStyle.Italic
        }
        assertEquals("bold", result.text.substring(bold!!.start, bold.end))
        assertEquals("italic", result.text.substring(italic!!.start, italic.end))
    }

    @Test
    fun `css classes drive chapter title and cursive`() {
        val html = """
            <html><head>
            <style>
            .title { font-size: 1.8em; font-weight: bold; text-align: center; }
            .subtitle { font-style: italic; }
            .p1 { text-align: justify; }
            </style>
            </head><body class="z">
            <div class="title"><p class="p">Глава 1</p></div>
            <p class="subtitle">Неофициальный перевод</p>
            <p class="p1">Обычный текст.</p>
            </body></html>
        """.trimIndent()
        val result = render(html)

        val titleStart = result.text.indexOf("Глава 1")
        assertTrue(titleStart >= 0)
        val titleStyle = result.spanStyles.filter {
            it.start <= titleStart && titleStart < it.end
        }
        assertTrue(titleStyle.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(titleStyle.any { it.item.fontSize != androidx.compose.ui.unit.TextUnit.Unspecified })

        val center = result.paragraphStyles.firstOrNull {
            it.start <= titleStart && titleStart < it.end
        }
        assertEquals(TextAlign.Center, center!!.item.textAlign)

        val subStart = result.text.indexOf("Неофициальный перевод")
        val subStyle = result.spanStyles.first {
            it.start <= subStart && subStart < it.end
        }
        assertEquals(FontStyle.Italic, subStyle.item.fontStyle)

        assertTrue(result.paragraphStyles.any {
            it.item.textAlign == TextAlign.Justify
        })
    }

    @Test
    fun `css text-indent sets paragraph first line indent`() {
        val result = render(
            """<html><head><style>.p1 { text-indent: 1.5em; }</style></head>
            <body><p class="p1">Обычный текст.</p></body></html>"""
        )
        val start = result.text.indexOf("Обычный текст")
        val style = result.paragraphStyles.first { it.start <= start && start < it.end }
        assertEquals(1.5f, style.item.textIndent?.firstLine?.value)
    }

    @Test
    fun `p paragraphs get default indent unless css overrides`() {
        val result = render(
            """<html><head><style>.plain { text-indent: 0; }</style></head>
            <body><p>Обычный.</p><p class="plain">Без отступа.</p></body></html>"""
        )
        val indentedStart = result.text.indexOf("Обычный.")
        val indented = result.paragraphStyles.first { it.start <= indentedStart && indentedStart < it.end }
        assertEquals(1.5f, indented.item.textIndent?.firstLine?.value)

        val plainStart = result.text.indexOf("Без отступа.")
        val plain = result.paragraphStyles.first { it.start <= plainStart && plainStart < it.end }
        assertEquals(0f, plain.item.textIndent?.firstLine?.value)
    }

    @Test
    fun `single css rule applies em font size once`() {
        val css = ".title3 { font-size: 1.3em; font-weight: bold; text-align: left; } .p { text-align: justify; }"
        val result = render(
            """<html><head><style>$css</style></head><body><div class="title3"><p class="p">V</p></div></body></html>"""
        )
        val start = result.text.indexOf("V")
        val size = result.spanStyles.filter { it.start <= start && start < it.end }
            .first { it.item.fontSize != androidx.compose.ui.unit.TextUnit.Unspecified }
        assertEquals(1.3f, size.item.fontSize.value, 0.01f)
    }

    @Test
    fun `duplicate css rules apply em font size once`() {
        val css = ".title3 { font-size: 1.3em; font-weight: bold; text-align: left; } .p { text-align: justify; }"
        val result = render(
            """<html><head><style>$css</style><style>$css</style></head><body><div class="title3"><p class="p">V</p></div></body></html>"""
        )
        val start = result.text.indexOf("V")
        val size = result.spanStyles.filter { it.start <= start && start < it.end }
            .first { it.item.fontSize != androidx.compose.ui.unit.TextUnit.Unspecified }
        assertEquals(1.3f, size.item.fontSize.value, 0.01f)
    }

    @Test
    fun `class rule does not match descendants`() {
        val result = render(
            """<html><head><style>.title3 { font-size: 1.3em; } .p { } </style></head><body><div class="title3"><p class="p">V</p></div></body></html>"""
        )
        val start = result.text.indexOf("V")
        val size = result.spanStyles.filter { it.start <= start && start < it.end }
            .first { it.item.fontSize != androidx.compose.ui.unit.TextUnit.Unspecified }
        assertEquals(1.3f, size.item.fontSize.value, 0.01f)
    }

    @Test
    fun `br after paragraph does not create blank line between paragraphs`() {
        val result = render("<p>First.</p><br><p>Second.</p>")
        assertEquals("First.\nSecond.\n", result.text)
    }

    @Test
    fun `consecutive br tags collapse to single line break`() {
        val result = render("<p>a<br><br>b</p>")
        assertEquals("a\nb\n", result.text)
    }

    @Test
    fun `paragraphs are separated by a single newline`() {
        val result = render("<p>One.</p>\n\n<p>Two.</p>\n<p>Three.</p>")
        assertEquals("One.\nTwo.\nThree.\n", result.text)
    }

    @Test
    fun `every paragraph newline is covered by a paragraph style`() {
        val result = render("<p>One.</p><p>Two.</p><p>Three.</p>")
        assertEquals("One.\nTwo.\nThree.\n", result.text)
        for (i in result.text.indices) {
            if (result.text[i] == '\n') {
                val covered = result.paragraphStyles.any { it.start <= i && i < it.end }
                assertTrue("newline at $i is not covered by any paragraph style", covered)
            }
        }
    }

    @Test
    fun `paragraph style ranges are contiguous without gaps`() {
        val result = render("<p>One.</p><p>Two.</p><p>Three.</p>")
        val sorted = result.paragraphStyles.sortedBy { it.start }
        for (i in 0 until sorted.lastIndex) {
            assertEquals(sorted[i].end, sorted[i + 1].start)
        }
        assertEquals("One.\nTwo.\nThree.\n".length, sorted.last().end)
    }

    @Test
    fun `emphasis with underline and nested bold italic`() {
        val html = "<p>У <u>подчёркнуто</u> и <b><i>жирный курсив</i></b>.</p>"
        val result = render(html)

        val underStart = result.text.indexOf("подчёркнуто")
        assertTrue(result.spanStyles.any {
            it.start <= underStart && underStart < it.end &&
                it.item.textDecoration == TextDecoration.Underline
        })

        val comboStart = result.text.indexOf("жирный курсив")
        val combo = result.spanStyles.filter {
            it.start <= comboStart && comboStart < it.end
        }
        assertTrue(combo.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(combo.any { it.item.fontStyle == FontStyle.Italic })
    }
}
