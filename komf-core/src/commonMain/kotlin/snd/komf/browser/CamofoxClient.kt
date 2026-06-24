package snd.komf.browser

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.bearerAuth
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
    private val accessKey = config.accessKey
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun io.ktor.client.request.HttpRequestBuilder.addAuth() {
        if (accessKey != null) {
            bearerAuth(accessKey)
        }
    }

    suspend fun createTab(url: String, sessionKey: String = "default"): TabInfo {
        logger.debug { "Creating tab for URL: $url" }
        val requestBody = json.encodeToString(CreateTabRequest.serializer(), CreateTabRequest(
            userId = userId,
            url = url,
            sessionKey = sessionKey
        ))
        val response = httpClient.post("$baseUrl/tabs") {
            addAuth()
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        return response.body()
    }

    suspend fun getSnapshot(
        tabId: String,
        includeScreenshot: Boolean = false,
        offset: Int? = null,
    ): SnapshotResponse {
        logger.debug { "Getting snapshot for tab: $tabId" }
        val response = httpClient.get("$baseUrl/tabs/$tabId/snapshot") {
            addAuth()
            parameter("userId", userId)
            if (includeScreenshot) parameter("includeScreenshot", "true")
            if (offset != null) parameter("offset", offset)
        }
        return response.body()
    }

    suspend fun navigate(tabId: String, url: String? = null, macro: String? = null, query: String? = null) {
        logger.debug { "Navigating tab $tabId to: ${url ?: macro}" }
        httpClient.post("$baseUrl/tabs/$tabId/navigate") {
            addAuth()
            contentType(ContentType.Application.Json)
            setBody(NavigateRequest(
                userId = userId,
                url = url,
                macro = macro,
                query = query
            ))
        }
    }

    suspend fun click(tabId: String, ref: String? = null, selector: String? = null) {
        logger.debug { "Clicking element in tab $tabId: ref=$ref, selector=$selector" }
        httpClient.post("$baseUrl/tabs/$tabId/click") {
            addAuth()
            contentType(ContentType.Application.Json)
            setBody(ClickRequest(
                userId = userId,
                ref = ref,
                selector = selector
            ))
        }
    }

    suspend fun type(tabId: String, ref: String, text: String, pressEnter: Boolean = false) {
        logger.debug { "Typing into tab $tabId: ref=$ref" }
        httpClient.post("$baseUrl/tabs/$tabId/type") {
            addAuth()
            contentType(ContentType.Application.Json)
            setBody(TypeRequest(
                userId = userId,
                ref = ref,
                text = text,
                pressEnter = pressEnter
            ))
        }
    }

    suspend fun scroll(tabId: String, direction: String = "down") {
        logger.debug { "Scrolling tab $tabId: $direction" }
        httpClient.post("$baseUrl/tabs/$tabId/scroll") {
            addAuth()
            contentType(ContentType.Application.Json)
            setBody(ScrollRequest(
                userId = userId,
                direction = direction
            ))
        }
    }

    suspend fun closeTab(tabId: String) {
        logger.debug { "Closing tab: $tabId" }
        try {
            httpClient.delete("$baseUrl/tabs/$tabId") {
                addAuth()
                parameter("userId", userId)
            }
        } catch (e: ClientRequestException) {
            if (e.response.status != HttpStatusCode.NotFound) throw e
        }
    }

    suspend fun listTabs(): List<TabInfo> {
        logger.debug { "Listing tabs for user: $userId" }
        return httpClient.get("$baseUrl/tabs") {
            addAuth()
            parameter("userId", userId)
        }.body()
    }

    suspend fun closeAllSessions() {
        logger.debug { "Closing all sessions for user: $userId" }
        try {
            httpClient.delete("$baseUrl/sessions/$userId") {
                addAuth()
            }
        } catch (e: ClientRequestException) {
            if (e.response.status != HttpStatusCode.NotFound) throw e
        }
    }

    suspend fun getPageHtml(url: String): String {
        logger.info { "Camofox navigating to: $url" }
        val tab = createTab(url)
        val tabId = tab.effectiveId()
        try {
            waitForSelector(tabId, "body", 10000)
            val pageUrl = evaluateJs(tabId, "window.location.href")
            val pageTitle = evaluateJs(tabId, "document.title")
            val isChallenge = evaluateJs(tabId, "document.querySelector('#challenge-running, #challenge-form, [data-cf-challenge]') !== null || document.title.includes('Just a moment')")
            logger.info { "Camofox page loaded: url=$pageUrl, title=$pageTitle, isCloudflareChallenge=$isChallenge" }
            val html = evaluateJs(tabId, "document.documentElement.outerHTML")
            logger.info { "Camofox got HTML: ${html.length} bytes" }
            return html
        } finally {
            closeTab(tabId)
        }
    }

    suspend fun waitForSelector(tabId: String, selector: String, timeout: Long = 10000) {
        logger.debug { "Waiting for selector '$selector' in tab $tabId" }
        httpClient.post("$baseUrl/tabs/$tabId/wait") {
            addAuth()
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(WaitRequest.serializer(), WaitRequest(
                userId = userId,
                selector = selector,
                timeout = timeout
            )))
        }
    }

    suspend fun evaluateJs(tabId: String, expression: String): String {
        logger.debug { "Evaluating JS in tab $tabId" }
        val response: EvaluateResponse = httpClient.post("$baseUrl/tabs/$tabId/evaluate") {
            addAuth()
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(EvaluateRequest.serializer(), EvaluateRequest(
                userId = userId,
                expression = expression
            )))
        }.body()
        return response.result ?: ""
    }

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

@Serializable
data class CamofoxConfig(
    val enabled: Boolean = false,
    val baseUrl: String = "http://localhost:9377",
    val accessKey: String? = null,
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

@Serializable
data class WaitRequest(
    val userId: String,
    val selector: String,
    val timeout: Long = 10000,
)

@Serializable
data class EvaluateRequest(
    val userId: String,
    val expression: String,
)

@Serializable
data class EvaluateResponse(
    val ok: Boolean = true,
    val result: String? = null,
)
