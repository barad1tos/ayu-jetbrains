package dev.ayuislands.syntax

internal data class SyntaxCellId(
    val languageStorageId: String,
    val primitiveStorageId: String,
) {
    init {
        require(languageStorageId.isNotBlank() && CELL_SEPARATOR !in languageStorageId)
        require(primitiveStorageId.isNotBlank() && CELL_SEPARATOR !in primitiveStorageId)
    }

    fun encode(): String = "$languageStorageId$CELL_SEPARATOR$primitiveStorageId"

    companion object {
        fun parse(compositeKey: String): SyntaxCellId? {
            val separatorIndex = compositeKey.indexOf(CELL_SEPARATOR)
            if (separatorIndex <= 0 || separatorIndex == compositeKey.lastIndex) return null
            if (compositeKey.indexOf(CELL_SEPARATOR, separatorIndex + 1) >= 0) return null
            val languageStorageId = compositeKey.substring(0, separatorIndex)
            val primitiveStorageId = compositeKey.substring(separatorIndex + 1)
            if (languageStorageId.isBlank() || primitiveStorageId.isBlank()) return null
            return SyntaxCellId(
                languageStorageId = languageStorageId,
                primitiveStorageId = primitiveStorageId,
            )
        }
    }
}

internal object SyntaxCellCodec {
    /** Decodes known sparse cells without modifying the opaque persisted source map. */
    fun <T : Any> decode(
        flat: Map<String, String>,
        decodeValue: (String) -> T?,
    ): Map<String, Map<String, T>> {
        val nested = mutableMapOf<String, MutableMap<String, T>>()
        for ((compositeKey, encodedValue) in flat) {
            val cell = SyntaxCellId.parse(compositeKey) ?: continue
            val decoded = decodeValue(encodedValue) ?: continue
            nested.getOrPut(cell.languageStorageId) { mutableMapOf() }[cell.primitiveStorageId] = decoded
        }
        return nested
    }

    /** Applies only explicit known-cell updates while preserving all opaque entries and their order. */
    fun <T : Any> updateKnownCells(
        original: Map<String, String>,
        updates: Map<SyntaxCellId, T?>,
        encode: (T) -> String,
    ): Map<String, String> =
        LinkedHashMap(original).apply {
            for ((cell, value) in updates) {
                if (value == null) remove(cell.encode()) else this[cell.encode()] = encode(value)
            }
        }

    fun removeKnownCells(
        store: MutableMap<String, String>,
        cells: Set<SyntaxCellId>,
    ) {
        cells.forEach { cell -> store.remove(cell.encode()) }
    }
}

private const val CELL_SEPARATOR = '|'
