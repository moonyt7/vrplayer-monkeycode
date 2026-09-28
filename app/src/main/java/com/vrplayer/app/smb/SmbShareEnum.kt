package com.vrplayer.app.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ImpersonationLevel
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.NamedPipe
import com.hierynomus.smbj.share.PipeShare
import com.hierynomus.smbj.share.Share
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.EnumSet
import java.util.UUID

object SmbShareEnum {
    private val srvsvc = UUID.fromString("4b324fc8-1670-01d3-1278-5a47bf6ee188")
    private val ndr = UUID.fromString("8a885d04-1ceb-11c9-9fe8-08002b104860")
    private const val STYPE_DISKTREE = 0
    private const val STYPE_PRINTQ = 1
    private const val STYPE_DEVICE = 2
    private const val STYPE_IPC = 3
    private const val STYPE_MASK = 0x000000FF
    private const val PFC_FIRST_LAST = 0x03
    private const val PFC_LAST = 0x02
    private const val DCE_REQUEST = 0
    private const val DCE_RESPONSE = 2
    private const val DCE_BIND = 11
    private const val DCE_BIND_ACK = 12
    private const val ERROR_MORE_DATA = 234
    private const val NERR_Success = 0

    fun listDiskShares(session: Session, host: String, netbios: String = ""): List<String> {
        val names = linkedSetOf<String>()
        names += runCatching { enumViaRpc(session, host, netbios) }.getOrDefault(emptyList())
        names += runCatching { enumViaRap(session) }.getOrDefault(emptyList())
        return names.toList()
    }

    private fun enumViaRpc(session: Session, host: String, netbios: String): List<String> {
        val names = linkedSetOf<String>()
        val servers = rpcServers(host, netbios)
        val share = session.connectShare("IPC$")
        share.use {
            for (maxFrag in intArrayOf(4280, 65535)) {
                val pipe = openSrvsvc(it) ?: break
                pipe.use pipe@{ p ->
                    val ack = sendPdu(p, rpcBind(maxFrag))
                    if (!bindOk(ack)) {
                        writePdu(p, rpcBind(maxFrag))
                        if (!bindOk(readPdu(p))) return@pipe
                    }
                    var callId = 2
                    for (server in servers) {
                        var resume = 0
                        var guard = 0
                        while (guard++ < 8) {
                            val resp = sendPdu(
                                p,
                                rpcRequest(
                                    callId++,
                                    15,
                                    netShareEnumStub(server, 1, resume, false, -1)
                                )
                            )
                            val parsed = parseNetShareEnum(resp, 1)
                            names += parsed.names
                            if (parsed.status != ERROR_MORE_DATA) break
                            if (parsed.resume == 0 || parsed.resume == resume) break
                            resume = parsed.resume
                        }
                        if (names.size > 1) return names.toList()
                    }
                }
                if (names.size > 1) return names.toList()
            }
        }
        return names.toList()
    }

    private fun rpcServers(host: String, netbios: String): List<ServerName> {
        val servers = ArrayList<ServerName>()
        servers += ServerName.Null
        servers += ServerName.Empty
        val nb = netbios.trim()
        if (nb.isNotEmpty()) {
            servers += ServerName.Value("\\\\$nb")
            servers += ServerName.Value(nb)
        }
        if (host.isNotBlank() && !host.equals(nb, true)) {
            servers += ServerName.Value("\\\\$host")
            servers += ServerName.Value(host)
        }
        servers += ServerName.Value("\\\\*")
        return servers
    }

    private fun enumViaRap(session: Session): List<String> {
        val share = session.connectShare("IPC$")
        share.use {
            val pipeShare = it as? PipeShare ?: return emptyList()
            val pipe = openPipe(pipeShare, "LANMAN") ?: openPipe(pipeShare, "\\LANMAN") ?: return emptyList()
            pipe.use { p ->
                val req = rapNetShareEnum()
                val resp = runCatching { p.transact(req) }.getOrNull() ?: run {
                    writePdu(p, req)
                    readBytes(p)
                }
                return parseRapShares(resp)
            }
        }
    }

