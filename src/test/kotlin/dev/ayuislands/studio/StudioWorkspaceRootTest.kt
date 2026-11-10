package dev.ayuislands.studio

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.registry.RegistryValue
import dev.ayuislands.projectview.RootPathLease
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.StudioEffectInput
import dev.ayuislands.studio.session.StudioNativeFacts
import dev.ayuislands.studio.session.StudioWorkspaceHost
import dev.ayuislands.studio.session.StudioWorkspaceRoot
import dev.ayuislands.studio.session.WorkspaceReferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import java.time.Instant
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StudioWorkspaceRootTest {
    private val origin = StudioProcess(41, Instant.ofEpochSecond(100))
    private val restarted = StudioProcess(42, Instant.ofEpochSecond(200))
    private lateinit var registry: RegistryValue
    private var raw = "TRUE"
    private var writes = 0
    private var failNextWrite = false

    @BeforeTest
    fun setUp() {
        mockkObject(Registry.Companion)
        val defaults = mockk<Registry>()
        every { Registry.getInstance() } returns defaults
        every { defaults.getBundleValueOrNull(any()) } returns "true"
        registry = mockk()
        every { registry.key } returns "project.tree.structure.show.url"
        every { registry.asString() } answers { raw }
        every { registry.asBoolean() } answers { raw.toBoolean() }
        every { registry.isChangedFromDefault() } answers { raw != "true" }
        every { registry.setValue(any<String>()) } answers { write(firstArg()) }
        every { registry.resetToDefault() } answers { write("true") }
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } throws IllegalStateException("Studio must use detached metadata")
    }

    @AfterTest
    fun tearDown() = unmockkAll()

    @Test
    fun `root capture is readonly and rollback preserves raw Registry spelling and dormant metadata`() =
        onEdt {
            val root = prepare()
            val captured = root.receipts
            assertEquals("TRUE", raw)
            assertEquals(0, writes)
            assertTrue(root.runtimeChanges.isEmpty())

            root.apply()

            assertEquals("false", raw)
            assertTrue(
                root.runtimeChanges.all {
                    it.address.field.name in
                        setOf("hasRootPathLease", "wasRootPathShown", "wasRootPathChanged")
                },
            )
            assertTrue(root.runtimeChanges.any { it.address.field.name == "hasRootPathLease" })
            root.prepareRestore(emptyList())
            assertEquals(captured.single(), root.receipts.first().copy(status = captured.single().status))
            root.restore(emptyList())
            assertEquals("TRUE", raw)
            assertTrue(root.runtimeChanges.isEmpty())
            assertTrue(root.receipts.all { it.status == RestoreStatus.RESTORED })
            verify(exactly = 0) { AyuIslandsSettings.getInstance() }
        }

    @Test
    fun `durable intent restores a native write with lost acknowledgement without reading Registry defaults`() =
        onEdt {
            every { Registry.getInstance().getBundleValueOrNull(any()) } throws
                AssertionError("Registry defaults are private")
            val root = prepare()
            val acknowledged = root.receipts

            root.apply()
            assertEquals("false", raw)
            val recovered =
                StudioWorkspaceRoot.recover(
                    acknowledged,
                    StudioNativeFacts(true, restarted),
                    RootPathLease(),
                    registry,
                )
            recovered.prepareRestore(emptyList())
            recovered.restore(emptyList())

            assertEquals("TRUE", raw)
            assertTrue(registry.isChangedFromDefault())
            assertTrue(recovered.receipts.all { it.status == RestoreStatus.RESTORED })
        }

    @Test
    fun `same process missing provenance or native owner never performs portable recovery writes`() =
        onEdt {
            val root = prepare()
            root.apply()
            val evidence = root.receipts
            val beforeWrites = writes
            for (process in listOf(origin, null, StudioProcess(origin.pid, null))) {
                val recovered =
                    StudioWorkspaceRoot.recover(
                        evidence,
                        StudioNativeFacts(true, process),
                        RootPathLease(),
                        registry,
                    )
                recovered.prepareRestore(emptyList())
                recovered.restore(emptyList())
                assertTrue(recovered.receipts.all { it.status == RestoreStatus.UNAVAILABLE })
            }
            val unavailable = StudioWorkspaceRoot.recover(evidence, StudioNativeFacts(true, restarted), null, null)
            unavailable.restore(emptyList())
            assertTrue(unavailable.receipts.all { it.status == RestoreStatus.UNAVAILABLE })
            assertEquals(beforeWrites, writes)
            assertEquals("false", raw)
        }

    @Test
    fun `restart accepts already restored values and preserves foreign writes`() =
        onEdt {
            val root = prepare()
            root.apply()
            val evidence = root.receipts
            for ((current, status) in listOf("TRUE" to RestoreStatus.RESTORED, "manual" to RestoreStatus.FOREIGN)) {
                raw = current
                val beforeWrites = writes
                val recovered =
                    StudioWorkspaceRoot.recover(
                        evidence,
                        StudioNativeFacts(true, restarted),
                        RootPathLease(),
                        registry,
                    )
                recovered.prepareRestore(emptyList())
                recovered.restore(emptyList())
                assertTrue(recovered.receipts.all { it.status == status })
                assertEquals(current, raw)
                assertEquals(beforeWrites, writes)
            }
        }

    @Test
    fun `acknowledged exact observation cannot regain ownership through its earlier write intent`() =
        onEdt {
            val root = prepare()
            root.apply()
            val acknowledged = root.receipts
            val beforeWrites = writes
            every { registry.isChangedFromDefault() } returns false
            val recovered =
                StudioWorkspaceRoot.recover(
                    acknowledged,
                    StudioNativeFacts(true, restarted),
                    RootPathLease(),
                    registry,
                )

            recovered.prepareRestore(emptyList())
            recovered.restore(emptyList())

            assertEquals("false", raw)
            assertEquals(beforeWrites, writes)
            assertTrue(recovered.receipts.all { it.status == RestoreStatus.FOREIGN })
        }

    @Test
    fun `crash after rollback acknowledgement still recognizes the forward native value`() =
        onEdt {
            val root = prepare()
            root.apply()
            val applied = root.receipts
            root.prepareRestore(emptyList())
            val acknowledged = root.receipts
            assertTrue(acknowledged.size > applied.size)
            assertEquals("false", raw)
            val recovered =
                StudioWorkspaceRoot.recover(
                    acknowledged,
                    StudioNativeFacts(true, restarted),
                    RootPathLease(),
                    registry,
                )

            recovered.prepareRestore(emptyList())
            recovered.restore(emptyList())

            assertEquals("TRUE", raw)
            assertTrue(recovered.receipts.all { it.status == RestoreStatus.RESTORED })
            assertTrue(recovered.runtimeChanges.isEmpty())
        }

    @Test
    fun `mutating restore failure retains evidence and retries without losing its native owner`() =
        onEdt {
            val root = prepare()
            root.apply()
            val recovered =
                StudioWorkspaceRoot.recover(
                    root.receipts,
                    StudioNativeFacts(true, restarted),
                    RootPathLease(),
                    registry,
                )
            recovered.prepareRestore(emptyList())
            failNextWrite = true
            assertFailsWith<IllegalStateException> { recovered.restore(emptyList()) }
            assertEquals("TRUE", raw)
            assertTrue(
                recovered.receipts.any { it.status == RestoreStatus.OWNED || it.status == RestoreStatus.PENDING },
            )
            val beforeRetry = writes
            recovered.prepareRestore(emptyList())
            recovered.restore(emptyList())
            assertEquals(beforeRetry, writes)
            assertTrue(recovered.receipts.all { it.status == RestoreStatus.RESTORED })
        }

    @Test
    fun `newer root owner blocks rollback before any native setter`() =
        onEdt {
            val root = prepare()
            root.apply()
            val receipt = root.receipts.first()
            val newer = receipt.copy(identity = receipt.identity.copy(ownerToken = "workspace/newer/0"))
            val beforeWrites = writes

            assertFailsWith<IllegalStateException> { root.prepareRestore(listOf(newer)) }
            assertFailsWith<IllegalStateException> { root.restore(listOf(newer)) }

            assertEquals("false", raw)
            assertEquals(beforeWrites, writes)
        }

    @Test
    fun `accepted journal cleanup releases recovered handles without replaying or restoring native values`() =
        onEdt {
            val root = prepare()
            root.apply()
            val evidence = root.receipts
            val beforeWrites = writes
            for (isAvailable in listOf(true, false)) {
                val recovered =
                    StudioWorkspaceRoot.recover(
                        evidence,
                        StudioNativeFacts(true, restarted),
                        if (isAvailable) RootPathLease() else null,
                        if (isAvailable) registry else null,
                    )

                recovered.accept()
                recovered.accept()

                assertEquals("false", raw)
                assertEquals(beforeWrites, writes)
                assertTrue(recovered.runtimeChanges.isEmpty())
            }
            verify(exactly = 0) { AyuIslandsSettings.getInstance() }
        }

    private fun prepare(): StudioWorkspaceRoot {
        val host = mockk<StudioWorkspaceHost>()
        every { host.rootLease() } returns RootPathLease()
        every { host.rootRegistry() } returns registry
        return checkNotNull(
            StudioWorkspaceRoot.prepare(
                StudioEffectInput(sessionDocument(), StudioTarget.Global, StudioList(emptyList())),
                WorkspaceReferences("workspace/operation/0", origin),
                host,
                setOf(mockk<Project>()),
                hidePath = true,
            ),
        )
    }

    private fun write(value: String) {
        raw = value
        writes++
        if (failNextWrite) {
            failNextWrite = false
            error("Registry listener failed after mutation")
        }
    }

    private fun onEdt(action: () -> Unit) = SwingUtilities.invokeAndWait(action)
}
