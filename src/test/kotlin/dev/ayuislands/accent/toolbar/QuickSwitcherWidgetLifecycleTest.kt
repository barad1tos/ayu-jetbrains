package dev.ayuislands.accent.toolbar

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.MessageBusConnection
import dev.ayuislands.accent.AccentChangedTopic
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AyuVariant
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Lifecycle integrity test. Simulates 10 install-disable-uninstall cycles on
 * [QuickSwitcherChipComponent]. Each cycle:
 *   1. `chip.addNotify()` - should open ONE [MessageBusConnection] via
 *      `application.messageBus.connect(chip)`.
 *   2. `chip.removeNotify()` - should call `connection.disconnect()` exactly once.
 *
 * After 10 cycles, MockK verifies:
 *   - `application.messageBus.connect(chip)` was called exactly 10 times.
 *   - `connection.disconnect()` was called exactly 10 times.
 *   - BOTH topic subscriptions happened exactly 10 times each
 *     ([AccentChangedTopic.TOPIC] AND [ApplicationActivationListener.TOPIC] -
 *     the chip wires both; see [QuickSwitcherChipComponent.addNotify]).
 *
 * Pattern E (per-instance `Disposable` parent) is the contract; a leak appears as
 * connect-count > disconnect-count, OR as either topic's subscribe count exceeding 10.
 *
 * Diagnostic shape on failure: the mid-loop `assertEquals` calls log the cycle index at
 * which the delta first diverged, so "Cycle 7: expected 7 disconnects after removeNotify,
 * got 6" clearly points at cycle 7 as the leak source.
 *
 * Note: the test matches `messageBus.connect(any<Disposable>())` because
 * `MessageBus.connect(Disposable)` is the JVM overload Kotlin resolves to
 * (verified via `javap` on `com.intellij.util.messages.MessageBus`). The chip is
 * both `JComponent` AND `Disposable`; matching against `Disposable` aligns with
 * the platform interface AND the sibling test `QuickSwitcherChipComponentTest`.
 *
 * `AyuVariant.detect()` is stubbed to `null` so the tail of `addNotify` -
 * `refreshFromFocusedProject()` - early-returns before touching `LafManager`, the
 * `EditorColorsManager`, or `AccentResolver`. The lifecycle contract under test
 * is purely the connection bookkeeping; the refresh body is exercised by the
 * sibling `QuickSwitcherChipComponentTest`.
 */
class QuickSwitcherWidgetLifecycleTest {
    private val application = mockk<Application>(relaxed = true)
    private val messageBus = mockk<MessageBus>(relaxed = true)
    private val connection = mockk<MessageBusConnection>(relaxed = true)

    @BeforeEach
    fun setUp() {
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns application
        every { application.messageBus } returns messageBus
        every { messageBus.connect(any<Disposable>()) } returns connection

        // `addNotify` ends with `refreshFromFocusedProject()` which calls
        // `AyuVariant.detect()` -> `LafManager.getInstance()`. Without booting
        // the IntelliJ application, the service lookup throws `ClassCastException`.
        // Stubbing `detect()` to `null` short-circuits the refresh body at the
        // first guard (`val variant = AyuVariant.detect() ?: return`) - the
        // lifecycle path under test is the connect/disconnect pair around the
        // refresh, not the refresh body itself.
        mockkObject(AyuVariant.Companion)
        every { AyuVariant.detect() } returns null
        mockkObject(AccentContext.Companion)
        every { AccentContext.detect() } returns null
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `10-cycle install-disable-uninstall produces zero leaked Disposables`() {
        var connectCount = 0
        var disconnectCount = 0

        every { messageBus.connect(any<Disposable>()) } answers {
            connectCount++
            connection
        }
        every { connection.disconnect() } answers {
            disconnectCount++
        }

        val chip = QuickSwitcherChipComponent()
        repeat(CYCLES) { cycle ->
            chip.addNotify()
            // Sanity check mid-loop - at cycle N, we expect (N+1) connects and N disconnects
            // (since removeNotify has not run yet for this cycle's addNotify).
            assertEquals(
                cycle + 1,
                connectCount,
                "Cycle ${cycle + 1}: expected ${cycle + 1} connects after addNotify, got $connectCount " +
                    "- Pattern E leak appeared during install phase",
            )
            chip.removeNotify()
            assertEquals(
                cycle + 1,
                disconnectCount,
                "Cycle ${cycle + 1}: expected ${cycle + 1} disconnects after removeNotify, got $disconnectCount " +
                    "- Pattern E leak appeared during uninstall phase",
            )
        }

        assertEquals(CYCLES, connectCount, "Final connect count != $CYCLES")
        assertEquals(CYCLES, disconnectCount, "Final disconnect count != $CYCLES")

        // Verify BOTH topics subscribed each cycle (chip contract - see
        // `QuickSwitcherChipComponent.addNotify`). The explicit `verify(exactly = CYCLES)`
        // is deliberate: silent consolidation of topics into one subscribe call
        // would still fail the test, which is the strictness contract this lock intends.
        verify(exactly = CYCLES) {
            connection.subscribe(eq(AccentChangedTopic.TOPIC), any())
        }
        verify(exactly = CYCLES) {
            connection.subscribe(eq(ApplicationActivationListener.TOPIC), any())
        }
    }

    @Test
    fun `addNotify is idempotent - calling twice in a row subscribes only once`() {
        // The chip's `connection != null` early-return protects against
        // double-subscribe within one widget lifetime.
        var connectCount = 0
        every { messageBus.connect(any<Disposable>()) } answers {
            connectCount++
            connection
        }

        val chip = QuickSwitcherChipComponent()
        chip.addNotify()
        chip.addNotify() // second call should be a no-op

        assertEquals(
            1,
            connectCount,
            "addNotify called twice produced $connectCount connections - idempotency broken",
        )
    }

    @Test
    fun `removeNotify disposes the connection parent (Pattern E)`() {
        // The chip is not Disposable itself; instead it owns a dedicated
        // `connectionParent` Disposable whose lifetime equals the message-bus
        // subscription lifetime. `removeNotify` disposes that holder AND calls
        // disconnect. Either path alone would be sufficient at the platform
        // level; both run for belt-and-braces symmetry.
        val chip = QuickSwitcherChipComponent()
        chip.addNotify()
        chip.removeNotify()

        verify(exactly = 1) { connection.disconnect() }
    }

    private companion object {
        const val CYCLES = 10
    }
}
