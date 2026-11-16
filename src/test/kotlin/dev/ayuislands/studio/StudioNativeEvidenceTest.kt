package dev.ayuislands.studio

import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.NativeValue
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.persistence.StudioReceiptXml
import dev.ayuislands.studio.persistence.StudioXml
import dev.ayuislands.studio.session.StudioNativeEvidence
import dev.ayuislands.studio.session.observeStudioProcess
import java.lang.management.ManagementFactory
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class StudioNativeEvidenceTest {
    @Test
    fun `durable receipts keep exact process time and raw native values across XML`() {
        val process = StudioProcess(42, Instant.ofEpochSecond(-10, 123_456_789))
        val fields =
            StudioNativeEvidence.record(
                "previousScheme" to StudioNativeEvidence.reference(null),
                "activeScheme" to StudioNativeEvidence.reference("scheme-instance-42"),
            )
        val receipt =
            PortableReceipt(
                "scheme",
                EffectOwner.SYNTAX,
                NativeFieldIdentity("editor", "scheme", "apply"),
                StudioNativeEvidence.envelope(process, fields),
                StudioNativeEvidence.envelope(StudioProcess(42, null), RawValue.Text("selected")),
                RestoreStatus.PENDING,
            )

        val bytes = StudioXml.write(StudioReceiptXml.write(receipt))
        val decoded = StudioReceiptXml.read(StudioXml.parse(bytes))
        assertEquals(receipt, decoded)
        assertEquals(process, StudioNativeEvidence.readProcess(decoded.before))
        assertEquals(StudioProcess(42, null), StudioNativeEvidence.readProcess(decoded.lastWritten))
        val before = assertIs<NativeValue.Present>(decoded.before)
        assertEquals(
            mapOf("previousScheme" to RawValue.Null, "activeScheme" to RawValue.Text("scheme-instance-42")),
            StudioNativeEvidence.fields(StudioNativeEvidence.fields(before.value).getValue("value")),
        )
        assertContentEquals(bytes, StudioXml.write(StudioReceiptXml.write(decoded)))
    }

    @Test
    fun `receipt records the identity of the running IDE process`() {
        val observed = observeStudioProcess().getOrThrow()
        val value = StudioNativeEvidence.envelope(observed, RawValue.Null)

        assertEquals(ManagementFactory.getRuntimeMXBean().pid, StudioNativeEvidence.readProcess(value).pid)
    }

    @Test
    fun `malformed process evidence cannot establish recovery provenance`() {
        val identity =
            StudioNativeEvidence.record(
                "pid" to RawValue.LongValue(42),
                "start" to
                    StudioNativeEvidence.record(
                        "seconds" to RawValue.LongValue(1),
                        "nanos" to RawValue.Integer(-1),
                    ),
            )
        val invalidTime = envelope(1, identity)
        val knownIdentity = StudioNativeEvidence.record("pid" to RawValue.LongValue(42), "start" to RawValue.Null)
        val wrongPidType = StudioNativeEvidence.record("pid" to RawValue.Integer(42), "start" to RawValue.Null)
        val missingStart = StudioNativeEvidence.record("pid" to RawValue.LongValue(42))
        val duplicateVersion =
            RawValue.OrderedMap(
                StudioList(
                    listOf(
                        RawValue.Entry("version", RawValue.Integer(1)),
                        RawValue.Entry("version", RawValue.Integer(2)),
                    ),
                ),
            )
        val malformedValues =
            listOf(
                NativeValue.Absent,
                NativeValue.Present(invalidTime),
                NativeValue.Present(duplicateVersion),
                NativeValue.Present(envelope(2, knownIdentity)),
                NativeValue.Present(envelope(1, wrongPidType)),
                NativeValue.Present(envelope(1, missingStart)),
            )
        for (malformed in malformedValues) {
            assertFailsWith<IllegalArgumentException> { StudioNativeEvidence.readProcess(malformed) }
        }
    }

    @Test
    fun `blank token cannot stand in for a native reference`() {
        assertFailsWith<IllegalArgumentException> { StudioNativeEvidence.reference(" ") }
    }

    private fun envelope(
        version: Int,
        identity: RawValue,
    ): RawValue =
        StudioNativeEvidence.record(
            "version" to RawValue.Integer(version),
            "process" to identity,
            "value" to RawValue.Null,
        )
}
