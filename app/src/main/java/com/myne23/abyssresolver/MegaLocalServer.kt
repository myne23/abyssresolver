package com.myne23.abyssresolver

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * Separate loopback HTTP proxy for MEGA. No Abyss classes or transport methods are used here.
 * The player sees ordinary MP4 bytes; encrypted MEGA ranges are decrypted locally with AES-CTR.
 */
internal object MegaLocalServer {
    private const val API = "https://g.api.mega.co.nz/cs?id=0"
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36"
    private const val DEFAULT_WINDOW = 6 * 1024 * 1024L
    private const val MAX_HEADER_BYTES = 8192
    private const val META_TTL_MS = 6 * 60 * 60 * 1000L

    private data class Metadata(val storageUrl: String, val size: Long, val expiresAt: Long)
    private val keys = ConcurrentHashMap<String, String>()
    private val metadata = ConcurrentHashMap<String, Metadata>()
    @Volatile private var listener: ServerSocket? = null
    @Volatile var lastActivityMs: Long = 0L
        private set

    @Synchronized
    fun start(handle: String, key: String): String {
        keys[handle] = key
        if (listener?.isClosed != false) {
            val socket = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
            listener = socket
            Thread {
                while (!socket.isClosed) {
                    try {
                        val client = socket.accept()
                        Thread({ serve(client) }, "mega-http-client").apply { isDaemon = true; start() }
                    } catch (_: Exception) {
                        if (!socket.isClosed) continue
                        break
                    }
                }
            }.apply { name = "mega-http"; isDaemon = true; start() }
        }
        lastActivityMs = System.currentTimeMillis()
        return "http://127.0.0.1:${listener!!.localPort}/mega/$handle"
    }

    @Synchronized
    fun stop() {
        runCatching { listener?.close() }
        listener = null
        keys.clear()
        metadata.clear()
    }

    private fun serve(socket: Socket) = socket.use { client ->
        var headersSent = false
        try {
            client.soTimeout = 30_000
            val input = BufferedInputStream(client.getInputStream())
            val request = readHeaders(input) ?: return
            lastActivityMs = System.currentTimeMillis()
            val first = request.firstOrNull()?.split(' ') ?: return
            val method = first.getOrNull(0).orEmpty()
            val target = first.getOrNull(1).orEmpty().substringBefore('?')
            val handle = Regex("^/mega/([A-Za-z0-9_-]+)$").matchEntire(target)?.groupValues?.get(1)
            if (method == "OPTIONS") {
                writeResponse(
                    client, 204, "No Content",
                    mapOf(
                        "Access-Control-Allow-Origin" to "*",
                        "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
                        "Access-Control-Allow-Headers" to "Range",
                        "Access-Control-Max-Age" to "600"
                    ),
                    ByteArray(0)
                )
                return
            }
            if ((method != "GET" && method != "HEAD") || handle == null) {
                writeResponse(client, 404, "Not Found", emptyMap(), "not found".toByteArray())
                return
            }
            val key = keys[handle]
            if (key == null) {
                writeResponse(client, 404, "Not Found", emptyMap(), "unknown handle".toByteArray())
                return
            }
            val headers = request.drop(1).mapNotNull { line ->
                val index = line.indexOf(':')
                if (index <= 0) null else line.substring(0, index).trim().lowercase() to line.substring(index + 1).trim()
            }.toMap()
            val meta = getMetadata(handle)
            val requested = parseRange(headers["range"], meta.size)
            if (requested == null) {
                writeResponse(client, 416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */${meta.size}", "Accept-Ranges" to "bytes"), ByteArray(0))
                return
            }
            val (start, end) = requested
            val bodyLength = end - start + 1L
            val body = if (method == "HEAD") ByteArray(0) else decryptRange(meta, key, start, end)
            val responseHeaders = linkedMapOf(
                "Content-Type" to "video/mp4",
                "Accept-Ranges" to "bytes",
                "Content-Length" to bodyLength.toString(),
                "Content-Range" to "bytes $start-$end/${meta.size}",
                "Cache-Control" to "no-store",
                "Access-Control-Allow-Origin" to "*",
                "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
                "Access-Control-Allow-Headers" to "Range",
                "Access-Control-Expose-Headers" to "Content-Length, Content-Range, Accept-Ranges, X-Resolver-Error",
                "Connection" to "close"
            )
            writeHeaders(client, 206, "Partial Content", responseHeaders)
            headersSent = true
            if (method != "HEAD") {
                val out = BufferedOutputStream(client.getOutputStream())
                out.write(body)
                out.flush()
            }
        } catch (e: MegaFailure) {
            if (!headersSent) runCatching {
                writeResponse(client, e.status, e.statusText, mapOf("Content-Type" to "text/plain; charset=utf-8"), e.message.orEmpty().toByteArray())
            }
        } catch (_: java.net.SocketException) {
            // Players commonly close a range request after they have enough buffered data.
        } catch (e: Throwable) {
            if (!headersSent) runCatching {
                writeResponse(client, 502, "Bad Gateway", mapOf("Content-Type" to "text/plain; charset=utf-8"), "MEGA upstream unavailable".toByteArray())
            }
        }
    }

    private fun readHeaders(input: InputStream): List<String>? {
        val bytes = ByteArrayOutputStream()
        var previous = 0
        while (bytes.size() < MAX_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) return null
            bytes.write(value)
            val current = value.toByte()
            if (previous == 13 && current == 10.toByte()) {
                val data = bytes.toByteArray()
                val n = data.size
                if (n >= 4 && data[n - 4] == 13.toByte() && data[n - 3] == 10.toByte()) {
                    return data.toString(Charsets.ISO_8859_1).trimEnd('\r', '\n').split("\r\n")
                }
            }
            previous = if (value == 10) 10 else value
        }
        throw MegaFailure(400, "Bad Request", "request headers too large")
    }

