package dev.ayuislands.studio

import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.DecodeResult
import dev.ayuislands.studio.persistence.JournalDecodeResult
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.NativeValue
import dev.ayuislands.studio.persistence.PendingJournal
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.PreparedToken
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.RecoveryPurpose
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.persistence.StudioJournalCodec
import dev.ayuislands.studio.persistence.StudioStateCodec
import dev.ayuislands.studio.persistence.StudioXml
import dev.ayuislands.studio.persistence.StudioXmlNode
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioJournalCodecTest {
    @Test
    fun `portable receipts preserve absence and explicit null independently`() {
        val journal = journal()
        val receipt =
            PortableReceipt(
                "receipt",
                EffectOwner.FONT,
                NativeFieldIdentity("scheme", "field", "owner"),
                NativeValue.Absent,
                NativeValue.Present(RawValue.Null),
                RestoreStatus.PENDING,
            )
        val values =
            listOf(
                receipt,
                receipt.copy(
                    id = "next",
                    before = receipt.lastWritten,
                    lastWritten = NativeValue.Present(RawValue.Text("user value")),
                ),
            )
        val input = journal.copy(receipts = StudioList(values))
        assertEquals(
            input,
            assertIs<JournalDecodeResult.Ready>(StudioJournalCodec.decode(StudioJournalCodec.encode(input))).journal,
        )
        assertTrue(StudioJournalCodec.isValid(StudioJournalCodec.encode(input)))
    }

    @Test
    fun `compressed and legacy journals retain the complete ordered raw receipt`() {
        val raw =
            RawValue.OrderedList(
                StudioList(
                    listOf(
                        RawValue.Null,
                        RawValue.Text($$"\u0000  literal $USER_HOME$\n"),
                        RawValue.BooleanValue(false),
                        RawValue.Integer(Int.MIN_VALUE),
                        RawValue.LongValue(Long.MAX_VALUE),
                        RawValue.FloatBits(0x7fc01234),
                        RawValue.FloatBits(Int.MIN_VALUE),
                        RawValue.Bytes(StudioBytes(byteArrayOf(0, 1, -1))),
                        RawValue.OrderedSet(StudioList(listOf("z", "future", "a"))),
                        RawValue.OrderedMap(
                            StudioList(
                                listOf(
                                    RawValue.Entry("z", RawValue.Text("")),
                                    RawValue.Entry("a", RawValue.Null),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        val receipt =
            PortableReceipt(
                "raw-receipt",
                EffectOwner.FONT,
                NativeFieldIdentity("scheme", "field", "owner"),
                NativeValue.Present(raw),
                NativeValue.Present(RawValue.Text("replacement")),
                RestoreStatus.OWNED,
            )
        val input = journal().copy(receipts = StudioList(listOf(receipt)))
        val encoded = StudioJournalCodec.encode(input)
        val envelope = StudioXml.parse(encoded)
        val content = envelope.child("payload")
        assertEquals("2", envelope.attribute("schema"))
        assertEquals("gzip-base64", content.attribute("encoding"))
        assertEquals(StudioXml.hashBytes(StudioXml.decodeBytes(content.text)), content.attribute("sha256"))
        assertEquals(input, assertIs<JournalDecodeResult.Ready>(StudioJournalCodec.decode(encoded)).journal)
        assertTrue(StudioJournalCodec.isValid(encoded))

        val legacy =
            StudioXml.write(
                StudioXml.envelope("journal", envelope.attributes + ("schema" to "1"), readPayload(envelope)),
            )
        assertEquals(input, assertIs<JournalDecodeResult.Ready>(StudioJournalCodec.decode(legacy)).journal)
        assertTrue(StudioJournalCodec.isValid(legacy))
    }

    @Test
    fun `damaged gzip is rejected even when its envelope checksum matches`() {
        val envelope = StudioXml.parse(StudioJournalCodec.encode(journal()))
        val content = envelope.child("payload")
        val compressed = StudioXml.decodeBytes(content.text)
        val damaged =
            listOf(
                compressed.copyOf().apply { this[0] = 0 },
                compressed.copyOf(compressed.size - 1),
                compressed.copyOf().apply { this[size - 8] = (this[size - 8].toInt() xor 1).toByte() },
            )
        for (bytes in damaged) {
            val invalid =
                StudioXml.write(
                    envelope.copy(
                        children =
                            listOf(
                                content.copy(
                                    attributes = content.attributes + ("sha256" to StudioXml.hashBytes(bytes)),
                                    text = StudioXml.encodeBytes(bytes),
                                ),
                            ),
                    ),
                )
            assertIs<JournalDecodeResult.Invalid>(StudioJournalCodec.decode(invalid))
            assertFalse(StudioJournalCodec.isValid(invalid))
        }
    }

    @Test
    fun `journal cannot replace immutable migration evidence from its accepted base`() {
        val original = journal()
        val header = original.candidate.header
        val changed =
            original.copy(
                candidate =
                    original.candidate.copy(
                        header = header.copy(migration = header.migration.copy(id = "foreign")),
                    ),
            )
        assertIs<JournalDecodeResult.Invalid>(StudioJournalCodec.decode(StudioJournalCodec.encode(changed)))
        assertFalse(StudioJournalCodec.isValid(StudioJournalCodec.encode(changed)))
    }

    @Test
    fun `duplicate receipt and reordered owner plan are invalid`() {
        val original = journal()
        val receipt =
            PortableReceipt(
                "receipt",
                EffectOwner.FONT,
                NativeFieldIdentity("scheme", "field", "owner"),
                NativeValue.Absent,
                NativeValue.Present(RawValue.Integer(14)),
                RestoreStatus.OWNED,
            )
        val duplicate = original.copy(receipts = StudioList(listOf(receipt, receipt)))
        assertIs<JournalDecodeResult.Invalid>(StudioJournalCodec.decode(StudioJournalCodec.encode(duplicate)))
        assertFalse(StudioJournalCodec.isValid(StudioJournalCodec.encode(duplicate)))
        val reordered =
            original.copy(
                recovery = RecoveryPlan(StudioTarget.Global, StudioList(listOf(EffectOwner.FONT, EffectOwner.ACCENT))),
            )
        assertIs<JournalDecodeResult.Invalid>(StudioJournalCodec.decode(StudioJournalCodec.encode(reordered)))
        assertFalse(StudioJournalCodec.isValid(StudioJournalCodec.encode(reordered)))
    }

    @Test
    fun `future schema and corrupt envelope never yield recoverable commands`() {
        val future = "<journal schema=\"3\"/>".encodeToByteArray()
        assertIs<JournalDecodeResult.FutureSchema>(StudioJournalCodec.decode(future))
        assertFalse(StudioJournalCodec.isValid(future))
        val encoded = StudioJournalCodec.encode(journal()).decodeToString()
        val corrupt = encoded.replace("sha256=\"", "sha256=\"0")
        assertIs<JournalDecodeResult.Invalid>(StudioJournalCodec.decode(corrupt.encodeToByteArray()))
        assertFalse(StudioJournalCodec.isValid(corrupt.encodeToByteArray()))
        val external = "<!DOCTYPE journal SYSTEM 'file:///unused'><journal/>".encodeToByteArray()
        assertIs<JournalDecodeResult.Invalid>(
            StudioJournalCodec.decode(external),
        )
        assertFalse(StudioJournalCodec.isValid(external))
    }

    @Test
    fun `journal rejects malformed receipt containers and records`() {
        val envelope = StudioXml.parse(StudioJournalCodec.encode(journal()))
        val prepared = readPayload(envelope)
        val receipts = prepared.child("receipts")
        val invalidContainers =
            listOf(
                receipts.copy(attributes = mapOf("unexpected" to "value")),
                receipts.copy(text = "unexpected"),
                receipts.copy(children = listOf(StudioXmlNode("unexpected"))),
                receipts.copy(children = listOf(StudioXmlNode("receipt"))),
            )
        val invalidPayloads =
            invalidContainers.map { container ->
                prepared.copy(children = prepared.children.map { if (it === receipts) container else it })
            } + prepared.copy(children = prepared.children + receipts)
        for (invalid in invalidPayloads) {
            val encoded =
                StudioXml.write(StudioXml.envelope("journal", envelope.attributes + ("schema" to "1"), invalid))
            assertIs<JournalDecodeResult.Invalid>(StudioJournalCodec.decode(encoded))
            assertFalse(StudioJournalCodec.isValid(encoded))
        }
    }

    @Test
    fun `metadata journal round-trips its distinct owner without native enrollment`() {
        val input =
            journal().copy(
                recovery =
                    RecoveryPlan(
                        StudioTarget.Global,
                        StudioList(emptyList()),
                        purpose = RecoveryPurpose.Metadata,
                    ),
            )

        val encoded = StudioJournalCodec.encode(input)

        assertEquals(input, assertIs<JournalDecodeResult.Ready>(StudioJournalCodec.decode(encoded)).journal)
        assertTrue(StudioJournalCodec.isValid(encoded))
        val nativeOwned = input.copy(recovery = input.recovery.copy(owners = StudioList(listOf(EffectOwner.FONT))))
        assertIs<JournalDecodeResult.Invalid>(StudioJournalCodec.decode(StudioJournalCodec.encode(nativeOwned)))
    }

    @Test
    fun `metadata marker cannot coexist with an automatic runtime owner`() {
        val input =
            journal().copy(
                recovery =
                    RecoveryPlan(
                        StudioTarget.Global,
                        StudioList(emptyList()),
                        purpose = RecoveryPurpose.Metadata,
                    ),
            )
        val envelope = StudioXml.parse(StudioJournalCodec.encode(input))
        val payload = readPayload(envelope)
        val recovery = payload.child("recovery")
        val mixed =
            recovery.copy(
                children =
                    recovery.children +
                        StudioXmlNode(
                            "runtime-purpose",
                            mapOf("kind" to "schedule", "operation" to "00000000-0000-0000-0000-000000000001"),
                        ),
            )
        val invalid = payload.copy(children = payload.children.map { if (it === recovery) mixed else it })
        val encoded = StudioXml.write(StudioXml.envelope("journal", envelope.attributes + ("schema" to "1"), invalid))

        assertIs<JournalDecodeResult.Invalid>(StudioJournalCodec.decode(encoded))
        assertFalse(StudioJournalCodec.isValid(encoded))
    }

    private fun readPayload(envelope: StudioXmlNode): StudioXmlNode =
        GZIPInputStream(StudioXml.decodeBytes(envelope.child("payload").text).inputStream()).use { stream ->
            StudioXml.parse(stream.readBytes())
        }

    private fun journal(): PendingJournal {
        val base =
            assertIs<MigrationResult.Ready>(
                StudioLegacyMigration.plan(StudioFixtures.load("unknown-and-ordered")),
            ).document
        val bytes = StudioStateCodec.encode(base)
        val digest = assertIs<DecodeResult.Ready>(StudioStateCodec.decode(bytes)).digest
        val candidate = base.copy(header = base.header.copy(revision = 1, transactionId = "next"))
        return PendingJournal(
            PreparedToken("next", 0, digest, 1),
            StudioBytes(bytes),
            candidate,
            RecoveryPlan(StudioTarget.Global, StudioList(listOf(EffectOwner.FONT))),
            StudioList(emptyList()),
        )
    }
}
