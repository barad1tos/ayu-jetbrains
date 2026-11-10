package dev.ayuislands.studio.session

import com.intellij.openapi.progress.ProcessCanceledException
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.indent.IndentPalette
import dev.ayuislands.indent.IndentPreset
import dev.ayuislands.studio.fields.StudioRuntimeFields
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioIntegrationSelection
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.RestoreStatus
import indent.rainbow.settings.IrColorsPaletteType
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioIntegrationCaptureTest {
    private val fixture = IntegrationCaptureFixture()
    private val explicitEnable = StudioIntegrationSelection(enablesCodeGlance = true, enablesIndentRainbow = true)

    @AfterTest
    fun closeFixture() = fixture.close()

    @Test
    fun `prepare captures portable receipts without changing native configs or raw candidate`() =
        runBlocking {
            val input = fixture.input()
            val before = input.candidate
            val operation = fixture.prepare(input)

            fixture.assertOriginal()
            assertTrue(fixture.writes.isEmpty())
            assertTrue(operation.runtimeChanges.isEmpty())
            assertEquals(6, operation.receipts.size)
            assertTrue(operation.receipts.all { it.owner == EffectOwner.ACCENT && it.status == RestoreStatus.PENDING })
            assertEquals(operation.receipts, fixture.roundTrip(input, operation.receipts))
            fixture.beforeSetter = {
                assertEquals(
                    6,
                    fixture.acknowledged.size,
                    "All native fields must be journaled before the first setter",
                )
            }
            fixture.acknowledge(input, operation)

            operation.apply()

            assertEquals("112233", fixture.codeGlance.viewportColor)
            assertEquals("112233", fixture.codeGlance.viewportBorderColor)
            assertEquals(1, fixture.codeGlance.viewportBorderThickness)
            val palette =
                IndentPalette
                    .forAccent("#112233", AyuVariant.MIRAGE)
                    .toColorStrings(IndentPreset.resolveAlpha("NEON", 217), false)
            assertEquals(IrColorsPaletteType.CUSTOM, fixture.indent.paletteType)
            assertEquals(palette.joinToString(", "), fixture.indent.customPalette)
            assertEquals(palette.size, fixture.indent.customPaletteNumberColors)
            assertEquals(before, input.candidate)
            val accepted = StudioRuntimeFields.merge(before.runtime, operation.runtimeChanges)
            assertEquals(RawValue.Text("123456"), fixture.value(accepted, "cgpBaseColor"))
            assertEquals(RawValue.Text("PASTEL"), fixture.value(accepted, "irBaseType"))
            assertEquals(RawValue.Text("OWNED"), fixture.value(accepted, "cgpOwnership"))
            assertEquals(RawValue.Text("OWNED"), fixture.value(accepted, "irOwnership"))
            val afterApply = fixture.snapshot()
            val calls = fixture.writes.toList()

            operation.accept()
            operation.accept()

            assertEquals(afterApply, fixture.snapshot())
            assertEquals(calls, fixture.writes, "Outer acceptance releases handles without replaying native Apply")
            assertEquals(before, input.candidate)
        }

    @Test
    fun `setter mutation followed by failure remains reversible under outer ownership`() =
        runBlocking {
            val input = fixture.input()
            val operation = fixture.prepare(input)
            fixture.acknowledge(input, operation)
            val failure = IllegalStateException("Viewport setter failed after storing its value")
            fixture.borderFailure = failure

            val thrown = assertFailsWith<IllegalStateException> { operation.apply() }
            assertEquals(failure.message, thrown.message)
            assertTrue(generateSequence<Throwable>(thrown) { it.cause }.any { it === failure })

            assertFalse(fixture.writes.isEmpty())
            assertEquals(6, fixture.acknowledged.size)
            fixture.restore(operation)
            fixture.assertOriginal()
            assertTrue(operation.receipts.all { it.status == RestoreStatus.RESTORED })
            assertTrue(operation.runtimeChanges.isEmpty())
            assertEquals(input.candidate, fixture.input().candidate)
        }

    @Test
    fun `later owner failure restores both integrations while preserving each foreign sibling`() =
        runBlocking {
            val input = fixture.input()
            val before = input.candidate
            val operation = fixture.prepare(input)
            fixture.acknowledge(input, operation)
            operation.apply()
            fixture.codeGlance.viewportBorderColor = "8899AA"
            fixture.indent.customPalette = "FFABCDEF, 80887766"
            fixture.writes.clear()

            fixture.restore(operation)

            fixture.assertOriginal(codeGlanceBorder = "8899AA", indentPalette = "FFABCDEF, 80887766")
            assertEquals(2, operation.receipts.count { it.status == RestoreStatus.FOREIGN })
            assertEquals(4, operation.receipts.count { it.status == RestoreStatus.RESTORED })
            assertTrue(fixture.writes.first().startsWith("cgp/"), "Rollback reverses the IR then CGP apply order")
            assertEquals(before, input.candidate)
            val calls = fixture.writes.toList()
            fixture.restore(operation)
            assertEquals(calls, fixture.writes)
        }

    @Test
    fun `live target replacement never rebinds either integration during rollback`() =
        runBlocking {
            val input = fixture.input()
            val operation = fixture.prepare(input)
            fixture.acknowledge(input, operation)
            operation.apply()
            fixture.reload()
            val replacement = fixture.snapshot()
            fixture.writes.clear()

            fixture.restore(operation)
            fixture.restore(operation)

            assertEquals(replacement, fixture.snapshot())
            assertTrue(fixture.writes.isEmpty())
            assertTrue(operation.receipts.all { it.status == RestoreStatus.UNAVAILABLE })
        }

    @Test
    fun `failed viewport rollback still restores indent and retry performs no completed setters`() =
        runBlocking {
            val input = fixture.input()
            val operation = fixture.prepare(input)
            fixture.acknowledge(input, operation)
            operation.apply()
            fixture.borderFailure = IllegalStateException("Rollback failed after storing the original border")

            assertFailsWith<IllegalStateException> { fixture.restore(operation) }
            fixture.assertOriginal()
            val completed = fixture.writes.toList()
            fixture.restore(operation)

            fixture.assertOriginal()
            assertEquals(completed, fixture.writes)
            assertTrue(operation.receipts.all { it.status == RestoreStatus.RESTORED })
        }

    @Test
    fun `native setter cancellation preserves its origin and both integrations can be restored`() =
        runBlocking {
            for (cancelled in listOf(ProcessCanceledException(), CancellationException("Cancelled viewport setter"))) {
                val input = fixture.input()
                val operation = fixture.prepare(input)
                fixture.acknowledge(input, operation)
                fixture.borderFailure = cancelled

                val thrown = assertFailsWith<RuntimeException> { operation.apply() }
                assertTrue(cancelled.javaClass === thrown.javaClass)
                assertEquals(cancelled.message, thrown.message)
                assertTrue(generateSequence<Throwable>(thrown) { it.cause }.any { it === cancelled })
                fixture.restore(operation)

                fixture.assertOriginal()
                assertTrue(operation.receipts.all { it.status == RestoreStatus.RESTORED })
            }
        }

    @Test
    fun `disabled and unlicensed gates restore baselines and keep dormant choices`() =
        runBlocking {
            for (licensed in listOf(true, false)) {
                val initial = fixture.input()
                val original = fixture.prepare(initial)
                fixture.acknowledge(initial, original)
                original.apply()
                val accepted = StudioRuntimeFields.merge(initial.candidate.runtime, original.runtimeChanges)
                original.accept()
                val requested =
                    fixture.settings().apply {
                        cgpIntegrationEnabled = !licensed
                        irIntegrationEnabled = !licensed
                        indentPresetName = "future-preset"
                        indentCustomAlpha = -37
                    }
                val candidate =
                    fixture.input(requested).let {
                        it.copy(candidate = it.candidate.copy(runtime = accepted))
                    }
                val before = candidate.candidate
                val gated = fixture.prepare(candidate, licensed)
                fixture.acknowledge(candidate, gated)

                gated.apply()
                gated.accept()

                fixture.assertOriginal()
                assertEquals(before, candidate.candidate)
                val settled = StudioRuntimeFields.merge(accepted, gated.runtimeChanges)
                assertEquals(RawValue.Text("UNOWNED"), fixture.value(settled, "cgpOwnership"))
                assertEquals(RawValue.Text("UNOWNED"), fixture.value(settled, "irOwnership"))
                assertEquals(-37, requested.indentCustomAlpha)
                assertEquals("future-preset", requested.indentPresetName)
            }
        }

    @Test
    fun `inactive context releases integrations without an invented accent and preserves abort and reenable state`() =
        runBlocking {
            val initial = fixture.input()
            val active = fixture.prepare(initial)
            fixture.acknowledge(initial, active)
            active.apply()
            val accepted = StudioRuntimeFields.merge(initial.candidate.runtime, active.runtimeChanges)
            active.accept()
            val activeNative = fixture.snapshot()
            val input = initial.copy(candidate = initial.candidate.copy(runtime = accepted))
            val before = input.candidate
            val cleanup = fixture.capture().prepare(input, null, null, fixture.facts)
            fixture.acknowledge(input, cleanup)

            cleanup.apply()

            fixture.assertOriginal()
            assertEquals(before, input.candidate)
            fixture.restore(cleanup)
            assertEquals(activeNative, fixture.snapshot())
            assertTrue(cleanup.receipts.all { it.status == RestoreStatus.RESTORED })

            val confirmed = fixture.capture().prepare(input, null, null, fixture.facts)
            fixture.acknowledge(input, confirmed)
            confirmed.apply()
            confirmed.accept()

            fixture.assertOriginal()
            assertEquals(before, input.candidate)
            val inactiveRuntime = StudioRuntimeFields.merge(accepted, confirmed.runtimeChanges)
            assertEquals(RawValue.Text("UNOWNED"), fixture.value(inactiveRuntime, "cgpOwnership"))
            assertEquals(RawValue.Text("UNOWNED"), fixture.value(inactiveRuntime, "irOwnership"))

            val reenabledInput = input.copy(candidate = input.candidate.copy(runtime = inactiveRuntime))
            val reenabled = fixture.prepare(reenabledInput)
            fixture.acknowledge(reenabledInput, reenabled)
            reenabled.apply()
            reenabled.accept()
            assertEquals(activeNative, fixture.snapshot())
            assertEquals(before.shared, reenabledInput.candidate.shared)
        }

    @Test
    fun `active integration without confirmed accent fails before native writes`() =
        runBlocking {
            val input = fixture.input()

            assertFailsWith<IllegalStateException> {
                fixture.capture().prepare(input, AccentContext.Ayu(AyuVariant.MIRAGE), null, fixture.facts)
            }

            fixture.assertOriginal()
            assertTrue(fixture.writes.isEmpty())
            assertEquals(input.candidate, fixture.input().candidate)
        }

    @Test
    fun `absent integrations leave settings intact and retain unavailable rollback receipts for retry`() =
        runBlocking {
            val input = fixture.input()
            val absent = StudioIntegrationCapture(null, null, fixture.context)
            val skipped = absent.prepare(input, AccentContext.Ayu(AyuVariant.MIRAGE), "#112233", fixture.facts)
            skipped.apply()
            skipped.accept()
            assertTrue(skipped.receipts.isEmpty())
            assertTrue(skipped.runtimeChanges.isEmpty())
            fixture.assertOriginal()

            val written = fixture.prepare(input)
            fixture.acknowledge(input, written)
            written.apply()
            val saved = fixture.roundTrip(input, written.receipts)
            val restarted = StudioNativeFacts(true, StudioProcess(82, Instant.EPOCH.plusSeconds(1)))
            val unavailable = absent.recover(input, saved, restarted)
            val pendingValues = fixture.snapshot()

            fixture.restore(unavailable)

            assertEquals(pendingValues, fixture.snapshot())
            assertTrue(unavailable.receipts.all { it.status == RestoreStatus.UNAVAILABLE })
            val available = fixture.capture().recover(input, unavailable.receipts, restarted)
            fixture.restore(available)
            fixture.assertOriginal()
            assertTrue(available.receipts.all { it.status == RestoreStatus.RESTORED })
        }

    @Test
    fun `native reload resumes rollback and committed cleanup keeps accepted settings`() =
        runBlocking {
            val input = fixture.input()
            val operation = fixture.prepare(input)
            fixture.acknowledge(input, operation)
            operation.apply()
            val saved = fixture.roundTrip(input, operation.receipts)
            val written = fixture.snapshot()
            fixture.reload()
            val restarted = StudioNativeFacts(true, StudioProcess(82, Instant.EPOCH.plusSeconds(1)))
            val cleanup = fixture.capture().recover(input, saved, restarted)
            fixture.writes.clear()

            cleanup.accept()
            cleanup.accept()

            assertEquals(written, fixture.snapshot())
            assertTrue(fixture.writes.isEmpty())
            // A rollback setter reached native storage before its acknowledgement was lost.
            fixture.codeGlance.viewportColor = "123456"
            fixture.indent.customPaletteNumberColors = 4
            val recovery = fixture.capture().recover(input, saved, restarted)
            assertFailsWith<IllegalStateException> { recovery.apply() }
            fixture.writes.clear()

            fixture.restore(recovery)

            fixture.assertOriginal()
            assertFalse("cgp/COLOR" in fixture.writes, "Already restored native values must not be replayed")
            assertFalse("ir/COUNT" in fixture.writes)
            assertTrue(recovery.receipts.all { it.status == RestoreStatus.RESTORED })
            assertEquals(input.candidate, fixture.input().candidate)
        }

    @Test
    fun `first explicit opt-in applies both integrations and abort discards migrated ownership`() =
        runBlocking {
            val requested =
                fixture.settings().apply {
                    isCgpOwnershipMigrated = false
                    isIrOwnershipMigrated = false
                }
            val input = fixture.input(requested).copy(integrationSelection = explicitEnable)
            val before = input.candidate
            val operation = fixture.prepare(input)
            assertTrue(operation.runtimeChanges.isEmpty())
            fixture.assertOriginal()
            fixture.acknowledge(input, operation)

            operation.apply()

            assertEquals("112233", fixture.codeGlance.viewportColor)
            assertEquals(IrColorsPaletteType.CUSTOM, fixture.indent.paletteType)
            val applied = StudioRuntimeFields.merge(before.runtime, operation.runtimeChanges)
            assertEquals(RawValue.BooleanValue(true), fixture.value(applied, "isCgpOwnershipMigrated"))
            assertEquals(RawValue.BooleanValue(true), fixture.value(applied, "isIrOwnershipMigrated"))
            assertEquals(RawValue.Text("OWNED"), fixture.value(applied, "cgpOwnership"))
            assertEquals(RawValue.Text("OWNED"), fixture.value(applied, "irOwnership"))
            assertEquals(RawValue.Text("123456"), fixture.value(applied, "cgpBaseColor"))
            assertEquals(RawValue.Text("PASTEL"), fixture.value(applied, "irBaseType"))

            fixture.restore(operation)

            fixture.assertOriginal()
            assertTrue(operation.runtimeChanges.isEmpty())
            assertEquals(before, input.candidate)
            assertFalse(requested.isCgpOwnershipMigrated)
            assertFalse(requested.isIrOwnershipMigrated)
        }

    @Test
    fun `explicit opt-in rearms only the selected suspended integration`() =
        runBlocking {
            val selections =
                listOf(
                    StudioIntegrationSelection(enablesCodeGlance = true),
                    StudioIntegrationSelection(enablesIndentRainbow = true),
                )
            for (selection in selections) {
                val requested =
                    fixture.settings().apply {
                        cgpOwnership = "SUSPENDED"
                        irOwnership = "SUSPENDED"
                        cgpBaseColor = "old-cgp-baseline"
                        irBaseType = "old-ir-baseline"
                    }
                val input = fixture.input(requested).copy(integrationSelection = selection)
                val operation = fixture.prepare(input)
                fixture.acknowledge(input, operation)

                operation.apply()

                val applied = StudioRuntimeFields.merge(input.candidate.runtime, operation.runtimeChanges)
                val codeGlanceOwner = if (selection.enablesCodeGlance) "OWNED" else "SUSPENDED"
                val indentOwner = if (selection.enablesIndentRainbow) "OWNED" else "SUSPENDED"
                assertEquals(RawValue.Text(codeGlanceOwner), fixture.value(applied, "cgpOwnership"))
                assertEquals(RawValue.Text(indentOwner), fixture.value(applied, "irOwnership"))
                val codeGlanceBase = if (selection.enablesCodeGlance) "123456" else "old-cgp-baseline"
                val indentBase = if (selection.enablesIndentRainbow) "PASTEL" else "old-ir-baseline"
                assertEquals(RawValue.Text(codeGlanceBase), fixture.value(applied, "cgpBaseColor"))
                assertEquals(RawValue.Text(indentBase), fixture.value(applied, "irBaseType"))
                assertEquals(3, operation.receipts.size)
                assertEquals(selection.enablesCodeGlance, fixture.writes.any { it.startsWith("cgp/") })
                assertEquals(selection.enablesIndentRainbow, fixture.writes.any { it.startsWith("ir/") })

                fixture.restore(operation)
                fixture.assertOriginal()
                fixture.writes.clear()
            }
        }

    @Test
    fun `ordinary reapply and external gates preserve suspended integrations`() =
        runBlocking {
            val requested =
                fixture.settings().apply {
                    cgpOwnership = "SUSPENDED"
                    irOwnership = "SUSPENDED"
                    externalThemeEnhancementsEnabled = true
                    externalThemeCodeGlanceProEnabled = true
                    externalThemeIndentRainbowEnabled = true
                }
            val input = fixture.input(requested)
            for (context in listOf(AccentContext.Ayu(AyuVariant.MIRAGE), AccentContext.External)) {
                val operation = fixture.capture().prepare(input, context, "#112233", fixture.facts)

                operation.apply()
                operation.accept()

                fixture.assertOriginal()
                assertTrue(fixture.writes.isEmpty())
                assertTrue(operation.receipts.isEmpty())
                assertTrue(operation.runtimeChanges.isEmpty())
            }
        }

    @Test
    fun `unlicensed explicit opt-in preserves suspension and saved baselines`() =
        runBlocking {
            val requested =
                fixture.settings().apply {
                    cgpOwnership = "SUSPENDED"
                    irOwnership = "SUSPENDED"
                    cgpBaseColor = "old-cgp-baseline"
                    irBasePalette = "old-ir-baseline"
                }
            val input = fixture.input(requested).copy(integrationSelection = explicitEnable)
            val operation = fixture.prepare(input, licensed = false)

            operation.apply()
            operation.accept()

            fixture.assertOriginal()
            assertTrue(fixture.writes.isEmpty())
            assertTrue(operation.receipts.isEmpty())
            assertTrue(operation.runtimeChanges.isEmpty())
            assertEquals(input.candidate, fixture.input(requested).candidate)
        }

    @Test
    fun `absent integrations and inactive context preserve explicit opt-in metadata on acceptance only`() =
        runBlocking {
            for (isAvailable in listOf(false, true)) {
                val requested =
                    fixture.settings().apply {
                        isCgpOwnershipMigrated = false
                        isIrOwnershipMigrated = false
                        cgpOwnership = "SUSPENDED"
                        irOwnership = "SUSPENDED"
                        cgpBaseColor = "old-cgp-baseline"
                        irBasePalette = "old-ir-baseline"
                    }
                val input = fixture.input(requested).copy(integrationSelection = explicitEnable)
                val capture =
                    if (isAvailable) {
                        fixture.capture()
                    } else {
                        StudioIntegrationCapture(
                            null,
                            null,
                            fixture.context,
                        )
                    }
                val context = if (isAvailable) null else AccentContext.Ayu(AyuVariant.MIRAGE)
                val operation = capture.prepare(input, context, null, fixture.facts)
                assertTrue(operation.runtimeChanges.isEmpty())

                operation.apply()

                fixture.assertOriginal()
                assertTrue(fixture.writes.isEmpty())
                assertTrue(operation.receipts.isEmpty())
                val applied = StudioRuntimeFields.merge(input.candidate.runtime, operation.runtimeChanges)
                assertEquals(RawValue.BooleanValue(true), fixture.value(applied, "isCgpOwnershipMigrated"))
                assertEquals(RawValue.BooleanValue(true), fixture.value(applied, "isIrOwnershipMigrated"))
                assertEquals(RawValue.Text("UNOWNED"), fixture.value(applied, "cgpOwnership"))
                assertEquals(RawValue.Text("UNOWNED"), fixture.value(applied, "irOwnership"))
                assertEquals(RawValue.Null, fixture.value(applied, "cgpBaseColor"))
                assertEquals(RawValue.Null, fixture.value(applied, "irBasePalette"))
                assertEquals(input.candidate, fixture.input(requested).candidate)

                fixture.restore(operation)
                assertTrue(operation.runtimeChanges.isEmpty())
            }
        }

    @Test
    fun `explicit opt-in preserves owned and pending recovery metadata`() =
        runBlocking {
            val absent = StudioIntegrationCapture(null, null, fixture.context)
            for (ownership in listOf("OWNED", "RECOVERY_PENDING")) {
                val requested =
                    fixture.settings().apply {
                        cgpOwnership = ownership
                        irOwnership = ownership
                        cgpBaseColor = "original-cgp"
                        cgpBaseBorder = "original-border"
                        cgpBaseThickness = 3
                        cgpAppliedColor = "last-cgp"
                        cgpAppliedBorder = "last-border"
                        cgpAppliedThickness = 1
                        irBaseType = "PASTEL"
                        irBasePalette = "original-ir"
                        irBaseColorCount = 7
                        irAppliedType = "CUSTOM"
                        irAppliedPalette = "last-ir"
                        irAppliedColorCount = 8
                    }
                val input = fixture.input(requested).copy(integrationSelection = explicitEnable)
                val operation = absent.prepare(input, null, null, fixture.facts)

                operation.apply()
                operation.accept()

                assertTrue(operation.runtimeChanges.isEmpty())
                assertEquals(input.candidate, fixture.input(requested).candidate)
                fixture.assertOriginal()
            }
        }
}
