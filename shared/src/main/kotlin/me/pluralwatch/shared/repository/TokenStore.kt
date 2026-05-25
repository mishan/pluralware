package me.pluralwatch.shared.repository

import me.pluralwatch.shared.api.PluralKitToken

/**
 * Where the PluralKit token lives. Implementations:
 *  - EncryptedTokenStore (Android, EncryptedSharedPreferences) — production.
 *  - InMemoryTokenStore  — for tests and previews.
 *
 * Suspend functions because [androidx.security.crypto] is disk-backed.
 */
interface TokenStore {
    suspend fun getToken(): PluralKitToken?
    suspend fun setToken(token: PluralKitToken)
    suspend fun clear()
}

class InMemoryTokenStore(initial: PluralKitToken? = null) : TokenStore {
    private var token: PluralKitToken? = initial
    override suspend fun getToken(): PluralKitToken? = token
    override suspend fun setToken(token: PluralKitToken) { this.token = token }
    override suspend fun clear() { token = null }
}
