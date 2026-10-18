package com.myne23.abyssresolver

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/** Loopback HTTP server that range-fetches and AES-CTR decrypts MEGA file bytes on demand. */
internal class MegaMediaServer(private val context: Context) {
    private val random = SecureRandom()
    @Volatile private var socket: ServerSocket? = null
    @Volatile private var source: MegaSource? = null
    @Volatile private var token: String? = null
    @Volatile var lastActivityMs: Long = System.currentTimeMillis()
        private set

    @Synchronized fun register(next: MegaSource): String {
        source = next
        token = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        if (socket == null || socket?.isClosed != false) {
            val s = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
            socket = s
            Thread {
                while (!s.isClosed) {
                    try { val client = s.accept(); Thread { serve(client) }.start() } catch (_: Exception) { break }
                }
            }.apply { name = "mega-http"; isDaemon = true }.start()
        }
        return "http://127.0.0.1:${socket!!.localPort}/s/${token}/video.mp4"
    }

    fun stop() { runCatching { socket?.close() }; socket = null; source = null }

    private fun serve(sock: java.net.Socket) = sock.use { client ->
        var headersSent = false
        try {
            client.soTimeout = 30000
            val input = BufferedInputStream(client.getInputStream())
            val request = readHeader(input) ?: return
            lastActivityMs = System.currentTimeMillis()
            val line = request.first().split(' ')
            val method = line.getOrNull(0) ?: return
            val path = line.getOrNull(1) ?: return
            val headers = request.drop(1).mapNotNull {
                val i = it.indexOf(':'); if (i <= 0) null else it.substring(0, i).trim().lowercase() to it.substring(i + 1).trim()
            }.toMap()
            val expected = "/s/${token}/video.mp4"
            if (method == "OPTIONS") {
                client.getOutputStream().write("HTTP/1.1 204 No Content\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, HEAD, OPTIONS\r\nAccess-Control-Allow-Headers: Range\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            if (path.substringBefore('?') != expected || (method != "GET" && method != "HEAD")) { reply(client, 404, "not found"); return }
            val src = source ?: run { reply(client, 503, "stream unavailable"); return }
            val range = headers["range"]
            val bounds = parseRange(range, src.size) ?: (0L to src.size - 1)
            if (bounds.first < 0 || bounds.second < bounds.first || bounds.first >= src.size) {
                client.getOutputStream().write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${src.size}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return
            }
            val (start, end) = bounds
            val partial = range != null
            val out = BufferedOutputStream(client.getOutputStream())
            out.write(("HTTP/1.1 ${if (partial) 206 else 200} ${if (partial) "Partial Content" else "OK"}\r\n" +
                "Content-Type: video/mp4\r\nAccept-Ranges: bytes\r\nContent-Length: ${end - start + 1}\r\n" +
                (if (partial) "Content-Range: bytes $start-$end/${src.size}\r\n" else "") +
                "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, HEAD, OPTIONS\r\nAccess-Control-Allow-Headers: Range\r\nAccess-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges, X-Resolver-Error\r\nConnection: close\r\n\r\n").toByteArray())
            headersSent = true
            if (method == "HEAD") { out.flush(); return }
            var pos = start
            while (pos <= end) {
                val count = min(1024 * 1024L, end - pos + 1).toInt()
                val bytes = readPlain(src, pos, count)
                if (bytes.isEmpty()) throw IllegalStateException("unexpected end of MEGA stream")
                out.write(bytes)
                pos += bytes.size
            }
            out.flush()
        } catch (e: Throwable) {
            if (!headersSent) runCatching { reply(client, 503, "MEGA upstream unavailable") }
        }
    }

    private fun readPlain(src: MegaSource, offset: Long, length: Int): ByteArray {
        val aligned = offset - (offset % 16L)
        val wanted = min(src.size - aligned, length.toLong() + (offset - aligned)).toInt()
        val conn = URL(src.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 45000; conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36")
        conn.setRequestProperty("Range", "bytes=$aligned-${aligned + wanted - 1}")
        val encrypted = try {
            val code = conn.responseCode
            if (code != 206 && !(code == 200 && aligned == 0L)) throw IllegalStateException("MEGA upstream HTTP $code")
            conn.inputStream.use { input ->
                val bytes = ByteArray(wanted)
                var n = 0
                while (n < wanted) {
                    val got = input.read(bytes, n, wanted - n)
                    if (got < 0) break
                    n += got
                }
                if (n == wanted) bytes else bytes.copyOf(n)
            }
        } finally { conn.disconnect() }
        if (encrypted.isEmpty()) return encrypted
        return MegaSourceResolver.decryptRange(src, aligned, encrypted)
            .let { plain -> plain.copyOfRange((offset - aligned).toInt().coerceAtMost(plain.size), min(plain.size, (offset - aligned).toInt() + length)) }
    }

    private fun parseRange(header: String?, total: Long): Pair<Long, Long>? {
        if (header == null) return null
        val m = Regex("bytes=(\\d*)-(\\d*)").matchEntire(header.trim()) ?: return -1L to -1L
        val a = m.groupValues[1].toLongOrNull()
        val b = m.groupValues[2].toLongOrNull()
        return when {
            a != null -> a to min(b ?: (total - 1), total - 1)
            b != null && b > 0 -> maxOf(0L, total - b) to total - 1
            else -> -1L to -1L
        }
    }

    private fun readHeader(input: BufferedInputStream): List<String>? {
        val out = StringBuilder()
        while (out.length < 8192) {
            val c = input.read()
            if (c < 0) return null
            out.append(c.toChar())
            if (out.endsWith("\r\n\r\n")) return out.toString().trimEnd().split("\r\n")
        }
        return null
    }

    private fun reply(client: java.net.Socket, status: Int, message: String) {
        val ascii = message.replace(Regex("[^ -~]"), " ").take(120)
        client.getOutputStream().write("HTTP/1.1 $status Error\r\nX-Resolver-Error: unavailable; $ascii\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
    }
}
