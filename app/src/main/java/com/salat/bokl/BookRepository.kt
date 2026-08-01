package com.salat.bokl

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.text.AnnotatedString
import java.io.File
import java.util.zip.ZipFile
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

data class BookContent(
    val text: AnnotatedString,
    val coverImagePath: String? = null
)

private data class OpfData(
    val spineHrefs: List<String>,
    val idToHref: Map<String, String>,
    val coverId: String?
)

class BookRepository(private val context: Context) {

    private val coverCache = mutableMapOf<String, String>()

    fun listBooks(folderUri: Uri): List<Book> {
        val treeDocId = DocumentsContract.getTreeDocumentId(folderUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, treeDocId)
        val books = mutableListOf<Book>()

        val cursor = context.contentResolver.query(childrenUri, null, null, null, null)
        cursor?.use {
            while (it.moveToNext()) {
                val docIdIdx = it.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = it.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = it.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                if (docIdIdx < 0 || nameIdx < 0 || mimeIdx < 0) continue
                val docId = it.getString(docIdIdx)
                val name = it.getString(nameIdx) ?: continue
                val mime = it.getString(mimeIdx)
                if (DocumentsContract.Document.MIME_TYPE_DIR == mime) continue

                val fileUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, docId)
                val format = detectFormat(name)
                if (format != BookFormat.UNSUPPORTED) {
                    books.add(Book(
                        id = docId,
                        title = name.removeSuffix(".txt").removeSuffix(".epub"),
                        uri = fileUri,
                        format = format
                    ))
                }
            }
        }
        return books.sortedBy { it.title.lowercase() }
    }

    fun readBookContent(book: Book): BookContent {
        return when (book.format) {
            BookFormat.TXT -> BookContent(AnnotatedString(readTxt(book.uri)))
            BookFormat.EPUB -> readEpub(book.uri, book.id)
            BookFormat.UNSUPPORTED -> BookContent(AnnotatedString(""))
        }
    }

    fun loadCover(book: Book): String? {
        if (book.format != BookFormat.EPUB) return null
        coverCache[book.id]?.let { return it }
        return extractCover(book.uri, book.id)
    }

    private fun extractCover(uri: Uri, bookId: String?): String? {
        val tempFile = File(context.cacheDir, "epub_${System.nanoTime()}.epub")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            extractCoverFromEpubFile(tempFile, bookId)
        } catch (e: Exception) {
            null
        } finally {
            tempFile.delete()
        }
    }

    private fun extractCoverFromEpubFile(epubFile: File, cacheKey: String?): String? {
        if (cacheKey != null) coverCache[cacheKey]?.let { return it }
        val zipFile = ZipFile(epubFile)
        try {
            val containerEntry = zipFile.getEntry("META-INF/container.xml") ?: return null
            val containerXml = zipFile.getInputStream(containerEntry).bufferedReader().readText()
            val opfPath = parseContainerXml(containerXml) ?: return null
            val basePath = opfPath.substringBeforeLast("/", missingDelimiterValue = "")
            val opfEntry = zipFile.getEntry(opfPath) ?: return null
            val opfXml = zipFile.getInputStream(opfEntry).bufferedReader().readText()
            val opfData = parseOpf(opfXml)
            val path = resolveCoverImage(zipFile, basePath, opfData) ?: return null
            return extractImage(zipFile, path).also { coverPath ->
                if (cacheKey != null) coverCache[cacheKey] = coverPath
            }
        } finally {
            zipFile.close()
        }
    }

    private fun readTxt(uri: Uri): String {
        return context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
    }

    private fun readEpub(uri: Uri, bookId: String): BookContent {
        val tempFile = File(context.cacheDir, "epub_${System.nanoTime()}.epub")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            return parseEpub(tempFile, bookId)
        } catch (e: Exception) {
            return BookContent(AnnotatedString("Error reading EPUB: ${e.message}"))
        } finally {
            tempFile.delete()
        }
    }

    private fun parseEpub(epubFile: File, bookId: String): BookContent {
        val zipFile = ZipFile(epubFile)

        try {
            val containerEntry = zipFile.getEntry("META-INF/container.xml")
                ?: return BookContent(AnnotatedString("Invalid EPUB: missing container.xml"))
            val containerXml = zipFile.getInputStream(containerEntry).bufferedReader().readText()
            val opfPath = parseContainerXml(containerXml)
                ?: return BookContent(AnnotatedString("Invalid EPUB: missing OPF path"))

            val basePath = opfPath.substringBeforeLast("/", missingDelimiterValue = "")
            val opfEntry = zipFile.getEntry(opfPath)
                ?: return BookContent(AnnotatedString("Invalid EPUB: missing OPF file"))
            val opfXml = zipFile.getInputStream(opfEntry).bufferedReader().readText()
            val opfData = parseOpf(opfXml)

            val builder = AnnotatedString.Builder()
            var firstChapter = true
            var previousBottomMarginEm = 0f
            for (href in opfData.spineHrefs) {
                val entryPath = resolvePath(basePath, href)
                val entry = zipFile.getEntry(entryPath)
                if (entry != null && !isImageEntry(entryPath)) {
                    val html = zipFile.getInputStream(entry).use { it.bufferedReader().readText() }
                    val chapterDir = entryPath.substringBeforeLast("/", missingDelimiterValue = "")
                    val (chapter, trailingMargin) = EpubStyler.renderChapterAndTrailingMargin(
                        html = html,
                        loadCss = { cssHref ->
                            if (cssHref.startsWith("http") || cssHref.startsWith("//") || cssHref.startsWith("data:")) {
                                return@renderChapterAndTrailingMargin null
                            }
                            val cssPath = resolvePath(chapterDir, cssHref)
                            zipFile.getEntry(cssPath)?.let { cssEntry ->
                                zipFile.getInputStream(cssEntry).use { it.bufferedReader().readText() }
                            }
                        },
                        isFirstChapter = firstChapter,
                        previousBottomMarginEm = previousBottomMarginEm
                    )
                    if (chapter.isNotEmpty()) {
                        builder.append(chapter)
                        firstChapter = false
                        previousBottomMarginEm = trailingMargin
                    }
                }
            }

            val coverPath = extractCoverFromEpubFile(epubFile, bookId)
            return BookContent(builder.toAnnotatedString().trimEndIfNeeded(), coverPath)
        } finally {
            zipFile.close()
        }
    }

    private fun AnnotatedString.trimEndIfNeeded(): AnnotatedString {
        val lastNonWhitespace = text.indexOfLast { !it.isWhitespace() }
        if (lastNonWhitespace < 0) return AnnotatedString("")
        if (lastNonWhitespace == text.length - 1) return this
        return subSequence(0, lastNonWhitespace + 1)
    }

    private fun resolvePath(basePath: String, href: String): String {
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

    private fun isImageEntry(path: String): Boolean {
        val lower = path.substringAfterLast('.').lowercase()
        return lower == "jpg" || lower == "jpeg" || lower == "png" || lower == "gif" || lower == "webp"
    }

    private fun resolveCoverImage(zipFile: ZipFile, basePath: String, opfData: OpfData): String? {
        val coverHref = opfData.coverId?.let { opfData.idToHref[it] }
        if (coverHref != null) {
            val path = resolvePath(basePath, coverHref)
            if (zipFile.getEntry(path) != null) {
                if (isImageEntry(path)) return path
                return findImageInHtml(zipFile, path)
            }
        }
        for (href in opfData.spineHrefs) {
            val path = resolvePath(basePath, href)
            if (zipFile.getEntry(path) != null && isImageEntry(path)) return path
        }
        if (opfData.spineHrefs.isNotEmpty()) {
            val first = resolvePath(basePath, opfData.spineHrefs[0])
            if (zipFile.getEntry(first) != null) {
                return findImageInHtml(zipFile, first)
            }
        }
        return null
    }

    private fun findImageInHtml(zipFile: ZipFile, path: String): String? {
        val entry = zipFile.getEntry(path) ?: return null
        val html = zipFile.getInputStream(entry).bufferedReader().readText()
        val src = Regex("""<img[^>]+src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1) ?: return null
        val baseDir = path.substringBeforeLast("/", missingDelimiterValue = "")
        return resolvePath(baseDir, src)
    }

    private fun extractImage(zipFile: ZipFile, path: String): String {
        val ext = path.substringAfterLast('.', "img")
        val file = File(context.cacheDir, "cover_${System.nanoTime()}.$ext")
        zipFile.getInputStream(zipFile.getEntry(path)).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file.absolutePath
    }

    private fun parseContainerXml(xml: String): String? {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(xml.reader())
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                return parser.getAttributeValue(null, "full-path")
            }
            parser.next()
        }
        return null
    }

    private fun parseOpf(xml: String): OpfData {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(xml.reader())

        val spineIdrefs = mutableListOf<String>()
        val idToHref = mutableMapOf<String, String>()
        var coverId: String? = null

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "itemref" -> parser.getAttributeValue(null, "idref")?.let { spineIdrefs.add(it) }
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        if (id != null && href != null) idToHref[id] = href
                    }
                    "meta" -> {
                        when {
                            parser.getAttributeValue(null, "name") == "cover" ->
                                coverId = parser.getAttributeValue(null, "content")
                            parser.getAttributeValue(null, "property") == "cover" ->
                                coverId = parser.getAttributeValue(null, "refines")?.removePrefix("#")
                        }
                    }
                }
            }
            parser.next()
        }

        val hrefs = spineIdrefs.mapNotNull { idToHref[it] }
        return OpfData(hrefs, idToHref, coverId)
    }

    private fun detectFormat(name: String): BookFormat {
        return when {
            name.endsWith(".txt", ignoreCase = true) -> BookFormat.TXT
            name.endsWith(".epub", ignoreCase = true) -> BookFormat.EPUB
            else -> BookFormat.UNSUPPORTED
        }
    }
}
