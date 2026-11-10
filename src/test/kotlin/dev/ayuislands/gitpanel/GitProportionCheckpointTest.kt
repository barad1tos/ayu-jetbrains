package dev.ayuislands.gitpanel

import com.intellij.ide.util.PropertiesComponent
import com.intellij.ui.JBSplitter
import dev.ayuislands.accent.AccentWriteState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitProportionCheckpointTest {
    @BeforeTest
    fun setUp() = mockkStatic(PropertiesComponent::class)

    @AfterTest
    fun tearDown() = unmockkAll()

    @Test
    fun `default elision rolls back exact raw values without capture or acceptance writes`() =
        withProperties { storage ->
            for (before in listOf(null, "", "0.20000000", "unparsed user value")) {
                storage.values[KEY] = before
                val splitter = PersistedSplitter(KEY, 0.2f, 0.3f)
                val property = GitProportionCheckpoint(KEY, storage.properties)
                val geometry = GitSplitterCheckpoint(splitter, 0.3f, { true }, property)
                val captured = property.record
                val writes = storage.writes
                assertEquals(before, captured.before)
                assertEquals(setOf(null, "0.3"), captured.plans.single().forwardOutcomes)
                assertEquals(setOf(null, "0.2"), captured.plans.single().rollbackOutcomes)
                assertTrue(captured.observations.isEmpty())
                assertEquals(0, splitter.writes)
                assertEquals(writes, storage.writes)

                assertTrue(geometry.apply())
                assertEquals(null, storage.values[KEY])
                assertTrue(property.record.observations.isNotEmpty())
                assertTrue(captured.observations.isEmpty(), "Acknowledged facts must remain immutable")
                property.prepareRestore()
                geometry.restore()
                property.restore()
                assertEquals(0.2f.toRawBits(), splitter.proportion.toRawBits())
                assertEquals(before, storage.values[KEY])
                val restoredWrites = storage.writes
                property.restore()
                property.commit()
                assertEquals(restoredWrites, storage.writes)
            }

            storage.values[KEY] = "0.20000000"
            val acceptedSplitter = PersistedSplitter(KEY, 0.2f, 0.5f)
            val acceptedProperty = GitProportionCheckpoint(KEY, storage.properties)
            val acceptedGeometry = GitSplitterCheckpoint(acceptedSplitter, 0.3f, { true }, acceptedProperty)
            assertTrue(acceptedGeometry.apply())
            val acceptedWrites = storage.writes
            acceptedGeometry.commit()
            acceptedProperty.commit()
            acceptedProperty.commit()
            assertEquals("0.3", storage.values[KEY])
            assertEquals(acceptedWrites, storage.writes)
            assertEquals(1, acceptedSplitter.writes)
        }

    @Test
    fun `partial native and raw restoration failures retain exact retry evidence`() =
        withProperties { storage ->
            storage.values[KEY] = "0.20000000"
            val splitter = PersistedSplitter(KEY, 0.2f, 0.5f)
            val property = GitProportionCheckpoint(KEY, storage.properties)
            val geometry = GitSplitterCheckpoint(splitter, 0.3f, { true }, property)
            splitter.failAfterWrite = true
            assertFailsWith<IllegalStateException> { geometry.apply() }
            assertEquals("0.3", storage.values[KEY])
            assertTrue(property.record.observations.isNotEmpty())

            val applied = property.record
            property.prepareRestore()
            assertFalse(applied.isRestorePrepared)
            assertTrue(property.record.isRestorePrepared)
            splitter.failAfterWrite = true
            assertFailsWith<IllegalStateException> { geometry.restore() }
            assertEquals("0.2", storage.values[KEY])
            val nativeWrites = splitter.writes
            geometry.restore()
            assertEquals(nativeWrites, splitter.writes)

            storage.failAfterWrite = true
            assertFailsWith<IllegalStateException> { property.restore() }
            assertEquals("0.20000000", storage.values[KEY])
            val rawWrites = storage.writes
            property.restore()
            assertEquals(rawWrites, storage.writes)
            assertEquals(AccentWriteState.RESTORED, property.record.state)
        }

    @Test
    fun `foreign raw value fences virtual setters while a changed key leaves original persistence recoverable`() =
        withProperties { storage ->
            storage.values[KEY] = "0.20000000"
            val splitter = PersistedSplitter(KEY, 0.2f, 0.5f)
            val property = GitProportionCheckpoint(KEY, storage.properties)
            val geometry = GitSplitterCheckpoint(splitter, 0.3f, { true }, property)
            assertTrue(geometry.apply())
            storage.values[KEY] = "foreign raw value"
            property.prepareRestore()
            val writes = splitter.writes
            geometry.restore()
            property.restore()
            assertEquals(writes, splitter.writes)
            assertEquals(0.3f.toRawBits(), splitter.proportion.toRawBits())
            assertEquals("foreign raw value", storage.values[KEY])
            assertEquals(AccentWriteState.RELINQUISHED, property.record.state)

            storage.values[KEY] = "0.20000000"
            val replaced = PersistedSplitter(KEY, 0.2f, 0.5f)
            val retained = GitProportionCheckpoint(KEY, storage.properties)
            val replacedGeometry = GitSplitterCheckpoint(replaced, 0.3f, { true }, retained)
            assertTrue(replacedGeometry.apply())
            replaced.splitterProportionKey = OTHER_KEY
            storage.values[OTHER_KEY] = "foreign key value"
            retained.prepareRestore()
            replacedGeometry.restore()
            retained.restore()
            assertEquals(1, replaced.writes)
            assertEquals("0.20000000", storage.values[KEY])
            assertEquals("foreign key value", storage.values[OTHER_KEY])
        }

    @Test
    fun `shared application key settles only after all independent geometries are restored`() =
        withProperties { storage ->
            storage.values[KEY] = "0.20000000"
            val property = GitProportionCheckpoint(KEY, storage.properties)
            val first = PersistedSplitter(KEY, 0.2f, 0.5f)
            val second = PersistedSplitter(KEY, 0.7f, 0.5f)
            val firstGeometry = GitSplitterCheckpoint(first, 0.3f, { true }, property)
            val secondGeometry = GitSplitterCheckpoint(second, 0.6f, { true }, property)
            val captured = property.record
            assertEquals(2, captured.plans.size)
            assertTrue(firstGeometry.apply())
            assertTrue(secondGeometry.apply())
            assertEquals("0.6", storage.values[KEY])

            property.prepareRestore()
            secondGeometry.restore()
            assertEquals("0.7", storage.values[KEY])
            firstGeometry.restore()
            assertEquals("0.2", storage.values[KEY])
            property.restore()
            assertEquals("0.20000000", storage.values[KEY])
            assertEquals(0.2f.toRawBits(), first.proportion.toRawBits())
            assertEquals(0.7f.toRawBits(), second.proportion.toRawBits())
            assertTrue(captured.observations.isEmpty())
        }

    @Test
    fun `durable raw recovery needs no native splitter and preserves foreign encodings`() =
        withProperties { storage ->
            for (current in listOf(null, "0.3", "0.3000", "foreign")) {
                storage.values[KEY] = current
                val recovery =
                    GitProportionCheckpoint.recover(
                        KEY,
                        "0.20000000",
                        setOf(null, "0.3"),
                        storage.properties,
                    )
                val beforeWrites = storage.writes
                assertEquals(current, storage.values[KEY])
                assertEquals(beforeWrites, storage.writes)
                recovery.prepareRestore()
                recovery.restore()
                assertEquals(if (current == null || current == "0.3") "0.20000000" else current, storage.values[KEY])
                val restoredWrites = storage.writes
                recovery.restore()
                assertEquals(restoredWrites, storage.writes)
            }

            storage.values[KEY] = "0.3"
            val absent = GitProportionCheckpoint.recover(KEY, null, setOf("0.3"), storage.properties)
            absent.prepareRestore()
            absent.restore()
            assertFalse(storage.values.containsKey(KEY), "An absent before-image must not become a string value")
        }

    private fun withProperties(action: (ProportionStorage) -> Unit) {
        SwingUtilities.invokeAndWait {
            val storage = ProportionStorage()
            every { PropertiesComponent.getInstance() } returns storage.properties
            action(storage)
        }
    }

    private companion object {
        const val KEY = "Git.Log.Proportion"
        const val OTHER_KEY = "Git.Log.Replacement"
    }
}

