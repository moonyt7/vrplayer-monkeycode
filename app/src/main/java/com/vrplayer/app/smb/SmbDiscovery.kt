package com.vrplayer.app.smb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

object SmbDiscovery {
    suspend fun findHosts(context: Context): List<SmbHost> = withContext(Dispatchers.IO) {
        val found = ConcurrentHashMap<String, SmbHost>()
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("vr-smb-discover").apply {
            setReferenceCounted(true)
            acquire()
        }
        try {
            coroutineScope {
                val nsd = async { nsdHosts(context) }
                val netbios = async { netbiosHosts() }
                val portScan = async { scanPort445() }
                nsd.await().forEach { merge(found, it) }
                netbios.await().forEach { merge(found, it) }
                portScan.await().forEach { merge(found, it) }
            }
            fillMissingNames(found)
        } finally {
            if (lock.isHeld) lock.release()
        }
        found.values.sortedBy { it.title.lowercase() }
    }

    private fun merge(map: ConcurrentHashMap<String, SmbHost>, host: SmbHost) {
        val old = map[host.address]
        if (old == null) {
            map[host.address] = host
            return
        }
        val betterName = pickName(old.name, host.name, host.address)
        if (betterName != old.name) {
            map[host.address] = old.copy(name = betterName)
        }
    }

    private fun pickName(a: String, b: String, ip: String): String {
        val left = cleanName(a, ip)
        val right = cleanName(b, ip)
        if (left.isEmpty()) return right
        if (right.isEmpty()) return left
        return if (right.length > left.length) right else left
    }

    private fun cleanName(raw: String, ip: String): String {
        var n = raw.trim().trimEnd('.')
        n = n.substringBefore("._")
        n = n.substringBefore(".local")
        if (n.contains('.')) n = n.substringBefore('.')
        if (n.isEmpty() || n.equals(ip, true)) return ""
        if (n.all { it.isDigit() }) return ""
        return n.removePrefix("smb-").removePrefix("SMB-")
    }

    private suspend fun fillMissingNames(map: ConcurrentHashMap<String, SmbHost>) = coroutineScope {
        map.values.filter { cleanName(it.name, it.address).isEmpty() }.map { host ->
            async(Dispatchers.IO) {
                val name = resolveName(host.address)
                if (name.isNotEmpty()) {
                    map[host.address] = host.copy(name = name)
                }
            }
        }.awaitAll()
        map.replaceAll { ip, host ->
            val name = cleanName(host.name, ip)
            if (name.isEmpty()) host.copy(name = "") else host.copy(name = name)
        }
    }

    private fun resolveName(ip: String): String {
        val mdns = dnsReverse(ip, "224.0.0.251", 5353, true)
        if (mdns.isNotEmpty()) return mdns
        val llmnr = dnsReverse(ip, "224.0.0.252", 5355, false)
        if (llmnr.isNotEmpty()) return llmnr
        val nb = nbstatUnicast(ip)
        if (nb.isNotEmpty()) return nb
        val smb = smbServerName(ip)
        if (smb.isNotEmpty()) return smb
        return try {
            val host = InetAddress.getByName(ip).canonicalHostName.orEmpty()
            cleanName(host, ip)
        } catch (_: Exception) {
            ""
        }
    }

    private fun dnsReverse(ip: String, multicast: String, port: Int, mdns: Boolean): String {
        val parts = ip.split('.')
        if (parts.size != 4) return ""
        val qname = "${parts[3]}.${parts[2]}.${parts[1]}.${parts[0]}.in-addr.arpa"
        val query = dnsPtrQuery(qname, mdns)
        val socket = DatagramSocket()
        try {
            socket.soTimeout = 500
            socket.send(DatagramPacket(query, query.size, InetAddress.getByName(multicast), port))
            val buf = ByteArray(512)
            val packet = DatagramPacket(buf, buf.size)
            socket.receive(packet)
            return cleanName(parseDnsPtr(packet.data, packet.length), ip)
        } catch (_: Exception) {
            return ""
        } finally {
            socket.close()
        }
    }

