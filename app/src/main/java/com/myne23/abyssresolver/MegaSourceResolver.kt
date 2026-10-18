package com.myne23.abyssresolver

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class MegaSource(
    val url: String,
    val size: Long,
    val aesKey: ByteArray,
    val nonce: ByteArray,
    val name: String
)

internal object MegaSourceResolver {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36"

    fun resolve(ref: String, cancelled: AtomicBoolean, progress: (Int, String) -> Unit): MegaSource {
        progress(5, "Validando enlace MEGA… / Validating MEGA link…")
        val uri = try { URL(ref) } catch (_: Exception) {
            throw ResolveFailure("unsupported", "Enlace MEGA inválido / Invalid MEGA link")
        }
        if (uri.protocol != "https" || !uri.host.lowercase().matches(Regex("(www\\.)?mega\\.(nz|io)"))) {
            throw ResolveFailure("unsupported", "El enlace no pertenece a MEGA / Link is not from MEGA")
        }
        val parts = uri.path.split('/').filter { it.isNotBlank() }
        if (parts.size < 2 || parts[0] !in setOf("file", "embed")) {
            throw ResolveFailure("unsupported", "Se esperaba un enlace de archivo MEGA / Expected a MEGA file link")
        }
        val handle = parts[1]
        if (!handle.matches(Regex("[A-Za-z0-9_-]{6,32}"))) throw ResolveFailure("unsupported", "Identificador MEGA inválido / Invalid MEGA handle")
        val keyText = uri.ref?.removePrefix("!")?.takeIf { it.isNotBlank() }
            ?: throw ResolveFailure("unsupported", "El enlace MEGA no contiene la clave (#key) / MEGA link is missing its decryption key (#key)")
        val fileKey = try {
            Base64.decode(keyText.replace('-', '+').replace('_', '/'), Base64.DEFAULT)
        } catch (_: Exception) {
            throw ResolveFailure("unsupported", "No se pudo leer la clave MEGA / Could not decode MEGA key")
        }
        if (fileKey.size != 32) throw ResolveFailure("unsupported", "Clave MEGA de longitud inesperada / Unexpected MEGA key length")
        val aesKey = ByteArray(16)
        for (i in 0 until 16) aesKey[i] = (fileKey[i].toInt() xor fileKey[i + 16].toInt()).toByte()
        val nonce = fileKey.copyOfRange(16, 24)

        if (cancelled.get()) throw ResolveFailure("cancelled", "Resolución cancelada / Resolution cancelled")
        progress(20, "Consultando metadatos de MEGA… / Fetching MEGA metadata…")
        val request = JSONArray().put(JSONObject().put("a", "g").put("g", 1).put("p", handle))
        val endpoint = URL("https://g.api.mega.co.nz/cs?id=" + System.currentTimeMillis())
        val conn = endpoint.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.doOutput = true
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Content-Type", "application/json")
        val responseText = try {
            conn.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) throw ResolveFailure("unavailable", "MEGA devolvió HTTP ${conn.responseCode} / MEGA returned HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: ResolveFailure) { throw e }
        catch (_: Exception) { throw ResolveFailure("unavailable", "No se pudieron obtener los metadatos de MEGA / Could not fetch MEGA metadata") }
        finally { conn.disconnect() }

        val result = try { JSONArray(responseText) } catch (_: Exception) {
            throw ResolveFailure("bad_response", "Respuesta de metadatos MEGA inválida / Invalid MEGA metadata response")
        }
        val item = result.optJSONObject(0)
        if (item == null) {
            val code = result.optInt(0, 0)
            throw ResolveFailure("unavailable", "MEGA no devolvió el archivo (código $code) / MEGA did not return the file (code $code)")
        }
        val streamUrl = item.optString("g")
        val size = item.optLong("s", -1L)
        if (!streamUrl.startsWith("https://") || size <= 0L) {
            val keys = item.keys().asSequence().toList().sorted().joinToString(",").ifBlank { "(ninguna)" }
            val apiError = item.opt("e")?.toString() ?: item.opt("err")?.toString() ?: item.opt("error")?.toString() ?: "ninguno"
            throw ResolveFailure(
                "unavailable",
                "MEGA respondió sin URL/tamaño válido (keys=$keys, apiError=$apiError, size=$size) / MEGA returned no valid URL/size (keys=$keys, apiError=$apiError, size=$size)"
            )
        }
        if (cancelled.get()) throw ResolveFailure("cancelled", "Resolución cancelada / Resolution cancelled")
        progress(100, "Archivo MEGA listo / MEGA file ready")
        return MegaSource(streamUrl, size, aesKey, nonce, "video.mp4")
    }

    internal fun decryptRange(source: MegaSource, offset: Long, encrypted: ByteArray): ByteArray {
        val block = offset / 16L
        val skip = (offset % 16L).toInt()
        val iv = ByteArray(16)
        System.arraycopy(source.nonce, 0, iv, 0, minOf(8, source.nonce.size))
        var counter = block
        for (i in 15 downTo 8) {
            iv[i] = (counter and 0xff).toByte()
            counter = counter ushr 8
        }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(source.aesKey, "AES"), IvParameterSpec(iv))
        val plain = cipher.doFinal(encrypted)
        return if (skip == 0) plain else plain.copyOfRange(skip.coerceAtMost(plain.size), plain.size)
    }
}