internal class ProportionStorage {
    val values = mutableMapOf<String, String?>()
    val properties = mockk<PropertiesComponent>()
    var writes = 0
        private set
    var failAfterWrite = false

    init {
        every { properties.getValue(any()) } answers { values[firstArg()] }
        every { properties.isValueSet(any()) } answers { values[firstArg()] != null }
        every { properties.setValue(any(), any<String>()) } answers { write(firstArg(), secondArg()) }
        every { properties.setValue(any(), null as String?) } answers { write(firstArg(), null) }
        every { properties.unsetValue(any()) } answers { write(firstArg(), null) }
        every { properties.setValue(any(), any<Float>(), any<Float>()) } answers {
            val value = secondArg<Float>()
            write(firstArg(), if (value == thirdArg<Float>()) null else value.toString())
        }
    }

    private fun write(
        key: String,
        value: String?,
    ) {
        if (value == null) values.remove(key) else values[key] = value
        writes++
        if (failAfterWrite) {
            failAfterWrite = false
            error("Property changed before failure")
        }
    }
}

internal class PersistedSplitter(
    key: String,
    before: Float,
    defaultProportion: Float,
) : JBSplitter(defaultProportion) {
    var writes = 0
        private set
    var failAfterWrite = false

    init {
        firstComponent = JPanel()
        secondComponent = JPanel()
        proportion = before
        splitterProportionKey = key
        writes = 0
    }

    override fun setProportion(proportion: Float) {
        super.setProportion(proportion)
        writes++
        if (failAfterWrite) {
            failAfterWrite = false
            error("Splitter changed before failure")
        }
    }
}
