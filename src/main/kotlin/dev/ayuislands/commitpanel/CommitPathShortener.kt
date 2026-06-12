package dev.ayuislands.commitpanel

internal data class CommitPathShorteningRequest(
    val pathText: String,
    val fullRowWidth: Int,
    val availableRowWidth: Int,
    val minHiddenLevels: Int,
    val maxHiddenLevels: Int,
)

internal object CommitPathShortener {
    private const val ELLIPSIS = "..."
    private const val UNIX_SEPARATOR = "/"
    private const val WINDOWS_SEPARATOR = "\\"

    fun shorten(request: CommitPathShorteningRequest): String {
        val corePath = request.pathText.trim()
        if (corePath.isEmpty()) return request.pathText

        val separator = separatorFor(corePath)
        val segments = corePath.split(separator).filter { it.isNotEmpty() }
        if (segments.size <= 1) return request.pathText

        val minHiddenLevels =
            request
                .minHiddenLevels
                .coerceAtLeast(0)
                .coerceAtMost(segments.size)
        val maxHiddenLevels =
            request
                .maxHiddenLevels
                .coerceAtLeast(minHiddenLevels)
                .coerceAtMost(segments.size)

        val hiddenLevels =
            if (request.fullRowWidth <= request.availableRowWidth) {
                minHiddenLevels
            } else {
                maxHiddenLevels
            }

        return candidateFor(
            original = request.pathText,
            segments = segments,
            separator = separator,
            hiddenLevels = hiddenLevels,
        )
    }

    private fun candidateFor(
        original: String,
        segments: List<String>,
        separator: String,
        hiddenLevels: Int,
    ): String {
        if (hiddenLevels <= 0) return original

        val keptCount = (segments.size - hiddenLevels).coerceAtLeast(0)
        val core =
            if (keptCount == 0) {
                ELLIPSIS
            } else {
                ELLIPSIS + separator + segments.takeLast(keptCount).joinToString(separator)
            }
        return preserveOuterWhitespace(original, core)
    }

    private fun preserveOuterWhitespace(
        original: String,
        core: String,
    ): String {
        val firstCoreIndex = original.indexOfFirst { !it.isWhitespace() }
        if (firstCoreIndex < 0) return original

        val lastCoreIndex = original.indexOfLast { !it.isWhitespace() }
        return original.take(firstCoreIndex) + core + original.drop(lastCoreIndex + 1)
    }

    private fun separatorFor(path: String): String {
        val windowsSeparators = path.count { it == '\\' }
        val unixSeparators = path.count { it == '/' }
        return if (windowsSeparators > unixSeparators) WINDOWS_SEPARATOR else UNIX_SEPARATOR
    }
}
