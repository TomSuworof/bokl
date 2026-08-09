package com.salat.bokl

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.isSpecified
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubStylerTest {

    private fun render(
        html: String,
        isFirstChapter: Boolean = true
    ): androidx.compose.ui.text.AnnotatedString {
        return EpubStyler.renderChapter(html, { null }, isFirstChapter)
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
        assertEquals(FontStyle.Italic.value, subStyle.item.fontStyle?.value)

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
        val indented =
            result.paragraphStyles.first { it.start <= indentedStart && indentedStart < it.end }
        assertEquals(1.5f, indented.item.textIndent?.firstLine?.value)

        val plainStart = result.text.indexOf("Без отступа.")
        val plain = result.paragraphStyles.first { it.start <= plainStart && plainStart < it.end }
        assertEquals(0f, plain.item.textIndent?.firstLine?.value)
    }

    @Test
    fun `single css rule applies em font size once`() {
        val css =
            ".title3 { font-size: 1.3em; font-weight: bold; text-align: left; } .p { text-align: justify; }"
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
        val css =
            ".title3 { font-size: 1.3em; font-weight: bold; text-align: left; } .p { text-align: justify; }"
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
    fun `br between paragraphs does not add a blank line`() {
        val result = render("<p>First.</p><br><p>Second.</p>")
        assertEquals("First.Second.", result.text)
        assertEquals(2, result.paragraphStyles.size)
    }

    @Test
    fun `consecutive br tags collapse to single line break`() {
        val result = render("<p>a<br><br>b</p>")
        assertEquals("a\nb", result.text)
    }

    @Test
    fun `paragraphs are not separated by newlines`() {
        val result = render("<p>One.</p>\n\n<p>Two.</p>\n<p>Three.</p>")
        assertEquals("One.Two.Three.", result.text)
        assertEquals(3, result.paragraphStyles.size)
    }

    @Test
    fun `no paragraph style range ends with a newline`() {
        val result = render("<p>One.</p><p>Two.</p><p>Three.</p>")
        assertEquals("One.Two.Three.", result.text)
        for (r in result.paragraphStyles) {
            assertTrue("paragraph range [$r) ends with newline", r.start < r.end)
            assertTrue(
                "paragraph range [$r) ends with newline",
                result.text[r.end - 1] != '\n'
            )
        }
    }

    @Test
    fun `paragraph style ranges are contiguous without gaps`() {
        val result = render("<p>One.</p><p>Two.</p><p>Three.</p>")
        val sorted = result.paragraphStyles.sortedBy { it.start }
        for (i in 0 until sorted.lastIndex) {
            assertEquals(sorted[i].end, sorted[i + 1].start)
        }
        assertEquals("One.Two.Three.".length, sorted.last().end)
    }

    @Test
    fun `title margins become spacing without newlines`() {
        val css = """
            .title2 { font-size: 1.5em; font-weight: bold; margin: 1em 0px 0.5em 1.563em; }
            .p { margin: 0px 0px 0.5em 0px; text-indent: 0px; }
            .p1 { margin: 0px; text-align: justify; text-indent: 1.5em; }
        """.trimIndent()
        val html = """<html><head><style>$css</style></head><body>
            <div class="title2"><p class="p">Chapter</p></div>
            <p class="p1">Body text.</p>
        </body></html>"""
        val result = render(html, isFirstChapter = false)

        assertTrue(result.text.none { it == '\n' })
        val spacers = result.paragraphStyles.filter { it.item.lineHeight.isSpecified }
        assertEquals(2, spacers.size)
        assertEquals(1.5f, spacers[0].item.lineHeight.value, 0.01f)
        assertEquals(0.75f, spacers[1].item.lineHeight.value, 0.01f)
    }

    @Test
    fun `first chapter skips leading title margin`() {
        val css =
            ".title2 { font-size: 1.5em; margin: 1em 0px 0.5em 1.563em; } .p { margin: 0px 0px 0.5em 0px; } .p1 { margin: 0px; }"
        val html = """<html><head><style>$css</style></head><body>
            <div class="title2"><p class="p">Chapter</p></div>
            <p class="p1">Body.</p>
        </body></html>"""
        val result = render(html)

        val spacers = result.paragraphStyles.filter { it.item.lineHeight.isSpecified }
        assertEquals(1, spacers.size)
        assertEquals(0.75f, spacers[0].item.lineHeight.value, 0.01f)
    }

    @Test
    fun `poem stanzas are separated by collapsed margins`() {
        val css =
            ".poem { margin: 0.5em 0px 0.5em 2em; } .stanza { margin: 0.5em 0px; } .v { margin: 0px; }"
        val html = """<html><head><style>$css</style></head><body>
            <div class="poem">
            <div class="stanza"><p class="v">a</p><p class="v">b</p></div>
            <div class="stanza"><p class="v">c</p><p class="v">d</p></div>
            </div>
        </body></html>"""
        val result = render(html)

        assertTrue(result.text.none { it == '\n' })
        assertEquals("ab\u200Bcd", result.text)
        val spacers = result.paragraphStyles.filter { it.item.lineHeight.isSpecified }
        assertEquals(1, spacers.size)
        assertEquals(0.5f, spacers[0].item.lineHeight.value, 0.01f)
    }

    @Test
    fun `empty line paragraph creates spacing from css height`() {
        val result = render(
            """<html><head><style>.empty-line { height: 1em; margin: 0px; }</style></head>
            <body><p>Day one.</p><p class="empty-line"/><p>Day two.</p></body></html>"""
        )
        assertTrue(result.text.none { it == '\n' })
        assertEquals("Day one.\u200BDay two.", result.text)
        val spacer = result.paragraphStyles.single { it.item.lineHeight.isSpecified }
        assertEquals(1.0f, spacer.item.lineHeight.value, 0.01f)
    }

    @Test
    fun `container margin applies once at container edge, not to inner paragraphs`() {
        val css = ".chapter { margin: 1em 0px 0px 0px; }"
        val html = """<html><head><style>$css</style></head><body>
            <div class="chapter"><p>A</p><p>B</p><p>C</p></div>
        </body></html>"""
        val result = render(html, isFirstChapter = false)

        assertEquals("\u200BABC", result.text)
        val spacers = result.paragraphStyles.filter { it.item.lineHeight.isSpecified }
        assertEquals(1, spacers.size)
        assertEquals(1.0f, spacers[0].item.lineHeight.value, 0.01f)
    }

    @Test
    fun `two value margin shorthand applies first value to top and bottom`() {
        val result = render(
            """<html><head><style>.a { margin: 1em 2em; }</style></head>
            <body><p class="a">A</p><p>B</p></body></html>""",
            isFirstChapter = false
        )

        val spacers = result.paragraphStyles.filter { it.item.lineHeight.isSpecified }
        assertEquals(2, spacers.size)
        assertEquals(1f, spacers[0].item.lineHeight.value, 0.01f)
        assertEquals(1f, spacers[1].item.lineHeight.value, 0.01f)
    }

    @Test
    fun `plain paragraphs across chapters keep a gap between chapters`() {
        val ch1 = EpubStyler.renderChapter(
            """<html><body><p>End of chapter one.</p></body></html>""",
            { null },
            isFirstChapter = true
        )
        val ch2 = EpubStyler.renderChapter(
            """<html><body><p>Start of chapter two.</p></body></html>""",
            { null },
            isFirstChapter = false,
            previousBottomMarginEm = 0f
        )
        val result = ch1 + ch2

        assertEquals("End of chapter one.\u200BStart of chapter two.", result.text)
        val spacers = result.paragraphStyles.filter { it.item.lineHeight.isSpecified }
        assertEquals(1, spacers.size)
        assertEquals(1.0f, spacers[0].item.lineHeight.value, 0.01f)
    }

    @Test
    fun `chapter boundary collapses trailing margin of previous chapter`() {
        val (ch1, trailing) = EpubStyler.renderChapterAndTrailingMargin(
            """<html><head><style>.end { margin: 0px 0px 2em 0px; }</style></head>
            <body><p class="end">End.</p></body></html>""",
            { null },
            isFirstChapter = true
        )
        assertEquals(2f, trailing, 0.01f)

        val ch2 = EpubStyler.renderChapter(
            """<html><body><p>Start.</p></body></html>""",
            { null },
            isFirstChapter = false,
            previousBottomMarginEm = trailing
        )
        val result = ch1 + ch2
        val spacers = result.paragraphStyles.filter { it.item.lineHeight.isSpecified }
        assertEquals(1, spacers.size)
        assertEquals(2.0f, spacers[0].item.lineHeight.value, 0.01f)
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
