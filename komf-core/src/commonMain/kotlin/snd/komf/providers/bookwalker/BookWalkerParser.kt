package snd.komf.providers.bookwalker

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Document
import com.fleeksoft.ksoup.nodes.Element
import io.ktor.http.decodeURLPart
import kotlinx.datetime.LocalDate
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
import snd.komf.model.BookRange
import snd.komf.providers.bookwalker.model.BookWalkerBook
import snd.komf.providers.bookwalker.model.BookWalkerBookId
import snd.komf.providers.bookwalker.model.BookWalkerBookListPage
import snd.komf.providers.bookwalker.model.BookWalkerSearchResult
import snd.komf.providers.bookwalker.model.BookWalkerSeriesBook
import snd.komf.providers.bookwalker.model.BookWalkerSeriesId
import io.github.oshai.kotlinlogging.KotlinLogging
import snd.komf.util.BookNameParser
import snd.komf.util.replaceFullwidthChars

private val parserLogger = KotlinLogging.logger {}

class BookWalkerParser {
    private val baseUrl = "https://bookwalker.com"
    private val oldBaseUrl = "https://global.bookwalker.jp"
    private val dateFormat = LocalDate.Format {
        monthName(MonthNames.ENGLISH_FULL)
        char(' ')
        day()
        chars(", ")
        year()
    }

    fun parseSearchResults(results: String): Collection<BookWalkerSearchResult> {
        val document = Ksoup.parse(results)
        
        // Try new bookwalker.com format first
        val newResults = parseNewSearchResults(document)
        if (newResults.isNotEmpty()) return newResults
        
        // Fall back to old global.bookwalker.jp format
        return document.getElementsByClass("o-tile-list").first()?.children()
            ?.map { parseOldSearchResult(it) }
            ?: emptyList()
    }

    private fun parseNewSearchResults(document: Document): Collection<BookWalkerSearchResult> {
        // New format: book-card-grid-view-module elements with links to /series/{id}/{slug}
        val results = mutableListOf<BookWalkerSearchResult>()
        
        // Find all series links in the search results
        val seriesLinks = document.select("a[href*=/series/]")
        parserLogger.info { "BookWalkerParser: Found ${seriesLinks.size} series links" }
        val seenIds = mutableSetOf<String>()
        
        for ((index, link) in seriesLinks.withIndex()) {
            val href = link.attr("href")
            parserLogger.debug { "Link $index: href=$href" }
            if (!href.contains("/series/")) {
                parserLogger.debug { "Link $index: skipped - no /series/ in href" }
                continue
            }
            
            // Skip image links (they have aria-label="Cover" or empty, and no useful text)
            val ariaLabel = link.attr("aria-label")
            if (ariaLabel == "Cover" || link.hasClass("book-card-grid-view-module__A8__ha__image")) {
                parserLogger.debug { "Link $index: skipped - image link (aria-label='$ariaLabel')" }
                continue
            }
            
            // Parse series ID from href like /series/27J7TDKH5FD0/a-dating-sim-of-life-or-death
            val seriesId = parseNewSeriesId(href)
            if (seriesId == null) {
                parserLogger.debug { "Link $index: failed to parse seriesId from $href" }
                continue
            }
            if (seenIds.contains(seriesId.id)) {
                parserLogger.debug { "Link $index: skipped - duplicate seriesId ${seriesId.id}" }
                continue
            }
            seenIds.add(seriesId.id)
            
            // Get title from the link text or aria-label
            val linkText = link.text().trim()
            val title = ariaLabel.ifEmpty { linkText }
            parserLogger.debug { "Link $index: ariaLabel='$ariaLabel', text='$linkText', title='$title'" }
            if (title.isEmpty()) {
                parserLogger.debug { "Link $index: skipped - empty title" }
                continue
            }
            
            // Get thumbnail from nearby img element
            val imageUrl = link.select("img").firstOrNull()?.let { img ->
                img.attr("srcset").split(",").lastOrNull()?.trim()?.split(" ")?.firstOrNull()
                    ?: img.attr("src")
            }
            
            results.add(BookWalkerSearchResult(
                seriesId = seriesId,
                bookId = null,
                seriesName = parseSeriesName(title),
                imageUrl = imageUrl,
            ))
        }
        
        return results
    }

