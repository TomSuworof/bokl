package com.salat.bokl

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.em
import kotlin.math.abs
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

private const val FONT_NORMAL = 0
private const val FONT_ITALIC = 1

private val WHITESPACE = Regex("\\s+")
private val COMMENT_REGEX = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
private val BLOCK_REGEX = Regex("""([^{}]+)\{([^{}]*)\}""")
private val COMPOUND_REGEX = Regex("""#([\w-]+)|\.([\w-]+)|([a-zA-Z][\w-]*)|(\*)""")
private val FONT_SIZE_REGEX = Regex("""^(-?\d+(?:\.\d+)?)\s*(px|pt|pc|em|rem|ex|%)$""")
private val TEXT_INDENT_REGEX = Regex("""^(-?\d+(?:\.\d+)?)\s*(em|rem|px|pt|pc|%)?$""")

private val BLOCK_TAGS = setOf(
    "p", "div", "h1", "h2", "h3", "h4", "h5", "h6",
    "blockquote", "pre", "ul", "ol", "li", "dl", "dt", "dd",
    "table", "thead", "tbody", "tfoot", "tr", "td", "th", "caption",
    "section", "article", "header", "footer", "aside", "nav",
    "figure", "figcaption", "address", "main"
)

private val PARAGRAPH_TAGS = setOf(
    "p", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "li", "pre", "figcaption", "dt", "dd"
)

private data class ComputedStyle(
    val fontWeight: Int = 400,
    val fontStyle: Int = FONT_NORMAL,
    val underline: Boolean = false,
    val lineThrough: Boolean = false,
    val fontSizeScale: Float = 1f,
    val textIndentEm: Float? = null,
    val textAlign: TextAlign? = null,
    val baselineShift: BaselineShift? = null,
    val pre: Boolean = false
)

private data class CssCompound(val id: String?, val classes: List<String>, val tags: List<String>)

private data class CssRule(
    val compounds: List<CssCompound>,
    val declarations: List<Pair<String, String>>,
    val specificity: Int,
    val order: Int
)

internal object EpubStyler {

    fun renderChapter(html: String, loadCss: (String) -> String?): AnnotatedString {
        val doc = Jsoup.parse(html)
        val cssTexts = mutableListOf<String>()
        doc.select("style").forEach { style ->
            style.data().takeIf { it.isNotBlank() }?.let { cssTexts.add(it) }
        }
        doc.select("link[rel=stylesheet][href]").forEach { link ->
            val href = link.attr("href")
            if (href.isNotBlank() && !isExternalPath(href)) {
                loadCss(href)?.takeIf { it.isNotBlank() }?.let { cssTexts.add(it) }
            }
        }
        val rules = parseRules(cssTexts)
        val acc = Accumulator()
        for (child in doc.body().childNodes()) {
            renderNode(child, ComputedStyle(), rules, acc)
        }
        return acc.toAnnotatedString()
    }

    private fun isExternalPath(href: String): Boolean {
        val lower = href.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("//") || lower.startsWith("data:")
    }

    private fun renderNode(node: Node, parentStyle: ComputedStyle, rules: List<CssRule>, acc: Accumulator) {
        when (node) {
            is TextNode -> renderTextNode(node, parentStyle, acc)
            is Element -> renderElement(node, parentStyle, rules, acc)
        }
    }

    private fun renderElement(elem: Element, parentStyle: ComputedStyle, rules: List<CssRule>, acc: Accumulator) {
        val tag = elem.tagName().lowercase()
        if (tag == "img") return
        if (tag == "br") {
            acc.appendBreak(1)
            return
        }
        if (tag == "hr") {
            acc.appendBreak(2)
            return
        }

        val style = computeStyle(elem, parentStyle, rules)
        val isBlock = tag in BLOCK_TAGS
        val isParagraph = tag in PARAGRAPH_TAGS

        if (isBlock && !acc.atLineStart) acc.appendBreak(1)

        val paraStart = if (isParagraph) acc.text.length else -1
        for (child in elem.childNodes()) {
            renderNode(child, style, rules, acc)
        }

        if (isBlock) {
            acc.endBlock()
            if (isParagraph && paraStart >= 0 && acc.text.length > paraStart) {
                acc.addParagraphRanges(paraStart, acc.text.length, style.textAlign, style.textIndentEm?.times(style.fontSizeScale))
            }
        }
    }

