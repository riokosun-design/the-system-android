package com.thesystem.app.service

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.DataInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * §6.3 DNS GUARD — opt-in family-filter layer (spec §6, "Optional VpnService
 * or DNS Guard").
 *
 * What it is: a single-purpose VpnService that routes **only** Cloudflare's
 * Family resolver IPs (1.1.1.3 / 1.0.0.3) through the tunnel and relays their
 * UDP/53 queries. Every other packet, byte and app on the device is untouched —
 * this is a DNS splint, not a man-in-the-middle. Adult domains therefore fail
 * to resolve system-wide.
 *
 * What it is not: it cannot stop apps that hard-code their own DoH servers,
 * and it drops TCP/53 (resolvers retry over UDP — the universal fallback).
 * Honest, opt-in, zero data leaves the device except standard DNS to
 * Cloudflare Family.
 */
class SystemDnsGuardService : VpnService() {

    companion object {
        const val ACTION_START = "com.thesystem.app.dnsguard.START"
        const val ACTION_STOP = "com.thesystem.app.dnsguard.STOP"

        @Volatile var running: Boolean = false
            private set

        private val FAMILY_DNS = arrayOf("1.1.1.3", "1.0.0.3")
    }

    private val worker = AtomicBoolean(false)
    private var tun: ParcelFileDescriptor? = null
    private var thread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutdown(); return START_NOT_STICKY }
            else -> startTunnel()
        }
        return START_STICKY
    }

    private fun startTunnel() {
        if (worker.get()) return
        val fd = Builder()
            .setSession("THE SYSTEM — DNS GUARD")
            .addAddress("10.88.0.2", 32)
            .addRoute(FAMILY_DNS[0], 32)
            .addRoute(FAMILY_DNS[1], 32)
            .addDnsServer(FAMILY_DNS[0])
            .addDnsServer(FAMILY_DNS[1])
            .setMtu(1500)
            .setBlocking(true)
            .establish() ?: run { stopSelf(); return }
        tun = fd
        worker.set(true)
        running = true
        thread = Thread({ pump(fd) }, "SystemDnsGuard").apply { isDaemon = true; start() }
    }

    private fun shutdown() {
        worker.set(false)
        running = false
        runCatching { tun?.close() }
        tun = null
        stopSelf()
    }

    override fun onRevoke() { shutdown(); super.onRevoke() }
    override fun onDestroy() { shutdown(); super.onDestroy() }

    // ═══════════════════════════════════════════════════════════════════════
    // UDP/53 SPLICER — request out via a protected socket, answer written back
    // ═══════════════════════════════════════════════════════════════════════

    private fun pump(fd: ParcelFileDescriptor) {
        val input = DataInputStream(FileInputStream(fd.fileDescriptor))
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(4096)
        while (worker.get()) {
            val n = try { input.read(buf) } catch (_: Exception) { break }
            if (n < 28) continue                       // shortest sane IPv4+UDP header
            val packet = buf.copyOf(n)
            val ihl = (packet[0].toInt() and 0x0F) * 4
            val proto = packet[9].toInt() and 0xFF
            if (ihl < 20 || n < ihl + 8 || proto != 17) continue   // UDP only
            val dstPort = packet.u16(ihl + 2)
            if (dstPort != 53) continue                             // DNS only

            val dstIp = packet.copyOfRange(16, 20)
            val srcIp = packet.copyOfRange(12, 16)
            val srcPort = packet.u16(ihl)
            val dnsPayload = packet.copyOfRange(ihl + 8, n)

            val answer = relay(dstIp, dnsPayload) ?: continue       // timeout → drop; resolver retries
            val reply = buildReply(packet, ihl, srcIp, dstIp, srcPort, answer) ?: continue
            try {
                synchronized(output) { output.write(reply); output.flush() }
            } catch (_: Exception) { /* tunnel is collapsing; the pump will exit */ }
        }
    }

    /** Forward the DNS payload to the same resolver IP outside the tunnel. */
    private fun relay(dstIp: ByteArray, payload: ByteArray): ByteArray? = runCatching {
        val socket = DatagramSocket()
        protect(socket)
        socket.soTimeout = 5_000
        socket.use {
            it.send(DatagramPacket(payload, payload.size, InetAddress.getByAddress(dstIp), 53))
            val buf = ByteArray(4096)
            val resp = DatagramPacket(buf, buf.size)
            it.receive(resp)
            buf.copyOf(resp.length)
        }
    }.getOrNull()

    /** Re-wrap the resolver's answer as an IPv4/UDP packet addressed to the app. */
    private fun buildReply(
        req: ByteArray, ihl: Int,
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, answer: ByteArray,
    ): ByteArray? {
        if (answer.size > 4000) return null
        val total = ihl + 8 + answer.size
        if (total > 65535) return null
        val out = ByteArray(total)
        // IPv4 header clone (addresses/lengths fixed below)
        System.arraycopy(req, 0, out, 0, ihl)
        out[2] = (total shr 8).toByte(); out[3] = total.toByte()
        out[8] = 64                                                   // TTL
        System.arraycopy(dstIp, 0, out, 12, 4)                        // src ← resolver
        System.arraycopy(srcIp, 0, out, 16, 4)                        // dst ← the asking app
        out[10] = 0; out[11] = 0
        val csum = ipChecksum(out, 0, ihl)
        out[10] = (csum shr 8).toByte(); out[11] = csum.toByte()
        // UDP header
        out[ihl] = 0; out[ihl + 1] = 53.toByte()                      // src port 53
        out[ihl + 2] = (srcPort shr 8).toByte(); out[ihl + 3] = srcPort.toByte()
        val ulen = 8 + answer.size
        out[ihl + 4] = (ulen shr 8).toByte(); out[ihl + 5] = ulen.toByte()
        out[ihl + 6] = 0; out[ihl + 7] = 0                            // IPv4 UDP checksum optional
        System.arraycopy(answer, 0, out, ihl + 8, answer.size)
        return out
    }

    private fun ipChecksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        while (i < off + len - 1) {
            sum += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (len and 1 == 1) sum += (b[off + len - 1].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv().toInt() and 0xFFFF
    }

    private fun ByteArray.u16(off: Int): Int =
        ((this[off].toInt() and 0xFF) shl 8) or (this[off + 1].toInt() and 0xFF)
}
