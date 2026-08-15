package com.salat.bokl

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration

/** A single annotation reference `[N]` found in the rendered book text. */
data class AnnotationReference(
    val number: Int,
    val start: Int,
    val end: Int
)

/** Parsed annotation data: the note texts and the clickable references. */
data class AnnotationInfo(
    val notes: Map<Int, String>,
    val references: List<AnnotationReference>
)

/**
 * Turns annotation references (`[N]`) in the rendered book text into clickable
 * links that open the full note text. The note texts themselves are extracted
 * from the EPUB structure (see [EpubAnnotations]); [notes] maps each note number
 * to its full text.
 */
internal object Annotations {

    /** Visual style applied to the clickable `[N]` references. */
    val LINK_STYLES = TextLinkStyles(
        style = SpanStyle(textDecoration = TextDecoration.Underline)
    )

    private val REFERENCE_REGEX = Regex("""\[\s*(\d+)\s*]""")

    /** Finds `[N]` references in [bookText] that have a matching entry in [notes]. */
    fun analyze(bookText: AnnotatedString, notes: Map<Int, String>): AnnotationInfo {
        val full = bookText.text
        val references = REFERENCE_REGEX.findAll(full)
            .map {
                AnnotationReference(
                    it.groupValues[1].toInt(),
                    it.range.first,
                    it.range.last + 1
                )
            }
            .filter { it.number in notes }
            .toList()
        return AnnotationInfo(notes, references)
    }

    /** Returns a copy of [text] with a clickable [LinkAnnotation] over every known reference. */
    fun applyLinks(
        text: AnnotatedString,
        info: AnnotationInfo,
        onOpen: (Int) -> Unit
    ): AnnotatedString {
        if (info.references.isEmpty()) return text
        val builder = AnnotatedString.Builder(text)
        for (ref in info.references) {
            builder.addLink(
                LinkAnnotation.Clickable(
                    tag = "annotation",
                    styles = LINK_STYLES,
                    linkInteractionListener = { onOpen(ref.number) }
                ),
                ref.start,
                ref.end
            )
        }
        return builder.toAnnotatedString()
    }
}