    private fun renderTextNode(node: TextNode, style: ComputedStyle, acc: Accumulator) {
        val raw = node.wholeText
        val text = if (style.pre) raw else WHITESPACE.replace(raw, " ")
        if (text.isEmpty()) return

        val spanStyles = style.toSpanStyles()
        if (spanStyles.isEmpty()) {
            acc.appendText(text)
            return
        }
        val start = acc.text.length
        acc.appendText(text)
        val end = acc.text.length
        if (end > start) {
            for (s in spanStyles) {
                acc.spans.add(AnnotatedString.Range(s, start, end))
            }
        }
    }

    private fun computeStyle(elem: Element, parent: ComputedStyle, rules: List<CssRule>): ComputedStyle {
        val tag = elem.tagName().lowercase()
        var style = applyTagDefaults(parent, tag)

        rules
            .filter { matchesSelector(elem, it) }
            .sortedWith(compareBy({ it.specificity }, { it.order }))
            .forEach { rule ->
                for ((prop, value) in rule.declarations) {
                    style = applyDeclaration(style, prop, value, parent)
                }
            }

        val inline = elem.attr("style")
        if (inline.isNotBlank()) {
            for ((prop, value) in parseDeclarations(inline)) {
                style = applyDeclaration(style, prop, value, parent)
            }
        }
        return style
    }

    private fun applyTagDefaults(parent: ComputedStyle, tag: String): ComputedStyle {
        var s = parent
        when (tag) {
            "sup" -> s = s.copy(baselineShift = BaselineShift.Superscript)
            "sub" -> s = s.copy(baselineShift = BaselineShift.Subscript)
            "pre" -> s = s.copy(pre = true)
            "p" -> s = s.copy(textIndentEm = 1.5f)
            "i", "em", "cite", "dfn", "var" -> s = s.copy(fontStyle = FONT_ITALIC)
            "b", "strong" -> s = s.copy(fontWeight = 700)
            "u", "ins" -> s = s.copy(underline = true)
            "s", "strike", "del" -> s = s.copy(lineThrough = true)
        }
        if (tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6') {
            val scale = when (tag[1]) {
                '1' -> 2.0f
                '2' -> 1.5f
                '3' -> 1.17f
                '4' -> 1.0f
                '5' -> 0.83f
                else -> 0.67f
            }
            s = s.copy(fontWeight = 700, fontSizeScale = s.fontSizeScale * scale)
        }
        return s
    }

    private fun applyDeclaration(style: ComputedStyle, prop: String, value: String, parent: ComputedStyle): ComputedStyle {
        return when (prop) {
            "font-weight" -> {
                val v = value.lowercase()
                val weight = when {
                    v == "bold" -> 700
                    v == "normal" -> 400
                    v == "bolder" -> if (style.fontWeight >= 700) 900 else 700
                    v == "lighter" -> if (style.fontWeight <= 400) 100 else 400
                    else -> v.toIntOrNull()?.coerceIn(100, 900) ?: style.fontWeight
                }
                style.copy(fontWeight = weight)
            }
            "font-style" -> style.copy(
                fontStyle = if (value.lowercase() in setOf("italic", "oblique")) FONT_ITALIC else FONT_NORMAL
            )
            "text-decoration" -> {
                val v = value.lowercase()
                if (v.contains("none")) {
                    style.copy(underline = false, lineThrough = false)
                } else {
                    style.copy(
                        underline = v.contains("underline"),
                        lineThrough = v.contains("line-through")
                    )
                }
            }
            "font-size" -> {
                val (relative, size) = parseFontSize(value)
                style.copy(fontSizeScale = if (relative) style.fontSizeScale * size else size)
            }
            "text-indent" -> style.copy(textIndentEm = parseTextIndent(value))
            "text-align" -> style.copy(
                textAlign = when (value.lowercase()) {
                    "left" -> TextAlign.Left
                    "right" -> TextAlign.Right
                    "center" -> TextAlign.Center
                    "justify" -> TextAlign.Justify
                    else -> style.textAlign
                }
            )
            else -> style
        }
    }