    private fun parseNewSeriesId(href: String): BookWalkerSeriesId? {
        // Parse /series/27J7TDKH5FD0/a-dating-sim-of-life-or-death
        val match = Regex("/series/([^/]+)(?:/.*)?").find(href) ?: return null
        return BookWalkerSeriesId(match.groupValues[1].decodeURLPart())
    }

    fun parseSeriesBooks(seriesBooks: String): BookWalkerBookListPage {
        val document = Ksoup.parse(seriesBooks)
        val books = document.getElementsByClass("o-tile-list").first()?.children()
            ?.map { parseSeriesBook(it) }
            ?: emptyList()
        val pageElement = document.getElementsByClass("pager-area").firstOrNull()?.child(0)
        val currentPage = pageElement?.children()?.first { it.className() == "on" }
            ?.text()?.toInt() ?: 1
        val totalPages = pageElement?.children()?.mapNotNull { it.text().toIntOrNull() }?.max() ?: 1
        return BookWalkerBookListPage(page = currentPage, totalPages = totalPages, books = books)
    }

    fun parseBook(book: String): BookWalkerBook {
        val document = Ksoup.parse(book)
        val synopsis = document.getElementsByClass("synopsis-text").first()?.wholeText()?.trim()?.replace("\n\n", "\n")
        val image =
            document.getElementsByClass("book-img").first()?.firstElementChild()?.firstElementChild()?.attr("src")
        val name = document.getElementsByClass("detail-book-title").first()!!.child(0).textNodes().first().text().trim()
        val productDetail = document.getElementsByClass("product-detail").first()!!.child(0)
        val seriesTitleElement = productDetail.children()
            .firstOrNull { it.child(0).text() == "Series Title" }
            ?.child(1)
        val seriesTitle = seriesTitleElement?.text()?.let { parseSeriesName(it) }
        val seriesId = seriesTitleElement?.getElementsByTag("a")?.first()?.attr("href")?.let { parseSeriesId(it) }
        val japaneseTitles = productDetail.children().firstOrNull { it.child(0).text() == "Japanese Title" }
            ?.child(1)?.child(0)

        var japaneseTitle: String? = null
        var romajiTitle: String? = null
        japaneseTitles?.let { titleElement ->
            when (titleElement.children().size) {
                0 -> japaneseTitle = replaceFullwidthChars(titleElement.text())
                else -> {
                    japaneseTitle = titleElement.child(0).textNodes()
                        .firstOrNull()?.text()
                        ?.removeSuffix(" (")?.trim()
                        ?.let { replaceFullwidthChars(it) }
                    romajiTitle = titleElement.child(0).getElementsByClass("product-detail-romaji")
                        .first()?.text()
                        ?.removeSuffix(")")?.trim()
                        ?.let { replaceFullwidthChars(it) }
                }
            }
        }
        val authors = productDetail.children()
            .firstOrNull { it.child(0).text() == "Author" || it.child(0).text() == "By (author)" }
            ?.child(1)?.children()
            ?.map { it.text() }
            ?.map { replaceFullwidthChars(it) } ?: emptyList()

        val artists = productDetail.children()
            .firstOrNull { it.child(0).text() == "Artist" || it.child(0).text() == "By (artist)" }
            ?.child(1)?.children()
            ?.map { it.text() }
            ?.map { replaceFullwidthChars(it) } ?: authors
        val publisher = productDetail.children().first { it.child(0).text() == "Publisher" }
            .child(1).text()
        val genres = productDetail.children().firstOrNull { it.child(0).text() == "Genre" }
            ?.child(1)?.child(0)?.children()?.map { it.text() }
            ?: emptyList()
        val availableSince = productDetail.children().firstOrNull { it.child(0).text() == "Available since" }
            ?.child(1)?.text()?.split("/")?.first()
            ?.replace("\\(.*\\) PT ".toRegex(), "")?.trim()
            ?.let { LocalDate.parse(it, dateFormat) }

        return BookWalkerBook(
            id = parseDocumentBookId(document),
            seriesId = seriesId,
            name = name,
            number = parseBookNumber(name),
            seriesTitle = seriesTitle,
            japaneseTitle = japaneseTitle,
            romajiTitle = romajiTitle,
            artists = artists,
            authors = authors,
            publisher = publisher,
            genres = genres,
            availableSince = availableSince,
            synopsis = synopsis,
            imageUrl = image
        )
    }

