package dev.ayuislands.studio

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.NativeValue
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.SchemeReceiptIdentity
import dev.ayuislands.studio.session.StudioNativeEvidence
import dev.ayuislands.studio.session.StudioNativeTokens
import dev.ayuislands.studio.session.StudioSchemeCellCodec
import dev.ayuislands.studio.session.StudioSchemeReceipts
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeEntry
import dev.ayuislands.theme.SchemeAdmissionFacts
import dev.ayuislands.theme.SchemeCellFacts
import dev.ayuislands.theme.SchemeColorFacts
import dev.ayuislands.theme.SchemeComponent
import dev.ayuislands.theme.SchemeEntryRecord
import dev.ayuislands.theme.SchemePersistenceContext
import dev.ayuislands.theme.SchemePersistenceKey
import dev.ayuislands.theme.SchemePersistenceLink
import dev.ayuislands.theme.SchemePersistenceResult
import dev.ayuislands.theme.SchemeSource
import dev.ayuislands.theme.SchemeStoredCell
import dev.ayuislands.theme.SchemeTarget
import dev.ayuislands.theme.SchemeWritePhase
import dev.ayuislands.theme.SchemeWriteProgress
import java.awt.Color
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class StudioSchemeReceiptsTest {
    private val process = StudioProcess(41, Instant.EPOCH)
    private val identity = SchemeReceiptIdentity(EffectOwner.SYNTAX, "shared-scheme-id", "attempt", process)

    @Test
    fun `original receipts keep value ownership and raw metadata as distinct native fields`() {
        val fixture = fixture()
        val receipts = StudioSchemeReceipts.encode(identity, fixture.record, fixture.persistence, fixture.tokens)

        assertEquals(3, receipts.size)
        assertEquals(
            SchemeComponent.entries.map {
                "editor/color/AYU_RECEIPT_COLOR/${it.name}"
            },
            receipts.map { it.identity.field },
        )
        assertEquals(setOf(identity.resource), receipts.map { it.identity.resource }.toSet())
        assertEquals(setOf(EffectOwner.SYNTAX), receipts.map { it.owner }.toSet())
        assertEquals(setOf(RestoreStatus.PENDING), receipts.map { it.status }.toSet())
        receipts.forEach {
            assertEquals(process, StudioNativeEvidence.readProcess(it.before))
            assertEquals(process, StudioNativeEvidence.readProcess(it.lastWritten))
        }
        val value = payload(receipts[0].before)
        assertEquals(SchemeStoredCell.Color(Color.BLUE.rgb), StudioSchemeCellCodec.decode(value.getValue("persisted")))
        val owner = payload(receipts[1].before)
        assertEquals(
            RawValue.Text(fixture.tokens.token(assertNotNull(fixture.record.before.owner))),
            owner.getValue("state"),
        )
        val metadata = StudioNativeEvidence.fields(payload(receipts[2].before).getValue("state"))
        assertEquals(RawValue.BooleanValue(true), metadata.getValue("present"))
        assertEquals(RawValue.Text("  raw owner\nЖ\t"), metadata.getValue("raw"))
        assertEquals(
            RawValue.Text(fixture.tokens.token(fixture.record.before.metadataTable)),
            metadata.getValue("table"),
        )
    }

    @Test
    fun `equal native colors retain distinct identities and all initially prepared destinations`() {
        val fixture = fixture()
        val receipt = StudioSchemeReceipts.encode(identity, fixture.record, fixture.persistence, fixture.tokens)[0]
        val before = payload(receipt.before)
        val original = StudioNativeEvidence.fields(before.getValue("state"))
        val restored = StudioNativeEvidence.fields(before.getValue("restored"))
        val released = StudioNativeEvidence.fields(before.getValue("released"))

        assertNotEquals(original.getValue("reference"), restored.getValue("reference"))
        assertEquals(restored, released)
        assertEquals(
            RawValue.Text(
                fixture.tokens.token(
                    assertNotNull(assertIs<SchemeCellFacts.Color>(fixture.record.before.cell).value).identity,
                ),
            ),
            original.getValue("reference"),
        )
        val ownership = StudioSchemeReceipts.encode(identity, fixture.record, fixture.persistence, fixture.tokens)[1]
        assertNotEquals(payload(ownership.before).getValue("restored"), payload(ownership.before).getValue("released"))
    }

    @Test
    fun `native component phases refresh status without rewriting any original payload`() {
        val fixture = fixture()
        val originals = StudioSchemeReceipts.encode(identity, fixture.record, fixture.persistence, fixture.tokens)
        val phases =
            mapOf(
                SchemeComponent.VALUE to SchemeWritePhase.APPLIED,
                SchemeComponent.OWNERSHIP to SchemeWritePhase.FOREIGN,
                SchemeComponent.METADATA to SchemeWritePhase.WRITING,
            )
        val progress =
            phases.mapValues { (_, phase) ->
                SchemeWriteProgress(phase, fixture.record.before, fixture.record.restored)
            }

        val refreshed = StudioSchemeReceipts.refresh(originals, progress)

        assertEquals(
            listOf(RestoreStatus.OWNED, RestoreStatus.FOREIGN, RestoreStatus.PENDING),
            refreshed.map { it.status },
        )
        for ((original, updated) in originals.zip(refreshed)) {
            assertEquals(original, updated.copy(status = original.status))
        }
        val restoring =
            SchemeComponent.entries.associateWith {
                SchemeWriteProgress(SchemeWritePhase.RESTORING, fixture.record.planned, fixture.record.restored)
            }
        val retry = StudioSchemeReceipts.refresh(refreshed, restoring)
        assertEquals(
            listOf(RestoreStatus.PENDING, RestoreStatus.FOREIGN, RestoreStatus.PENDING),
            retry.map { it.status },
        )
        assertSame(refreshed[1], retry[1])
    }

    @Test
    fun `restoration evidence appends actual phase expected and destination without replacing original facts`() {
        val fixture = fixture()
        val originals = StudioSchemeReceipts.encode(identity, fixture.record, fixture.persistence, fixture.tokens)
        val original = originals[0]
        val progress = SchemeWriteProgress(SchemeWritePhase.APPLIED, fixture.record.before, fixture.record.restored)

        val encoder = StudioSchemeReceipts.Restoration(identity.process, fixture.tokens)
        val restoration = encoder.encodeRestore(original, 0, progress)

        assertEquals("${original.id}/restore/0", restoration.id)
        assertEquals(original.identity, restoration.identity)
        assertEquals(RestoreStatus.PENDING, restoration.status)
        val before = payload(restoration.before)
        assertEquals(RawValue.Text(original.id), before.getValue("source"))
        assertEquals(RawValue.Text("restore"), before.getValue("role"))
        assertEquals(RawValue.Text(SchemeWritePhase.APPLIED.name), before.getValue("phase"))
        assertEquals(payload(original.before).getValue("state"), before.getValue("state"))
        assertNotEquals(payload(original.lastWritten).getValue("state"), before.getValue("state"))
        assertEquals(payload(original.before).getValue("restored"), payload(restoration.lastWritten).getValue("state"))
        assertEquals(
            RawValue.Text("unqualified"),
            StudioNativeEvidence.fields(before.getValue("persisted")).getValue("kind"),
        )
        assertEquals(
            originals,
            StudioSchemeReceipts.encode(identity, fixture.record, fixture.persistence, fixture.tokens),
        )
        assertEquals(restoration, encoder.encodeRestore(original, 0, progress))
        assertFailsWith<IllegalArgumentException> {
            encoder.encodeRestore(original, -1, progress)
        }
    }

    @Test
    fun `omitted persisted cells remain omission and never become loaded local absence`() {
        val fixture = fixture()
        val omitted =
            SchemePersistenceResult.Ready(
                fixture.record,
                SchemeStoredCell.Omitted,
                fixture.persistence.plannedWithCapturedBase,
                fixture.persistence.dependencies,
            )
        val original = StudioSchemeReceipts.encode(identity, fixture.record, omitted, fixture.tokens)[0]
        val before = payload(original.before)

        assertEquals(SchemeStoredCell.Omitted, StudioSchemeCellCodec.decode(before.getValue("persisted")))
        assertEquals(RawValue.Text("color"), StudioNativeEvidence.fields(before.getValue("state")).getValue("kind"))
        assertFalse(original.before is NativeValue.Absent)
    }

    @Test
    fun `shared native field identities stay the same across editor surface owners`() {
        val fixture = fixture()
        val syntax = StudioSchemeReceipts.encode(identity, fixture.record, fixture.persistence, fixture.tokens)
        val accent =
            StudioSchemeReceipts.encode(
                identity.copy(owner = EffectOwner.ACCENT),
                fixture.record,
                fixture.persistence,
                fixture.tokens,
            )
        val vcs =
            StudioSchemeReceipts.encode(
                identity.copy(owner = EffectOwner.VCS),
                fixture.record,
                fixture.persistence,
                fixture.tokens,
            )

        assertEquals(syntax.map { it.identity }, accent.map { it.identity })
        assertEquals(syntax.map { it.identity }, vcs.map { it.identity })
        assertNotEquals(syntax.map { it.id }, accent.map { it.id })
        assertNotEquals(syntax.map { it.id }, vcs.map { it.id })
        assertEquals(setOf(EffectOwner.ACCENT), accent.map { it.owner }.toSet())
        assertEquals(setOf(EffectOwner.VCS), vcs.map { it.owner }.toSet())
    }

    @Test
    fun `a persistence capture from another native record cannot encode this attempt`() {
        val fixture = fixture()
        val other = fixture()

        assertFailsWith<IllegalArgumentException> {
            StudioSchemeReceipts.encode(identity, fixture.record, other.persistence, fixture.tokens)
        }
    }

    private fun fixture(): ReceiptFixture {
        val scheme = EditorColorsSchemeImpl(null).apply { name = "Receipt fixture" }
        val target = assertNotNull(SchemeTarget.capture(SchemeSource.Named("fixture")) { scheme })
        val key = ColorKey.createColorKey("AYU_RECEIPT_COLOR")
        val entry = SchemeEntry.ColorEntry(key)
        val table = Any()
        val owner = Any()
        val before = admission(Color.BLUE, owner, table, "  raw owner\nЖ\t")
        val planned = admission(Color.RED, Any(), table, "planned metadata")
        val restored = admission(Color(Color.BLUE.rgb), owner, table, assertIs<String>(before.metadataValue))
        val released = SchemeAdmissionFacts(restored.cell, Any(), table, true, "released metadata", null)
        val record = SchemeEntryRecord(entry, before, planned, restored, released)
        val context =
            SchemePersistenceContext(
                target,
                null,
                null,
                null,
                SchemeCellFacts.Color(null),
                SchemePersistenceKey(entry, null, null),
                listOf(SchemePersistenceLink(scheme, null, before.cell, key)),
            )
        val persistence =
            SchemePersistenceResult.Ready(
                record,
                SchemeStoredCell.Color(Color.BLUE.rgb),
                SchemeStoredCell.Color(Color.RED.rgb),
                context,
            )
        return ReceiptFixture(record, persistence, StudioNativeTokens("receipt-test"))
    }

    /** Owner and table values are opaque native identities, never values to serialize or compare. */
    private fun admission(
        color: Color,
        owner: Any,
        table: Any,
        metadata: String,
    ): SchemeAdmissionFacts =
        SchemeAdmissionFacts(
            SchemeCellFacts.Color(SchemeColorFacts.Plain(color, color.rgb)),
            owner,
            table,
            true,
            metadata,
            null,
        )

    private fun payload(value: NativeValue): Map<String, RawValue> {
        val envelope = StudioNativeEvidence.fields(assertIs<NativeValue.Present>(value).value)
        return StudioNativeEvidence.fields(envelope.getValue("value"))
    }

    private class ReceiptFixture(
        val record: SchemeEntryRecord,
        val persistence: SchemePersistenceResult.Ready,
        val tokens: StudioNativeTokens,
    )
}
