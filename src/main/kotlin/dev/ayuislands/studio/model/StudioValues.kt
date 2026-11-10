package dev.ayuislands.studio.model

/** A collection value whose storage cannot be changed through its input or a cast. */
internal class StudioList<out T>(
    elements: Collection<T>,
) : AbstractList<T>() {
    private val stored: List<T> = elements.toList()

    override val size: Int get() = stored.size

    override fun get(index: Int): T = stored[index]
}

/** Exact source or opaque payload; never exposes its backing array. */
internal class StudioBytes(
    bytes: ByteArray,
) {
    private val stored: ByteArray = bytes.copyOf()

    fun toByteArray(): ByteArray = stored.copyOf()

    override fun equals(other: Any?): Boolean = other is StudioBytes && stored.contentEquals(other.stored)

    override fun hashCode(): Int = stored.contentHashCode()
}

internal sealed interface RawValue {
    data object Null : RawValue

    data class Text(
        val value: String,
    ) : RawValue

    data class BooleanValue(
        val value: Boolean,
    ) : RawValue

    data class Integer(
        val value: Int,
    ) : RawValue

    data class LongValue(
        val value: Long,
    ) : RawValue

    data class FloatBits(
        val bits: Int,
    ) : RawValue

    data class Entry(
        val key: String,
        val value: RawValue,
    )

    data class OrderedMap(
        val entries: StudioList<Entry>,
    ) : RawValue

    data class OrderedSet(
        val values: StudioList<String>,
    ) : RawValue

    data class OrderedList(
        val values: StudioList<RawValue>,
    ) : RawValue

    data class Bytes(
        val value: StudioBytes,
    ) : RawValue
}
