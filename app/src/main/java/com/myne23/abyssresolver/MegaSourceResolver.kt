package com.myne23.abyssresolver

/**
 * Parses the MEGA links passed by Kino. This parser is intentionally independent of
 * the Abyss resolver so the existing Abyss URL and transport paths are not involved.
 */
internal data class MegaSource(val handle: String, val key: String)

internal object MegaSourceResolver {
    private val handlePattern = Regex("^[A-Za-z0-9_-]{6,}$")
    private val keyPattern = Regex("^[A-Za-z0-9_-]{43}=?$")

    fun parse(ref: String): MegaSource? {
        val input = ref.trim().let {
            if (it.startsWith("mega:", ignoreCase = true)) it.substringAfter(':').trim() else it
        }
        val host = Regex("mega\\.nz", RegexOption.IGNORE_CASE).find(input) ?: return null
        var tail = input.substring(host.range.last + 1).trimStart('/')
        if (tail.isBlank()) return null

        // Keep the fragment: it contains the decryption key and is not sent to MEGA's server.
        val beforeFragment = tail.substringBefore('#').substringBefore('?')
        val fragment = tail.substringAfter('#', "").substringBefore('?')

        // Legacy links use /#!HANDLE!KEY and embed links can use /embed#!HANDLE!KEY.
        if (fragment.startsWith("!")) {
            val pieces = fragment.removePrefix("!").split('!')
            if (pieces.size >= 2) return validated(pieces[0], pieces[1])
        }

        // Modern links use /embed/HANDLE#KEY or /file/HANDLE#KEY.
        val pathParts = beforeFragment.split('/').filter { it.isNotBlank() }
        val handle = when {
            pathParts.size >= 2 && (pathParts[0].equals("embed", true) || pathParts[0].equals("file", true)) -> pathParts[1]
            pathParts.isNotEmpty() && !pathParts[0].equals("embed", true) && !pathParts[0].equals("file", true) -> pathParts[0]
            else -> ""
        }
        if (handle.isNotBlank() && fragment.isNotBlank()) return validated(handle, fragment)

        // Fallback for slightly different MEGA URL spellings.
        val fallback = Regex("(?:embed/|file/|/)([A-Za-z0-9_-]{6,})[#!]([A-Za-z0-9_-]{43}=?)(?:$|[?&])", RegexOption.IGNORE_CASE)
            .find(input)
        if (fallback != null) return validated(fallback.groupValues[1], fallback.groupValues[2])
        return null
    }

    private fun validated(handle: String, key: String): MegaSource? {
        val h = handle.trim()
        val k = key.trim().trimEnd('/')
        if (!handlePattern.matches(h) || !keyPattern.matches(k)) return null
        return MegaSource(h, k)
    }
}
