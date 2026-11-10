package dev.ayuislands.integration

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.FontPreferences
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.colors.impl.FontPreferencesImpl
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import dev.ayuislands.font.FontSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.studio.EmptyEffectRuntime
import dev.ayuislands.studio.StudioFixtures
import dev.ayuislands.studio.fields.StudioRuntimeFields
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.StudioLegacyProjection
import dev.ayuislands.studio.session.FontSchemeView
import dev.ayuislands.studio.session.StudioEffectInput
import dev.ayuislands.studio.session.StudioEffectOperation
import dev.ayuislands.studio.session.StudioFontHost
import dev.ayuislands.studio.session.StudioFontParticipant
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertIs

/** Uses real native scheme peers and the production participant; only persistence publication is test-owned. */
internal class FontNativeFixture {
    var state = AyuIslandsState()
    var scheme: EditorColorsScheme =
        EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme).apply {
            name = "Personal font scheme"
            fontPreferences = preferences("Dialog", 17.5f)
            consoleFontPreferences = preferences("Monospaced", 15.5f)
        }
    val schemes = mutableListOf(scheme)
    var refreshes = 0
    var pending: StudioEffectOperation? = null
    private val host =
        object : StudioFontHost {
            override fun observe() = FontSchemeView(schemes.toList(), scheme, null)

            override suspend fun resolveFamily(settings: FontSettings): String = settings.fontFamily

            override fun newIdentity(): String = UUID.randomUUID().toString()

            override fun refresh() {
                refreshes++
            }
        }
    val participant = StudioFontParticipant(host, EmptyCoroutineContext)

    fun apply(settings: FontSettings) {
        val candidate =
            copyState().apply {
                fontPresetEnabled = true
                fontPresetName = settings.preset.name
                fontPresetCustomizations[settings.preset.name] = settings.encode()
                fontApplyToConsole = settings.applyToConsole
            }
        accept(candidate)
    }

    fun disable() = accept(copyState().apply { fontPresetEnabled = false })

    fun accept(candidate: AyuIslandsState) =
        runBlocking {
            val document =
                assertIs<MigrationResult.Ready>(
                    StudioLegacyMigration.plan(StudioFixtures.capture(candidate)),
                ).document
            val input = StudioEffectInput(document, StudioTarget.Global, StudioList(emptyList()))
            val operation = participant.prepare(input, EmptyEffectRuntime)
            pending = operation
            operation.apply()
            val committed =
                document.copy(
                    runtime = StudioRuntimeFields.merge(document.runtime, operation.runtimeChanges),
                )
            val projected = requireNotNull(StudioLegacyProjection.project(committed, StudioTarget.Global)).settings
            state.fontOwnershipVersion = projected.fontOwnershipVersion
            state.fontOwnershipSnapshots.clear()
            state.fontOwnershipSnapshots.putAll(projected.fontOwnershipSnapshots)
            operation.accept()
            pending = null
        }

    fun restoreAttempt() =
        runBlocking {
            val operation = requireNotNull(pending)
            operation.prepareRestore(StudioList(emptyList()))
            operation.restore(StudioList(emptyList()))
            pending = null
        }

    fun reload() {
        state = copyState()
    }

    fun copyState(): AyuIslandsState =
        XmlSerializer.deserialize(
            JDOMUtil.load(JDOMUtil.writeElement(XmlSerializer.serialize(state))),
            AyuIslandsState::class.java,
        )

    fun replace(replacement: EditorColorsScheme) {
        schemes[schemes.indexOf(scheme)] = replacement
        scheme = replacement
    }

    companion object {
        fun preferences(
            family: String,
            size: Float,
        ): FontPreferencesImpl =
            FontPreferencesImpl().apply {
                register(family, size)
                addFontFamily("Unavailable Personal Font")
                setFontSize("Unavailable Personal Font", 19.25f)
                regularSubFamily = "Medium"
                boldSubFamily = "Bold"
                lineSpacing = 1.45f
                setUseLigatures(true)
            }

        fun samePreferences(
            expected: FontPreferences,
            actual: FontPreferences,
        ) {
            kotlin.test.assertEquals(
                dev.ayuislands.font.FontData
                    .captureExact(expected),
                dev.ayuislands.font.FontData
                    .captureExact(actual),
            )
        }
    }
}
