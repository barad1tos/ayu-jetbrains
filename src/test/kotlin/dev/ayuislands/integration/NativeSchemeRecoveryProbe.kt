package dev.ayuislands.integration

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.markup.TextAttributes
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.font.FontIdentityRules
import dev.ayuislands.font.FontSchemeCensus
import dev.ayuislands.studio.StudioFixtures
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.openStudioFiles
import dev.ayuislands.studio.session.SchemeReceiptIdentity
import dev.ayuislands.studio.session.StudioNativeEvidence
import dev.ayuislands.studio.session.StudioNativeTokens
import dev.ayuislands.studio.session.StudioRestoreContinuation
import dev.ayuislands.studio.session.StudioSchemeRecovery
import dev.ayuislands.studio.session.StudioSchemeWrite
import dev.ayuislands.studio.session.observeStudioProcess
import dev.ayuislands.theme.AyuSchemeResource
import dev.ayuislands.theme.EditorSchemeOverrides
import dev.ayuislands.theme.EditorSchemeOverrides.CheckpointEntry
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeEntry
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeValue
import dev.ayuislands.theme.SchemePersistenceCapture
import dev.ayuislands.theme.SchemePersistenceResult
import dev.ayuislands.theme.SchemeSource
import dev.ayuislands.theme.SchemeStoredCell
import dev.ayuislands.theme.SchemeTarget
import java.awt.Color
import java.awt.Font
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Shares only native scheme files and the acknowledged journal between disposable JVMs. */
internal object NativeSchemeRecoveryProbe {
    private const val ID = "11800832-b0dc-44ab-a989-d8d333013fbe"
    private const val OWNER = "native-recovery"
    private val color = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_NATIVE_RECOVERY_COLOR"))
    private val keyword = SchemeEntry.AttributesEntry(DefaultLanguageHighlighterColors.KEYWORD)

    fun execute(
        mode: String,
        root: Path,
    ) {
        val result = Properties()
        result.setProperty("process", observeStudioProcess().getOrThrow().toString())
        when (mode) {
            "SAVE" -> prepare(root, result)
            "READ" -> restore(root, result, expectApplied = true)
            "CHECK" -> restore(root, result, expectApplied = false)
            else -> error("Unknown recovery probe mode")
        }
        Files.newOutputStream(root.resolve("RECOVERY_$mode.properties")).use {
            result.store(it, "Native scheme recovery")
        }
    }

    private fun prepare(
        root: Path,
        result: Properties,
    ) {
        lateinit var write: StudioSchemeWrite
        SwingUtilities.invokeAndWait {
            val scheme = editable()
            scheme.metaProperties[FontIdentityRules.METADATA_KEY] = ID
            scheme.metaProperties[color.metadataKey] = "original owner"
            scheme.metaProperties["recovery.unrelated"] = "untouched"
            scheme.setColor(color.key, Color.BLUE)
            assertEquals(assertNotNull(scheme.original).getAttributes(keyword.key), scheme.getAttributes(keyword.key))
            val target = assertNotNull(SchemeTarget.capture(SchemeSource.Named(AyuVariant.MIRAGE.editorSchemeResource)))
            val resource =
                assertNotNull(
                    AyuSchemeResource.capture(AyuVariant.MIRAGE, assertIs<AbstractColorsScheme>(scheme.original)),
                )
            val batch =
                EditorSchemeOverrides.checkpoints.prepareBatch(
                    target,
                    mapOf(
                        color to CheckpointEntry(SchemeValue.ColorValue(Color.ORANGE), null, "written owner"),
                        keyword to
                            CheckpointEntry(
                                SchemeValue.AttributesValue(TextAttributes(Color.MAGENTA, null, null, null, Font.BOLD)),
                                null,
                                "written keyword owner",
                            ),
                    ),
                )
            val identity = SchemeReceiptIdentity(EffectOwner.SYNTAX, ID, OWNER, observeStudioProcess().getOrThrow())
            assertEquals(
                SchemeStoredCell.Omitted,
                assertIs<SchemePersistenceResult.Ready>(
                    SchemePersistenceCapture.capture(target, batch.records.single { it.entry == keyword }, resource),
                ).before,
            )
            write = StudioSchemeWrite.capture(identity, target, batch, StudioNativeTokens(OWNER), resource)
            observe(result, scheme)
        }
        StudioAppearanceStore(openStudioFiles(root.resolve("recovery-store"))).use { store ->
            assertEquals(StoreLoad.Empty, store.load())
            val candidate =
                assertIs<MigrationResult.Ready>(
                    StudioLegacyMigration.plan(StudioFixtures.capture(fresh = true)),
                ).document
            assertEquals(JournalResult.Acknowledged, store.preserveSource(candidate.header.migration))
            val plan = RecoveryPlan(StudioTarget.Global, StudioList(listOf(EffectOwner.SYNTAX)))
            val token = assertIs<PrepareResult.Prepared>(store.prepare(-1, candidate, plan)).token
            assertEquals(JournalResult.Acknowledged, store.recordRecovery(token, write.receipts))
            SwingUtilities.invokeAndWait { write.apply() }
            assertEquals(JournalResult.Acknowledged, store.recordRecovery(token, write.receipts))
        }
        NativeSchemeProbe.saveSchemes()
    }

