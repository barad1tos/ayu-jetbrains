package dev.ayuislands.settings.mappings

import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behaviour lock for [AyuIslandsQuickSwitcherGroupBuilder] (Phase 48 Wave 5 — D-17 extraction).
 *
 * The builder mirrors [OverridesGroupBuilder]'s `buildGroup` / `isModified` / `apply` /
 * `reset` shape. Tests exercise the lifecycle round-trip via the public surface plus
 * reflection on the `pendingEnabled` / `storedEnabled` fields (the alternative — synthesising
 * a real `Panel` outside the IDE harness — pulls in the Kotlin UI DSL bootstrap and is
 * heavier than the field probe).
 *
 * Pattern G adjacency lock (test #5): the builder source MUST NOT call `AccentApplicator` /
 * `LafManager` / `applyFromHexString` — those would double-apply on top of the BGT-tick
 * cascade that already polls `state.quickSwitcherWidgetEnabled`.
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
    fun `builder source has no AccentApplicator or LafManager or applyFromHexString (Pattern G)`() {
        // Pattern G adjacency lock: the builder's `apply()` MUST NOT trigger a manual
        // cascade — the chip's `update()` BGT tick polls `state.quickSwitcherWidgetEnabled`
        // on its own (~500 ms cadence), so the state write is sufficient. A direct
        // `AccentApplicator.applyForFocusedProject` or `LafManager` call from here would
        // double-apply on the next tick and risk a glitch.
        val source =
            Files.readString(
                Paths.get(
                    "src/main/kotlin/dev/ayuislands/settings/mappings/AyuIslandsQuickSwitcherGroupBuilder.kt",
                ),
            )
        assertFalse(source.contains("AccentApplicator"), "Builder must not call AccentApplicator")
        assertFalse(
            source.contains("LafManager.getInstance()"),
            "Builder must not call LafManager.getInstance()",
        )
        assertFalse(
            source.contains("applyFromHexString"),
            "Builder must not call applyFromHexString — cascade owns propagation",
        )
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