    private fun dnsPtrQuery(name: String, mdns: Boolean): ByteArray {
        val labels = name.split('.')
        val size = 12 + labels.sumOf { it.length + 1 } + 1 + 4
        val buf = ByteBuffer.allocate(size)
        buf.putShort(0x1234.toShort())
        buf.putShort(if (mdns) 0 else 0x0100.toShort())
        buf.putShort(1)
        buf.putShort(0)
        buf.putShort(0)
        buf.putShort(0)
        labels.forEach { label ->
            buf.put(label.length.toByte())
            buf.put(label.toByteArray(Charsets.US_ASCII))
        }
        buf.put(0)
        buf.putShort(12)
        buf.putShort(if (mdns) 0x8001.toShort() else 1)
        return buf.array()
    }

    private fun parseDnsPtr(data: ByteArray, length: Int): String {
        if (length < 12) return ""
        val answers = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        if (answers <= 0) return ""
        var i = 12
        i = skipDnsName(data, i, length)
        i += 4
        repeat(answers) {
            if (i + 10 > length) return ""
            i = skipDnsName(data, i, length)
            if (i + 10 > length) return ""
            val type = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 8
            val rdlen = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
            if (type == 12) return readDnsName(data, i, length)
            i += rdlen
        }
        return ""
    }

    private fun skipDnsName(data: ByteArray, start: Int, length: Int): Int {
        var i = start
        while (i < length) {
            val len = data[i].toInt() and 0xFF
            if (len == 0) return i + 1
            if (len and 0xC0 == 0xC0) return i + 2
            i += len + 1
        }
        return length
    }

    private fun readDnsName(data: ByteArray, start: Int, length: Int): String {
        val parts = ArrayList<String>()
        var i = start
        var hops = 0
        while (i < length && hops < 12) {
            val len = data[i].toInt() and 0xFF
            if (len == 0) break
            if (len and 0xC0 == 0xC0) {
                if (i + 1 >= length) break
                i = ((len and 0x3F) shl 8) or (data[i + 1].toInt() and 0xFF)
                hops++
                continue
            }
            if (i + 1 + len > length) break
            parts += String(data, i + 1, len, Charsets.US_ASCII)
            i += len + 1
        }
        return parts.joinToString(".")
    }

    private fun smbServerName(ip: String): String {
        val client = SmbAuth.quickClient()
        try {
            client.connect(ip).use { conn ->
                val ctx = conn.connectionContext
                val nb = cleanName(ctx.netBiosName.orEmpty(), ip)
                if (nb.isNotEmpty()) return nb
                return cleanName(ctx.serverName.orEmpty(), ip)
            }
        } catch (_: Exception) {
            return ""
        } finally {
            client.close()
        }
    }

