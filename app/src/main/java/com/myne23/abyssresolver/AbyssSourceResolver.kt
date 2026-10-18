package com.myne23.abyssresolver

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.Charset
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class AbyssSource(val url: String, val filename: String, val referer: String)
internal class ResolveFailure(val code: String, message: String) : Exception(message)

internal object AbyssSourceResolver {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36"
    private val latin1: Charset = Charsets.ISO_8859_1

    fun resolve(ref: String, cancelled: java.util.concurrent.atomic.AtomicBoolean, diagnostic: (String, String) -> Unit = { _, _ -> }, progress: (Int, String) -> Unit): AbyssSource {
        val parts = ref.split(":", limit = 4)
        if (parts.size != 4 || parts[0] != "abyss") throw ResolveFailure("unsupported", "Referencia Abyss inválida / Invalid Abyss reference")
        val rawSlug = parts[1]
        val quality = parts[2]
        val referer = decode(parts[3]).ifBlank { "https://mytsumi.com/" }
        val embed = if (rawSlug.startsWith("https%3A", true) || rawSlug.startsWith("https://")) decode(rawSlug) else
            "https://player.abyssplayer.com/$rawSlug"
        val embedUrl = try { URL(embed) } catch (_: Exception) {
            throw ResolveFailure("unsupported", "URL de Abyss inválida / Invalid Abyss URL")
        }
        if (embedUrl.protocol != "https" || embedUrl.host != "player.abyssplayer.com") throw ResolveFailure("unsupported", "El enlace no pertenece a AbyssPlayer / Link is not from AbyssPlayer")

        progress(8, "Abriendo reproductor Abyss… / Opening Abyss player…")
        val html = getText(embed, referer, cancelled)
        val marker = "const datas = \""
        val at = html.indexOf(marker)
        if (at < 0) throw ResolveFailure("bad_response", "No se encontró el paquete de datos de Abyss / Abyss data payload was not found")
        val start = at + marker.length
        val end = html.indexOf('"', start)
        if (end <= start) throw ResolveFailure("bad_response", "Paquete de datos incompleto / Incomplete data payload")
        val packed = try { Base64.decode(html.substring(start, end), Base64.DEFAULT) } catch (_: Exception) {
            throw ResolveFailure("bad_response", "No se pudo decodificar el paquete de Abyss / Could not decode Abyss payload")
        }
        val jsonText = String(packed, latin1)
        val data = try { JSONObject(jsonText) } catch (_: Exception) {
            throw ResolveFailure("bad_response", "El paquete de Abyss no es JSON válido / Abyss payload is not valid JSON")
        }
        val userId = data.optString("user_id")
        val slug = data.optString("slug")
        val md5Id = data.optString("md5_id")
        val encryptedMedia = data.optString("media")
        if (userId.isBlank() || slug.isBlank() || md5Id.isBlank() || encryptedMedia.isBlank()) {
            throw ResolveFailure("bad_response", "Faltan campos de identificación de Abyss / Abyss identification fields are missing")
        }

        progress(32, "Descifrando metadatos… / Decrypting metadata…")
        val mediaKey = md5("$userId:$slug:$md5Id").toByteArray(Charsets.US_ASCII)
        val mediaCipher = Cipher.getInstance("AES/CTR/NoPadding")
        mediaCipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(mediaKey, "AES"), IvParameterSpec(mediaKey.copyOfRange(0, 16)))
        val encryptedBytes = Base64.encode(encryptedMedia.toByteArray(latin1), Base64.NO_WRAP)
        val plaintext = String(mediaCipher.doFinal(Base64.decode(encryptedBytes, Base64.NO_WRAP)), Charsets.UTF_8)
        val payload = try { JSONObject(plaintext) } catch (_: Exception) {
            throw ResolveFailure("bad_response", "No se pudieron descifrar los metadatos MP4 / Could not decrypt MP4 metadata")
        }
        // Abyss variants may wrap provider metadata inside a "media" object.
        // Normalize both shapes before selecting a source.
        val media = payload.optJSONObject("media") ?: payload
        val mp4 = media.optJSONObject("mp4")
        val hls = media.optJSONObject("hls")
        if (mp4 == null) {
            val keys = media.keys().asSequence().take(12).toList().joinToString(",")
            if (hls != null) {
                val hlsKeys = hls.keys().asSequence().take(12).toList().joinToString(",")
                val hlsSources = hls.optJSONArray("sources")
                val metadata = hls.optJSONObject("metadata")
                val streamsCount = metadata?.optJSONArray("streams")?.length() ?: 0
                val mediaCount = metadata?.optJSONArray("media")?.length() ?: 0
                throw ResolveFailure(
                    "hls_only",
                    "Abyss parece ofrecer HLS pero no MP4 / HLS detected without MP4; mediaKeys=$keys hlsKeys=$hlsKeys hlsSources=${hlsSources?.length() ?: 0} streams=$streamsCount media=$mediaCount"
                )
            }
            throw ResolveFailure("unavailable", "Abyss no ofrece fuentes MP4 reconocibles / No MP4 sources object; payloadKeys=$keys")
        }
        val sources = mp4.optJSONArray("sources") ?: JSONArray()
        val rawSources = (0 until sources.length()).mapNotNull { sources.optJSONObject(it) }
        val options = rawSources.map { json ->
            AbyssSourceOption(
                label = json.optString("label").takeUnless { it == "null" }.orEmpty(),
                resId = json.optInt("res_id", 0),
                size = json.optLong("size", 0L),
                partSize = json.optLong("partSize", 0L),
                status = json.optBoolean("status", false),
                path = json.optString("path").takeUnless { it == "null" }.orEmpty(),
                url = json.optString("url").takeUnless { it == "null" }.orEmpty(),
                keys = json.keys().asSequence().toList()
            )
        }
        val selection = AbyssSourceSelection.select(options, quality)
        val selected = selection.selected
        val perSource = options.mapIndexed { index, option ->
            val reason = when {
                option.path.isBlank() || option.url.isBlank() || !option.url.startsWith("https://") -> "sin path/url (missing path/url)"
                option.partSize > 0L && option.partSize < option.size -> "fragmentada (fragmented)"
                selected === option -> "elegida (selected)"
                else -> "no es la calidad pedida (not requested quality)"
            }
            "#$index label=${option.label} res_id=${option.resId} size=${option.size} partSize=${option.partSize} reason=$reason keys=${option.keys.joinToString(",")}"
        }.joinToString(" || ")
        val diagnosticSummary = "sourceCountBefore=${options.size} sourceCountAfter=${selection.validCount}; $perSource"
        diagnostic("source_selection", diagnosticSummary)
        if (selected == null) {
            val onlyFragmented = options.isNotEmpty() && options.all {
                it.path.isNotBlank() && it.url.startsWith("https://") && it.partSize > 0L && it.partSize < it.size
            }
            if (onlyFragmented) {
                throw ResolveFailure(
                    "unsupported",
                    "Este contenido solo está disponible en un formato fragmentado que todavía no se soporta / This content is only available in a split format that is not supported yet"
                )
            }
            val mp4Keys = mp4.keys().asSequence().toList().joinToString(",")
            val domains = describeMetadata(mp4.opt("domains"))
            val firstDatas = describeMetadata(mp4.opt("fristDatas"))
            if (hls != null) {
                val hlsKeys = hls.keys().asSequence().take(12).toList().joinToString(",")
                val hlsSources = hls.optJSONArray("sources")
                val metadata = hls.optJSONObject("metadata")
                val streamsCount = metadata?.optJSONArray("streams")?.length() ?: 0
                val mediaCount = metadata?.optJSONArray("media")?.length() ?: 0
                throw ResolveFailure(
                    "hls_only",
                    "Abyss no tiene MP4 utilizable; HLS disponible / No usable MP4; HLS metadata found; mp4Keys=$mp4Keys sourceCountBefore=${options.size} sourceCountAfter=0 hlsKeys=$hlsKeys hlsSources=${hlsSources?.length() ?: 0} streams=$streamsCount media=$mediaCount"
                )
            }
            throw ResolveFailure(
                "unavailable",
                "No hay fuentes MP4 válidas / No valid MP4 sources; sourceCountBefore=${options.size} sourceCountAfter=0 domains=$domains fristDatas=$firstDatas"
            )
        }
        val base = selected.url.trimEnd('/')
        val path = selected.path.trimStart('/')
        val finalUrl = "$base/$path"
        val filename = URL(finalUrl).path.substringAfterLast('/').ifBlank { path.substringAfterLast('/') }
        if (!finalUrl.startsWith("https://")) throw ResolveFailure("unsupported", "La fuente no usa HTTPS / Source is not HTTPS")
        progress(88, "Preparando transmisión segura… / Preparing stream…")
        if (cancelled.get()) throw ResolveFailure("cancelled", "Resolución cancelada / Resolution cancelled")
        progress(100, "Fuente encontrada / Source found")
        return AbyssSource(finalUrl, filename, referer)
    }

    private fun describeMetadata(value: Any?): String {
        return when (value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> "object(keys=" + value.keys().asSequence().take(12).toList().joinToString(",") + ")"
            is JSONArray -> {
                val first = value.opt(0)
                val firstSummary = when (first) {
                    is JSONObject -> "object(keys=" + first.keys().asSequence().take(12).toList().joinToString(",") + ")"
                    is JSONArray -> "array(count=" + first.length() + ")"
                    is String -> "string(" + first.take(80).replace(" ", "_") + ")"
                    null, JSONObject.NULL -> "null"
                    else -> first.javaClass.simpleName
                }
                "array(count=${value.length()},first=$firstSummary)"
            }
            is String -> "string(" + value.take(80).replace(" ", "_") + ")"
            else -> value.javaClass.simpleName
        }
    }

    private fun qualityHeight(o: JSONObject): Int {
        val s = listOf(o.optString("quality"), o.optString("label"), o.optString("path")).joinToString(" ")
        return Regex("(\\d{3,4})\\s*p?", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun getText(url: String, referer: String, cancelled: java.util.concurrent.atomic.AtomicBoolean): String {
        if (cancelled.get()) throw ResolveFailure("cancelled", "Resolución cancelada / Resolution cancelled")
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Referer", referer)
        c.setRequestProperty("Origin", "https://player.abyssplayer.com")
        try {
            if (c.responseCode !in 200..299) throw ResolveFailure("unavailable", "Abyss devolvió HTTP ${c.responseCode} / Abyss returned HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
    private fun md5(s: String) = AbyssCrypto.md5Hex(s)
}
