package com.myne23.abyssresolver

/**
 * JVM-only source selection policy. Keeps source classification separate from Android/network code.
 */
internal data class AbyssSourceOption(
    val label: String,
    val resId: Int,
    val size: Long,
    val partSize: Long,
    val status: Boolean,
    val path: String,
    val url: String,
    val keys: List<String> = emptyList()
)

internal data class AbyssSourceSelectionResult(
    val selected: AbyssSourceOption?,
    val validCount: Int
)

internal object AbyssSourceSelection {
    fun select(options: List<AbyssSourceOption>, requestedQuality: String): AbyssSourceSelectionResult {
        val valid = options.filter { option ->
            option.path.isNotBlank() &&
                option.url.startsWith("https://") &&
                (option.partSize == 0L || option.partSize >= option.size)
        }
        if (valid.isEmpty()) return AbyssSourceSelectionResult(null, 0)

        val requested = when (requestedQuality.trim().lowercase()) {
            "3", "480", "480p" -> 3
            "4", "720", "720p" -> 4
            "5", "1080", "1080p" -> 5
            "best" -> Int.MAX_VALUE
            else -> Int.MAX_VALUE
        }
        val chosenResId = if (requested == Int.MAX_VALUE) {
            valid.maxOf { it.resId }
        } else {
            valid.filter { it.resId == requested }.maxByOrNull { rank(it) }?.resId
                ?: valid.filter { it.resId < requested }.maxOfOrNull { it.resId }
                ?: valid.minOf { it.resId }
        }
        val selected = valid.asSequence()
            .filter { it.resId == chosenResId }
            .sortedWith(compareByDescending<AbyssSourceOption> { it.status }.thenByDescending { it.size })
            .firstOrNull()
        return AbyssSourceSelectionResult(selected, valid.size)
    }

    fun diagnosticSummary(options: List<AbyssSourceOption>, result: AbyssSourceSelectionResult): String {
        val perSource = options.mapIndexed { index, option ->
            val reason = when {
                option.path.isBlank() || option.url.isBlank() || !option.url.startsWith("https://") -> "sin path/url (missing path/url)"
                option.partSize > 0L && option.partSize < option.size -> "fragmentada (fragmented)"
                result.selected === option -> "elegida (selected)"
                else -> "no es la calidad pedida (not requested quality)"
            }
            "#$index label=${option.label} res_id=${option.resId} size=${option.size} partSize=${option.partSize} reason=$reason keys=${option.keys.joinToString(",")}"
        }.joinToString(" || ")
        return "sourceCountBefore=${options.size} sourceCountAfter=${result.validCount}; $perSource"
    }

    private fun rank(option: AbyssSourceOption): Long =
        (if (option.status) 1L shl 62 else 0L) + option.size.coerceAtLeast(0L).coerceAtMost((1L shl 62) - 1)
}
