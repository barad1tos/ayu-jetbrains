package dev.ayuislands.integration

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.colors.impl.DefaultColorsScheme
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.accent.AccentElementId
import dev.ayuislands.theme.EditorSchemeOverrides
import dev.ayuislands.theme.EditorSchemeOverrides.CheckpointEntry
import dev.ayuislands.theme.EditorSchemeOverrides.OverrideState
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeEntry
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeValue
import dev.ayuislands.theme.EditorSchemeOwner
import dev.ayuislands.theme.SchemeAdmission
import dev.ayuislands.theme.SchemeAdmissionFacts
import dev.ayuislands.theme.SchemeComparison
import dev.ayuislands.theme.SchemeEquality
import dev.ayuislands.theme.SchemeSource
import dev.ayuislands.theme.SchemeTarget
import dev.ayuislands.theme.SchemeWriteBatch
import dev.ayuislands.theme.captureAttributeFacts
import org.junit.Rule
import org.junit.Test
import java.awt.Color
import java.awt.Font
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SchemeOverridePreparationTest {
    @get:Rule val applicationRule = ApplicationRule()

    @Test
    fun `preparation captures disappearing owned keys and requested keys without native effects`() {
        SwingUtilities.invokeAndWait {
            val scheme = nativeScheme()
            val removed = entry("AYU_OVERRIDE_REMOVED")
            val added = entry("AYU_OVERRIDE_ADDED")
            val accepted = own(scheme, removed)
            val before = scheme.directlyDefinedAttributes.toMap()
            val metadata = scheme.metaProperties.toMap()
            val saveState = scheme.schemeState

            val prepared =
                EditorSchemeOverrides.checkpoints.prepareOverrides(
                    target(scheme),
                    EditorSchemeOwner.Syntax,
                    mapOf(added to SchemeValue.AttributesValue(attributes(Color.GREEN))),
                    setOf(added),
                )

            assertEquals(
                setOf(removed, added),
                prepared.batch.records
                    .map { it.entry }
                    .toSet(),
            )
            assertTrue(prepared.relinquished.isEmpty())
            assertEquals(before.keys, scheme.directlyDefinedAttributes.keys)
            before.forEach { (name, value) -> assertSame(value, scheme.directlyDefinedAttributes[name]) }
            assertEquals(metadata, scheme.metaProperties.toMap())
            assertEquals(saveState, scheme.schemeState)
            assertSame(accepted.owner, captureAdmission(scheme, removed).owner)
            assertTrue(prepared.batch.apply())
            assertEquals(Color.RED, scheme.getAttributes(removed.key).foregroundColor)
            assertEquals(Color.GREEN, scheme.getAttributes(added.key).foregroundColor)
            assertNull(captureAdmission(scheme, removed).owner)
            restore(prepared.batch)
            assertSame(accepted.owner, captureAdmission(scheme, removed).owner)
            assertSame(accepted.metadataValue, scheme.metaProperties[removed.metadataKey])
            assertFalse(scheme.directlyDefinedAttributes.containsKey(added.key.externalName))
        }
    }

    @Test
    fun `disappearing owned keys retire their claims while preserving foreign explicit or absent values`() {
        SwingUtilities.invokeAndWait {
            for (absent in listOf(false, true)) {
                val scheme = nativeScheme()
                val entry = entry("AYU_OVERRIDE_REMOVED_FOREIGN_$absent")
                val accepted = own(scheme, entry)
                if (absent) {
                    scheme.setAttributes(entry.key, AbstractColorsScheme.INHERITED_ATTRS_MARKER)
                    scheme.optimizeAttributeMap()
                } else {
                    scheme.setAttributes(entry.key, attributes(Color.GREEN))
                }
                val current = scheme.directlyDefinedAttributes[entry.key.externalName]
                if (absent) assertNull(current) else assertNotNull(current)
                val prepared =
                    EditorSchemeOverrides.checkpoints.prepareOverrides(
                        target(scheme),
                        EditorSchemeOwner.Syntax,
                        emptyMap(),
                        emptySet(),
                    )
                val record = prepared.batch.records.single()

                assertTrue(SchemeAdmission.matchesCell(record.before.cell, record.planned.cell))
                assertNull(record.planned.owner)
                assertFalse(record.planned.hasMetadata)
                assertTrue(prepared.relinquished.isEmpty())
                assertTrue(prepared.batch.apply())
                assertSame(current, scheme.directlyDefinedAttributes[entry.key.externalName])
                assertNull(captureAdmission(scheme, entry).owner)
                restore(prepared.batch)
                assertTrue(prepared.batch.seeds.isEmpty())
                assertTrue(prepared.batch.deletions.isEmpty())
                assertSame(current, scheme.directlyDefinedAttributes[entry.key.externalName])
                assertSame(accepted.owner, captureAdmission(scheme, entry).owner)
                assertSame(accepted.metadataValue, scheme.metaProperties[entry.metadataKey])
            }
        }
    }

    @Test
    fun `managed reapplication keeps the original baseline and rollback retains the accepted claim`() {
        SwingUtilities.invokeAndWait {
            val scheme = nativeScheme()
            val entry = entry("AYU_OVERRIDE_REAPPLY")
            val accepted = own(scheme, entry)
            val before = assertNotNull(scheme.directlyDefinedAttributes[entry.key.externalName])
            val prepared =
                EditorSchemeOverrides.checkpoints.prepareOverrides(
                    target(scheme),
                    EditorSchemeOwner.Syntax,
                    mapOf(entry to SchemeValue.AttributesValue(attributes(Color.YELLOW))),
                    setOf(entry),
                )
            val planned =
                prepared.batch.records
                    .single()
                    .planned
            val claim = assertIs<OverrideState.Owned>(planned.owner)

            assertEquals(
                Color.RED,
                assertNotNull(assertIs<SchemeValue.AttributesValue>(claim.original).value).foregroundColor,
            )
            assertEquals(
                Color.YELLOW,
                assertNotNull(assertIs<SchemeValue.AttributesValue>(claim.lastWritten).value).foregroundColor,
            )
            assertSame(before, scheme.directlyDefinedAttributes[entry.key.externalName])
            assertSame(accepted.owner, captureAdmission(scheme, entry).owner)
            assertTrue(prepared.batch.apply())
            assertEquals(Color.YELLOW, scheme.getAttributes(entry.key).foregroundColor)
            restore(prepared.batch)
            assertSame(before.flyweight, scheme.getAttributes(entry.key).flyweight)
            assertSame(accepted.owner, captureAdmission(scheme, entry).owner)
            assertSame(accepted.metadataValue, scheme.metaProperties[entry.metadataKey])
        }
    }

    @Test
    fun `changed and already relinquished managed entries preserve their current native value`() {
        SwingUtilities.invokeAndWait {
            for (wasRelinquished in listOf(false, true)) {
                val scheme = nativeScheme()
                val entry = entry("AYU_OVERRIDE_ACTIVE_REL_$wasRelinquished")
                val accepted =
                    if (wasRelinquished) {
                        install(
                            scheme,
                            entry,
                            OverrideState.Relinquished(EditorSchemeOwner.Syntax),
                            SchemeValue.AttributesValue(attributes(Color.GREEN)),
                            "R;S",
                        )
                    } else {
                        own(scheme, entry).also { scheme.setAttributes(entry.key, attributes(Color.GREEN)) }
                    }
                val current = assertNotNull(scheme.directlyDefinedAttributes[entry.key.externalName])

                val prepared =
                    EditorSchemeOverrides.checkpoints.prepareOverrides(
                        target(scheme),
                        EditorSchemeOwner.Syntax,
                        mapOf(entry to SchemeValue.AttributesValue(attributes(Color.BLUE))),
                        setOf(entry),
                    )

                assertEquals(setOf(entry), prepared.relinquished)
                assertSame(accepted.owner, captureAdmission(scheme, entry).owner)
                assertTrue(prepared.batch.apply())
                assertSame(current, scheme.directlyDefinedAttributes[entry.key.externalName])
                val observed = captureAdmission(scheme, entry)
                assertEquals(EditorSchemeOwner.Syntax, assertIs<OverrideState.Relinquished>(observed.owner).owner)
                if (wasRelinquished) {
                    assertSame(accepted.owner, observed.owner)
                    assertSame(accepted.metadataValue, observed.metadataValue)
                }
                restore(prepared.batch)
                assertSame(accepted.owner, captureAdmission(scheme, entry).owner)
                assertSame(current, scheme.directlyDefinedAttributes[entry.key.externalName])
            }
        }
    }

    @Test
    fun `metadata only retirement preserves actual absent and explicit native cells`() {
        SwingUtilities.invokeAndWait {
            for (absent in listOf(false, true)) {
                val scheme = nativeScheme()
                val entry = entry("AYU_OVERRIDE_RETIRED_ABSENCE_$absent")
                val accepted =
                    install(
                        scheme,
                        entry,
                        OverrideState.Relinquished(EditorSchemeOwner.Syntax),
                        SchemeValue.AttributesValue(attributes(Color.GREEN)),
                        "R;S",
                    )
                if (absent) {
                    scheme.setAttributes(entry.key, AbstractColorsScheme.INHERITED_ATTRS_MARKER)
                    scheme.optimizeAttributeMap()
                }
                val current = scheme.directlyDefinedAttributes[entry.key.externalName]
                if (absent) assertNull(current) else assertNotNull(current)
                val prepared =
                    EditorSchemeOverrides.checkpoints.prepareOverrides(
                        target(scheme),
                        EditorSchemeOwner.Syntax,
                        emptyMap(),
                        emptySet(),
                    )
                val record = prepared.batch.records.single()

                assertTrue(SchemeAdmission.matchesCell(record.before.cell, record.planned.cell))
                assertNull(record.planned.owner)
                assertFalse(record.planned.hasMetadata)
                assertSame(current, scheme.directlyDefinedAttributes[entry.key.externalName])
                assertTrue(prepared.batch.apply())
                assertSame(current, scheme.directlyDefinedAttributes[entry.key.externalName])
                assertFalse(scheme.metaProperties.containsKey(entry.metadataKey))
                restore(prepared.batch)
                assertTrue(prepared.batch.seeds.isEmpty())
                assertTrue(prepared.batch.deletions.isEmpty())
                assertSame(current, scheme.directlyDefinedAttributes[entry.key.externalName])
                assertSame(accepted.owner, captureAdmission(scheme, entry).owner)
                assertSame(accepted.metadataValue, scheme.metaProperties[entry.metadataKey])
            }
        }
    }

    @Test
    fun `ordinary writes preserve another owner's exact native claim and raw string reference`() {
        SwingUtilities.invokeAndWait {
            val scheme = nativeScheme()
            val entry = entry("AYU_OVERRIDE_ORDINARY_FOREIGN_OWNER")
            val accepted = own(scheme, entry, EditorSchemeOwner.Vcs)
            val prepared =
                EditorSchemeOverrides.checkpoints.prepareOverrides(
                    target(scheme),
                    EditorSchemeOwner.Syntax,
                    mapOf(entry to SchemeValue.AttributesValue(attributes(Color.PINK))),
                    emptySet(),
                )
            val planned =
                prepared.batch.records
                    .single()
                    .planned

            assertSame(accepted.owner, planned.owner)
            assertSame(accepted.metadataValue, planned.metadataValue)
            assertTrue(prepared.batch.apply())
            assertEquals(Color.PINK, scheme.getAttributes(entry.key).foregroundColor)
            assertSame(accepted.owner, captureAdmission(scheme, entry).owner)
            assertSame(accepted.metadataValue, scheme.metaProperties[entry.metadataKey])
            restore(prepared.batch)
            assertSame(accepted.owner, captureAdmission(scheme, entry).owner)
            assertSame(accepted.metadataValue, scheme.metaProperties[entry.metadataKey])
            assertEquals(Color.ORANGE, scheme.getAttributes(entry.key).foregroundColor)
        }
    }

    @Test
    fun `managed requests leave unrelated owners untouched with legacy relinquishment reporting`() {
        SwingUtilities.invokeAndWait {
            for (relinquished in listOf(false, true)) {
                val scheme = nativeScheme()
                val entry = entry("AYU_OVERRIDE_MANAGED_OTHER_$relinquished")
                val accepted =
                    if (relinquished) {
                        install(
                            scheme,
                            entry,
                            OverrideState.Relinquished(EditorSchemeOwner.Vcs),
                            SchemeValue.AttributesValue(attributes(Color.GREEN)),
                            "R;V",
                        )
                    } else {
                        own(scheme, entry, EditorSchemeOwner.Vcs)
                    }
                val current = scheme.directlyDefinedAttributes[entry.key.externalName]
                val prepared =
                    EditorSchemeOverrides.checkpoints.prepareOverrides(
                        target(scheme),
                        EditorSchemeOwner.Syntax,
                        mapOf(entry to SchemeValue.AttributesValue(attributes(Color.PINK))),
                        setOf(entry),
                    )

                assertEquals(if (relinquished) setOf(entry) else emptySet(), prepared.relinquished)
                assertTrue(prepared.batch.apply())
                assertSame(current, scheme.directlyDefinedAttributes[entry.key.externalName])
                assertSame(accepted.owner, captureAdmission(scheme, entry).owner)
                assertSame(accepted.metadataValue, scheme.metaProperties[entry.metadataKey])
                restore(prepared.batch)
                assertSame(current, scheme.directlyDefinedAttributes[entry.key.externalName])
            }
        }
    }

    @Test
    fun `ordinary mode retires the prior Syntax claim before applying its computed value`() {
        SwingUtilities.invokeAndWait {
            for (foreign in listOf(false, true)) {
                val scheme = nativeScheme()
                val entry = entry("AYU_OVERRIDE_MANAGED_TO_ORDINARY_$foreign")
                val accepted = own(scheme, entry)
                if (foreign) scheme.setAttributes(entry.key, attributes(Color.GREEN))
                val before = assertNotNull(scheme.directlyDefinedAttributes[entry.key.externalName])
                val prepared =
                    EditorSchemeOverrides.checkpoints.prepareOverrides(
                        target(scheme),
                        EditorSchemeOwner.Syntax,
                        mapOf(entry to SchemeValue.AttributesValue(attributes(Color.PINK))),
                        emptySet(),
                    )
                val planned =
                    prepared.batch.records
                        .single()
                        .planned

                assertNull(planned.owner)
                assertFalse(planned.hasMetadata)
                assertTrue(prepared.relinquished.isEmpty())
                assertTrue(prepared.batch.apply())
                assertEquals(Color.PINK, scheme.getAttributes(entry.key).foregroundColor)
                assertNull(captureAdmission(scheme, entry).owner)
                restore(prepared.batch)
                assertSame(before.flyweight, scheme.getAttributes(entry.key).flyweight)
                assertSame(accepted.owner, captureAdmission(scheme, entry).owner)
                assertSame(accepted.metadataValue, scheme.metaProperties[entry.metadataKey])
            }
        }
    }

    @Test
    fun `empty Syntax requests preserve unrelated persisted and unknown metadata without decoding it`() {
        SwingUtilities.invokeAndWait {
            val otherHeaders = listOf("V", "A", "E:${AccentElementId.CARET_ROW.name}")
            val rawValues =
                otherHeaders.map { "O;$it;C,${Color.RED.rgb};C,${Color.ORANGE.rgb}" } +
                    listOf(
                        "O;V;A,not-base64!;A,not-base64!",
                        "  future-version;UNKNOWN; payload\n",
                        "opaque raw metadata",
                    )
            for ((index, raw) in rawValues.withIndex()) {
                val scheme = nativeScheme()
                val entry = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_OVERRIDE_UNRELATED_PERSISTED_$index"))
                scheme.setColor(entry.key, Color.GREEN)
                scheme.metaProperties[entry.metadataKey] = raw
                val metadata = scheme.metaProperties.toMap()
                val saveState = scheme.schemeState
                assertNull(captureAdmission(scheme, entry).owner)

                val prepared =
                    EditorSchemeOverrides.checkpoints.prepareOverrides(
                        target(scheme),
                        EditorSchemeOwner.Syntax,
                        emptyMap(),
                        emptySet(),
                    )

                assertTrue(prepared.batch.records.isEmpty())
                assertTrue(prepared.relinquished.isEmpty())
                assertTrue(prepared.batch.apply())
                restore(prepared.batch)
                assertSame(Color.GREEN, scheme.directlyDefinedColors[entry.key])
                assertSame(raw, scheme.metaProperties[entry.metadataKey])
                assertEquals(metadata, scheme.metaProperties.toMap())
                assertEquals(saveState, scheme.schemeState)
                assertNull(captureAdmission(scheme, entry).owner)
            }
        }
    }

    @Test
    fun `persisted only and unknown managed claims refuse preparation without hydrating ownership`() {
        SwingUtilities.invokeAndWait {
            val encoded = "O;S;C,${Color.RED.rgb};C,${Color.ORANGE.rgb}"
            for (raw in listOf(encoded, "  future-version;S; payload\n")) {
                for (requested in listOf(false, true)) {
                    val scheme = nativeScheme()
                    val entry = SchemeEntry.ColorEntry(ColorKey.createColorKey("AYU_OVERRIDE_PERSISTED_ONLY"))
                    scheme.setColor(entry.key, Color.ORANGE)
                    scheme.metaProperties[entry.metadataKey] = raw
                    val saveState = scheme.schemeState
                    assertNull(captureAdmission(scheme, entry).owner)

                    assertFailsWith<IllegalStateException> {
                        EditorSchemeOverrides.checkpoints.prepareOverrides(
                            target(scheme),
                            EditorSchemeOwner.Syntax,
                            if (requested) mapOf(entry to SchemeValue.ColorValue(Color.PINK)) else emptyMap(),
                            if (requested) setOf(entry) else emptySet(),
                        )
                    }

                    assertSame(Color.ORANGE, scheme.directlyDefinedColors[entry.key])
                    assertSame(raw, scheme.metaProperties[entry.metadataKey])
                    assertEquals(saveState, scheme.schemeState)
                    assertNull(captureAdmission(scheme, entry).owner)
                }
            }
        }
    }

    @Test
    fun `ordinary unclaimed fields retain unknown raw metadata without parsing or normalizing it`() {
        SwingUtilities.invokeAndWait {
            val scheme = nativeScheme()
            val entry = entry("AYU_OVERRIDE_ORDINARY_RAW")
            val raw = String("  future-version; owner\nЖ\t".toCharArray())
            scheme.setAttributes(entry.key, attributes(Color.BLUE))
            scheme.metaProperties[entry.metadataKey] = raw
            val prepared =
                EditorSchemeOverrides.checkpoints.prepareOverrides(
                    target(scheme),
                    EditorSchemeOwner.Syntax,
                    mapOf(entry to SchemeValue.AttributesValue(attributes(Color.PINK))),
                    emptySet(),
                )
            val record = prepared.batch.records.single()

            assertNull(record.before.owner)
            assertNull(record.planned.owner)
            assertSame(raw, record.before.metadataValue)
            assertSame(raw, record.planned.metadataValue)
            assertTrue(prepared.batch.apply())
            assertSame(raw, scheme.metaProperties[entry.metadataKey])
            assertNull(captureAdmission(scheme, entry).owner)
            restore(prepared.batch)
            assertSame(raw, scheme.metaProperties[entry.metadataKey])
            assertEquals(Color.BLUE, scheme.getAttributes(entry.key).foregroundColor)
        }
    }

    @Test
    fun `unknown native claim comparison refuses instead of adopting or classifying a foreign edit`() {
        SwingUtilities.invokeAndWait {
            val scheme = nativeScheme()
            val entry = entry("AYU_OVERRIDE_UNKNOWN_COMPARISON")
            val original = attributes(Color.RED)
            val written = layeredAttributes()
            scheme.setAttributes(entry.key, original)
            val accepted =
                install(
                    scheme,
                    entry,
                    OverrideState.Owned(
                        EditorSchemeOwner.Syntax,
                        SchemeValue.AttributesValue(original),
                        SchemeValue.AttributesValue(written),
                    ),
                    SchemeValue.AttributesValue(written),
                    "live layered claim",
                )
            val foreign =
                TextAttributes(
                    written.foregroundColor,
                    written.backgroundColor,
                    written.effectColor,
                    written.effectType,
                    written.fontType,
                )
            scheme.setAttributes(entry.key, foreign)
            assertEquals(
                SchemeEquality.UNKNOWN,
                SchemeComparison.attributes(captureAttributeFacts(written), captureAttributeFacts(foreign)),
            )

            assertFailsWith<IllegalStateException> {
                EditorSchemeOverrides.checkpoints.prepareOverrides(
                    target(scheme),
                    EditorSchemeOwner.Syntax,
                    mapOf(entry to SchemeValue.AttributesValue(attributes(Color.PINK))),
                    setOf(entry),
                )
            }

            assertSame(foreign, scheme.directlyDefinedAttributes[entry.key.externalName])
            assertSame(accepted.owner, captureAdmission(scheme, entry).owner)
            assertSame(accepted.metadataValue, scheme.metaProperties[entry.metadataKey])
        }
    }

    private fun nativeScheme(): EditorColorsSchemeImpl =
        EditorColorsSchemeImpl(DefaultColorsScheme()).apply { name = "Override preparation fixture" }

    private fun target(scheme: EditorColorsSchemeImpl): SchemeTarget =
        assertNotNull(SchemeTarget.capture(SchemeSource.Named("fixture")) { scheme })

    private fun entry(name: String): SchemeEntry.AttributesEntry =
        SchemeEntry.AttributesEntry(TextAttributesKey.createTextAttributesKey(name))

    private fun attributes(color: Color): TextAttributes = TextAttributes(color, null, null, null, Font.PLAIN)

    private fun own(
        scheme: EditorColorsSchemeImpl,
        entry: SchemeEntry.AttributesEntry,
        owner: EditorSchemeOwner = EditorSchemeOwner.Syntax,
    ): SchemeAdmissionFacts {
        val original = attributes(Color.RED)
        val written = attributes(Color.ORANGE)
        scheme.setAttributes(entry.key, original)
        return install(
            scheme,
            entry,
            OverrideState.Owned(owner, SchemeValue.AttributesValue(original), SchemeValue.AttributesValue(written)),
            SchemeValue.AttributesValue(written),
            String("  live claim\nЖ\t".toCharArray()),
        )
    }

    private fun install(
        scheme: EditorColorsSchemeImpl,
        entry: SchemeEntry,
        owner: OverrideState,
        value: SchemeValue,
        metadata: String,
    ): SchemeAdmissionFacts {
        val batch =
            EditorSchemeOverrides.checkpoints.prepareBatch(
                target(scheme),
                mapOf(entry to CheckpointEntry(value, owner, metadata)),
            )
        assertTrue(batch.apply())
        batch.accept()
        return batch.records.single().planned
    }

    /** Reads the existing owner's facts without applying its prepared write. */
    private fun captureAdmission(
        scheme: EditorColorsSchemeImpl,
        entry: SchemeEntry,
    ): SchemeAdmissionFacts {
        val value =
            when (entry) {
                is SchemeEntry.AttributesEntry -> SchemeValue.AttributesValue(attributes(Color.PINK))
                is SchemeEntry.ColorEntry -> SchemeValue.ColorValue(Color.PINK)
            }
        return EditorSchemeOverrides.checkpoints
            .prepareBatch(
                target(scheme),
                mapOf(entry to CheckpointEntry(value, null, scheme.metaProperties.getProperty(entry.metadataKey))),
            ).records
            .single()
            .before
    }

    private fun restore(batch: SchemeWriteBatch) {
        batch.prepareRestore()
        if (!batch.restore()) {
            batch.prepareRestore()
            assertTrue(batch.restore())
        }
    }

    private fun layeredAttributes(): TextAttributes =
        assertNotNull(
            TextAttributes.merge(
                TextAttributes(Color(0x44223355, true), null, Color.RED, EffectType.BOXED, Font.BOLD),
                TextAttributes(null, null, Color.GREEN, EffectType.LINE_UNDERSCORE, Font.PLAIN),
            ),
        )
}