    private fun getMetadata(handle: String): Metadata {
        val now = System.currentTimeMillis()
        metadata[handle]?.takeIf { it.expiresAt > now }?.let { return it }
        synchronized(metadata) {
            metadata[handle]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { return it }
            val connection = (URL("$API&n=$handle").openConnection() as HttpURLConnection)
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.doOutput = true
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use {
                    it.write("""[{"a":"g","g":1,"ssl":1,"p":"$handle"}]""".toByteArray(Charsets.UTF_8))
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (status !in 200..299) throw MegaFailure(502, "Bad Gateway", "MEGA API HTTP $status")
                val array = JSONArray(text)
                val first = array.opt(0)
                if (first is Number && first.toInt() < 0) {
                    val code = first.toInt()
                    if (code == -9) throw MegaFailure(404, "Not Found", "MEGA file not found")
                    if (code == -11 || code == -14) throw MegaFailure(429, "Too Many Requests", "MEGA rate limit or quota exceeded")
                    throw MegaFailure(502, "Bad Gateway", "MEGA API error $code")
                }
                val item = array.optJSONObject(0) ?: throw MegaFailure(502, "Bad Gateway", "Invalid MEGA API response")
                val storageUrl = item.optString("g")
                val size = item.optLong("s", -1L)
                if (storageUrl.isBlank() || storageUrl == "null" || size <= 0L) {
                    throw MegaFailure(502, "Bad Gateway", "MEGA response did not include a storage URL and file size")
                }
                return Metadata(storageUrl, size, System.currentTimeMillis() + META_TTL_MS).also { metadata[handle] = it }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun decryptRange(meta: Metadata, encodedKey: String, start: Long, end: Long): ByteArray {
        val keyBytes = try {
            val urlSafe = encodedKey.replace('-', '+').replace('_', '/')
            val padded = urlSafe + "=".repeat((4 - urlSafe.length % 4) % 4)
            Base64.decode(padded, Base64.DEFAULT or Base64.NO_WRAP)
        } catch (_: Exception) {
            throw MegaFailure(400, "Bad Request", "Invalid MEGA key")
        }
        if (keyBytes.size != 32) throw MegaFailure(400, "Bad Request", "MEGA key must decode to 32 bytes")
        val words = ByteBuffer.wrap(keyBytes)
        val k = IntArray(8) { words.int }
        val aesKey = ByteBuffer.allocate(16)
            .putInt(k[0] xor k[4]).putInt(k[1] xor k[5]).putInt(k[2] xor k[6]).putInt(k[3] xor k[7]).array()
        val nonce = ByteBuffer.allocate(8).putInt(k[4]).putInt(k[5]).array()
        val alignedStart = (start / 16L) * 16L
        val encryptedLength = (end - alignedStart + 1L).toInt()
        val connection = (URL(meta.storageUrl).openConnection() as HttpURLConnection)
        val encrypted = try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Range", "bytes=$alignedStart-$end")
            val status = connection.responseCode
            if (status != 206 && !(status == 200 && alignedStart == 0L)) {
                throw MegaFailure(502, "Bad Gateway", "MEGA storage range returned HTTP $status")
            }
            readExact(connection.inputStream, encryptedLength)
        } finally {
            connection.disconnect()
        }
        val iv = ByteBuffer.allocate(16).put(nonce).putLong(alignedStart / 16L).array()
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(iv))
        val clear = cipher.doFinal(encrypted)
        val skip = (start - alignedStart).toInt()
        return clear.copyOfRange(skip, skip + (end - start + 1L).toInt())
    }

    private fun readExact(input: InputStream, count: Int): ByteArray {
        val result = ByteArray(count)
        input.use { stream ->
            var offset = 0
            while (offset < count) {
                val n = stream.read(result, offset, count - offset)
                if (n < 0) throw MegaFailure(502, "Bad Gateway", "MEGA storage ended before requested range")
                offset += n
            }
        }
        return result
    }

    private fun parseRange(value: String?, size: Long): Pair<Long, Long>? {
        if (size <= 0L) return null
        if (value.isNullOrBlank()) return 0L to min(size - 1L, DEFAULT_WINDOW - 1L)
        val match = Regex("^bytes=(\\d*)-(\\d*)$", RegexOption.IGNORE_CASE).matchEntire(value.trim()) ?: return null
        val left = match.groupValues[1]
        val right = match.groupValues[2]
        if (left.isBlank() && right.isBlank()) return null
        val start: Long
        val end: Long
        if (left.isBlank()) {
            val suffix = right.toLongOrNull()?.takeIf { it > 0L } ?: return null
            end = size - 1L
            start = maxOf(0L, end - min(suffix, DEFAULT_WINDOW) + 1L)
        } else {
            start = left.toLongOrNull() ?: return null
            val requestedEnd = if (right.isBlank()) size - 1L else (right.toLongOrNull() ?: return null)
            end = min(min(size - 1L, requestedEnd), start + DEFAULT_WINDOW - 1L)
        }
        if (start >= size || end < start) return null
        return start to min(end, size - 1L)
    }

    private fun writeResponse(client: Socket, code: Int, reason: String, headers: Map<String, String>, body: ByteArray) {
        val values = LinkedHashMap(headers)
        values.putIfAbsent("Content-Length", body.size.toString())
        values.putIfAbsent("Connection", "close")
        writeHeaders(client, code, reason, values)
        if (body.isNotEmpty()) client.getOutputStream().apply { write(body); flush() }
    }

    private fun writeHeaders(client: Socket, code: Int, reason: String, headers: Map<String, String>) {
        val out = BufferedOutputStream(client.getOutputStream())
        out.write("HTTP/1.1 $code $reason\r\n".toByteArray(Charsets.ISO_8859_1))
        headers.forEach { (key, value) -> out.write("$key: $value\r\n".toByteArray(Charsets.ISO_8859_1)) }
        out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private class MegaFailure(val status: Int, val statusText: String, message: String) : Exception(message)
}
