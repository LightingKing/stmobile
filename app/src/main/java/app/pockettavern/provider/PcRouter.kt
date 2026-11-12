package app.pockettavern.provider

import app.pockettavern.domain.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Picks a PC writing server's LAN address while this phone is at home, else its main (remote)
 * address. Before any token goes over the plain-HTTP LAN address, the PC must prove it knows the
 * token: it signs a random nonce with it, and only its LAN listener answers. A device that happens
 * to have the same IP on another network cannot, so the token is never sent to it.
 */
class PcRouter(
    client: OkHttpClient = OkHttpClient(),
    private val allowInsecureHttpForTests: Boolean = false,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val client = client.newBuilder()
        .connectTimeout(800, TimeUnit.MILLISECONDS)
        .callTimeout(2, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        // A dead reused connection would make the PC look absent from the LAN (see ProviderClient).
        .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS))
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private data class Verdict(val lan: Boolean, val at: Long)
    private val verdicts = ConcurrentHashMap<String, Verdict>()
    private val random = SecureRandom()

    private fun cacheKey(provider: ProviderConfig) = "${provider.id}|${provider.lanBaseUrl.trim()}"

    private fun usesLan(provider: ProviderConfig) =
        provider.kind.equals("writing-server", ignoreCase = true) && provider.lanBaseUrl.isNotBlank()

    /** The address to use now. Probes at most every few minutes; a network change clears that. */
    suspend fun resolve(provider: ProviderConfig, key: String): ProviderConfig {
        if (!usesLan(provider) || key.isBlank()) return provider
        val known = verdicts[cacheKey(provider)]
        val fresh = known != null && now() - known.at < if (known.lan) LAN_TTL else REMOTE_TTL
        val lan = if (fresh) known!!.lan else withContext(Dispatchers.IO) { proves(provider, key) }.also {
            verdicts[cacheKey(provider)] = Verdict(it, now())
        }
        return if (lan) provider.copy(baseUrl = provider.lanBaseUrl.trim()) else provider
    }

    /** Without probing: the LAN address only if the last check found it. For blocking callers. */
    fun current(provider: ProviderConfig): ProviderConfig =
        if (usesLan(provider) && verdicts[cacheKey(provider)]?.lan == true) provider.copy(baseUrl = provider.lanBaseUrl.trim()) else provider

    /** Whether the last check put this provider on the LAN; null before any check. */
    fun onLan(provider: ProviderConfig): Boolean? = verdicts[cacheKey(provider)]?.lan

    /** The phone changed networks or a LAN request failed: check again next time. */
    fun forget() = verdicts.clear()

    private fun proves(provider: ProviderConfig, key: String): Boolean = try {
        val base: HttpUrl = ProviderUrlPolicy.parse(provider.copy(baseUrl = provider.lanBaseUrl.trim()), allowInsecureHttpForTests)
        val nonce = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val url = base.newBuilder().addPathSegments("pocket/hello").addQueryParameter("nonce", nonce).build()
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) return false
            val proof = JSONObject(response.body?.string().orEmpty()).optString("proof")
            MessageDigest.isEqual(proof.toByteArray(), expectedProof(key, nonce).toByteArray())
        }
    } catch (_: Exception) {
        false
    }

    companion object {
        private const val LAN_TTL = 5 * 60_000L
        private const val REMOTE_TTL = 60_000L

        fun expectedProof(key: String, nonce: String): String {
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.trim().toByteArray(Charsets.UTF_8), "HmacSHA256")) }
            return mac.doFinal("pocket-tavern-lan:$nonce".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }
}