    private fun restore(
        root: Path,
        result: Properties,
        expectApplied: Boolean,
    ) {
        StudioAppearanceStore(openStudioFiles(root.resolve("recovery-store"))).use { store ->
            val journal = assertIs<StoreLoad.Recovering>(store.load()).journal
            val origin = StudioNativeEvidence.readProcess(journal.receipts.first().before)
            val current = observeStudioProcess().getOrThrow()
            assertNotEquals(origin, current)
            val identity = SchemeReceiptIdentity(EffectOwner.SYNTAX, ID, OWNER, origin)
            val recovery = StudioSchemeRecovery.recover(identity, journal.receipts, current)
            lateinit var retainedAttributes: TextAttributes
            SwingUtilities.invokeAndWait {
                val scheme = editable()
                if (expectApplied) {
                    assertEquals(Color.ORANGE.rgb, scheme.directlyDefinedColors[color.key]?.rgb)
                    assertEquals(
                        Color.MAGENTA.rgb,
                        scheme.directlyDefinedAttributes[keyword.key.externalName]?.foregroundColor?.rgb,
                    )
                } else {
                    assertRestored(scheme)
                }
                retainedAttributes = assertNotNull(scheme.directlyDefinedAttributes[keyword.key.externalName])
                recovery.prepareRestore(FontSchemeCensus(arrayOf(scheme)), emptyList())
            }
            assertEquals(JournalResult.Acknowledged, store.recordRecovery(journal.token, recovery.receipts))
            SwingUtilities.invokeAndWait {
                val scheme = editable()
                assertEquals(
                    StudioRestoreContinuation.NONE,
                    recovery.restore(FontSchemeCensus(arrayOf(scheme)), emptyList()),
                )
                assertRestored(scheme)
                if (!expectApplied) {
                    assertSame(retainedAttributes, scheme.directlyDefinedAttributes[keyword.key.externalName])
                }
                observe(result, scheme)
                assertTrue(recovery.receipts.all { it.status == RestoreStatus.RESTORED })
            }
            if (!expectApplied) {
                assertEquals(JournalResult.Acknowledged, store.recordRecovery(journal.token, recovery.receipts))
                assertEquals(JournalResult.Acknowledged, store.finishRollback(journal.token))
                assertEquals(StoreLoad.Empty, store.load())
            }
            // READ deliberately loses the final acknowledgement after the native rollback.
        }
        NativeSchemeProbe.saveSchemes()
    }

    private fun assertRestored(scheme: AbstractColorsScheme) {
        assertEquals(Color.BLUE.rgb, scheme.directlyDefinedColors[color.key]?.rgb)
        assertEquals(
            assertNotNull(scheme.original).getAttributes(keyword.key),
            scheme.directlyDefinedAttributes[keyword.key.externalName],
        )
        assertEquals("original owner", scheme.metaProperties[color.metadataKey])
        assertEquals(null, scheme.metaProperties[keyword.metadataKey])
        assertEquals("untouched", scheme.metaProperties["recovery.unrelated"])
    }

    private fun editable(): AbstractColorsScheme =
        assertIs<AbstractColorsScheme>(
            EditorColorsManager.getInstance().getScheme(AyuVariant.MIRAGE.editorSchemeResource),
        )

    private fun observe(
        result: Properties,
        scheme: AbstractColorsScheme,
    ) {
        result.setProperty("name", scheme.name)
        result.setProperty("color", scheme.directlyDefinedColors[color.key]?.rgb.toString())
        result.setProperty(
            "keyword",
            scheme.directlyDefinedAttributes[keyword.key.externalName]
                ?.foregroundColor
                ?.rgb
                .toString(),
        )
        result.setProperty(
            "canonical.keyword",
            assertNotNull(scheme.original)
                .getAttributes(keyword.key)
                ?.foregroundColor
                ?.rgb
                .toString(),
        )
        result.setProperty(
            "keyword.present",
            scheme.directlyDefinedAttributes.containsKey(keyword.key.externalName).toString(),
        )
    }
}
