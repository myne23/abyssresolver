package com.myne23.abyssresolver

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.HttpURLConnection
import android.util.Log
import java.security.SecureRandom
import kotlin.math.min

/**
 * Loopback-only HTTP server. It caches 1 MiB upstream chunks, decrypts the first 64 KiB once, and supports player Range seeks.
 * The MP4 prefix algorithm is provisional until checked against known-good files from the live player.
 */
internal class AbyssMediaServer(private val context: Context) {
    private val random = SecureRandom()
    private val cache = File(context.cacheDir, "abyss-chunks").apply { mkdirs() }
    @Volatile private var socket: ServerSocket? = null
    @Volatile private var source: AbyssSource? = null
    @Volatile private var token: String? = null
    @Volatile private var probedUrl: String? = null
    @Volatile private var probedLength: Long = -1L
    @Volatile var lastActivityMs: Long = System.currentTimeMillis()
        private set
    // Smaller chunks make random seeks less likely to wait for a full megabyte download.
    private val chunkSize = 256 * 1024

    @Synchronized fun register(next: AbyssSource): String {
        if (source?.url != next.url) {
            probedUrl = null
            probedLength = -1L
        }
        source = next
        token = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        if (socket == null || socket?.isClosed != false) {
            val s = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
            socket = s
            Thread {
                while (!s.isClosed) {
                    try { val client = s.accept(); Thread { serve(client) }.start() } catch (_: Exception) { break }
                }
            }.apply { name = "abyss-http"; isDaemon = true }.start()
        }
        return "http://127.0.0.1:${socket!!.localPort}/s/${token}/video.mp4"
    }

    fun stop() { runCatching { socket?.close() }; socket = null; source = null }

    private fun serve(sock: Socket) = sock.use { client ->
        var headersSent = false
        val requestStartedAt = System.currentTimeMillis()
        var requestMethod = "unknown"
        var requestRange = "unknown"
        var requestStart = -1L
        var requestEnd = -1L
        var bytesWritten = 0L
        var knownTotal = -1L
        try {
            client.soTimeout = 30000
            val input = BufferedInputStream(client.getInputStream())
            val request = readHeader(input) ?: return
            lastActivityMs = System.currentTimeMillis()
            val line = request.first().split(' ')
            val method = line.getOrNull(0) ?: return
            requestMethod = method
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
            val total = totalLength(src)
            knownTotal = total
            val range = headers["range"]
            requestRange = range ?: "full"
            val bounds = parseRange(range, total) ?: (0L to total - 1)
            if (bounds.first < 0 || bounds.second < bounds.first || bounds.first >= total) {
                client.getOutputStream().write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$total\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return
            }
            val (start, end) = bounds
            requestStart = start
            requestEnd = end
            val partial = range != null
            diagnostic("media_request_start", "$method range=$requestRange status=${if (partial) 206 else 200} start=$start end=$end total=$total")
            val out = BufferedOutputStream(client.getOutputStream())
            out.write(("HTTP/1.1 ${if (partial) 206 else 200} ${if (partial) "Partial Content" else "OK"}\r\n" +
                "Content-Type: video/mp4\r\nAccept-Ranges: bytes\r\nContent-Length: ${end - start + 1}\r\n" +
                (if (partial) "Content-Range: bytes $start-$end/$total\r\n" else "") +
                "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, HEAD, OPTIONS\r\nAccess-Control-Allow-Headers: Range\r\nAccess-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges, X-Resolver-Error\r\nConnection: close\r\n\r\n").toByteArray())
            headersSent = true
            if (method == "HEAD") {
                out.flush()
                diagnostic("media_request", "$method range=$requestRange bytes=0 total=$total durationMs=${System.currentTimeMillis() - requestStartedAt}")
                return
            }
            var pos = start
            val buf = ByteArray(64 * 1024)
            while (pos <= end) {
                val n = min(buf.size.toLong(), end - pos + 1).toInt()
                val bytes = readDecrypted(src, pos, buf, n, total)
                if (bytes <= 0) throw IllegalStateException("unexpected end of upstream stream")
                out.write(buf, 0, bytes)
                bytesWritten += bytes
                pos += bytes
            }
            out.flush()
            diagnostic("media_request", "$method range=$requestRange sent=$bytesWritten expected=${end - start + 1} total=$total durationMs=${System.currentTimeMillis() - requestStartedAt}")
        } catch (e: Throwable) {
            diagnostic("media_request_error", "$requestMethod range=$requestRange sent=$bytesWritten pos=$requestStart..$requestEnd total=$knownTotal durationMs=${System.currentTimeMillis() - requestStartedAt} ${e.javaClass.simpleName}: ${e.message ?: "no message"}")
            if (!headersSent) runCatching { reply(client, 503, "Abyss upstream unavailable") }
        }
    }

    private fun diagnostic(stage: String, detail: String) {
        Log.i("KinoExternalResolver", "$stage: $detail")
        context.getSharedPreferences(ResolverService.DIAG_PREFS, Context.MODE_PRIVATE).edit()
            .putString("stage", stage)
            .putString("detail", detail.take(600))
            .putLong("time", System.currentTimeMillis())
            .putString("history", ((context.getSharedPreferences(ResolverService.DIAG_PREFS, Context.MODE_PRIVATE).getString("history", "") ?: "") +
                "${System.currentTimeMillis()}|$stage|${detail.replace("\n", " ").take(220)}\n").lines().filter { it.isNotBlank() }.takeLast(12).joinToString("\n"))
            .apply()
    }