    private fun parseTextIndent(value: String): Float? {
        val v = value.trim().lowercase()
        val m = TEXT_INDENT_REGEX.find(v)
        if (m != null) {
            val num = m.groupValues[1].toFloat()
            return when (m.groupValues[2]) {
                "em" -> num
                "rem" -> num
                "%" -> num / 100f
                "px" -> num / 16f
                "pt" -> num * 4f / 3f / 16f
                "pc" -> num
                "" -> num / 16f
                else -> null
            }
        }
        return null
    }

    private fun parseFontSize(value: String): Pair<Boolean, Float> {
        val v = value.trim().lowercase()
        val m = FONT_SIZE_REGEX.find(v)
        if (m != null) {
            val num = m.groupValues[1].toFloat()
            return when (m.groupValues[2]) {
                "em" -> true to num
                "%" -> true to (num / 100f)
                "rem" -> false to num
                "px" -> false to (num / 16f)
                "pt" -> false to (num * 4f / 3f / 16f)
                "pc" -> false to (num * 16f / 16f)
                "ex" -> true to (num * 0.5f)
                else -> false to 1f
            }
        }
        return when (v) {
            "medium" -> false to 1f
            "large" -> false to 1.2f
            "x-large" -> false to 1.5f
            "xx-large" -> false to 2f
            "small" -> false to 0.83f
            "x-small" -> false to 0.67f
            "xx-small" -> false to 0.5f
            "smaller" -> true to 0.83f
            "larger" -> true to 1.2f
            else -> false to 1f
        }
    }

    private fun parseRules(cssTexts: List<String>): List<CssRule> {
        val rules = mutableListOf<CssRule>()
        val seen = mutableSetOf<String>()
        var order = 0
        for (css in cssTexts) {
            val cleaned = css.replace(COMMENT_REGEX, "")
            for (m in BLOCK_REGEX.findAll(cleaned)) {
                val selectorText = m.groupValues[1].trim()
                if (selectorText.isEmpty() || selectorText.startsWith("@")) continue
                val declarations = parseDeclarations(m.groupValues[2])
                if (declarations.isEmpty()) continue
                for (selector in selectorText.split(",")) {
                    val trimmed = selector.trim()
                    if (trimmed.isEmpty()) continue
                    val compounds = parseSelector(trimmed)
                    if (compounds.isEmpty()) continue
                    val key = "$trimmed|${declarations.joinToString(";")}"
                    if (!seen.add(key)) continue
                    rules.add(CssRule(compounds, declarations, specificity(compounds), order++))
                }
            }
        }
        return rules
    }

    private fun parseDeclarations(body: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        for (part in body.split(";")) {
            val idx = part.indexOf(':')
            if (idx <= 0) continue
            val prop = part.substring(0, idx).trim().lowercase()
            val value = part.substring(idx + 1).trim()
            if (prop.isNotEmpty() && value.isNotEmpty() && prop.all { it.isLetter() || it == '-' }) {
                result.add(prop to value)
            }
        }
        return result
    }

    private fun parseSelector(selector: String): List<CssCompound> {
        val tokens = selector.split(WHITESPACE).filter { it.isNotEmpty() && it != ">" }
        return tokens.mapNotNull { parseCompound(it) }
    }

    private fun parseCompound(token: String): CssCompound? {
        var id: String? = null
        val classes = mutableListOf<String>()
        val tags = mutableListOf<String>()
        var matched = false
        for (m in COMPOUND_REGEX.findAll(token)) {
            matched = true
            when {
                m.groupValues[1].isNotEmpty() -> id = m.groupValues[1]
                m.groupValues[2].isNotEmpty() -> classes.add(m.groupValues[2])
                m.groupValues[3].isNotEmpty() -> tags.add(m.groupValues[3])
            }
        }
        if (!matched) return null
        return CssCompound(id, classes, tags)
    }