    private fun openSrvsvc(share: Share): NamedPipe? {
        val pipeShare = share as? PipeShare ?: return null
        return openPipe(pipeShare, "srvsvc") ?: openPipe(pipeShare, "\\srvsvc")
    }

    private fun openPipe(share: PipeShare, name: String): NamedPipe? {
        return try {
            share.open(
                name,
                SMB2ImpersonationLevel.Impersonation,
                EnumSet.of(
                    AccessMask.GENERIC_READ,
                    AccessMask.GENERIC_WRITE,
                    AccessMask.FILE_READ_DATA,
                    AccessMask.FILE_WRITE_DATA,
                    AccessMask.SYNCHRONIZE
                ),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun sendPdu(pipe: NamedPipe, data: ByteArray): ByteArray {
        val viaTransact = runCatching { pipe.transact(data) }.getOrNull()
        if (viaTransact != null && viaTransact.isNotEmpty()) {
            if (viaTransact.size >= 4 && viaTransact[0].toInt() == 5 && !isLastFrag(viaTransact)) {
                return mergeFrags(listOf(viaTransact) + readMoreFrags(pipe))
            }
            return viaTransact
        }
        writePdu(pipe, data)
        return readPdu(pipe)
    }

    private fun isLastFrag(data: ByteArray): Boolean {
        if (data.size < 4) return true
        return data[3].toInt() and PFC_LAST != 0
    }

    private fun writePdu(pipe: NamedPipe, data: ByteArray) {
        var off = 0
        while (off < data.size) {
            val n = pipe.write(data, off, data.size - off)
            if (n <= 0) break
            off += n
        }
    }

    private fun readPdu(pipe: NamedPipe): ByteArray {
        return mergeFrags(readMoreFrags(pipe, first = true))
    }

    private fun readMoreFrags(pipe: NamedPipe, first: Boolean = false): List<ByteArray> {
        val chunks = ArrayList<ByteArray>()
        var guard = 0
        while (guard++ < 8) {
            val pdu = readOneFrag(pipe) ?: break
            if (pdu.isEmpty()) break
            chunks += pdu
            if (isLastFrag(pdu)) break
            if (!first && chunks.isNotEmpty() && isLastFrag(chunks.last())) break
        }
        return chunks
    }

    private fun readOneFrag(pipe: NamedPipe): ByteArray? {
        val first = ByteArray(16)
        val got = readFully(pipe, first, 0, 16)
        if (got < 16) return if (got <= 0) null else first.copyOf(got)
        val frag = (first[8].toInt() and 0xFF) or ((first[9].toInt() and 0xFF) shl 8)
        if (frag < 16 || frag > 1_000_000) return first.copyOf(got)
        val out = ByteArray(frag)
        System.arraycopy(first, 0, out, 0, 16)
        val rest = readFully(pipe, out, 16, frag - 16)
        return if (rest < frag - 16) out.copyOf(16 + rest) else out
    }

    private fun mergeFrags(chunks: List<ByteArray>): ByteArray {
        if (chunks.isEmpty()) return ByteArray(0)
        if (chunks.size == 1) return chunks[0]
        val body = ByteArrayOutputStream()
        for (pdu in chunks) {
            if (pdu.size > 24) body.write(pdu, 24, pdu.size - 24)
        }
        val stub = body.toByteArray()
        val merged = ByteArray(24 + stub.size)
        System.arraycopy(chunks[0], 0, merged, 0, minOf(24, chunks[0].size))
        System.arraycopy(stub, 0, merged, 24, stub.size)
        val len = merged.size
        merged[8] = (len and 0xFF).toByte()
        merged[9] = ((len shr 8) and 0xFF).toByte()
        merged[3] = (merged[3].toInt() or PFC_LAST).toByte()
        return merged
    }

    private fun readBytes(pipe: NamedPipe): ByteArray {
        val buf = ByteArray(65535)
        val n = pipe.read(buf)
        return if (n <= 0) ByteArray(0) else buf.copyOf(n)
    }

    private fun readFully(pipe: NamedPipe, buf: ByteArray, offset: Int, length: Int): Int {
        var off = offset
        var left = length
        while (left > 0) {
            val n = pipe.read(buf, off, left)
            if (n <= 0) break
            off += n
            left -= n
        }
        return off - offset
    }

    private fun rpcBind(maxFrag: Int = 4280): ByteArray {
        val frag = maxFrag.coerceIn(2048, 65535)
        val buf = ByteBuffer.allocate(72).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(5)
        buf.put(0)
        buf.put(DCE_BIND.toByte())
        buf.put(PFC_FIRST_LAST.toByte())
        buf.putInt(0x00000010)
        buf.putShort(72.toShort())
        buf.putShort(0)
        buf.putInt(1)
        buf.putShort(frag.toShort())
        buf.putShort(frag.toShort())
        buf.putInt(0)
        buf.put(1)
        buf.put(0)
        buf.putShort(0)
        buf.putShort(0)
        buf.put(1)
        buf.put(0)
        buf.put(uuidBytes(srvsvc))
        buf.putInt(3)
        buf.put(uuidBytes(ndr))
        buf.putInt(2)
        return buf.array()
    }

    private fun bindOk(data: ByteArray): Boolean {
        if (data.size < 16) return false
        return data[0].toInt() == 5 && (data[2].toInt() and 0xFF) == DCE_BIND_ACK
    }

    private fun rpcRequest(callId: Int, opnum: Int, stub: ByteArray): ByteArray {
        val total = 24 + stub.size
        val hdr = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        hdr.put(5)
        hdr.put(0)
        hdr.put(DCE_REQUEST.toByte())
        hdr.put(PFC_FIRST_LAST.toByte())
        hdr.putInt(0x00000010)
        hdr.putShort(total.toShort())
        hdr.putShort(0)
        hdr.putInt(callId)
        hdr.putInt(stub.size)
        hdr.putShort(0)
        hdr.putShort(opnum.toShort())
        hdr.put(stub)
        return hdr.array()
    }

    private sealed class ServerName {
        data object Null : ServerName()
        data object Empty : ServerName()
        data class Value(val text: String) : ServerName()
    }

    private fun netShareEnumStub(
        server: ServerName,
        level: Int,
        resume: Int,
        pointerResume: Boolean,
        prefMax: Int
    ): ByteArray {
        val name = when (server) {
            is ServerName.Null -> ndrNull()
            is ServerName.Empty -> ndrUniqueWString("")
            is ServerName.Value -> ndrUniqueWString(server.text)
        }
        val tail = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
        tail.putInt(level)
        tail.putInt(level)
        tail.putInt(0x00020004)
        tail.putInt(0)
        tail.putInt(0)
        tail.putInt(prefMax)
        if (pointerResume) {
            tail.putInt(0x00020008)
            tail.putInt(resume)
        } else {
            tail.putInt(resume)
        }
        val out = ByteArray(name.size + tail.position())
        System.arraycopy(name, 0, out, 0, name.size)
        System.arraycopy(tail.array(), 0, out, name.size, tail.position())
        return out
    }

    private fun ndrNull(): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array()
    }

    private fun ndrUniqueWString(value: String): ByteArray {
        val chars = (value + '\u0000').toCharArray()
        val pad = (4 - ((chars.size * 2) % 4)) % 4
        val buf = ByteBuffer.allocate(16 + chars.size * 2 + pad).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x00020000)
        buf.putInt(chars.size)
        buf.putInt(0)
        buf.putInt(chars.size)
        for (c in chars) buf.putShort(c.code.toShort())
        repeat(pad) { buf.put(0) }
        return buf.array()
    }

    private data class EnumResult(
        val names: List<String>,
        val resume: Int,
        val status: Int
    )

    private fun stubOffset(data: ByteArray): Int {
        if (data.size >= 24 && data[0].toInt() == 5 && (data[2].toInt() and 0xFF) == DCE_RESPONSE) return 24
        if (data.size >= 16 && data[0].toInt() == 5) return 24
        return 0
    }

    private fun parseNetShareEnum(data: ByteArray, level: Int): EnumResult {
        if (data.size < 16) return EnumResult(emptyList(), 0, -1)
        val start = stubOffset(data)
        val grouped = parseShareInfo(data, start, level, false)
        val interleaved = parseShareInfo(data, start, level, true)
        val names = linkedSetOf<String>()
        names += grouped.names
        names += interleaved.names
        names += scanUtf16Names(data)
        names += scanShareNames(data, start)
        val trailer = when {
            grouped.status == NERR_Success || grouped.status == ERROR_MORE_DATA -> grouped
            interleaved.status == NERR_Success || interleaved.status == ERROR_MORE_DATA -> interleaved
            grouped.names.size >= interleaved.names.size -> grouped
            else -> interleaved
        }
        return EnumResult(names.toList(), trailer.resume, trailer.status)
    }

    private fun parseShareInfo(data: ByteArray, start: Int, level: Int, interleaved: Boolean): EnumResult {
        val r = NdrReader(data, start)
        val gotLevel = r.u32()
        val tag = r.u32()
        if (gotLevel != level || tag != level) return EnumResult(emptyList(), 0, -1)
        val containerPtr = r.u32()
        if (containerPtr == 0) return EnumResult(emptyList(), 0, -1)
        val entriesRead = r.u32()
        val bufPtr = r.u32()
        if (bufPtr == 0 || entriesRead <= 0) return EnumResult(emptyList(), 0, -1)
        val maxCount = r.u32()
        val n = minOf(entriesRead, maxCount).coerceIn(0, 512)
        data class Ent(val namePtr: Int, val type: Int, val remarkPtr: Int)
        val ents = ArrayList<Ent>(n)
        if (level == 0) {
            repeat(n) { ents += Ent(r.u32(), STYPE_DISKTREE, 0) }
        } else {
            repeat(n) { ents += Ent(r.u32(), r.u32(), r.u32()) }
        }
        val names = Array(n) { "" }
        val types = IntArray(n) { STYPE_DISKTREE }
        if (interleaved) {
            for (i in 0 until n) {
                if (ents[i].namePtr != 0) names[i] = r.ndrString()
                types[i] = ents[i].type
                if (ents[i].remarkPtr != 0) r.ndrString()
            }
        } else {
            for (i in 0 until n) {
                names[i] = if (ents[i].namePtr != 0) r.ndrString() else ""
                types[i] = ents[i].type
            }
            for (i in 0 until n) {
                if (ents[i].remarkPtr != 0) r.ndrString()
            }
        }
        val out = ArrayList<String>()
        for (i in 0 until n) {
            val name = names[i].trim().trimEnd('\\')
            if (!keepShare(name, types[i], level == 0)) continue
            out += name
        }
        val trailer = r.trailer()
        return EnumResult(out.distinct(), trailer.resume, trailer.status)
    }

    private fun scanUtf16Names(data: ByteArray): List<String> {
        val out = linkedSetOf<String>()
        var i = 0
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                i += 2
                continue
            }
            var end = i
            var ok = true
            val chars = StringBuilder()
            while (end + 1 < data.size) {
                val lo = data[end].toInt() and 0xFF
                val hi = data[end + 1].toInt() and 0xFF
                if (lo == 0 && hi == 0) break
                val cp = lo or (hi shl 8)
                val c = cp.toChar()
                if (c.code < 32 || c in "\\/:*?\"<>|") {
                    ok = false
                    break
                }
                chars.append(c)
                end += 2
            }
            val name = chars.toString()
            if (ok && keepShare(name, STYPE_DISKTREE, true) && !name.equals("IPC", true)) {
                out += name
            }
            i = if (end > i) end + 2 else i + 2
        }
        return out.toList()
    }

    private fun scanShareNames(data: ByteArray, start: Int): List<String> {
        val r = NdrReader(data, start)
        val out = linkedSetOf<String>()
        var guard = 0
        while (r.off + 12 <= data.size && guard++ < 512) {
            val before = r.off
            val s = r.ndrString()
            if (s.isEmpty()) {
                if (r.off == before) r.off += 4
                continue
            }
            val name = s.trim().trimEnd('\\')
            if (keepShare(name, STYPE_DISKTREE, true)) out += name
        }
        return out.toList()
    }

    private fun rapNetShareEnum(): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }
        fun z(s: String) {
            out.write(s.toByteArray(Charsets.US_ASCII))
            out.write(0)
        }
        u16(0)
        z("WrLeh")
        z("B13BWz")
        u16(1)
        u16(0xFFFF)
        return out.toByteArray()
    }

    private fun parseRapShares(data: ByteArray): List<String> {
        if (data.size < 8) return emptyList()
        fun u16(at: Int): Int {
            if (at + 1 >= data.size) return 0
            return (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
        }
        val error = u16(0)
        if (error != NERR_Success && error != ERROR_MORE_DATA) return emptyList()
        val returned = u16(4)
        val out = ArrayList<String>()
        var off = 8
        repeat(returned.coerceIn(0, 512)) {
            if (off + 20 > data.size) return out.distinct()
            val raw = data.copyOfRange(off, off + 13)
            val zero = raw.indexOf(0)
            val name = String(raw, 0, if (zero >= 0) zero else raw.size, Charsets.US_ASCII).trim()
            val type = u16(off + 14)
            off += 20
            if (keepShare(name, type, false)) out += name
        }
        return out.distinct()
    }

    private fun keepShare(name: String, type: Int, ignoreType: Boolean): Boolean {
        if (!plausibleShare(name)) return false
        if (name.matches(Regex("^[A-Za-z]\\$"))) {
            return ignoreType || (type and STYPE_MASK) == STYPE_DISKTREE
        }
        if (name.endsWith('$')) return false
        if (ignoreType) return true
        val kind = type and STYPE_MASK
        if (kind == STYPE_PRINTQ || kind == STYPE_DEVICE || kind == STYPE_IPC) return false
        return kind == STYPE_DISKTREE
    }

    private fun plausibleShare(name: String): Boolean {
        if (name.length !in 2..80) return false
        if (name.any { it.code < 32 || it in "\\/:*?\"<>|" }) return false
        return true
    }

    private fun uuidBytes(id: UUID): ByteArray {
        val msb = id.mostSignificantBits
        val lsb = id.leastSignificantBits
        val buf = ByteBuffer.allocate(16)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt((msb ushr 32).toInt())
        buf.putShort((msb ushr 16).toShort())
        buf.putShort(msb.toShort())
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.putLong(lsb)
        return buf.array()
    }

    private data class Trailer(val resume: Int, val status: Int)

    private class NdrReader(val data: ByteArray, start: Int) {
        var off = start

        fun u32(): Int {
            align4()
            if (off + 4 > data.size) return 0
            val v = (data[off].toInt() and 0xFF) or
                ((data[off + 1].toInt() and 0xFF) shl 8) or
                ((data[off + 2].toInt() and 0xFF) shl 16) or
                ((data[off + 3].toInt() and 0xFF) shl 24)
            off += 4
            return v
        }

        fun ndrString(): String {
            align4()
            if (off + 12 > data.size) return ""
            val max = u32()
            val offset = u32()
            val actual = u32()
            if (actual <= 0 || actual > 4096 || max < actual || offset < 0) return ""
            if (off + actual * 2 > data.size) return ""
            val chars = CharArray(actual)
            repeat(actual) { idx ->
                chars[idx] = ((data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)).toChar()
                off += 2
            }
            align4()
            val end = if (actual > 0 && chars[actual - 1].code == 0) actual - 1 else actual
            return String(chars, 0, end)
        }

        fun trailer(): Trailer {
            align4()
            val left = (data.size - off) / 4
            if (left <= 0) return Trailer(0, -1)
            val words = IntArray(left.coerceAtMost(6))
            val saved = off
            for (i in words.indices) words[i] = u32()
            off = saved
            val status = words.last()
            val resume = when {
                words.size >= 3 && words[words.size - 2] != 0 &&
                    (status == NERR_Success || status == ERROR_MORE_DATA) -> words[words.size - 2]
                words.size >= 2 && (status == NERR_Success || status == ERROR_MORE_DATA) -> words[words.size - 2]
                else -> 0
            }
            return Trailer(resume, status)
        }

        private fun align4() {
            val pad = (4 - (off % 4)) % 4
            off += pad
        }
    }
}
