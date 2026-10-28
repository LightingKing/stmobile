package app.pockettavern.provider

import app.pockettavern.domain.ProviderConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The one URL policy shared by the provider editor and the HTTP client.
 *
 * A writing-server may use plain HTTP only when its host is an RFC1918
 * literal IPv4 address. This is deliberately narrower than allowing
 * localhost, DNS names, IPv6, or arbitrary private-looking hostnames.
 */
object ProviderUrlPolicy {
    fun violation(provider: ProviderConfig, allowInsecureHttpForTests: Boolean = false): String? {
        val raw = provider.baseUrl.trim()
        val url = raw.toHttpUrlOrNull() ?: return "API 地址无效"
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return "API 地址不能包含账号信息"
        if (url.encodedQuery != null || url.fragment != null) return "API 地址不能包含查询参数或片段"
        if (url.scheme == "https") return null
        if (allowInsecureHttpForTests && isLoopback(url.host) && url.scheme == "http") return null
        if (provider.kind.equals("writing-server", ignoreCase = true) && url.scheme == "http" && isPrivateIpv4Literal(url.host)) return null
        return "API 地址必须使用 HTTPS；局域网写作服务仅允许 RFC1918 字面 IPv4 的 HTTP 地址"
    }

    fun isPrivateIpv4Literal(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { part ->
                part.isEmpty() || (part.length > 1 && part[0] == '0') || part.any { character -> !character.isDigit() }
            }) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        return octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }

    private fun isLoopback(host: String): Boolean = host == "localhost" || host == "127.0.0.1" || host == "::1"

    fun parse(provider: ProviderConfig, allowInsecureHttpForTests: Boolean = false): HttpUrl {
        violation(provider, allowInsecureHttpForTests)?.let { throw IllegalArgumentException(it) }
        return provider.baseUrl.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("API 地址无效")
    }
}
