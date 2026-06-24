package snd.komf.browser

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

/**
 * Client for Camofox Browser REST API.
 * Provides anti-detection browser capabilities for web scraping.
 */
class CamofoxClient(
    private val httpClient: HttpClient,
    private val config: CamofoxConfig,
) {
    private val baseUrl = config.baseUrl.trimEnd('/')
    private val userId = config.userId
    private val json = Json { 
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Creates a new browser tab and navigates to the specified URL.
     * @param url The URL to navigate to
     * @param sessionKey Optional session key for tab grouping
     * @return Tab information including the tab ID
     */
    suspend fun createTab(url: String, sessionKey: String = "default"): TabInfo {
        logger.debug { "Creating tab for URL: $url" }
        val requestBody = json.encodeToString(CreateTabRequest.serializer(), CreateTabRequest(
            userId = userId,
            url = url,
            sessionKey = sessionKey
        ))
        val response = httpClient.post("$baseUrl/tabs") {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        return response.body()
    }

    /**
     * Gets the accessibility snapshot of a tab.
     * @param tabId The tab ID
     * @param includeScreenshot Whether to include a base64 PNG screenshot
     * @param offset Pagination offset for large snapshots
     * @return The accessibility snapshot with element refs
     */
    suspend fun getSnapshot(
        tabId: String,
        includeScreenshot: Boolean = false,
        offset: Int? = null,
    ): SnapshotResponse {
        logger.debug { "Getting snapshot for tab: $tabId" }
        val response = httpClient.get("$baseUrl/tabs/$tabId/snapshot") {
            parameter("userId", userId)
            if (includeScreenshot) parameter("includeScreenshot", "true")
            if (offset != null) parameter("offset", offset)
        }
        return response.body()
    }

    /**
     * Navigates a tab to a URL or search macro.
     * @param tabId The tab ID
     * @param url The URL to navigate to
     * @param macro Optional search macro (e.g., @google_search)
     * @param query Optional search query for macros
     */
    suspend fun navigate(tabId: String, url: String? = null, macro: String? = null, query: String? = null) {
        logger.debug { "Navigating tab $tabId to: ${url ?: macro}" }
        httpClient.post("$baseUrl/tabs/$tabId/navigate") {
            contentType(ContentType.Application.Json)
            setBody(NavigateRequest(
                userId = userId,
                url = url,
                macro = macro,
                query = query
            ))
        }
    }

    /**
     * Clicks an element by ref or CSS selector.
     * @param tabId The tab ID
     * @param ref Element reference (e.g., "e1")
     * @param selector CSS selector
     */
    suspend fun click(tabId: String, ref: String? = null, selector: String? = null) {
        logger.debug { "Clicking element in tab $tabId: ref=$ref, selector=$selector" }
        httpClient.post("$baseUrl/tabs/$tabId/click") {
            contentType(ContentType.Application.Json)
            setBody(ClickRequest(
                userId = userId,
                ref = ref,
                selector = selector
            ))
        }
    }

    /**
     * Types text into an element.
     * @param tabId The tab ID
     * @param ref Element reference
     * @param text Text to type
     * @param pressEnter Whether to press Enter after typing
     */
    suspend fun type(tabId: String, ref: String, text: String, pressEnter: Boolean = false) {
        logger.debug { "Typing into tab $tabId: ref=$ref" }
        httpClient.post("$baseUrl/tabs/$tabId/type") {
            contentType(ContentType.Application.Json)
            setBody(TypeRequest(
                userId = userId,
                ref = ref,
                text = text,
                pressEnter = pressEnter
            ))
        }
    }

    /**
     * Scrolls the page.
     * @param tabId The tab ID
     * @param direction Scroll direction (up, down, left, right)
     */
    suspend fun scroll(tabId: String, direction: String = "down") {
        logger.debug { "Scrolling tab $tabId: $direction" }
        httpClient.post("$baseUrl/tabs/$tabId/scroll") {
            contentType(ContentType.Application.Json)
            setBody(ScrollRequest(
                userId = userId,
                direction = direction
            ))
        }
    }

    /**
     * Closes a specific tab.
     * @param tabId The tab ID
     */
    suspend fun closeTab(tabId: String) {
        logger.debug { "Closing tab: $tabId" }
        try {
            httpClient.delete("$baseUrl/tabs/$tabId") {
                parameter("userId", userId)
            }
        } catch (e: ClientRequestException) {
            if (e.response.status != HttpStatusCode.NotFound) throw e
        }
    }

    /**
     * Lists all open tabs for the user.
     * @return List of tab information
     */
    suspend fun listTabs(): List<TabInfo> {
        logger.debug { "Listing tabs for user: $userId" }
        return httpClient.get("$baseUrl/tabs") {
            parameter("userId", userId)
        }.body()
    }

    /**
     * Closes all tabs and sessions for the user.
     */
    suspend fun closeAllSessions() {
        logger.debug { "Closing all sessions for user: $userId" }
        try {
            httpClient.delete("$baseUrl/sessions/$userId")
        } catch (e: ClientRequestException) {
            if (e.response.status != HttpStatusCode.NotFound) throw e
        }
    }

    /**
     * Gets the page HTML by navigating and extracting from snapshot.
     * @param url The URL to fetch
     * @return The page HTML content
     */
    suspend fun getPageHtml(url: String): String {
        val tab = createTab(url)
        val tabId = tab.effectiveId()
        try {
            // Wait for page to load
            kotlinx.coroutines.delay(2000)
            val snapshot = getSnapshot(tabId)
            return snapshot.snapshot
        } finally {
            closeTab(tabId)
        }
    }

    /**
     * Checks if the Camofox server is healthy.
     * @return true if server is reachable
     */
    suspend fun healthCheck(): Boolean {
        return try {
            val response = httpClient.get("$baseUrl/health")
            response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            logger.warn(e) { "Camofox health check failed" }
            false
        }
    }
}

// Request/Response models

@Serializable
data class CamofoxConfig(
    val enabled: Boolean = false,
    val baseUrl: String = "http://localhost:9377",
    val apiKey: String? = null,
    val userId: String = "komf",
)

@Serializable
data class CreateTabRequest(
    val userId: String,
    val url: String,
    val sessionKey: String = "default",
)

@Serializable
data class TabInfo(
    val tabId: String = "",
    val url: String = "",
    val id: String = "",
    val userId: String = "",
    val sessionKey: String = "default",
) {
    fun effectiveId(): String = tabId.ifEmpty { id }
}

@Serializable
data class SnapshotResponse(
    val snapshot: String,
    val screenshot: String? = null,
)

@Serializable
data class NavigateRequest(
    val userId: String,
    val url: String? = null,
    val macro: String? = null,
    val query: String? = null,
)

@Serializable
data class ClickRequest(
    val userId: String,
    val ref: String? = null,
    val selector: String? = null,
)

@Serializable
data class TypeRequest(
    val userId: String,
    val ref: String,
    val text: String,
    val pressEnter: Boolean = false,
)

@Serializable
data class ScrollRequest(
    val userId: String,
    val direction: String = "down",
)
