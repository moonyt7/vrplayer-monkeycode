package com.vrplayer.app.smb

import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.util.Properties

object SmbJcifs {
    fun listDiskShares(
        host: String,
        username: String,
        password: String,
        domain: String,
        extraDomain: String = ""
    ): List<String> {
        val names = linkedSetOf<String>()
        for (auth in authenticators(username, password, domain, extraDomain)) {
            val found = runCatching { enumShares(host, auth) }.getOrDefault(emptyList())
            names += found
            if (names.size > 1) return names.toList()
        }
        return names.toList()
    }

    private fun authenticators(
        username: String,
        password: String,
        domain: String,
        extraDomain: String
    ): List<NtlmPasswordAuthenticator?> {
        val list = ArrayList<NtlmPasswordAuthenticator?>()
        if (username.isBlank()) {
            list += null
            return list
        }
        val domains = linkedSetOf<String>()
        val slash = username.indexOf('\\')
        val user: String
        if (slash >= 0) {
            domains += username.substring(0, slash).trim()
            user = username.substring(slash + 1).trim()
        } else {
            user = username.trim()
        }
        domains += domain.trim()
        domains += extraDomain.trim()
        domains += ""
        domains += "."
        domains += "WORKGROUP"
        for (d in domains) {
            list += NtlmPasswordAuthenticator(d, user, password)
        }
        return list
    }

    private fun enumShares(host: String, auth: NtlmPasswordAuthenticator?): List<String> {
        val props = Properties()
        props.setProperty("jcifs.smb.client.minVersion", "SMB202")
        props.setProperty("jcifs.smb.client.maxVersion", "SMB311")
        props.setProperty("jcifs.smb.client.dfs.disabled", "true")
        props.setProperty("jcifs.smb.client.soTimeout", "10000")
        props.setProperty("jcifs.smb.client.connTimeout", "8000")
        props.setProperty("jcifs.smb.client.responseTimeout", "10000")
        props.setProperty("jcifs.smb.client.ipcSigningEnforced", "false")
        val base = BaseContext(PropertyConfiguration(props))
        val ctx: CIFSContext = if (auth == null) {
            base.withAnonymousCredentials()
        } else {
            base.withCredentials(auth)
        }
        try {
            SmbFile("smb://$host/", ctx).use { root ->
                val files = root.listFiles() ?: return emptyList()
                val out = ArrayList<String>()
                for (file in files) {
                    val name = file.name.trimEnd('/').trimEnd('\\').trim()
                    if (!keep(name, file)) continue
                    out += name
                }
                return out.distinct()
            }
        } finally {
            runCatching { base.close() }
        }
    }

    private fun keep(name: String, file: SmbFile): Boolean {
        if (name.length !in 2..80) return false
        if (name.any { it.code < 32 || it in "\\/:*?\"<>|" }) return false
        if (name.equals("IPC$", true) || name.equals("IPC", true)) return false
        val type = runCatching { file.type }.getOrDefault(0)
        if (type == SmbFile.TYPE_PRINTER || type == SmbFile.TYPE_NAMED_PIPE || type == SmbFile.TYPE_COMM) {
            return false
        }
        if (name.matches(Regex("^[A-Za-z]\\$"))) return true
        return !name.endsWith('$')
    }
}
