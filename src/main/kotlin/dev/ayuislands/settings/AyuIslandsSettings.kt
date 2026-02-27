package dev.ayuislands.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import dev.ayuislands.accent.AyuVariant

@Service
@State(
    name = "AyuIslandsSettings",
    storages = [Storage("ayuIslands.xml")]
)
class AyuIslandsSettings : SimplePersistentStateComponent<AyuIslandsState>(AyuIslandsState()) {

    companion object {
        fun getInstance(): AyuIslandsSettings =
            ApplicationManager.getApplication().getService(AyuIslandsSettings::class.java)
    }

    fun getAccentForVariant(variant: AyuVariant): String =
        when (variant) {
            AyuVariant.MIRAGE -> state.mirageAccent ?: variant.defaultAccent
            AyuVariant.DARK -> state.darkAccent ?: variant.defaultAccent
            AyuVariant.LIGHT -> state.lightAccent ?: variant.defaultAccent
        }

    fun setAccentForVariant(variant: AyuVariant, hex: String) {
        when (variant) {
            AyuVariant.MIRAGE -> state.mirageAccent = hex
            AyuVariant.DARK -> state.darkAccent = hex
            AyuVariant.LIGHT -> state.lightAccent = hex
        }
    }
}