    private fun parseSeriesBook(book: Element): BookWalkerSeriesBook {
        val titleElement = book.getElementsByClass("a-tile-ttl").first()!!
        return BookWalkerSeriesBook(
            id = parseBookId(titleElement.child(0).attr("href")),
            name = titleElement.text(),
            number = parseBookNumber(titleElement.text())
        )
    }

    private fun parseOldSearchResult(result: Element): BookWalkerSearchResult {
        val imageUrl = getSearchResultThumbnail(result)
        val titleElement = result.getElementsByClass("a-tile-ttl").first()!!
        val resultUrl = titleElement.child(0).attr("href")
        val seriesId = parseSeriesId(resultUrl)
        val bookId = if (seriesId == null) parseBookId(resultUrl) else null

        return BookWalkerSearchResult(
            seriesId = seriesId,
            bookId = bookId,
            seriesName = parseSeriesName(titleElement.text()),
            imageUrl = imageUrl,
        )
    }

    private fun parseSeriesId(url: String): BookWalkerSeriesId? {
        // Handle both old and new URL formats
        val normalizedUrl = url.replace(oldBaseUrl, baseUrl)
        if (!normalizedUrl.startsWith("$baseUrl/series/") && !normalizedUrl.startsWith("/series/")) return null

        val path = normalizedUrl.removePrefix(baseUrl).removePrefix("/")
        return path.removePrefix("series/")
            .replace("/.*/$".toRegex(), "")
            .removeSuffix("/")
            .split("/")
            .firstOrNull()
            ?.let { BookWalkerSeriesId(it.decodeURLPart()) }
    }

    private fun parseBookId(url: String): BookWalkerBookId {
        val normalizedUrl = url.replace(oldBaseUrl, baseUrl)
        return normalizedUrl.removePrefix("$baseUrl/")
            .replace("/.*/$".toRegex(), "")
            .removeSuffix("/")
            .let { BookWalkerBookId(it.decodeURLPart()) }
    }

    private fun parseSeriesName(name: String): String {
        return name.trimEnd()
            .replace("\\(?(Manga|Light Novels| Vol. \\d$)\\)?$".toRegex(), "")
            .trim()
    }

    private fun getSearchResultThumbnail(result: Element): String? {
        return result.getElementsByClass("a-tile-thumb-img").first()
            ?.child(0)?.attr("data-srcset")
            ?.split(",")?.get(1)
            ?.removeSuffix(" 2x")
            ?.trim()
    }

    private fun parseDocumentBookId(document: Document): BookWalkerBookId {
        return parseBookId(document.getElementsByTag("meta").first { it.attr("property") == "og:url" }
            .attr("content"))
    }

    private fun parseBookNumber(name: String): BookRange? {
        return BookNameParser.getVolumes(name)
            ?: "(?i)(?<!chapter)\\s\\d+".toRegex().findAll(name).lastOrNull()?.value?.toDoubleOrNull()
                ?.let { BookRange(it, it) }
    }
}
