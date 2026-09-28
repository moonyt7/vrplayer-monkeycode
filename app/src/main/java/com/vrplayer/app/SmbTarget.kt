package com.vrplayer.app

import android.content.Intent

data class SmbTarget(
    val host: String,
    val share: String,
    val path: String,
    val username: String,
    val password: String,
    val domain: String
) {
    fun child(name: String): SmbTarget {
        val next = if (path.isEmpty()) name else "$path\\$name"
        return copy(path = next)
    }

    fun parent(): SmbTarget {
        val idx = path.lastIndexOf('\\')
        val next = if (idx <= 0) "" else path.substring(0, idx)
        return copy(path = next)
    }

    fun put(intent: Intent) {
        intent.putExtra(EXTRA_HOST, host)
        intent.putExtra(EXTRA_SHARE, share)
        intent.putExtra(EXTRA_PATH, path)
        intent.putExtra(EXTRA_USER, username)
        intent.putExtra(EXTRA_PASSWORD, password)
        intent.putExtra(EXTRA_DOMAIN, domain)
    }

    companion object {
        const val EXTRA_HOST = "smb_host"
        const val EXTRA_SHARE = "smb_share"
        const val EXTRA_PATH = "smb_path"
        const val EXTRA_USER = "smb_user"
        const val EXTRA_PASSWORD = "smb_password"
        const val EXTRA_DOMAIN = "smb_domain"

        fun from(intent: Intent): SmbTarget? {
            val host = intent.getStringExtra(EXTRA_HOST) ?: return null
            val share = intent.getStringExtra(EXTRA_SHARE) ?: return null
            return SmbTarget(
                host = host,
                share = share,
                path = intent.getStringExtra(EXTRA_PATH).orEmpty(),
                username = intent.getStringExtra(EXTRA_USER).orEmpty(),
                password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty(),
                domain = intent.getStringExtra(EXTRA_DOMAIN).orEmpty()
            )
        }
    }
}
