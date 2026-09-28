package com.vrplayer.app.smb

import android.content.Context

data class SmbSavedAccount(
    val host: String,
    val username: String,
    val password: String,
    val domain: String,
    val anonymous: Boolean
)

class SmbCredentialsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(host: String): SmbSavedAccount? {
        val key = keyOf(host)
        if (!prefs.getBoolean(key + SUFFIX_SAVED, false)) return null
        return SmbSavedAccount(
            host = host,
            username = prefs.getString(key + SUFFIX_USER, "").orEmpty(),
            password = prefs.getString(key + SUFFIX_PASS, "").orEmpty(),
            domain = prefs.getString(key + SUFFIX_DOMAIN, "").orEmpty(),
            anonymous = prefs.getBoolean(key + SUFFIX_ANON, false)
        )
    }

    fun save(account: SmbSavedAccount) {
        val key = keyOf(account.host)
        prefs.edit()
            .putBoolean(key + SUFFIX_SAVED, true)
            .putString(key + SUFFIX_USER, account.username)
            .putString(key + SUFFIX_PASS, account.password)
            .putString(key + SUFFIX_DOMAIN, account.domain)
            .putBoolean(key + SUFFIX_ANON, account.anonymous)
            .apply()
    }

    fun clear(host: String) {
        val key = keyOf(host)
        prefs.edit()
            .remove(key + SUFFIX_SAVED)
            .remove(key + SUFFIX_USER)
            .remove(key + SUFFIX_PASS)
            .remove(key + SUFFIX_DOMAIN)
            .remove(key + SUFFIX_ANON)
            .apply()
    }

    private fun keyOf(host: String): String = "h_" + host.lowercase()

    companion object {
        private const val PREFS = "smb_accounts"
        private const val SUFFIX_SAVED = "_saved"
        private const val SUFFIX_USER = "_user"
        private const val SUFFIX_PASS = "_pass"
        private const val SUFFIX_DOMAIN = "_domain"
        private const val SUFFIX_ANON = "_anon"
    }
}
