package dev.ayuislands.integration

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.FontPreferences
import com.intellij.openapi.editor.colors.ModifiableFontPreferences
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.colors.impl.FontPreferencesImpl
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.font.FontData
import dev.ayuislands.font.FontFamily
import dev.ayuislands.font.FontIdentityCheckpoint
import dev.ayuislands.font.FontIdentityPlan
import dev.ayuislands.font.FontIdentityRules
import dev.ayuislands.font.FontSchemeCensus
import dev.ayuislands.font.FontSnapshot
import dev.ayuislands.font.FontSurface
import dev.ayuislands.font.FontWriteCheckpoint
import dev.ayuislands.studio.FaultingStudioFiles
import dev.ayuislands.studio.IoBoundary
import dev.ayuislands.studio.IoEvent
import dev.ayuislands.studio.IoOperation
import dev.ayuislands.studio.StudioFixtures
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.PreparedToken
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.openStudioFiles
import dev.ayuislands.studio.session.FontReceiptIdentity
import dev.ayuislands.studio.session.StudioFontReceipts
import dev.ayuislands.studio.session.StudioSchemeIdentityReceipt
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioFontJournalTest {
    @get:Rule val applicationRule = ApplicationRule()

    @get:Rule val temporary = TemporaryFolder()

    private val identity =
        FontReceiptIdentity("11800832-b0dc-44ab-a989-d8d333013fbe", FontSurface.EDITOR, "font-operation")

    @Test
    fun `saved scheme and original journal recover adopted identity together with native fonts`() {
        SwingUtilities.invokeAndWait {
            val root = temporary.newFolder().toPath()
            val scheme = JournalFontScheme()
            scheme.name = "Journal"
            scheme.fontPreferences = FontPreferencesImpl().apply { register("Dialog", 17f) }
            val before = FontData.captureExact(scheme.fontPreferences)
            val target =
                before.copy(
                    effectiveFamilies = listOf("Serif"),
                    families = listOf(FontFamily("Serif", 19f)),
                    templateSize = 19f,
                )
            val font = FontWriteCheckpoint(scheme, FontSurface.EDITOR, FontSnapshot.Explicit(target))
            val adoption = FontIdentityCheckpoint(scheme, FontIdentityPlan.Adopt(identity.resource))
            StudioAppearanceStore(openStudioFiles(root)).use { store ->
                val token = initialize(store)
                val identityReceipt =
                    StudioSchemeIdentityReceipt.encode(
                        EffectOwner.FONT,
                        identity.ownerToken,
                        adoption.record,
                    )
                assertEquals(
                    JournalResult.Acknowledged,
                    store.recordRecovery(
                        token,
                        listOf(identityReceipt) + receipts(font),
                    ),
                )
                adoption.apply(FontSchemeCensus(arrayOf(scheme)), emptySet())
                font.apply()
            }
            val saved = JDOMUtil.writeElement(scheme.writeScheme())
            val reloaded = JournalFontScheme().apply { readExternal(JDOMUtil.load(saved)) }
            recoverAdoption(root, reloaded)
            assertEquals(before, FontData.captureExact(reloaded.fontPreferences))
            assertNull(reloaded.metaProperties[FontIdentityRules.METADATA_KEY])
        }
    }

    @Test
    fun `lost forward acknowledgement restores native values from reopened journal and preserves foreign spacing`() {
        SwingUtilities.invokeAndWait {
            for (boundary in IoBoundary.entries) {
                val root = temporary.newFolder(boundary.name).toPath()
                val scheme = JournalFontScheme()
                val checkpoint = prepare(scheme)
                val files = FaultingStudioFiles(openStudioFiles(root))
                StudioAppearanceStore(files).use { store ->
                    val token = initialize(store)
                    acknowledge(store, token, checkpoint)
                    checkpoint.apply()
                    files.arm(IoEvent(IoOperation.REPLACE, "pending.xml", boundary))
                    assertIs<JournalResult.Rejected>(store.recordRecovery(token, receipts(checkpoint)))
                    assertEquals(0, files.pendingFailures)
                }
                (scheme.fontPreferences as ModifiableFontPreferences).lineSpacing = 1.9f
                finish(root, scheme)
                assertEquals(original().copy(lineSpacing = 1.9f), FontData.captureExact(scheme.fontPreferences))
            }
        }
    }

    @Test
    fun `failed restoration acknowledgement cannot authorize a setter and keeps original evidence on reopen`() {
        SwingUtilities.invokeAndWait {
            for (boundary in IoBoundary.entries) {
                val root = temporary.newFolder(boundary.name).toPath()
                val scheme = JournalFontScheme()
                val checkpoint = prepare(scheme)
                val files = FaultingStudioFiles(openStudioFiles(root))
                StudioAppearanceStore(files).use { store ->
                    val token = initialize(store)
                    acknowledge(store, token, checkpoint)
                    checkpoint.apply()
                    acknowledge(store, token, checkpoint)
                    val writes = scheme.writes
                    checkpoint.prepareRestore()
                    assertTrue(checkpoint.record.origins.isNotEmpty())
                    files.arm(IoEvent(IoOperation.REPLACE, "pending.xml", boundary))
                    assertIs<JournalResult.Rejected>(store.recordRecovery(token, receipts(checkpoint)))
                    assertEquals(writes, scheme.writes)
                    assertEquals(requested(), FontData.captureExact(scheme.fontPreferences))
                }
                finish(root, scheme)
                assertEquals(original(), FontData.captureExact(scheme.fontPreferences))
            }
        }
    }

    @Test
    fun `lost final acknowledgement settles after reopen without repeating native setters`() {
        SwingUtilities.invokeAndWait {
            for (boundary in IoBoundary.entries) {
                val root = temporary.newFolder(boundary.name).toPath()
                val scheme = JournalFontScheme()
                val checkpoint = prepare(scheme)
                val files = FaultingStudioFiles(openStudioFiles(root))
                StudioAppearanceStore(files).use { store ->
                    val token = initialize(store)
                    acknowledge(store, token, checkpoint)
                    checkpoint.apply()
                    acknowledge(store, token, checkpoint)
                    checkpoint.prepareRestore()
                    acknowledge(store, token, checkpoint)
                    assertTrue(checkpoint.restore())
                    files.arm(IoEvent(IoOperation.REPLACE, "pending.xml", boundary))
                    assertIs<JournalResult.Rejected>(store.recordRecovery(token, receipts(checkpoint)))
                }
                (scheme.fontPreferences as ModifiableFontPreferences).lineSpacing = 1.9f
                val writes = scheme.writes
                finish(root, scheme)
                assertEquals(writes, scheme.writes)
                assertEquals(original().copy(lineSpacing = 1.9f), FontData.captureExact(scheme.fontPreferences))
            }
        }
    }

    private fun prepare(scheme: JournalFontScheme): FontWriteCheckpoint {
        scheme.fontPreferences = original().toPreferences()
        return FontWriteCheckpoint(scheme, FontSurface.EDITOR, FontSnapshot.Explicit(requested()))
    }

    private fun recoverAdoption(
        root: Path,
        scheme: JournalFontScheme,
    ) {
        StudioAppearanceStore(openStudioFiles(root)).use { store ->
            val journal = assertIs<StoreLoad.Recovering>(store.load()).journal
            val adoption =
                FontIdentityCheckpoint.recover(
                    FontSchemeCensus(arrayOf(scheme)),
                    StudioSchemeIdentityReceipt.decode(EffectOwner.FONT, identity.ownerToken, journal.receipts.first()),
                )
            val font =
                FontWriteCheckpoint.recover(
                    scheme,
                    FontSurface.EDITOR,
                    StudioFontReceipts.decode(identity, journal.receipts.drop(1)),
                )

            fun acknowledgeBoth() {
                val metadata =
                    StudioSchemeIdentityReceipt.encode(
                        EffectOwner.FONT,
                        identity.ownerToken,
                        adoption.record,
                    )
                assertEquals(
                    JournalResult.Acknowledged,
                    store.recordRecovery(
                        journal.token,
                        listOf(metadata) + receipts(font),
                    ),
                )
            }
            font.prepareRestore()
            acknowledgeBoth()
            assertTrue(font.restore())
            acknowledgeBoth()
            assertTrue(adoption.restore(FontSchemeCensus(arrayOf(scheme)), dependentsSettled = true))
            acknowledgeBoth()
            assertEquals(JournalResult.Acknowledged, store.finishRollback(journal.token))
            assertEquals(StoreLoad.Empty, store.load())
        }
    }

    private fun finish(
        root: Path,
        scheme: JournalFontScheme,
    ) {
        StudioAppearanceStore(openStudioFiles(root)).use { reopened ->
            val journal = assertIs<StoreLoad.Recovering>(reopened.load()).journal
            val record = StudioFontReceipts.decode(identity, journal.receipts)
            assertEquals(FontSnapshot.Explicit(original()), record.before)
            val recovered = FontWriteCheckpoint.recover(scheme, FontSurface.EDITOR, record)
            recovered.prepareRestore()
            acknowledge(reopened, journal.token, recovered)
            assertTrue(recovered.restore())
            acknowledge(reopened, journal.token, recovered)
            assertEquals(JournalResult.Acknowledged, reopened.finishRollback(journal.token))
            assertEquals(StoreLoad.Empty, reopened.load())
        }
    }

    private fun initialize(store: StudioAppearanceStore): PreparedToken {
        val candidate =
            assertIs<MigrationResult.Ready>(
                StudioLegacyMigration.plan(StudioFixtures.load("unknown-and-ordered")),
            ).document
        assertEquals(StoreLoad.Empty, store.load())
        assertEquals(JournalResult.Acknowledged, store.preserveSource(candidate.header.migration))
        val recovery = RecoveryPlan(StudioTarget.Global, StudioList(listOf(EffectOwner.FONT)))
        return assertIs<PrepareResult.Prepared>(store.prepare(-1, candidate, recovery)).token
    }

    private fun acknowledge(
        store: StudioAppearanceStore,
        token: PreparedToken,
        checkpoint: FontWriteCheckpoint,
    ) {
        assertEquals(JournalResult.Acknowledged, store.recordRecovery(token, receipts(checkpoint)))
    }

    private fun receipts(checkpoint: FontWriteCheckpoint) = StudioFontReceipts.encode(identity, checkpoint.record)

    private fun original(): FontData =
        FontData(
            listOf("Monospaced", "Dialog"),
            listOf(FontFamily("Monospaced", 13.5f), FontFamily("Dialog", null)),
            14.25f,
            1.3f,
            false,
            null,
            "",
        )

    private fun requested(): FontData =
        original().copy(
            effectiveFamilies = listOf("Serif"),
            families = listOf(FontFamily("Serif", 16.5f)),
            templateSize = 16.5f,
            lineSpacing = 1.1f,
            ligatures = true,
            regularSubFamily = "Regular",
        )
}

private class JournalFontScheme : EditorColorsSchemeImpl(EditorColorsManager.getInstance().globalScheme) {
    var writes = 0

    override fun setFontPreferences(preferences: FontPreferences) {
        writes++
        super.setFontPreferences(preferences)
    }
}
