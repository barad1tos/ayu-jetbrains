package dev.ayuislands.accent

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.MessageBusConnection
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.awt.Color
import javax.swing.UIManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Unit tests for [ChromeBaseColors] — the stock-color snapshot that blend callers
 * consult so cumulative tint application always blends from the true base, not
 * from a previously tinted value.
 */
class ChromeBaseColorsTest {
    private val statusBarStock = Color(0x1F, 0x24, 0x30)
    private val navBarStock = Color(0x25, 0x2E, 0x38)
    private val mutatedStatusBar = Color(0x80, 0x80, 0x80)

    @BeforeTest
    fun setUp() {
        mockkStatic(ApplicationManager::class)
        val app = mockk<Application>(relaxed = true)
        val bus = mockk<MessageBus>(relaxed = true)
        val connection = mockk<MessageBusConnection>(relaxed = true)
        every { ApplicationManager.getApplication() } returns app
        every { app.messageBus } returns bus
        // ChromeBaseColors now anchors its MessageBusConnection to the Application
        // Disposable (Phase 40 Round 3 C-4), so the mock must match `connect(app)`
        // as well as the bare `connect()` overload to stay forward-compatible.
        every { bus.connect() } returns connection
        every { bus.connect(any<Disposable>()) } returns connection
        every { connection.subscribe(any(), any()) } returns Unit

        mockkStatic(UIManager::class)
        every { UIManager.getColor("StatusBar.background") } returns statusBarStock
        every { UIManager.getColor("NavBar.background") } returns navBarStock
        every { UIManager.getColor("Missing.key") } returns null

        // Clear whatever a previous test run may have captured. ChromeBaseColors
        // is an object singleton — state leaks across tests without this reset.
        ChromeBaseColors.refresh()
    }

    @AfterTest
    fun tearDown() {
        ChromeBaseColors.refresh()
        unmockkAll()
    }

    @Test
    fun `get captures current UIManager value on first access`() {
        val captured = ChromeBaseColors.get("StatusBar.background")
        assertEquals(statusBarStock, captured)
    }

    @Test
    fun `get returns cached value even after UIManager mutates — this is the core invariant`() {
        // First access captures the stock color.
        val first = ChromeBaseColors.get("StatusBar.background")
        assertEquals(statusBarStock, first)

        // Simulate a later apply having written a tinted color into UIManager.
        every { UIManager.getColor("StatusBar.background") } returns mutatedStatusBar

        // Subsequent gets must STILL return the first-captured stock value — this
        // is the whole point of the snapshot. A naive re-read would compound
        // saturation per apply.
        val second = ChromeBaseColors.get("StatusBar.background")
        assertEquals(statusBarStock, second)
        assertNotSame(mutatedStatusBar, second)
        assertSame(first, second)
    }

    @Test
    fun `refresh clears the snapshot so next get re-captures from UIManager`() {
        ChromeBaseColors.get("StatusBar.background")
        every { UIManager.getColor("StatusBar.background") } returns mutatedStatusBar

        ChromeBaseColors.refresh()

        val reCaptured = ChromeBaseColors.get("StatusBar.background")
        assertEquals(mutatedStatusBar, reCaptured)
    }

    @Test
    fun `get returns null when UIManager has no entry for the key`() {
        assertNull(ChromeBaseColors.get("Missing.key"))
    }

    @Test
    fun `get does not cache null — a later UIManager entry is picked up on the next call`() {
        // First call: key missing → returns null, nothing cached.
        assertNull(ChromeBaseColors.get("Lazy.key"))
        assertNull(ChromeBaseColors.get("Lazy.key"))

        // UIManager starts serving the key. No refresh required.
        every { UIManager.getColor("Lazy.key") } returns Color.RED
        assertEquals(Color.RED, ChromeBaseColors.get("Lazy.key"))
    }

    // --- Round 3 hotfix regression tests (C5, C6) ---
    //
    // C5 locks that repeated `get` calls for a key UIManager does not serve
    // stay silent after the first miss — the `missingKeyLogged` latch gate
    // introduced in Round 3 C-3 must consistently return null for every
    // follow-up read without flipping into a cached state.
    //
    // C6 locks the "clear latch BEFORE snapshot" ordering from Round 1
    // MEDIUM-2 / Round 3 C-3: after `refresh()`, the next `get` must capture
    // the current UIManager value (including a newly-populated key) AND
    // subsequent `get` calls must return that same cached snapshot even if
    // UIManager mutates afterwards.

    @Test
    fun `get returns null consistently across repeated calls on a missing key`() {
        // UIManager returns null for "Missing.key" — see setUp. Repeated
        // reads must all return null without caching any sentinel.
        assertNull(ChromeBaseColors.get("Missing.key"))
        assertNull(ChromeBaseColors.get("Missing.key"))
        assertNull(ChromeBaseColors.get("Missing.key"))
    }

    @Test
    fun `refresh clears the missing-key latch so a newly-populated key is picked up`() {
        // UIManager starts with no entry for Lazy.key.
        every { UIManager.getColor("Lazy.key") } returns null
        assertNull(ChromeBaseColors.get("Lazy.key"))

        // Clear the latch + snapshot. Now UIManager is populated.
        ChromeBaseColors.refresh()
        every { UIManager.getColor("Lazy.key") } returns Color.RED

        val captured = ChromeBaseColors.get("Lazy.key")
        assertEquals(Color.RED, captured)

        // Snapshot invariant: once captured, a later UIManager mutation
        // must NOT bleed through. This locks the "latch cleared before
        // snapshot" ordering — refresh must first drop the latch, then
        // drop the snapshot, so the next get re-captures from UIManager
        // and the cache freezes that value for subsequent reads.
        every { UIManager.getColor("Lazy.key") } returns Color.BLUE
        assertEquals(Color.RED, ChromeBaseColors.get("Lazy.key"))
    }

    @Test
    fun `independent keys snapshot independently`() {
        val status = ChromeBaseColors.get("StatusBar.background")
        val navBar = ChromeBaseColors.get("NavBar.background")

        assertEquals(statusBarStock, status)
        assertEquals(navBarStock, navBar)

        // Mutating one does not disturb the other.
        every { UIManager.getColor("StatusBar.background") } returns mutatedStatusBar
        assertEquals(navBarStock, ChromeBaseColors.get("NavBar.background"))
        assertEquals(statusBarStock, ChromeBaseColors.get("StatusBar.background"))
    }
}
