package dev.ayuislands.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.SystemAccentProvider
import dev.ayuislands.font.FontCatalog
import java.awt.GraphicsEnvironment

@Service
@State(
    name = "AyuIslandsSettings",
    storages = [Storage("ayuIslands.xml")],
)
class AyuIslandsSettings : SimplePersistentStateComponent<AyuIslandsState>(AyuIslandsState()) {
    companion object {
        fun getInstance(): AyuIslandsSettings =
            ApplicationManager
                .getApplication()
                .getService(AyuIslandsSettings::class.java)
    }

    fun getAccentForVariant(variant: AyuVariant): String {
        if (state.followSystemAccent) {
            SystemAccentProvider.resolve()?.let { return it }
        }
        return when (variant) {
            AyuVariant.MIRAGE -> state.mirageAccent ?: variant.defaultAccent
            AyuVariant.DARK -> state.darkAccent ?: variant.defaultAccent
            AyuVariant.LIGHT -> state.lightAccent ?: variant.defaultAccent
        }
    }

    /**
     * Seed [AyuIslandsState.installedFonts] from the JVM font registry on first run.
     *
     * Returning users who installed Whisper/Ambient/Neon/Cyberpunk fonts manually (or via
     * the previous Settings panel brew flow) wouldn't otherwise be marked as "installed"
     * and would get re-prompted by the wizard. This idempotent probe walks every
     * [FontCatalog] entry, checks `GraphicsEnvironment.availableFontFamilyNames` for the
     * canonical family name, and adds matches to state.
     *
     * Gated on [AyuIslandsState.installedFontsSeeded] — runs once per install.
     */
    fun seedInstalledFontsFromDiskIfNeeded() {
        if (state.installedFontsSeeded) return
        try {
            val available =
                GraphicsEnvironment
                    .getLocalGraphicsEnvironment()
                    .availableFontFamilyNames
                    .toHashSet()
            for (entry in FontCatalog.entries) {
                if (available.contains(entry.familyName) && !state.installedFonts.contains(entry.familyName)) {
                    state.installedFonts.add(entry.familyName)
                }
            }
        } finally {
            state.installedFontsSeeded = true
        }
    }

    fun setAccentForVariant(
        variant: AyuVariant,
        hex: String,
    ) {
        when (variant) {
            AyuVariant.MIRAGE -> state.mirageAccent = hex
            AyuVariant.DARK -> state.darkAccent = hex
            AyuVariant.LIGHT -> state.lightAccent = hex
        }
    }
}
