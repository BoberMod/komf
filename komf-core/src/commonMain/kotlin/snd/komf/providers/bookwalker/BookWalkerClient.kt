package snd.komf.providers.bookwalker

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import snd.komf.browser.HtmlFetcher
import snd.komf.model.Image
import snd.komf.providers.bookwalker.model.BookWalkerBook
import snd.komf.providers.bookwalker.model.BookWalkerBookId
import snd.komf.providers.bookwalker.model.BookWalkerBookInfo
import snd.komf.providers.bookwalker.model.BookWalkerBookListPage
import snd.komf.providers.bookwalker.model.BookWalkerCategory
import snd.komf.providers.bookwalker.model.BookWalkerSearchResult
import snd.komf.providers.bookwalker.model.BookWalkerSeriesId

private val logger = KotlinLogging.logger {}

const val bookWalkerBaseUrl = "https://bookwalker.com"

class BookWalkerClient(
    ktor: HttpClient,
    json: Json,
    private val htmlFetcher: HtmlFetcher? = null,
) {
    private val parser = BookWalkerParser()
    private val apiClient = ktor.config { install(ContentNegotiation) { json(json) } }
    private val htmlClient = ktor.config {
        defaultRequest {
            cookie("safeSearch", "111")
            cookie("glSafeSearch", "1")
            cookie("mySetting/showCoverR15", "1")
        }
    }

    suspend fun searchSeries(name: String, category: BookWalkerCategory): Collection<BookWalkerSearchResult> {

        return try {
            // New bookwalker.com uses /browse?search=X format
            val url = "$bookWalkerBaseUrl/browse?search=$name"
            logger.info { "BookWalker htmlFetcher is: ${if (htmlFetcher != null) "available" else "null"}" }
            val document = if (htmlFetcher != null) {
                logger.info { "BookWalker using Camofox: $url" }
                htmlFetcher.fetchHtml(url)
            } else {
                logger.info { "BookWalker using direct HTTP: $url" }
                htmlClient.get(url).bodyAsText()
            }
            logger.info { "BookWalker HTML length: ${document.length}, starts with: ${document.take(200)}" }
            val results = parser.parseSearchResults(document)
            logger.info { "BookWalker found ${results.size} results" }
            results
        } catch (e: ClientRequestException) {
            if (e.response.status == HttpStatusCode.NotFound) emptyList()
            else throw e
        }

    }

    suspend fun getSeriesBooks(id: BookWalkerSeriesId, page: Int): BookWalkerBookListPage {
        val url = "$bookWalkerBaseUrl/series/${id.id}?page=$page"
        val document = if (htmlFetcher != null) {
            htmlFetcher.fetchHtml(url)
        } else {
            htmlClient.get(url) {
                parameter("page", page)
            }.bodyAsText()
        }
        return parser.parseSeriesBooks(document)
    }

    suspend fun getBook(id: BookWalkerBookId): BookWalkerBook {
        val url = "$bookWalkerBaseUrl/title/${id.id}"
        val document = if (htmlFetcher != null) {
            htmlFetcher.fetchHtml(url)
        } else {
            htmlClient.get(url).bodyAsText()
        }
        return parser.parseBook(document)
    }

    suspend fun getBookApi(bookId: BookWalkerBookId): BookWalkerBookInfo {
        val result: List<BookWalkerBookInfo> = apiClient.get("https://member-app.bookwalker.jp/api/books/updates") {
            parameter("fileType", "EPUB")
            parameter(bookId.id.removePrefix("de"), "0")
        }.body()

        check(result.isNotEmpty()) { "Failed to retrieve book info for bookId ${bookId.id}" }
        return result.first()
    }

    suspend fun getThumbnail(url: String): Image? {
        val bytes: ByteArray = apiClient.get(url).body()
        return Image(bytes)
    }
}