    private suspend fun nsdHosts(context: Context): List<SmbHost> {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return emptyList()
        val pending = ConcurrentHashMap<String, SmbHost>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                try {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            val addr = resolved.host?.hostAddress ?: return
                            if (addr.contains(':')) return
                            pending[addr] = SmbHost(addr, resolved.serviceName.orEmpty(), "NSD")
                        }
                    })
                } catch (_: Exception) {
                }
            }
        }
        return try {
            nsd.discoverServices("_smb._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
            delay(2800)
            pending.values.toList()
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    private suspend fun netbiosHosts(): List<SmbHost> = withContext(Dispatchers.IO) {
        val out = mutableListOf<SmbHost>()
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            soTimeout = 1200
            bind(InetSocketAddress(0))
        }
        try {
            val query = nbstatQuery()
            val broadcast = InetAddress.getByName("255.255.255.255")
            socket.send(DatagramPacket(query, query.size, broadcast, 137))
            val buf = ByteArray(576)
            val end = System.currentTimeMillis() + 1400
            while (System.currentTimeMillis() < end) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val name = parseNbstatName(packet.data, packet.length)
                    val ip = packet.address.hostAddress ?: continue
                    if (ip.contains(':')) continue
                    out.add(SmbHost(ip, name, "NetBIOS"))
                } catch (_: Exception) {
                    break
                }
            }
        } catch (_: Exception) {
        } finally {
            socket.close()
        }
        out
    }

    private suspend fun scanPort445(): List<SmbHost> = coroutineScope {
        val prefix = localPrefix() ?: return@coroutineScope emptyList()
        (1..254).map { last ->
            async(Dispatchers.IO) {
                val ip = "$prefix.$last"
                if (open445(ip)) SmbHost(ip, "", "445") else null
            }
        }.awaitAll().filterNotNull()
    }

    private fun nbstatUnicast(ip: String): String {
        val socket = DatagramSocket()
        try {
            socket.soTimeout = 700
            val query = nbstatQuery()
            socket.send(DatagramPacket(query, query.size, InetAddress.getByName(ip), 137))
            val buf = ByteArray(576)
            val packet = DatagramPacket(buf, buf.size)
            socket.receive(packet)
            return parseNbstatName(packet.data, packet.length)
        } catch (_: Exception) {
            return ""
        } finally {
            socket.close()
        }
    }

    private fun localPrefix(): String? {
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (iface in ifaces) {
            if (!iface.isUp || iface.isLoopback) continue
            for (addr in iface.interfaceAddresses) {
                val inet = addr.address
                if (inet is Inet4Address && !inet.isLoopbackAddress) {
                    val host = inet.hostAddress ?: continue
                    val parts = host.split('.')
                    if (parts.size == 4) return parts.take(3).joinToString(".")
                }
            }
        }
        return null
    }

    private fun open445(ip: String): Boolean {
        return try {
            Socket().use { s ->
                s.soTimeout = 400
                s.connect(InetSocketAddress(ip, 445), 400)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun nbstatQuery(): ByteArray {
        val name = encodeNetbiosName("*")
        val packet = ByteArray(12 + name.size + 4)
        packet[0] = 0x12
        packet[1] = 0x34
        packet[5] = 1
        System.arraycopy(name, 0, packet, 12, name.size)
        val t = 12 + name.size
        packet[t] = 0x00
        packet[t + 1] = 0x21
        packet[t + 2] = 0x00
        packet[t + 3] = 0x01
        return packet
    }

    private fun encodeNetbiosName(raw: String): ByteArray {
        val padded = (raw + " ".repeat(15)).take(15) + "\u0000"
        val out = ByteArray(34)
        out[0] = 0x20
        for (i in 0 until 16) {
            val b = padded[i].code and 0xFF
            out[1 + i * 2] = ((b shr 4) + 0x41).toByte()
            out[2 + i * 2] = ((b and 0x0F) + 0x41).toByte()
        }
        out[33] = 0
        return out
    }

    private fun parseNbstatName(data: ByteArray, length: Int): String {
        if (length < 57) return ""
        var i = 12
        val label = data[i].toInt() and 0xFF
        i += if (label == 0x20) 34 else label + 2
        i += 10
        if (i + 1 >= length) return ""
        val names = data[i].toInt() and 0xFF
        i += 1
        var first = ""
        var fileServer = ""
        repeat(names) {
            if (i + 18 > length) return fileServer.ifEmpty { first }
            val raw = data.copyOfRange(i, i + 15).toString(Charsets.US_ASCII).trim()
            val suffix = data[i + 15].toInt() and 0xFF
            val flags = ((data[i + 16].toInt() and 0xFF) shl 8) or (data[i + 17].toInt() and 0xFF)
            i += 18
            val group = flags and 0x8000 != 0
            if (raw.isBlank() || raw.startsWith("\u0001") || group) return@repeat
            if (suffix == 0x20 && fileServer.isEmpty()) fileServer = raw
            if (suffix == 0x00 && first.isEmpty()) first = raw
        }
        return fileServer.ifEmpty { first }
    }
}
