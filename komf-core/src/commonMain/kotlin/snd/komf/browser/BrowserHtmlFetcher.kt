package snd.komf.browser

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText

private val logger = KotlinLogging.logger {}

/**
 * Interface for fetching HTML content from URLs.
 * Supports both direct HTTP and browser-based fetching via Camofox.
 */
interface HtmlFetcher {
    suspend fun fetchHtml(url: String): String
}

/**
 * Direct HTTP fetcher using Ktor HttpClient.
 */
class DirectHtmlFetcher(
    private val httpClient: HttpClient,
) : HtmlFetcher {
    override suspend fun fetchHtml(url: String): String {
        return httpClient.get(url).bodyAsText()
    }
}

/**
 * Browser-based HTML fetcher using Camofox.
 * Provides anti-detection capabilities for sites that block direct HTTP requests.
 */
class CamofoxHtmlFetcher(
    private val camofoxClient: CamofoxClient,
) : HtmlFetcher {
    override suspend fun fetchHtml(url: String): String {
        logger.info { "Fetching via Camofox browser: $url" }
        return camofoxClient.getPageHtml(url)
    }
}

/**
 * Composite fetcher that tries Camofox first (if available), then falls back to direct HTTP.
 */
class FallbackHtmlFetcher(
    private val directFetcher: DirectHtmlFetcher,
    private val camofoxFetcher: CamofoxHtmlFetcher?,
) : HtmlFetcher {
    override suspend fun fetchHtml(url: String): String {
        if (camofoxFetcher != null) {
            return try {
                camofoxFetcher.fetchHtml(url)
            } catch (e: Exception) {
                logger.warn(e) { "Camofox fetch failed, falling back to direct HTTP: $url" }
                directFetcher.fetchHtml(url)
            }
        }
        return directFetcher.fetchHtml(url)
    }
}
