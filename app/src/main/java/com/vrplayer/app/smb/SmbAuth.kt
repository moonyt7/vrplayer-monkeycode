package com.vrplayer.app.smb

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.auth.NtlmAuthenticator
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import java.util.concurrent.TimeUnit

class SmbSessionHandle(
    val client: SMBClient,
    val connection: Connection,
    val session: Session
) : AutoCloseable {
    override fun close() {
        try {
            session.close()
        } catch (_: Exception) {
        }
        try {
            connection.close()
        } catch (_: Exception) {
        }
        client.close()
    }
}

object SmbAuth {
    fun client(): SMBClient = SMBClient(config(12))

    fun quickClient(): SMBClient = SMBClient(config(2))

    fun open(
        host: String,
        username: String,
        password: String,
        domain: String,
        extraDomain: String = ""
    ): SmbSessionHandle {
        var last: Exception? = null
        for (ctx in contexts(username, password, domain, extraDomain)) {
            val client = client()
            var conn: Connection? = null
            try {
                conn = client.connect(host)
                val session = conn.authenticate(ctx)
                return SmbSessionHandle(client, conn, session)
            } catch (e: Exception) {
                last = e
                try {
                    conn?.close()
                } catch (_: Exception) {
                }
                client.close()
                if (!isLogonFailure(e)) throw e
            }
        }
        throw last ?: SmbException("auth")
    }

    fun contexts(
        username: String,
        password: String,
        domain: String,
        extraDomain: String = ""
    ): List<AuthenticationContext> {
        val raw = username.trim()
        if (raw.isEmpty()) {
            return listOf(
                AuthenticationContext.anonymous(),
                AuthenticationContext.guest()
            )
        }
        val (user, parsedDomain) = splitUser(raw, domain)
        val users = linkedSetOf(user)
        if (raw != user) users += raw
        val domains = linkedSetOf<String>()
        if (parsedDomain.isNotEmpty()) domains += parsedDomain
        val extra = extraDomain.trim()
        if (extra.isNotEmpty() && extra != user && !hostLikeIp(extra)) {
            domains += extra
        }
        domains += "."
        domains += ""
        domains += "WORKGROUP"
        val out = ArrayList<AuthenticationContext>(users.size * domains.size)
        for (u in users) {
            for (d in domains) {
                out += AuthenticationContext(u, password.toCharArray(), d)
            }
        }
        return out
    }

    fun splitUser(username: String, domain: String): Pair<String, String> {
        val raw = username.trim()
        val slash = raw.indexOf('\\')
        if (slash > 0) {
            return raw.substring(slash + 1) to raw.substring(0, slash)
        }
        val slash2 = raw.indexOf('/')
        if (slash2 > 0) {
            return raw.substring(slash2 + 1) to raw.substring(0, slash2)
        }
        return raw to domain.trim()
    }

    fun isLogonFailure(e: Throwable): Boolean {
        var cur: Throwable? = e
        while (cur != null) {
            if (cur is SMBApiException) {
                when (cur.status) {
                    NtStatus.STATUS_LOGON_FAILURE,
                    NtStatus.STATUS_PASSWORD_EXPIRED,
                    NtStatus.STATUS_ACCOUNT_DISABLED,
                    NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED,
                    NtStatus.STATUS_NETWORK_SESSION_EXPIRED -> return true
                    else -> Unit
                }
            }
            val msg = cur.message.orEmpty().lowercase()
            if (
                "status_logon_failure" in msg ||
                "logon failure" in msg ||
                "logon_failure" in msg ||
                "authentication failed" in msg ||
                "could not authenticate" in msg
            ) {
                return true
            }
            cur = cur.cause
        }
        return false
    }

    private fun hostLikeIp(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { it.toIntOrNull() != null }
    }

    private fun config(seconds: Long): SmbConfig {
        val readSize = 1024 * 1024
        return SmbConfig.builder()
            .withTimeout(seconds, TimeUnit.SECONDS)
            .withSoTimeout(seconds, TimeUnit.SECONDS)
            .withReadTimeout(seconds, TimeUnit.SECONDS)
            .withTransactTimeout(seconds, TimeUnit.SECONDS)
            .withReadBufferSize(readSize)
            .withWriteBufferSize(readSize)
            .withTransactBufferSize(readSize)
            .withDfsEnabled(false)
            .withSigningRequired(false)
            .withDialects(
                SMB2Dialect.SMB_3_1_1,
                SMB2Dialect.SMB_3_0_2,
                SMB2Dialect.SMB_3_0,
                SMB2Dialect.SMB_2_1,
                SMB2Dialect.SMB_2_0_2
            )
            .withAuthenticators(NtlmAuthenticator.Factory())
            .build()
    }
}
