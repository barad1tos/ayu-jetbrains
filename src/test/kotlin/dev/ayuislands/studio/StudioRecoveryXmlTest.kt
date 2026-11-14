package dev.ayuislands.studio

import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.rotation.AccentRotationMode
import dev.ayuislands.rotation.RotationCandidate
import dev.ayuislands.rotation.RotationHandoffKind
import dev.ayuislands.rotation.RotationIntent
import dev.ayuislands.rotation.RuntimeOwner
import dev.ayuislands.studio.model.EffectLimitation
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.LimitationReason
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioNativeChange
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.StudioThemeChange
import dev.ayuislands.studio.persistence.NativeFieldIdentity
import dev.ayuislands.studio.persistence.NativeValue
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.RecoveryPurpose
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.persistence.StudioReceiptPolicy
import dev.ayuislands.studio.persistence.StudioReceiptXml
import dev.ayuislands.studio.persistence.StudioRecoveryXml
import dev.ayuislands.studio.persistence.StudioXml
import dev.ayuislands.studio.persistence.StudioXmlNode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioRecoveryXmlTest {
    @Test
    fun `a receipt distinguishes absent native fields from explicit null and preserves ordered values`() {
        val previous =
            RawValue.OrderedMap(
                StudioList(
                    listOf(
                        RawValue.Entry("Шрифт", RawValue.Text("  Ідея 🌿  ")),
                        RawValue.Entry("z", RawValue.FloatBits(0x7fc01234)),
                        RawValue.Entry("a", RawValue.Bytes(StudioBytes(byteArrayOf(0, -1, 2)))),
                    ),
                ),
            )
        val original = receipt()
        val receipts =
            listOf(
                original,
                original.copy(
                    before = NativeValue.Present(RawValue.Null),
                    lastWritten = NativeValue.Present(previous),
                    status = RestoreStatus.OWNED,
                ),
                original.copy(before = NativeValue.Present(previous), lastWritten = NativeValue.Absent),
            )
        for (receipt in receipts) {
            val bytes = StudioXml.write(StudioReceiptXml.write(receipt))
            val decoded = StudioReceiptXml.read(StudioXml.parse(bytes))
            assertEquals(receipt, decoded)
            assertContentEquals(bytes, StudioXml.write(StudioReceiptXml.write(decoded)))
        }
    }

    @Test
    fun `invalid native identity or presence cannot produce a rollback instruction`() {
        val original = receipt()
        for (identity in listOf(NativeFieldIdentity(" ", "font", "token"), NativeFieldIdentity("scheme", "font", ""))) {
            val invalid = StudioReceiptXml.write(original.copy(identity = identity))
            assertFailsWith<IllegalArgumentException> { StudioReceiptXml.read(invalid) }
        }
        val node = StudioReceiptXml.write(original)
        val wrongPresence = StudioXmlNode("before", mapOf("presence" to "missing"))
        val absentWithValue =
            StudioXmlNode("before", mapOf("presence" to "absent"), listOf(StudioXmlNode("value")))
        for (before in listOf(wrongPresence, absentWithValue)) {
            val children = node.children.map { if (it.name == "before") before else it }
            val malformed = StudioXmlNode("receipt", node.attributes, children)
            assertFailsWith<IllegalArgumentException> { StudioReceiptXml.read(malformed) }
        }
        assertFailsWith<IllegalArgumentException> { StudioReceiptXml.read(node.copy(name = "unexpected")) }
    }

    @Test
    fun `only owned Workspace geometry may remain after restart`() {
        val geometry =
            receipt().copy(
                owner = EffectOwner.WORKSPACE,
                identity = NativeFieldIdentity("ProjectView", "WIDTH_BOUNDS", "owner-7"),
                status = RestoreStatus.KEPT_AFTER_RESTART,
            )
        val bytes = StudioXml.write(StudioReceiptXml.write(geometry))
        assertEquals(geometry, StudioReceiptXml.read(StudioXml.parse(bytes)))
        assertTrue(StudioReceiptPolicy.isTerminal(geometry))
        assertTrue(StudioReceiptPolicy.isTerminal(geometry.copy(status = RestoreStatus.RESTORED)))
        assertTrue(StudioReceiptPolicy.isTerminal(geometry.copy(status = RestoreStatus.FOREIGN)))
        assertFalse(StudioReceiptPolicy.isTerminal(geometry.copy(status = RestoreStatus.PENDING)))
        assertFalse(StudioReceiptPolicy.isTerminal(geometry.copy(status = RestoreStatus.OWNED)))
        assertFalse(StudioReceiptPolicy.isTerminal(geometry.copy(status = RestoreStatus.UNAVAILABLE)))
        val invalid =
            listOf(
                geometry.copy(owner = EffectOwner.FONT),
                geometry.copy(identity = geometry.identity.copy(field = "SHOW_PATH")),
            )
        for (receipt in invalid) {
            assertFalse(StudioReceiptPolicy.isTerminal(receipt))
            assertFailsWith<IllegalArgumentException> { StudioReceiptXml.read(StudioReceiptXml.write(receipt)) }
        }
    }

    @Test
    fun `recovery retains the exact context and automatic operation identity`() {
        val operation = UUID.fromString("99f80e27-50f6-4c28-8d21-c24f542531d2")
        val target = StudioTarget.Project(StudioProjectKey("/work/Проєкт"))
        val step =
            RotationCandidate(
                operation,
                RuntimeOwner(target, 7),
                RotationIntent(true, "PRESET", 2),
                AccentRotationMode.PRESET,
                AyuVariant.MIRAGE,
                "#FFCD66",
                3,
            )
        val plan =
            RecoveryPlan(
                target,
                StudioList(listOf(EffectOwner.ACCENT, EffectOwner.FONT)),
                StudioList(
                    listOf(
                        EffectLimitation(EffectOwner.FONT, LimitationReason.PRO_REQUIRED, blocksEffect = false),
                        EffectLimitation(EffectOwner.PLUGINS, LimitationReason.MISSING_PLUGIN),
                    ),
                ),
            )
        val purposes =
            listOf(
                RecoveryPurpose.UserAcceptance,
                RecoveryPurpose.RotationStep(operation),
                RecoveryPurpose.RotationSchedule(operation),
                RecoveryPurpose.ContextHandoff(operation, RotationHandoffKind.FOCUS),
                RecoveryPurpose.ContextHandoff(operation, RotationHandoffKind.CONTINUITY),
                RecoveryPurpose.NativeChange(operation, StudioNativeChange.Appearance),
                RecoveryPurpose.NativeChange(operation, StudioNativeChange.AccentContext),
                RecoveryPurpose.NativeChange(operation, StudioNativeChange.Theme(StudioThemeChange.FollowSystem)),
                RecoveryPurpose.NativeChange(
                    operation,
                    StudioNativeChange.Theme(StudioThemeChange.Explicit("  Ayu Mirage 🌿  ")),
                ),
            )
        for (purpose in purposes) {
            val rotation = step.takeIf { purpose is RecoveryPurpose.RotationStep }
            val expected = plan.copy(purpose = purpose, rotationStep = rotation)
            assertEquals(expected, readPlan(expected))
        }
        assertEquals(plan.copy(rotationStep = step), readPlan(plan.copy(rotationStep = step)))
        val retryOperation = UUID.fromString("f5866919-f2d0-4c47-872c-d9fc40e1b44b")
        val retry = plan.copy(purpose = RecoveryPurpose.RotationStep(retryOperation), rotationStep = step)
        assertEquals(retry, readPlan(retry))
        val metadata = RecoveryPlan(target, StudioList(emptyList()), purpose = RecoveryPurpose.Metadata)
        assertEquals(metadata, readPlan(metadata))
    }

    @Test
    fun `duplicate owners and contradictory recovery purposes are rejected`() {
        val valid = RecoveryPlan(StudioTarget.Global, StudioList(listOf(EffectOwner.ACCENT, EffectOwner.FONT)))
        val invalid =
            listOf(
                valid.copy(owners = StudioList(listOf(EffectOwner.FONT, EffectOwner.ACCENT))),
                valid.copy(owners = StudioList(listOf(EffectOwner.ACCENT, EffectOwner.ACCENT))),
                valid.copy(
                    limitations = StudioList(listOf(EffectLimitation(EffectOwner.FONT, LimitationReason.PRO_REQUIRED))),
                ),
            )
        for (plan in invalid) {
            assertFailsWith<IllegalArgumentException> { readPlan(plan) }
        }
        val metadataPlan = RecoveryPlan(valid.target, StudioList(emptyList()), purpose = RecoveryPurpose.Metadata)
        val metadata = StudioRecoveryXml.write(metadataPlan)
        val runtime =
            StudioXmlNode(
                "runtime-purpose",
                mapOf("kind" to "schedule", "operation" to "00000000-0000-0000-0000-000000000001"),
            )
        val mixed = StudioXmlNode("recovery", children = metadata.children + runtime)
        assertFailsWith<IllegalArgumentException> { StudioRecoveryXml.read(mixed) }
    }

    @Test
    fun `automatic rotation cannot recover without its frozen candidate`() {
        val operation = UUID.fromString("99f80e27-50f6-4c28-8d21-c24f542531d2")
        val plan =
            RecoveryPlan(
                StudioTarget.Global,
                StudioList(listOf(EffectOwner.ACCENT)),
                purpose = RecoveryPurpose.RotationStep(operation),
            )
        assertFailsWith<IllegalArgumentException> { readPlan(plan) }
    }

    @Test
    fun `recovery cannot address a blank project identity`() {
        val target = StudioTarget.Project(StudioProjectKey(" \t"))
        val plan = RecoveryPlan(target, StudioList(emptyList()), purpose = RecoveryPurpose.Metadata)
        assertFailsWith<IllegalArgumentException> { readPlan(plan) }
    }

    @Test
    fun `rotation recovery rejects a frozen choice for another target`() {
        val operation = UUID.fromString("99f80e27-50f6-4c28-8d21-c24f542531d2")
        val other = StudioTarget.Project(StudioProjectKey("/work/other"))
        val step =
            RotationCandidate(
                operation,
                RuntimeOwner(other, 7),
                RotationIntent(true, "PRESET", 2),
                AccentRotationMode.PRESET,
                AyuVariant.MIRAGE,
                "#FFCD66",
                3,
            )
        val plan = RecoveryPlan(StudioTarget.Global, StudioList(listOf(EffectOwner.ACCENT)), rotationStep = step)
        assertFailsWith<IllegalArgumentException> { readPlan(plan) }
        val automatic = plan.copy(purpose = RecoveryPurpose.RotationStep(operation))
        assertFailsWith<IllegalArgumentException> { readPlan(automatic) }
    }

    private fun readPlan(plan: RecoveryPlan): RecoveryPlan {
        val bytes = StudioXml.write(StudioRecoveryXml.write(plan))
        return StudioRecoveryXml.read(StudioXml.parse(bytes))
    }

    private fun receipt(): PortableReceipt =
        PortableReceipt(
            "font:scheme-7:size",
            EffectOwner.FONT,
            NativeFieldIdentity("scheme-7", "editor-font-size", "owner-7"),
            NativeValue.Absent,
            NativeValue.Present(RawValue.Null),
            RestoreStatus.PENDING,
        )
}