    private fun readDecrypted(src: AbyssSource, offset: Long, out: ByteArray, len: Int, total: Long): Int {
        if (offset >= total) return -1
        val chunk = (offset / chunkSize).toInt()
        // Include the chunk size in the key so cache files from older app versions cannot be misread.
        val file = File(cache, md5(src.url) + "-${chunkSize}-$chunk.bin")
        if (!file.exists()) synchronized(file.absolutePath.intern()) {
            if (!file.exists()) fetchChunk(src, chunk, file, total)
        }
        val inChunk = (offset % chunkSize).toInt()
        val count = min(len, (file.length() - inChunk).toInt().coerceAtLeast(0))
        if (count <= 0) return -1
        java.io.RandomAccessFile(file, "r").use { it.seek(inChunk.toLong()); it.readFully(out, 0, count) }
        return count
    }

    private fun fetchChunk(src: AbyssSource, index: Int, target: File, total: Long) {
        val started = System.currentTimeMillis()
        val start = index.toLong() * chunkSize
        val end = min(total - 1, start + chunkSize - 1)
        val expected = end - start + 1
        val conn = URL(src.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 30000; conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36")
        conn.setRequestProperty("Referer", src.referer.ifBlank { "https://player.abyssplayer.com/" })
        conn.setRequestProperty("Origin", "https://player.abyssplayer.com")
        conn.setRequestProperty("Range", "bytes=$start-$end")
        diagnostic("upstream_chunk_start", "chunk=$index range=$start-$end expected=$expected")
        var code = -1
        var received = 0L
        try {
            code = conn.responseCode
            val contentRange = conn.getHeaderField("Content-Range")
            if (code != 206 && !(code == 200 && start == 0L)) {
                throw IllegalStateException("upstream HTTP $code range=$start-$end contentRange=${contentRange ?: "none"}")
            }
            if (code == 206) {
                val match = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
                    .matchEntire(contentRange?.trim() ?: "")
                    ?: throw IllegalStateException("invalid upstream Content-Range")
                val actualStart = match.groupValues[1].toLong()
                val actualEnd = match.groupValues[2].toLong()
                if (actualStart != start || actualEnd < actualStart || actualEnd > end) {
                    throw IllegalStateException("upstream range mismatch requested=$start-$end got=$actualStart-$actualEnd")
                }
            }
            val temp = File(target.parentFile, target.name + ".part")
            conn.inputStream.use { input -> temp.outputStream().use { output ->
                var left = expected
                val buf = ByteArray(16384)
                while (left > 0) {
                    val n = input.read(buf, 0, min(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    output.write(buf, 0, n); left -= n; received += n
                }
                if (left > 0) throw IllegalStateException("upstream truncated chunk expected=$expected received=$received")
            } }
            if (start == 0L) {
                val bytes = temp.readBytes()
                val prefixLength = min(65536, bytes.size)
                val encryptedMarker = if (bytes.size >= 8) String(bytes, 4, 4, Charsets.US_ASCII) else "short"
                if (prefixLength >= 8) {
                    decryptPrefix(bytes, prefixLength, src.filename)
                    val marker = String(bytes, 4, 4, Charsets.US_ASCII)
                    diagnostic("upstream_prefix_check", "chunk=$index bytes=$prefixLength markerBefore=$encryptedMarker markerAfter=$marker")
                    if (marker != "ftyp") throw IllegalStateException("MP4 prefix check failed marker=$marker")
                    temp.writeBytes(bytes)
                } else throw IllegalStateException("upstream first chunk too short bytes=${bytes.size}")
            }
            if (!temp.renameTo(target)) throw IllegalStateException("could not commit cache chunk")
            diagnostic("upstream_chunk_ok", "chunk=$index status=$code received=$received contentRange=${contentRange ?: "none"} durationMs=${System.currentTimeMillis() - started}")
        } catch (e: Throwable) {
            diagnostic("upstream_chunk_error", "chunk=$index status=$code range=$start-$end received=$received durationMs=${System.currentTimeMillis() - started} ${e.javaClass.simpleName}: ${e.message ?: "no message"}")
            throw e
        } finally { conn.disconnect() }
    }

    private fun decryptPrefix(bytes: ByteArray, length: Int, filename: String) {
        AbyssCrypto.decryptMp4Prefix(bytes, length, filename)
    }

    private fun totalLength(src: AbyssSource): Long {
        if (probedUrl == src.url && probedLength > 0L) return probedLength
        synchronized(this) {
            if (probedUrl == src.url && probedLength > 0L) return probedLength
            val length = probe(src).first
            probedUrl = src.url
            probedLength = length
            return length
        }
    }

    private fun probe(src: AbyssSource): Pair<Long, Boolean> {
        val conn = URL(src.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000; conn.readTimeout = 15000; conn.setRequestProperty("Range", "bytes=0-0")
        conn.setRequestProperty("Referer", "https://player.abyssplayer.com/")
        try {
            val code = conn.responseCode
            val total = if (code == 206) conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
                else conn.contentLengthLong.takeIf { it > 0 }
            return (total ?: throw IllegalStateException("source length unavailable")) to (code == 206)
        } finally { conn.disconnect() }
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

    private fun reply(client: Socket, status: Int, message: String) {
        val ascii = message.replace(Regex("[^ -~]"), " ").take(120)
        val bytes = "HTTP/1.1 $status Error\r\nX-Resolver-Error: unavailable; $ascii\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
        client.getOutputStream().write(bytes)
    }

    private fun md5(s: String) = AbyssCrypto.md5Hex(s)
}
