package app.pockettavern.domain

import java.math.BigDecimal

/** Which Venice scope produced a balance snapshot. */
enum class VeniceBalanceScope {
    /** Account-level wallet balance from /billing/balance. */
    ACCOUNT,
    /** API-key-level allowance from /api_keys/rate_limits. */
    API_KEY
}

data class VeniceBalance(
    val subscriptionCredits: BigDecimal? = null,
    val purchasedCredits: BigDecimal? = null,
    val diem: BigDecimal? = null,
    val earnedCredits: BigDecimal? = null,
    val scope: VeniceBalanceScope = VeniceBalanceScope.ACCOUNT,
    val canConsume: Boolean? = null,
    val fetchedAt: Long = System.currentTimeMillis()
)

data class VeniceBalanceState(
    val balance: VeniceBalance? = null,
    val loading: Boolean = false,
    val error: String = ""
)
