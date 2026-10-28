package app.pockettavern.provider

import app.pockettavern.domain.ProviderConfig
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class ProviderUrlPolicyTest {
    private fun provider(kind: String, url: String) = ProviderConfig(kind = kind, baseUrl = url)

    @Test
    fun `writing server accepts only private literal ipv4 http`() {
        listOf(
            "http://10.0.0.1:8791/v1",
            "http://172.16.0.1:8791/v1",
            "http://172.31.255.254:8791/v1",
            "http://192.168.1.22:8791/v1"
        ).forEach { assertNull(ProviderUrlPolicy.violation(provider("writing-server", it))) }
    }

    @Test
    fun `writing server rejects public dns credentials query and ipv4 boundaries`() {
        listOf(
            "http://9.255.255.255:8791/v1",
            "http://172.15.255.255:8791/v1",
            "http://172.32.0.1:8791/v1",
            "http://192.167.1.22:8791/v1",
            "http://example.test:8791/v1",
            "http://10.0.0.1:8791/v1?token=secret",
            "http://user:pass@10.0.0.1:8791/v1",
            "http://10.0.0.1:8791/v1#fragment",
            "http://127.0.0.1:8791/v1",
            "http://[::1]:8791/v1"
        ).forEach { assertEquals(it, true, ProviderUrlPolicy.violation(provider("writing-server", it)) != null) }
    }

    @Test
    fun `normal providers still require https`() {
        assertEquals(true, ProviderUrlPolicy.violation(provider("openai", "http://192.168.1.22:8791/v1")) != null)
        assertNull(ProviderUrlPolicy.violation(provider("openai", "https://api.example.test/v1")))
    }
}
