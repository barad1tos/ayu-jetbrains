package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.PortableReceipt

/** Native Accent fields retain their own admission and recovery rules until outer acceptance. */
internal sealed interface StudioAccentWrite {
    val receipts: StudioList<PortableReceipt>

    fun apply()

    fun restore(blockedBy: List<PortableReceipt>)

    fun accept()
}
