package dev.ayuislands.settings.mappings

import dev.ayuislands.accent.AccentApplicator
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behaviour lock for [AyuIslandsQuickSwitcherGroupBuilder].
 *
 * The builder mirrors [OverridesGroupBuilder]'s `buildGroup` / `isModified` / `apply` /
 * `reset` shape. Tests exercise the lifecycle round-trip via the public surface plus
 * reflection on the `pendingEnabled` / `storedEnabled` fields (the alternative — synthesising
 * a real `Panel` outside the IDE harness — pulls in the Kotlin UI DSL bootstrap and is
 * heavier than the field probe).
 *
 * Pattern G adjacency lock: `apply()` MUST NOT invoke an accent cascade
 * (`AccentApplicator.applyForFocusedProject` / `applyFromHexString`) — that would double-apply
 * on top of the BGT-tick cascade that already polls `state.quickSwitcherWidgetEnabled`.
 */
class AyuIslandsQuickSwitcherGroupBuilderTest {
    private lateinit var realState: AyuIslandsState

    @BeforeEach
    fun setUp() {
        realState = AyuIslandsState()
        mockkObject(AyuIslandsSettings.Companion)
        val mockSettings = mockk<AyuIslandsSettings>(relaxed = true)
        every { mockSettings.state } returns realState
        every { AyuIslandsSettings.getInstance() } returns mockSettings
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `isModified is false on a fresh builder before any user edit`() {
        val builder = AyuIslandsQuickSwitcherGroupBuilder()
        assertFalse(
            builder.isModified(),
            "A fresh builder with no pending edit must not report modified",
        )
    }

    @Test
    fun `isModified flips true when pending diverges from stored`() {
        realState.quickSwitcherWidgetEnabled = true
        val builder = AyuIslandsQuickSwitcherGroupBuilder()
        setPrivate(builder, "storedEnabled", true)
        setPrivate(builder, "pendingEnabled", false)
        assertTrue(builder.isModified(), "pending=false vs stored=true must report modified")
    }

    @Test
    fun `apply writes pending value into AyuIslandsSettings state and clears modified flag`() {
        realState.quickSwitcherWidgetEnabled = true
        val builder = AyuIslandsQuickSwitcherGroupBuilder()
        setPrivate(builder, "storedEnabled", true)
        setPrivate(builder, "pendingEnabled", false)
        assertTrue(builder.isModified())

        builder.apply()

        assertEquals(
            false,
            realState.quickSwitcherWidgetEnabled,
            "apply must persist pendingEnabled into state.quickSwitcherWidgetEnabled",
        )
        assertFalse(
            builder.isModified(),
            "After apply, storedEnabled catches up to pendingEnabled — no longer modified",
        )
    }

    @Test
    fun `reset reverts pending back to stored without touching state`() {
        realState.quickSwitcherWidgetEnabled = false
        val builder = AyuIslandsQuickSwitcherGroupBuilder()
        setPrivate(builder, "storedEnabled", false)
        setPrivate(builder, "pendingEnabled", true)
        assertTrue(builder.isModified())

        builder.reset()

        assertFalse(builder.isModified(), "reset must restore pendingEnabled to stored value")
        assertEquals(
            false,
            getPrivateBool(builder, "pendingEnabled"),
            "pendingEnabled returns to stored=false",
        )
        assertEquals(
            false,
            realState.quickSwitcherWidgetEnabled,
            "reset must NOT mutate AyuIslandsSettings state — only the in-memory pending field",
        )
    }

    @Test
    fun `apply does not trigger an accent cascade (Pattern G no double-apply)`() {
        // The chip's BGT tick (~500ms cadence) polls state.quickSwitcherWidgetEnabled
        // and re-applies on its own, so apply() only writes state. A manual
        // AccentApplicator call here would double-apply on the next tick.
        mockkObject(AccentApplicator)
        realState.quickSwitcherWidgetEnabled = true
        val builder = AyuIslandsQuickSwitcherGroupBuilder()
        setPrivate(builder, "storedEnabled", true)
        setPrivate(builder, "pendingEnabled", false)

        builder.apply()

        verify(exactly = 0) { AccentApplicator.applyForFocusedProject(any()) }
        verify(exactly = 0) { AccentApplicator.applyFromHexString(any()) }
    }

    private companion object {
        fun setPrivate(
            target: Any,
            fieldName: String,
            value: Boolean,
        ) {
            val field = target.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }
            field.setBoolean(target, value)
        }

        fun getPrivateBool(
            target: Any,
            fieldName: String,
        ): Boolean {
            val field = target.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }
            return field.getBoolean(target)
        }
    }
}
