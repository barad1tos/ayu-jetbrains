package dev.ayuislands.integration

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.impl.DefaultColorsScheme
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.font.FontIdentityRules
import dev.ayuislands.font.FontSchemeCensus
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.SchemeReceiptIdentity
import dev.ayuislands.studio.session.StudioNativeTokens
import dev.ayuislands.studio.session.StudioRestoreContinuation
import dev.ayuislands.studio.session.StudioSchemeRecovery
import dev.ayuislands.studio.session.StudioSchemeWrite
import dev.ayuislands.syntax.SyntaxIntensityApplicator
import dev.ayuislands.syntax.SyntaxPreset
import dev.ayuislands.theme.EditorSchemeOverrides
import dev.ayuislands.theme.EditorSchemeOverrides.CheckpointEntry
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeEntry
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeValue
import dev.ayuislands.theme.EditorSchemeOwner
import dev.ayuislands.theme.SchemeSource
import dev.ayuislands.theme.SchemeTarget
import org.jdom.Element
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.time.Instant
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StudioSchemeRecoveryTest {
    @get:Rule val applicationRule = ApplicationRule()

    private val origin = StudioProcess(41, Instant.EPOCH)
    private val restarted = StudioProcess(42, Instant.EPOCH.plusSeconds(1))
    private val identity = SchemeReceiptIdentity(EffectOwner.SYNTAX, ID, "recovery-attempt", origin)

    @After
    fun clearNativeOwnership() {
        SwingUtilities.invokeAndWait { EditorSchemeOverrides.reset() }
    }

    @Test
    fun `Custom materialization restores true local absence after native reload`() {
        SwingUtilities.invokeAndWait {
            val fixture = customFixture("ABSENT", absent = true)
            assertFalse(fixture.scheme.directlyDefinedAttributes.containsKey(fixture.attributes.key.externalName))
            fixture.write.apply()
            val loaded = reload(fixture.scheme)
            assertNotNull(loaded.directlyDefinedAttributes[fixture.attributes.key.externalName])
            val recovery = recover(fixture.write.receipts)

            settle(recovery, loaded)

            assertFalse(loaded.directlyDefinedAttributes.containsKey(fixture.attributes.key.externalName))
            assertFalse(reload(loaded).directlyDefinedAttributes.containsKey(fixture.attributes.key.externalName))
            assertEquals(null, loaded.metaProperties[fixture.attributes.metadataKey])
            assertEquals(Color.BLUE.rgb, loaded.directlyDefinedColors[fixture.color.key]?.rgb)
            assertTrue(recovery.receipts.all { it.status == RestoreStatus.RESTORED })
        }
    }

    @Test
    fun `value-only foreign edit releases our metadata and restores independent owned fields`() {
        SwingUtilities.invokeAndWait {
            val fixture = customFixture("FOREIGN_VALUE", absent = false)
            fixture.write.apply()
            val loaded = reload(fixture.scheme)
            val ownedMetadata = loaded.metaProperties[fixture.attributes.metadataKey]
            val foreign = TextAttributes(Color.PINK, null, Color.GREEN, EffectType.WAVE_UNDERSCORE, Font.BOLD)
            loaded.setAttributes(fixture.attributes.key, foreign)
            assertEquals(ownedMetadata, loaded.metaProperties[fixture.attributes.metadataKey])
            val recovery = recover(fixture.write.receipts)

            settle(recovery, loaded)

            assertSame(foreign, loaded.directlyDefinedAttributes[fixture.attributes.key.externalName])
            assertEquals(fixture.releasedMetadata, loaded.metaProperties[fixture.attributes.metadataKey])
            assertEquals(Color.BLUE.rgb, loaded.directlyDefinedColors[fixture.color.key]?.rgb)
            val fields = recovery.receipts.filter { it.identity.field.startsWith("editor/attributes/") }
            assertTrue(
                fields.filter { it.identity.field.endsWith("/VALUE") }.all { it.status == RestoreStatus.FOREIGN },
            )
            assertTrue(
                fields.filter { it.identity.field.endsWith("/METADATA") }.all {
                    it.status ==
                        RestoreStatus.RESTORED
                },
            )
        }
    }

    @Test
    fun `foreign edit after acknowledged rollback preparation releases metadata across restart`() {
        SwingUtilities.invokeAndWait {
            val fixture = customFixture("FOREIGN_ACK", absent = false)
            fixture.write.apply()
            val loaded = reload(fixture.scheme)
            val recovery = recover(fixture.write.receipts)
            recovery.prepareRestore(census(loaded), emptyList())
            val acknowledged = recovery.receipts.toList()
            val foreign = TextAttributes(Color.PINK, null, Color.GREEN, EffectType.WAVE_UNDERSCORE, Font.BOLD)
            loaded.setAttributes(fixture.attributes.key, foreign)

            assertEquals(StudioRestoreContinuation.NONE, recovery.restore(census(loaded), emptyList()))

            assertSame(foreign, loaded.directlyDefinedAttributes[fixture.attributes.key.externalName])
            assertEquals(fixture.releasedMetadata, loaded.metaProperties[fixture.attributes.metadataKey])
            assertEquals(Color.BLUE.rgb, loaded.directlyDefinedColors[fixture.color.key]?.rgb)
            val reloaded = reload(loaded)
            val retainedAttributes = reloaded.directlyDefinedAttributes[fixture.attributes.key.externalName]
            val resumed =
                StudioSchemeRecovery.recover(
                    identity,
                    acknowledged,
                    StudioProcess(43, Instant.EPOCH.plusSeconds(2)),
                )

            settle(resumed, reloaded)

            assertSame(retainedAttributes, reloaded.directlyDefinedAttributes[fixture.attributes.key.externalName])
            assertEquals(fixture.releasedMetadata, reloaded.metaProperties[fixture.attributes.metadataKey])
            assertTrue(
                resumed.receipts.filter { it.identity.field == "editor/attributes/SWIFT.BRACKETS/VALUE" }.all {
                    it.status == RestoreStatus.FOREIGN
                },
            )
            assertTrue(
                resumed.receipts.all { it.status == RestoreStatus.FOREIGN || it.status == RestoreStatus.RESTORED },
            )
        }
    }

    @Test
    fun `restart after acknowledged rollback seed settles metadata without rematerializing the absent value`() {
        SwingUtilities.invokeAndWait {
            val fixture = customFixture("ROLLBACK_SEED", absent = true)
            fixture.write.apply()
            fixture.write.prepareRestore(emptyList())
            val acknowledged = fixture.write.receipts.toList()
            assertEquals(StudioRestoreContinuation.REQUIRED, fixture.write.restore(emptyList()))
            val loaded = reload(fixture.scheme)
            assertFalse(loaded.directlyDefinedAttributes.containsKey(fixture.attributes.key.externalName))
            assertNotNull(loaded.metaProperties[fixture.attributes.metadataKey])
            val recovery = recover(acknowledged)

            settle(recovery, loaded)

            assertFalse(loaded.directlyDefinedAttributes.containsKey(fixture.attributes.key.externalName))
            assertEquals(null, loaded.metaProperties[fixture.attributes.metadataKey])
            assertEquals(Color.BLUE.rgb, loaded.directlyDefinedColors[fixture.color.key]?.rgb)
            assertTrue(recovery.receipts.all { it.status == RestoreStatus.RESTORED })
        }
    }

    @Test
    fun `native loaded explicit cells restore their persisted originals and unrelated fields survive`() {
        SwingUtilities.invokeAndWait {
            val fixture = fixture("EXPLICIT")
            val expected = reload(fixture.scheme)
            fixture.write.apply()
            val loaded = reload(fixture.scheme)
            val recovery = recover(fixture.write.receipts)
            val appliedAttributes = loaded.directlyDefinedAttributes[fixture.attributes.key.externalName]

            recovery.prepareRestore(census(loaded), emptyList())

            assertEquals(Color.ORANGE.rgb, loaded.directlyDefinedColors[fixture.color.key]?.rgb)
            assertSame(appliedAttributes, loaded.directlyDefinedAttributes[fixture.attributes.key.externalName])
            assertEquals(StudioRestoreContinuation.NONE, recovery.restore(census(loaded), emptyList()))
            assertCells(expected, reload(loaded), fixture)
            assertTrue(recovery.receipts.all { it.status == RestoreStatus.RESTORED })
        }
    }

    @Test
    fun `recovery waits for a unique UUID and never transfers a bound native target`() {
        SwingUtilities.invokeAndWait {
            val fixture = fixture("IDENTITY")
            fixture.write.apply()
            val loaded = reload(fixture.scheme)
            val duplicate = reload(fixture.scheme)
            val recovery = recover(fixture.write.receipts)

            recovery.prepareRestore(census(), emptyList())
            assertEquals(StudioRestoreContinuation.NONE, recovery.restore(census(), emptyList()))
            assertTrue(recovery.receipts.any { it.status == RestoreStatus.UNAVAILABLE })
            recovery.prepareRestore(census(loaded, duplicate), emptyList())
            assertEquals(StudioRestoreContinuation.NONE, recovery.restore(census(loaded, duplicate), emptyList()))
            assertTrue(recovery.receipts.any { it.status == RestoreStatus.UNAVAILABLE })
            recovery.prepareRestore(census(loaded), emptyList())
            assertEquals(StudioRestoreContinuation.NONE, recovery.restore(census(duplicate), emptyList()))
            assertTrue(recovery.receipts.any { it.status == RestoreStatus.UNAVAILABLE })
            assertEquals(Color.ORANGE.rgb, duplicate.directlyDefinedColors[fixture.color.key]?.rgb)
            recovery.prepareRestore(census(loaded), emptyList())
            assertEquals(StudioRestoreContinuation.NONE, recovery.restore(census(loaded), emptyList()))
            assertEquals(Color.BLUE.rgb, loaded.directlyDefinedColors[fixture.color.key]?.rgb)
            assertEquals(Color.ORANGE.rgb, duplicate.directlyDefinedColors[fixture.color.key]?.rgb)
        }
    }

    @Test
    fun `foreign native value and metadata survive while an independent owned entry restores`() {
        SwingUtilities.invokeAndWait {
            val fixture = fixture("FOREIGN")
            val expected = reload(fixture.scheme)
            fixture.write.apply()
            val loaded = reload(fixture.scheme)
            loaded.setColor(fixture.color.key, Color.PINK)
            loaded.metaProperties[fixture.color.metadataKey] = "foreign owner"
            val recovery = recover(fixture.write.receipts)

            recovery.prepareRestore(census(loaded), emptyList())
            assertEquals(StudioRestoreContinuation.NONE, recovery.restore(census(loaded), emptyList()))

            assertSame(Color.PINK, loaded.directlyDefinedColors[fixture.color.key])
            assertEquals("foreign owner", loaded.metaProperties[fixture.color.metadataKey])
            assertEquals(
                expected.directlyDefinedAttributes[fixture.attributes.key.externalName],
                loaded.directlyDefinedAttributes[fixture.attributes.key.externalName],
            )
            assertEquals(
                expected.metaProperties[fixture.attributes.metadataKey],
                loaded.metaProperties[fixture.attributes.metadataKey],
            )
            assertTrue(recovery.receipts.any { it.status == RestoreStatus.FOREIGN })
            assertEquals("untouched", loaded.metaProperties["recovery.unrelated"])
        }
    }

    @Test
    fun `lost final rollback acknowledgement can resume after native reload without replaying restoration`() {
        SwingUtilities.invokeAndWait {
            val fixture = fixture("ACKNOWLEDGEMENT")
            val expected = reload(fixture.scheme)
            fixture.write.apply()
            val loaded = reload(fixture.scheme)
            val recovery = recover(fixture.write.receipts)
            recovery.prepareRestore(census(loaded), emptyList())
            val acknowledged = recovery.receipts.toList()
            assertEquals(StudioRestoreContinuation.NONE, recovery.restore(census(loaded), emptyList()))
            val reloaded = reload(loaded)
            val retainedAttributes = reloaded.directlyDefinedAttributes[fixture.attributes.key.externalName]
            val resumed =
                StudioSchemeRecovery.recover(
                    identity,
                    acknowledged,
                    StudioProcess(43, Instant.EPOCH.plusSeconds(2)),
                )

            resumed.prepareRestore(census(reloaded), emptyList())
            assertEquals(StudioRestoreContinuation.NONE, resumed.restore(census(reloaded), emptyList()))

            assertSame(retainedAttributes, reloaded.directlyDefinedAttributes[fixture.attributes.key.externalName])
            assertCells(expected, reloaded, fixture)
            assertTrue(resumed.receipts.all { it.status == RestoreStatus.RESTORED })
        }
    }

    @Test
    fun `committed cleanup neither rolls back native cells nor requires the removed target`() {
        SwingUtilities.invokeAndWait {
            val fixture = fixture("COMMITTED")
            fixture.write.apply()
            val loaded = reload(fixture.scheme)
            val retainedAttributes = loaded.directlyDefinedAttributes[fixture.attributes.key.externalName]
            val metadata = loaded.metaProperties.toMap()
            val recovery = recover(fixture.write.receipts)
            val receipts = recovery.receipts

            recovery.accept()
            recovery.accept()

            assertSame(retainedAttributes, loaded.directlyDefinedAttributes[fixture.attributes.key.externalName])
            assertEquals(Color.ORANGE.rgb, loaded.directlyDefinedColors[fixture.color.key]?.rgb)
            assertEquals(metadata, loaded.metaProperties.toMap())
            assertEquals(receipts, recovery.receipts)
            assertFailsWith<IllegalStateException> { recovery.prepareRestore(census(), emptyList()) }
            assertFailsWith<IllegalStateException> { recovery.restore(census(), emptyList()) }
        }
    }

    private fun customFixture(
        suffix: String,
        absent: Boolean,
    ): RecoveryFixture {
        val color = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_RECOVERY_${suffix}_COLOR"))
        val attributes = SchemeEntry.AttributesEntry(TextAttributesKey.find("SWIFT.BRACKETS"))
        val fallback = TextAttributesKey.find("DEFAULT_BRACKETS")
        val computed =
            SyntaxIntensityApplicator.compute(
                SyntaxIntensityApplicator.Request(
                    preset = SyntaxPreset.CUSTOM,
                    variantName = "Mirage",
                    editorBg = Color(0x1F2430),
                    baseline =
                        mapOf(
                            attributes.key to TextAttributes(),
                            fallback to TextAttributes(Color(0xCCCAC2), null, null, null, Font.PLAIN),
                        ),
                    overlay = emptyMap(),
                    customOverrides = mapOf("Swift" to mapOf("OPERATOR" to 70)),
                    fallbacks = mapOf(attributes.key.externalName to fallback.externalName),
                ),
            )
        val materialized = assertNotNull(computed[attributes.key])
        assertNotNull(materialized.foregroundColor)
        val scheme =
            EditorColorsSchemeImpl(nativeParent()).apply {
                name = "Custom recovery $suffix"
                metaProperties[FontIdentityRules.METADATA_KEY] = ID
                setColor(color.key, Color.BLUE)
                if (!absent) setAttributes(attributes.key, TextAttributes(Color.BLUE, null, null, null, Font.PLAIN))
            }
        val target = assertNotNull(SchemeTarget.capture(SchemeSource.Named(scheme.name)) { scheme })
        val batch =
            EditorSchemeOverrides.checkpoints
                .prepareOverrides(
                    target,
                    EditorSchemeOwner.Syntax,
                    mapOf(
                        color to SchemeValue.ColorValue(Color.ORANGE),
                        attributes to SchemeValue.AttributesValue(materialized),
                    ),
                    setOf(attributes),
                ).batch
        val released =
            assertNotNull(
                batch.records
                    .single { it.entry == attributes }
                    .released
                    ?.metadataValue as? String,
            )
        return RecoveryFixture(
            scheme,
            color,
            attributes,
            StudioSchemeWrite.capture(identity, target, batch, StudioNativeTokens(suffix)),
            released,
        )
    }

    private fun settle(
        recovery: StudioSchemeRecovery,
        scheme: EditorColorsSchemeImpl,
    ) {
        repeat(2) {
            recovery.prepareRestore(census(scheme), emptyList())
            if (recovery.restore(census(scheme), emptyList()) == StudioRestoreContinuation.NONE) return
        }
        error("Native recovery exceeded the coordinator continuation bound")
    }

    private fun fixture(suffix: String): RecoveryFixture {
        val color = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_RECOVERY_${suffix}_COLOR"))
        val attributes =
            SchemeEntry.AttributesEntry(
                TextAttributesKey.createTextAttributesKey("AYU_RECOVERY_${suffix}_ATTRIBUTES"),
            )
        val scheme =
            EditorColorsSchemeImpl(nativeParent()).apply {
                name = "Recovery $suffix"
                metaProperties[FontIdentityRules.METADATA_KEY] = ID
                metaProperties[color.metadataKey] = "original color owner"
                metaProperties[attributes.metadataKey] = "original attributes owner Ж"
                metaProperties["recovery.unrelated"] = "untouched"
                setColor(color.key, Color.BLUE)
                setAttributes(
                    attributes.key,
                    TextAttributes(Color(0x44223355, true), null, Color.GREEN, EffectType.WAVE_UNDERSCORE, Font.BOLD),
                )
            }
        val target = assertNotNull(SchemeTarget.capture(SchemeSource.Named(scheme.name)) { scheme })
        val batch =
            EditorSchemeOverrides.checkpoints.prepareBatch(
                target,
                mapOf(
                    color to CheckpointEntry(SchemeValue.ColorValue(Color.ORANGE), null, "written color owner"),
                    attributes to
                        CheckpointEntry(
                            SchemeValue.AttributesValue(TextAttributes(Color.RED, null, null, null, Font.ITALIC)),
                            null,
                            "written attributes owner",
                        ),
                ),
            )
        return RecoveryFixture(
            scheme,
            color,
            attributes,
            StudioSchemeWrite.capture(identity, target, batch, StudioNativeTokens(suffix)),
        )
    }

    private fun recover(receipts: List<PortableReceipt>): StudioSchemeRecovery =
        StudioSchemeRecovery.recover(identity, receipts, restarted)

    private fun nativeParent(): DefaultColorsScheme =
        assertIs<DefaultColorsScheme>(
            EditorColorsManager.getInstance().getScheme(EditorColorsScheme.getDefaultSchemeName()),
        )

    private fun reload(source: EditorColorsSchemeImpl): EditorColorsSchemeImpl {
        val detached =
            (source.clone() as EditorColorsSchemeImpl).apply {
                metaProperties.clear()
                metaProperties.putAll(source.metaProperties)
            }
        val element = Element("scheme")
        detached.writeExternal(element)
        return EditorColorsSchemeImpl(source.parentScheme).apply {
            readExternal(JDOMUtil.load(JDOMUtil.writeElement(element)))
        }
    }

    private fun census(vararg schemes: EditorColorsScheme): FontSchemeCensus = FontSchemeCensus(arrayOf(*schemes))

    private fun assertCells(
        expected: EditorColorsSchemeImpl,
        actual: EditorColorsSchemeImpl,
        fixture: RecoveryFixture,
    ) {
        assertEquals(
            expected.directlyDefinedColors[fixture.color.key]?.rgb,
            actual.directlyDefinedColors[fixture.color.key]?.rgb,
        )
        assertEquals(
            expected.directlyDefinedAttributes[fixture.attributes.key.externalName],
            actual.directlyDefinedAttributes[fixture.attributes.key.externalName],
        )
        assertEquals(expected.metaProperties.toMap(), actual.metaProperties.toMap())
    }

    private class RecoveryFixture(
        val scheme: EditorColorsSchemeImpl,
        val color: SchemeEntry.ColorEntry,
        val attributes: SchemeEntry.AttributesEntry,
        val write: StudioSchemeWrite,
        val releasedMetadata: String? = null,
    )

    private companion object {
        const val ID = "11800832-b0dc-44ab-a989-d8d333013fbe"
    }
}
