package dev.ayuislands.projectview

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.registry.RegistryValue
import dev.ayuislands.accent.AccentApplyPlanRunner
import dev.ayuislands.accent.AccentWriteState
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RootPathLeaseTest {
    private lateinit var state: AyuIslandsState
    private val posted = mutableListOf<Runnable>()
    private val registry = mockk<Registry>()

    @BeforeTest
    fun setUp() {
        state = AyuIslandsState()
        mockkObject(Registry.Companion)
        every { Registry.getInstance() } returns registry
        every { registry.getBundleValueOrNull(any()) } returns "true"
        val settings = mockk<AyuIslandsSettings>()
        every { settings.state } answers {
            dev.ayuislands.studio.StudioFixtures
                .legacyView(state)
                .settings
        }
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings
        mockkObject(AccentApplyPlanRunner)
        every { AccentApplyPlanRunner.post(any()) } answers { posted += firstArg<Runnable>() }
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `capture observes registry and membership without acquiring a hold`() =
        onEdt {
            val probe = RegistryProbe()
            val lease = RootPathLease()
            val firstProject = mockk<Project>()
            val secondProject = mockk<Project>()
            val detached = detachedState()
            val checkpoint = lease.prepare(setOf(firstProject), probe.value, true, detached)

            assertEquals("true", probe.raw)
            assertTrue(probe.writes.isEmpty())
            assertFalse(detached.hasRootPathLease)
            assertFalse(state.hasRootPathLease)
            assertEquals(AccentWriteState.PREPARED, checkpoint.state)
            assertFalse(checkpoint.hasPending)

            acceptMembership(lease, secondProject, probe.value, hide = true)

            assertEquals("false", probe.raw)
            assertFalse(checkpoint.apply())
            assertEquals(AccentWriteState.RELINQUISHED, checkpoint.state)
            acceptMembership(lease, secondProject, probe.value, hide = false)
            assertEquals("true", probe.raw)
        }

    @Test
    fun `native apply and rollback use public Registry operations without reading defaults`() =
        onEdt {
            every { registry.getBundleValueOrNull(any()) } throws AssertionError("Registry defaults are private")
            for (initial in listOf("TRUE", "true")) {
                val probe = RegistryProbe(initial)
                val checkpoint =
                    RootPathLease().prepare(setOf(mockk<Project>()), probe.value, true, detachedState())

                assertTrue(checkpoint.apply())
                assertEquals("false", probe.raw)
                checkpoint.restore(emptyList())

                assertEquals(initial, probe.raw)
                assertEquals(initial != "true", probe.value.isChangedFromDefault())
                assertEquals(AccentWriteState.RESTORED, checkpoint.state)
                assertEquals(if (initial == "true") 1 else 0, probe.resets)
            }
        }

    @Test
    fun `retained apply and rollback preserve raw Registry value default status and detached metadata`() =
        onEdt {
            for (defaultRaw in listOf("true", "false")) {
                every { registry.getBundleValueOrNull(any()) } returns defaultRaw
                for (initial in listOf("true", "TRUE", "false", "FALSE")) {
                    val probe = RegistryProbe(initial, defaultRaw)
                    val lease = RootPathLease()
                    val project = mockk<Project>()
                    val detached =
                        detachedState().apply {
                            wasRootPathShown = true
                            wasRootPathChanged = true
                        }
                    val checkpoint = lease.prepare(setOf(project), probe.value, true, detached)

                    assertEquals(RootPathValue(initial, initial != defaultRaw), checkpoint.before)
                    assertEquals(RootPathWrite.Set("false"), checkpoint.planned)
                    assertEquals(checkpoint.before, checkpoint.lastWritten)
                    assertTrue(checkpoint.apply())

                    assertEquals(RootPathValue("false", "false" != defaultRaw), checkpoint.lastWritten)
                    assertFalse(probe.value.asBoolean())
                    assertTrue(detached.hasRootPathLease)
                    assertEquals(initial.toBoolean(), detached.wasRootPathShown)
                    assertEquals(initial != defaultRaw, detached.wasRootPathChanged)
                    assertFalse(state.hasRootPathLease)
                    assertTrue(checkpoint.hasPending)

                    checkpoint.restore(emptyList())

                    assertEquals(initial, probe.raw)
                    assertEquals(initial != defaultRaw, probe.value.isChangedFromDefault())
                    assertEquals(checkpoint.before, checkpoint.lastWritten)
                    assertFalse(detached.hasRootPathLease)
                    assertTrue(detached.wasRootPathShown)
                    assertTrue(detached.wasRootPathChanged)
                    assertFalse(state.hasRootPathLease)
                    assertEquals(AccentWriteState.RESTORED, checkpoint.state)
                    assertFalse(checkpoint.hasPending)
                    if (initial == "true" && defaultRaw == "true") assertEquals(1, probe.resets)
                }
            }
        }

    @Test
    fun `apply holds reentrant callbacks until commit and commit does not persist detached state`() =
        onEdt {
            val probe = RegistryProbe()
            val lease = RootPathLease()
            val firstProject = mockk<Project>()
            val secondProject = mockk<Project>()
            val callbackProject = mockk<Project>()
            val detached = detachedState()
            val checkpoint = lease.prepare(linkedSetOf(firstProject, secondProject), probe.value, true, detached)
            probe.afterWrite = {
                acceptMembership(lease, firstProject, probe.value, hide = false)
                acceptMembership(lease, callbackProject, probe.value, hide = true)
            }

            assertTrue(checkpoint.apply())
            probe.afterWrite = {}
            val beforeCommit = probe.writes.toList()
            checkpoint.commit()

            assertFalse(checkpoint.hasPending)
            assertTrue(detached.hasRootPathLease)
            assertFalse(state.hasRootPathLease)
            assertEquals(beforeCommit, probe.writes)
            acceptMembership(lease, firstProject, probe.value, hide = false)
            assertEquals("false", probe.raw)
            acceptMembership(lease, secondProject, probe.value, hide = false)
            assertEquals("true", probe.raw, "Callbacks must not add or remove memberships during the hold")
        }

    @Test
    fun `batch release rollback restores existing memberships and leaves other projects untouched`() =
        onEdt {
            val probe = RegistryProbe()
            val lease = RootPathLease()
            val firstProject = mockk<Project>()
            val secondProject = mockk<Project>()
            acceptMembership(lease, firstProject, probe.value, hide = true)
            acceptMembership(lease, secondProject, probe.value, hide = true)
            val detached = detachedState()
            val checkpoint = lease.prepare(setOf(firstProject), probe.value, false, detached)
            val beforeApply = probe.writes.toList()

            assertTrue(checkpoint.apply())

            assertEquals("false", probe.raw)
            assertTrue(detached.hasRootPathLease)
            assertTrue(state.hasRootPathLease)
            assertEquals(beforeApply, probe.writes, "A remaining member still owns the hidden path")
            checkpoint.restore(emptyList())
            acceptMembership(lease, secondProject, probe.value, hide = false)
            assertEquals("false", probe.raw, "Rollback must put the first project back in the lease")
            acceptMembership(lease, firstProject, probe.value, hide = false)
            assertEquals("true", probe.raw)
        }

    @Test
    fun `rollback preserves a foreign raw Registry edit even when its boolean is unchanged`() =
        onEdt {
            val probe = RegistryProbe()
            val lease = RootPathLease()
            val project = mockk<Project>()
            val detached = detachedState()
            val checkpoint = lease.prepare(setOf(project), probe.value, true, detached)
            assertTrue(checkpoint.apply())
            probe.raw = "FALSE"
            val beforeRestore = probe.writes.toList()

            checkpoint.restore(emptyList())

            assertEquals("FALSE", probe.raw)
            assertEquals(beforeRestore, probe.writes)
            assertEquals(AccentWriteState.RELINQUISHED, checkpoint.state)
            assertFalse(checkpoint.hasPending)
            assertFalse(detached.hasRootPathLease)
            assertFalse(state.hasRootPathLease)
            lease.prepare(setOf(project), probe.value, false, detached)
        }

    @Test
    fun `mutating apply and restore failures retain the hold and exact baseline until retry`() =
        onEdt {
            val probe = RegistryProbe()
            val lease = RootPathLease()
            val project = mockk<Project>()
            val callbackProject = mockk<Project>()
            val detached = detachedState()
            val checkpoint = lease.prepare(setOf(project), probe.value, true, detached)
            probe.afterWrite = { error("registry setter failed after mutation") }

            assertFailsWith<IllegalStateException> { checkpoint.apply() }

            assertEquals("false", probe.raw)
            assertEquals(RootPathValue("false", true), checkpoint.lastWritten)
            assertTrue(checkpoint.hasPending)
            assertTrue(detached.hasRootPathLease)
            assertFalse(state.hasRootPathLease)
            assertFailsWith<IllegalStateException> { checkpoint.restore(emptyList()) }
            assertEquals("true", probe.raw)
            assertTrue(checkpoint.hasPending)
            acceptMembership(lease, callbackProject, probe.value, hide = true)
            assertEquals("true", probe.raw, "Failed rollback must retain its callback hold")
            probe.afterWrite = {}

            checkpoint.restore(emptyList())

            assertEquals("true", probe.raw)
            assertEquals(AccentWriteState.RESTORED, checkpoint.state)
            assertFalse(checkpoint.hasPending)
            assertFalse(detached.hasRootPathLease)
            assertFalse(state.hasRootPathLease)
            acceptMembership(lease, project, probe.value, hide = false)
            assertEquals("true", probe.raw)
        }

    @Test
    fun `cold release rollback preserves the durable baseline without writing global settings`() =
        onEdt {
            state.hasRootPathLease = true
            state.wasRootPathShown = true
            state.wasRootPathChanged = false
            val detached = detachedState()
            val probe = RegistryProbe("false")
            val checkpoint = RootPathLease().prepare(setOf(mockk<Project>()), probe.value, false, detached)

            assertTrue(checkpoint.apply())

            assertEquals("true", probe.raw)
            assertFalse(detached.hasRootPathLease)
            assertTrue(state.hasRootPathLease)
            assertTrue(state.wasRootPathShown)

            checkpoint.restore(emptyList())

            assertEquals("false", probe.raw)
            assertTrue(detached.hasRootPathLease)
            assertTrue(detached.wasRootPathShown)
            assertFalse(detached.wasRootPathChanged)
            assertTrue(state.hasRootPathLease)
        }

    @Test
    fun `member release during native write is replayed once after rollback or acceptance settles`() =
        onEdt {
            for (accept in listOf(false, true)) {
                val probe = RegistryProbe()
                val lease = RootPathLease()
                val project = mockk<Project>()
                acceptMembership(lease, project, probe.value, hide = true)
                val detached = detachedState()
                val checkpoint = lease.prepare(setOf(project), probe.value, false, detached)
                probe.afterWrite = { acceptMembership(lease, project, probe.value, hide = false) }

                assertTrue(checkpoint.apply())
                probe.afterWrite = {}
                assertTrue(posted.isEmpty(), "Lifecycle callbacks cannot escape a pending transaction")
                if (accept) {
                    checkpoint.commit()
                    state.hasRootPathLease = detached.hasRootPathLease
                    state.wasRootPathShown = detached.wasRootPathShown
                    state.wasRootPathChanged = detached.wasRootPathChanged
                } else {
                    checkpoint.restore(emptyList())
                }
                val afterSettlement = probe.writes.toList()
                checkpoint.releaseResources()
                checkpoint.releaseResources()

                assertEquals(
                    afterSettlement,
                    probe.writes,
                    "Settlement must post lifecycle work, never run its setters inline",
                )
                assertEquals(1, posted.size)
                posted.removeAt(0).run()

                assertEquals("true", probe.raw, "A disposed member cannot keep a restored lease alive")
                assertFalse(state.hasRootPathLease)
                assertTrue(posted.isEmpty())
                val afterReplay = probe.writes.toList()
                acceptMembership(lease, project, probe.value, hide = false)
                assertEquals(afterReplay, probe.writes, "A repeated release must remain inert")
            }
        }

    @Test
    fun `receipt recovery restores its exact before image instead of the pre Ayu baseline`() =
        onEdt {
            state.hasRootPathLease = true
            state.wasRootPathShown = true
            state.wasRootPathChanged = false
            val probe = RegistryProbe("false")
            val before = RootPathValue("FALSE", true)
            val written = RootPathValue("false", true)
            val lease = RootPathLease()
            every { AyuIslandsSettings.getInstance() } throws
                AssertionError("Receipt recovery must not access settings")

            val recovery = assertNotNull(lease.recoverReceipt(probe.value, before, written))

            assertEquals("false", probe.raw)
            assertTrue(probe.writes.isEmpty())
            assertTrue(recovery.hasPending)
            assertEquals(written, recovery.lastWritten)
            recovery.restore(emptyList())

            assertEquals("FALSE", probe.raw)
            assertEquals(0, probe.resets)
            assertEquals(before, recovery.lastWritten)
            assertEquals(AccentWriteState.RESTORED, recovery.state)
            assertFalse(recovery.hasPending)
            assertTrue(state.hasRootPathLease)
            assertTrue(state.wasRootPathShown)
            assertFalse(state.wasRootPathChanged)
            val calls = probe.writes.toList()
            recovery.restore(emptyList())
            assertEquals(calls, probe.writes)
        }

    @Test
    fun `receipt recovery leaves an already restored or foreign Registry value untouched`() =
        onEdt {
            for (current in listOf("true", "TRUE", "FALSE")) {
                val probe = RegistryProbe(current)
                val recovery =
                    assertNotNull(
                        RootPathLease().recoverReceipt(
                            probe.value,
                            RootPathValue("true", false),
                            RootPathValue("false", true),
                        ),
                    )

                recovery.restore(emptyList())

                assertEquals(current, probe.raw)
                assertTrue(probe.writes.isEmpty())
                assertFalse(recovery.hasPending)
                val outcome = if (current == "true") AccentWriteState.RESTORED else AccentWriteState.RELINQUISHED
                assertEquals(outcome, recovery.state)
            }
        }

    @Test
    fun `receipt recovery refuses an existing owner or hold and rechecks a later owner`() =
        onEdt {
            val probe = RegistryProbe()
            val lease = RootPathLease()
            val project = mockk<Project>()
            val before = RootPathValue("true", false)
            val written = RootPathValue("false", true)
            acceptMembership(lease, project, probe.value, hide = true)
            assertNull(lease.recoverReceipt(probe.value, before, written))
            acceptMembership(lease, project, probe.value, hide = false)
            val checkpoint = lease.prepare(setOf(project), probe.value, true, detachedState())
            assertTrue(checkpoint.apply())
            assertNull(lease.recoverReceipt(probe.value, before, written))
            checkpoint.restore(emptyList())
            val recovery = assertNotNull(lease.recoverReceipt(probe.value, before, written))

            acceptMembership(lease, project, probe.value, hide = true)
            val calls = probe.writes.toList()
            assertFailsWith<IllegalStateException> { recovery.restore(emptyList()) }

            assertEquals(calls, probe.writes)
            assertEquals("false", probe.raw)
            assertTrue(recovery.hasPending)
            acceptMembership(lease, project, probe.value, hide = false)
            recovery.restore(emptyList())
            assertEquals(AccentWriteState.RESTORED, recovery.state)
        }

    @Test
    fun `receipt recovery preserves a foreign exact value after the Registry default changes`() =
        onEdt {
            val probe = RegistryProbe("false")
            val lease = RootPathLease()
            val before = RootPathValue("true", false)
            val written = RootPathValue("false", true)
            val recovery = assertNotNull(lease.recoverReceipt(probe.value, before, written))
            probe.defaultRaw = "false"

            recovery.restore(emptyList())

            assertEquals("false", probe.raw)
            assertTrue(probe.writes.isEmpty())
            assertEquals(AccentWriteState.RELINQUISHED, recovery.state)
            assertFalse(recovery.hasPending)
        }

    @Test
    fun `receipt restore mutation then failure retains progress and queues callbacks until retry`() =
        onEdt {
            val probe = RegistryProbe("false")
            val lease = RootPathLease()
            val before = RootPathValue("true", false)
            val recovery =
                assertNotNull(lease.recoverReceipt(probe.value, before, RootPathValue("false", true)))
            var callbacks = 0
            probe.afterWrite = {
                assertTrue(lease.deferIfHeld { callbacks += 1 })
                error("Registry restore failed after mutation")
            }
            every { AyuIslandsSettings.getInstance() } throws
                AssertionError("Receipt recovery must not access settings")

            assertFailsWith<IllegalStateException> { recovery.restore(emptyList()) }

            assertEquals("true", probe.raw)
            assertEquals(before, recovery.lastWritten)
            assertEquals(AccentWriteState.OWNED, recovery.state)
            assertTrue(recovery.hasPending)
            assertTrue(posted.isEmpty())
            assertEquals(0, callbacks)
            val calls = probe.writes.toList()
            probe.afterWrite = {}
            recovery.restore(emptyList())

            assertEquals(calls, probe.writes, "Retry must acknowledge the already restored native value")
            assertEquals(AccentWriteState.RESTORED, recovery.state)
            assertFalse(recovery.hasPending)
            assertEquals(1, posted.size)
            posted.removeAt(0).run()
            assertEquals(1, callbacks)
            recovery.releaseResources()
            assertTrue(posted.isEmpty())
        }

    @Test
    fun `committed receipt cleanup performs no forward apply or Registry restoration`() =
        onEdt {
            val probe = RegistryProbe("false")
            val lease = RootPathLease()
            every { AyuIslandsSettings.getInstance() } throws AssertionError("Receipt cleanup must not access settings")
            val recovery =
                assertNotNull(
                    lease.recoverReceipt(probe.value, RootPathValue("true", false), RootPathValue("false", true)),
                )

            recovery.commit()
            recovery.commit()
            recovery.releaseResources()

            assertEquals("false", probe.raw)
            assertTrue(probe.writes.isEmpty())
            assertFalse(recovery.hasPending)
            assertFalse(lease.deferIfHeld { error("An accepted receipt cannot retain its hold") })
        }

    @Test
    fun `newer receipt on the same Registry key blocks restoration without acquiring a hold`() =
        onEdt {
            val probe = RegistryProbe("false")
            val lease = RootPathLease()
            val before = RootPathValue("true", false)
            val written = RootPathValue("false", true)
            val recovery = assertNotNull(lease.recoverReceipt(probe.value, before, written))
            val newer = assertNotNull(RootPathLease().recoverReceipt(probe.value, before, written))

            assertFailsWith<IllegalStateException> { recovery.restore(listOf(newer)) }

            assertEquals("false", probe.raw)
            assertTrue(probe.writes.isEmpty())
            assertTrue(recovery.hasPending)
            assertFalse(lease.deferIfHeld { error("A blocked receipt must not acquire a native hold") })
            newer.commit()
            recovery.restore(listOf(newer))
            assertEquals("true", probe.raw)
            assertEquals(AccentWriteState.RESTORED, recovery.state)
        }

    /** Accepts explicit fixture input through the production checkpoint, then records its runtime result. */
    private fun acceptMembership(
        lease: RootPathLease,
        project: Project,
        value: RegistryValue,
        hide: Boolean,
    ) {
        if (lease.deferIfHeld { acceptMembership(lease, project, value, hide) }) return
        val detached = detachedState()
        val checkpoint = lease.prepare(setOf(project), value, hide, detached)
        assertTrue(checkpoint.apply())
        checkpoint.commit()
        state.hasRootPathLease = detached.hasRootPathLease
        state.wasRootPathShown = detached.wasRootPathShown
        state.wasRootPathChanged = detached.wasRootPathChanged
    }

    @Test
    fun `cold accepted cleanup restores durable baseline and preserves foreign native values`() =
        onEdt {
            for (changed in listOf(false, true)) {
                for (foreign in listOf(false, true)) {
                    state.hasRootPathLease = true
                    state.wasRootPathShown = true
                    state.wasRootPathChanged = changed
                    val defaultRaw = if (changed) "false" else "true"
                    every { registry.getBundleValueOrNull(any()) } returns defaultRaw
                    val probe = RegistryProbe(if (foreign) "TRUE" else "false", defaultRaw)
                    val before = probe.writes.toList()
                    acceptMembership(RootPathLease(), mockk(), probe.value, hide = false)
                    assertEquals(if (foreign) "TRUE" else "true", probe.raw)
                    assertFalse(state.hasRootPathLease)
                    if (foreign) {
                        assertEquals(before, probe.writes)
                    } else {
                        assertEquals(if (changed) 0 else 1, probe.resets)
                    }
                }
            }
        }

    private fun detachedState(): AyuIslandsState =
        AyuIslandsState().apply {
            hasRootPathLease = state.hasRootPathLease
            wasRootPathShown = state.wasRootPathShown
            wasRootPathChanged = state.wasRootPathChanged
        }

    private fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)

    private class RegistryProbe(
        initial: String = "true",
        var defaultRaw: String = "true",
    ) {
        var raw = initial
        val writes = mutableListOf<String>()
        var resets = 0
        var afterWrite: () -> Unit = {}
        val value = mockk<RegistryValue>(relaxed = true)

        init {
            every { value.key } returns "project.tree.structure.show.url"
            every { value.asBoolean() } answers { raw.toBoolean() }
            every { value.asString() } answers { raw }
            every { value.isChangedFromDefault() } answers { raw != defaultRaw }
            every { value.setValue(any<Boolean>()) } answers { write(firstArg<Boolean>().toString()) }
            every { value.setValue(any<String>()) } answers { write(firstArg()) }
            every { value.resetToDefault() } answers {
                resets += 1
                write(defaultRaw)
            }
        }

        private fun write(next: String) {
            raw = next
            writes += next
            afterWrite()
        }
    }
}
