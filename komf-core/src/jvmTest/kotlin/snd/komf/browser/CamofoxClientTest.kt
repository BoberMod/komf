package snd.komf.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CamofoxClientTest {

    @Test
    fun `CamofoxConfig has correct defaults`() {
        val config = CamofoxConfig()
        assertFalse(config.enabled)
        assertEquals("http://localhost:9377", config.baseUrl)
        assertEquals(null, config.apiKey)
        assertEquals("komf", config.userId)
    }

    @Test
    fun `CamofoxConfig can be customized`() {
        val config = CamofoxConfig(
            enabled = true,
            baseUrl = "http://custom-host:8080",
            apiKey = "test-api-key",
            userId = "custom-user"
        )
        assertTrue(config.enabled)
        assertEquals("http://custom-host:8080", config.baseUrl)
        assertEquals("test-api-key", config.apiKey)
        assertEquals("custom-user", config.userId)
    }

    @Test
    fun `CreateTabRequest serializes correctly`() {
        val request = CreateTabRequest(
            userId = "komf",
            url = "https://example.com",
            sessionKey = "test-session"
        )
        assertEquals("komf", request.userId)
        assertEquals("https://example.com", request.url)
        assertEquals("test-session", request.sessionKey)
    }

    @Test
    fun `TabInfo deserializes correctly`() {
        val tabInfo = TabInfo(
            id = "tab-123",
            url = "https://example.com",
            userId = "komf",
            sessionKey = "test-session"
        )
        assertEquals("tab-123", tabInfo.id)
        assertEquals("https://example.com", tabInfo.url)
        assertEquals("komf", tabInfo.userId)
        assertEquals("test-session", tabInfo.sessionKey)
    }

    @Test
    fun `SnapshotResponse can hold snapshot and screenshot`() {
        val snapshot = SnapshotResponse(
            snapshot = "[link e1] Example Domain",
            screenshot = "base64encodedpng"
        )
        assertNotNull(snapshot.snapshot)
        assertTrue(snapshot.snapshot.contains("Example Domain"))
        assertNotNull(snapshot.screenshot)
    }

    @Test
    fun `NavigateRequest supports url and macro`() {
        val urlRequest = NavigateRequest(
            userId = "komf",
            url = "https://example.com"
        )
        assertEquals("https://example.com", urlRequest.url)
        assertEquals(null, urlRequest.macro)

        val macroRequest = NavigateRequest(
            userId = "komf",
            macro = "@google_search",
            query = "test query"
        )
        assertEquals(null, macroRequest.url)
        assertEquals("@google_search", macroRequest.macro)
        assertEquals("test query", macroRequest.query)
    }
}
