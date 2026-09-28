package com.vrplayer.app.smb

import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.session.Session
import com.vrplayer.app.SmbTarget

data class SmbEntry(
    val name: String,
    val directory: Boolean
)

class SmbException(message: String, cause: Throwable? = null) : Exception(message, cause)

object SmbBrowser {
    private val videoExt = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "ts", "m2ts", "wmv", "flv")

    fun listShares(
        host: String,
        username: String,
        password: String,
        domain: String,
        extraDomain: String = ""
    ): List<String> {
        try {
            val names = linkedSetOf<String>()
            names += runCatching {
                SmbJcifs.listDiskShares(host, username, password, domain, extraDomain)
            }.getOrDefault(emptyList())
            SmbAuth.open(host, username, password, domain, extraDomain).use { handle ->
                names += SmbShareEnum.listDiskShares(handle.session, host, extraDomain)
                names += probeShares(handle.session)
            }
            return names.toList()
        } catch (e: Exception) {
            throw mapError(e)
        }
    }

    fun list(target: SmbTarget): List<SmbEntry> {
        try {
            SmbAuth.open(target.host, target.username, target.password, target.domain).use { handle ->
                handle.session.connectShare(target.share).use { share ->
                    val disk = share as com.hierynomus.smbj.share.DiskShare
                    val path = target.path.replace('/', '\\')
                    val files = disk.list(path)
                    return files.mapNotNull { toEntry(it) }
                        .sortedWith(compareByDescending<SmbEntry> { it.directory }.thenBy { it.name.lowercase() })
                }
            }
        } catch (e: Exception) {
            throw mapError(e)
        }
    }

    private fun probeShares(session: Session): List<String> {
        val names = listOf(
            "Public", "Videos", "Movies", "Shared", "Share", "Media", "Users",
            "Download", "Downloads", "Music", "Photo", "Photos", "home", "homes",
            "video", "Video", "movie", "data", "Data", "disk", "Disk",
            "C$", "D$", "E$", "F$", "G$", "H$"
        )
        val found = ArrayList<String>()
        for (name in names) {
            try {
                session.connectShare(name).use { share ->
                    if (share is com.hierynomus.smbj.share.DiskShare) found += name
                }
            } catch (_: Exception) {
            }
        }
        return found.distinct()
    }

    private fun toEntry(info: FileIdBothDirectoryInformation): SmbEntry? {
        val name = info.fileName
        if (name == "." || name == "..") return null
        val dir = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
        return SmbEntry(name.trim(), dir)
    }

    fun isVideo(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in videoExt
    }

    private fun mapError(e: Exception): SmbException {
        if (e is SmbException) return e
        if (SmbAuth.isLogonFailure(e)) return SmbException("auth", e)
        var cur: Throwable? = e
        while (cur != null) {
            if (cur is SMBApiException) {
                when (cur.status) {
                    NtStatus.STATUS_BAD_NETWORK_NAME,
                    NtStatus.STATUS_BAD_NETWORK_PATH,
                    NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
                    NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
                    NtStatus.STATUS_ACCESS_DENIED -> return SmbException("share", e)
                    else -> Unit
                }
            }
            cur = cur.cause
        }
        val msg = e.message.orEmpty().lowercase()
        return when {
            "share" in msg && ("not found" in msg || "bad" in msg) -> SmbException("share", e)
            else -> SmbException("host", e)
        }
    }
}
