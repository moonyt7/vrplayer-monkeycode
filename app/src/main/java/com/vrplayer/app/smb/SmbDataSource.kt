package com.vrplayer.app.smb

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.vrplayer.app.SmbTarget
import java.util.EnumSet

@UnstableApi
class SmbDataSource(
    private val target: SmbTarget,
    private val factory: Factory
) : BaseDataSource(/* isNetwork = */ true) {

    private var share: DiskShare? = null
    private var file: File? = null
    private var openedUri: Uri? = null
    private var bytesRemaining = 0L
    private var position = 0L
    private var fileSize = 0L
    private val cache = ByteArray(PREFETCH)
    private var cachePos = -1L
    private var cacheLen = 0

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val handle = factory.session()
        val disk = handle.session.connectShare(target.share) as DiskShare
        share = disk
        val remote = disk.openFile(
            target.path.replace('/', '\\'),
            EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES, AccessMask.SYNCHRONIZE),
            null,
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
            SMB2CreateDisposition.FILE_OPEN,
            null
        )
        file = remote
        fileSize = remote.fileInformation.standardInformation.endOfFile
        position = dataSpec.position
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            (fileSize - position).coerceAtLeast(0L)
        } else {
            dataSpec.length
        }
        cachePos = -1L
        cacheLen = 0
        openedUri = dataSpec.uri
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val want = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), bytesRemaining).toInt()
        }
        fill(position, want)
        if (cacheLen <= 0 || position < cachePos || position >= cachePos + cacheLen) {
            return C.RESULT_END_OF_INPUT
        }
        val from = (position - cachePos).toInt()
        val n = minOf(want, cacheLen - from)
        System.arraycopy(cache, from, buffer, offset, n)
        position += n
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= n
        }
        bytesTransferred(n)
        return n
    }

    private fun fill(pos: Long, minNeed: Int) {
        if (cachePos >= 0 && pos >= cachePos && pos + minNeed <= cachePos + cacheLen) return
        val remote = file ?: return
        val take = minOf(PREFETCH.toLong(), (fileSize - pos).coerceAtLeast(0L)).toInt()
        if (take <= 0) {
            cacheLen = 0
            return
        }
        var got = 0
        while (got < take) {
            val n = remote.read(cache, pos + got, got, take - got)
            if (n <= 0) break
            got += n
        }
        cachePos = pos
        cacheLen = got
    }

    override fun getUri(): Uri? = openedUri

    override fun close() {
        try {
            file?.close()
        } finally {
            file = null
        }
        try {
            share?.close()
        } finally {
            share = null
        }
        cachePos = -1L
        cacheLen = 0
        if (openedUri != null) {
            transferEnded()
            openedUri = null
        }
    }

    class Factory(
        private val target: SmbTarget
    ) : DataSource.Factory {
        private val lock = Any()
        private var handle: SmbSessionHandle? = null

        fun session(): SmbSessionHandle {
            synchronized(lock) {
                handle?.let { return it }
                val opened = SmbAuth.open(target.host, target.username, target.password, target.domain)
                handle = opened
                return opened
            }
        }

        fun release() {
            synchronized(lock) {
                try {
                    handle?.close()
                } finally {
                    handle = null
                }
            }
        }

        override fun createDataSource(): DataSource = SmbDataSource(target, this)
    }

    companion object {
        private const val PREFETCH = 1024 * 1024
    }
}