    private fun specificity(compounds: List<CssCompound>): Int {
        var spec = 0
        for (c in compounds) {
            if (c.id != null) spec += 1000
            spec += c.classes.size * 100
            spec += c.tags.size
        }
        return spec
    }

    private fun matchesSelector(elem: Element, rule: CssRule): Boolean {
        var node: Element? = elem
        var idx = rule.compounds.size - 1
        while (node != null) {
            if (matchesCompound(node, rule.compounds[idx])) {
                idx--
                if (idx < 0) return true
            } else if (idx == rule.compounds.size - 1) {
                return false
            }
            node = node.parent()
        }
        return false
    }

    private fun matchesCompound(elem: Element, compound: CssCompound): Boolean {
        if (compound.id != null && elem.id() != compound.id) return false
        if (compound.classes.isNotEmpty() && compound.classes.any { !elem.hasClass(it) }) return false
        if (compound.tags.isNotEmpty() && compound.tags.none { elem.tagName().equals(it, true) }) return false
        return true
    }

    private fun ComputedStyle.toSpanStyles(): List<SpanStyle> {
        val styles = mutableListOf<SpanStyle>()
        var style = SpanStyle()
        if (fontWeight != 400) style = style.merge(SpanStyle(fontWeight = FontWeight(fontWeight)))
        if (fontStyle == FONT_ITALIC) style = style.merge(SpanStyle(fontStyle = FontStyle.Italic))
        if (abs(fontSizeScale - 1f) > 0.01f) style = style.merge(SpanStyle(fontSize = fontSizeScale.em))
        if (baselineShift != null) style = style.merge(SpanStyle(baselineShift = baselineShift))
        if (underline) styles.add(style.merge(SpanStyle(textDecoration = TextDecoration.Underline)))
        if (lineThrough) styles.add(style.merge(SpanStyle(textDecoration = TextDecoration.LineThrough)))
        if (styles.isEmpty() && style != SpanStyle()) styles.add(style)
        return styles
    }

    private class Accumulator {
        val text = StringBuilder()
        val spans = mutableListOf<AnnotatedString.Range<SpanStyle>>()
        val paragraphs = mutableListOf<AnnotatedString.Range<ParagraphStyle>>()
        var atLineStart = true
            private set

        fun appendText(s: String) {
            val clean = if (atLineStart) s.trimStart() else s
            if (clean.isEmpty()) return
            text.append(clean)
            atLineStart = text[text.length - 1] == '\n'
        }

        fun appendBreak(n: Int) {
            if (text.isEmpty()) return
            while (text.isNotEmpty() && (text[text.length - 1] == '\n' || text[text.length - 1].isWhitespace())) {
                text.deleteCharAt(text.length - 1)
            }
            text.append("\n".repeat(n))
            atLineStart = true
        }

        fun endBlock() {
            if (text.isEmpty()) return
            while (text.isNotEmpty() && (text[text.length - 1] == '\n' || text[text.length - 1] == '\r')) {
                text.deleteCharAt(text.length - 1)
            }
            text.append('\n')
            atLineStart = true
        }

        fun addParagraphRanges(start: Int, end: Int, align: TextAlign?, textIndentEm: Float?) {
            if (align == null && textIndentEm == null) return
            var paragraphStyle = ParagraphStyle()
            if (align != null) paragraphStyle = paragraphStyle.merge(ParagraphStyle(textAlign = align))
            if (textIndentEm != null) {
                paragraphStyle = paragraphStyle.merge(ParagraphStyle(textIndent = TextIndent(firstLine = textIndentEm.em)))
            }
            var p = start
            while (p < end) {
                val nl = text.indexOf("\n", p)
                if (nl < 0 || nl >= end) {
                    paragraphs.add(AnnotatedString.Range(paragraphStyle, p, end))
                    break
                }
                paragraphs.add(AnnotatedString.Range(paragraphStyle, p, nl + 1))
                p = nl + 1
            }
        }

        fun toAnnotatedString(): AnnotatedString {
            return AnnotatedString(text.toString(), spanStyles = spans, paragraphStyles = paragraphs)
        }
    }
}
