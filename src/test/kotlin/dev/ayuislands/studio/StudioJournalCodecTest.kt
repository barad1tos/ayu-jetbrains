package dev.ayuislands.studio

import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.LegacyArchive
import dev.ayuislands.studio.model.LegacyFile
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.MigrationSource
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.DecodeIssue
import dev.ayuislands.studio.persistence.DecodeResult
import dev.ayuislands.studio.persistence.JournalDecodeResult
import dev.ayuislands.studio.persistence.LegacyCapture
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.NativeValue
import dev.ayuislands.studio.persistence.PendingJournal
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.PreparedToken
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.RecoveryPurpose
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.persistence.StudioJournalCodec
import dev.ayuislands.studio.persistence.StudioLegacyCapture
import dev.ayuislands.studio.persistence.StudioStateCodec
import dev.ayuislands.studio.persistence.StudioXml
import dev.ayuislands.studio.persistence.StudioXmlNode
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StudioJournalCodecTest {
    @Test
    fun `both journal encodings retain exact base bytes and receipt order`() {
        val original = journal()
        val envelope = StudioXml.parse(StudioJournalCodec.encode(original))
        val compressed = envelope.child("payload")
        val data = StudioXml.decodeBytes(compressed.text)
        assertEquals("2", envelope.attribute("schema"))
        assertEquals("gzip-base64", compressed.attribute("encoding"))
        assertEquals(StudioXml.hashBytes(data), compressed.attribute("sha256"))

        val prepared = readPrepared(envelope)
        for (bytes in listOf(StudioXml.write(envelope), plain(envelope, prepared))) {
            assertTrue(StudioJournalCodec.isValid(bytes))
            val decoded = assertIs<JournalDecodeResult.Ready>(StudioJournalCodec.decode(bytes)).journal
            assertEquals(original, decoded)
            assertContentEquals(checkNotNull(original.base).toByteArray(), checkNotNull(decoded.base).toByteArray())
        }
    }

    @Test
    fun `initial migration has no prior generation but metadata requires one`() {
        val original = journal()
        val header = original.candidate.header.copy(revision = 0, transactionId = "capture")
        val first =
            original.copy(
                token = PreparedToken("capture", -1, null, 0),
                candidate = original.candidate.copy(header = header),
                base = null,
            )
        assertEquals(first, roundTrip(first))
        val metadata = RecoveryPlan(StudioTarget.Global, StudioList(emptyList()), purpose = RecoveryPurpose.Metadata)
        val update = original.copy(recovery = metadata, receipts = StudioList(emptyList()))
        assertEquals(update, roundTrip(update))
        assertInvalid(first.copy(recovery = metadata, receipts = StudioList(emptyList())))
        assertInvalid(update.copy(receipts = original.receipts))
    }

    @Test
    fun `a checksummed journal cannot substitute a different base candidate or migration archive`() {
        val original = journal()
        val header = original.candidate.header
        val changedMigration = header.migration.copy(id = "foreign")
        val changedSource = original.candidate.copy(header = header.copy(migration = changedMigration))
        val invalid =
            listOf(
                original.copy(token = original.token.copy(baseDigest = "foreign")),
                original.copy(base = null),
                original.copy(candidate = original.candidate.copy(header = header.copy(transactionId = "unrelated"))),
                original.copy(candidate = original.candidate.copy(header = header.copy(revision = 2))),
                original.copy(candidate = changedSource),
            )
        for (changed in invalid) {
            assertInvalid(changed)
        }
        val envelope = StudioXml.parse(StudioJournalCodec.encode(original))
        val mismatched = envelope.copy(attributes = envelope.attributes + ("revision" to "2"))
        assertFalse(StudioJournalCodec.isValid(StudioXml.write(mismatched)))
    }

    @Test
    fun `only unique receipts from enrolled owners can become recovery work`() {
        val original = journal()
        assertInvalid(original.copy(receipts = StudioList(original.receipts + original.receipts[0])))
        val foreign = original.receipts[0].copy(owner = EffectOwner.WORKSPACE)
        assertInvalid(original.copy(receipts = StudioList(listOf(foreign))))

        val envelope = StudioXml.parse(StudioJournalCodec.encode(original))
        val prepared = readPrepared(envelope)
        val receipts = prepared.child("receipts")
        val invalid =
            listOf(
                StudioXmlNode("receipts", mapOf("extra" to "field"), receipts.children),
                StudioXmlNode("receipts", children = listOf(StudioXmlNode("unexpected"))),
                StudioXmlNode("receipts", children = receipts.children, text = "unexpected"),
            )
        for (malformed in invalid) {
            val children = prepared.children.map { if (it === receipts) malformed else it }
            val payload = StudioXmlNode("prepared", prepared.attributes, children)
            assertFalse(StudioJournalCodec.isValid(plain(envelope, payload)))
        }
        val duplicate = StudioXmlNode("prepared", prepared.attributes, prepared.children + receipts)
        assertFalse(StudioJournalCodec.isValid(plain(envelope, duplicate)))
    }

    @Test
    fun `future unsupported and damaged transports never expose a candidate`() {
        val future = """<journal schema="3"><payload>not base64</payload></journal>""".encodeToByteArray()
        assertEquals(JournalDecodeResult.FutureSchema(3), StudioJournalCodec.decode(future))
        assertFalse(StudioJournalCodec.isValid(future))

        val external = """<!DOCTYPE journal SYSTEM 'file:///unused'><journal/>""".encodeToByteArray()
        assertIs<JournalDecodeResult.Invalid>(StudioJournalCodec.decode(external))
        val envelope = StudioXml.parse(StudioJournalCodec.encode(journal()))
        val payload = envelope.child("payload")
        val badChecksum = payload.copy(attributes = payload.attributes + ("sha256" to "0".repeat(64)))
        val checksumBytes = StudioXml.write(StudioXmlNode("journal", envelope.attributes, listOf(badChecksum)))
        assertEquals(JournalDecodeResult.Invalid(DecodeIssue.CHECKSUM), StudioJournalCodec.decode(checksumBytes))
        val data = StudioXml.decodeBytes(payload.text)
        val badTrailer = data.copyOf().apply { this[size - 8] = (this[size - 8].toInt() xor 1).toByte() }
        for (damaged in listOf(data.copyOf(data.size - 1), badTrailer)) {
            val attributes = payload.attributes + ("sha256" to StudioXml.hashBytes(damaged))
            val node = StudioXmlNode("payload", attributes, text = StudioXml.encodeBytes(damaged))
            val bytes = StudioXml.write(StudioXmlNode("journal", envelope.attributes, listOf(node)))
            assertIs<JournalDecodeResult.Invalid>(StudioJournalCodec.decode(bytes))
            assertFalse(StudioJournalCodec.isValid(bytes))
        }
    }

    private fun assertInvalid(journal: PendingJournal) {
        val bytes = StudioJournalCodec.encode(journal)
        assertIs<JournalDecodeResult.Invalid>(StudioJournalCodec.decode(bytes))
        assertFalse(StudioJournalCodec.isValid(bytes))
    }

    private fun roundTrip(journal: PendingJournal): PendingJournal =
        assertIs<JournalDecodeResult.Ready>(StudioJournalCodec.decode(StudioJournalCodec.encode(journal))).journal

    private fun readPrepared(envelope: StudioXmlNode): StudioXmlNode {
        val bytes = StudioXml.decodeBytes(envelope.child("payload").text)
        return GZIPInputStream(bytes.inputStream()).use { StudioXml.parse(it.readBytes()) }
    }

    private fun plain(
        envelope: StudioXmlNode,
        prepared: StudioXmlNode,
    ): ByteArray = StudioXml.write(StudioXml.envelope("journal", envelope.attributes + ("schema" to "1"), prepared))

    private fun journal(): PendingJournal {
        val archives = StudioList(LegacyStore.entries.map { LegacyArchive(it, LegacyFile.Absent) })
        val source = MigrationSource("capture", archives)
        val capture = assertIs<LegacyCapture.Ready>(StudioLegacyCapture.read(source, "capture", "/work"))
        val base = assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(capture.snapshot)).document
        val bytes = StudioStateCodec.encode(base)
        val digest = assertIs<DecodeResult.Ready>(StudioStateCodec.decode(bytes)).digest
        val receipt =
            PortableReceipt(
                "font:size",
                EffectOwner.FONT,
                NativeFieldIdentity("Редактор", "size", "scheme-7"),
                NativeValue.Absent,
                NativeValue.Present(RawValue.Null),
                RestoreStatus.PENDING,
            )
        val family = receipt.copy(id = "font:family", lastWritten = NativeValue.Present(RawValue.Text("Ідея")))
        return PendingJournal(
            PreparedToken("next", 0, digest, 1),
            StudioBytes(bytes),
            base.copy(header = base.header.copy(revision = 1, transactionId = "next")),
            RecoveryPlan(StudioTarget.Global, StudioList(listOf(EffectOwner.FONT))),
            StudioList(listOf(receipt, family)),
        )
    }
}
