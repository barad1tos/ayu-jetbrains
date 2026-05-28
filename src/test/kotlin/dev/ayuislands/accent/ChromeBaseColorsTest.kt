package dev.ayuislands.accent

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.messages.MessageBus
import com.intellij.util.messages.MessageBusConnection
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
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
 * Unit tests for [ChromeBaseColors] - the stock-color snapshot that blend callers
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
        val parentDisposable = mockk<ChromeBaseColorsLifecycle>(relaxed = true)
        every { ApplicationManager.getApplication() } returns app
        every { app.getService(ChromeBaseColorsLifecycle::class.java) } returns parentDisposable
        every { app.messageBus } returns bus
        // ChromeBaseColors anchors its MessageBusConnection to a plugin-owned
        // service Disposable (Pattern E), so the mock must match the Disposable
        // overload rather than the banned `connect(Application)` shape.
        every { bus.connect() } returns connection
        every { bus.connect(any<Disposable>()) } returns connection
        every { connection.subscribe(any(), any()) } returns Unit

        mockkStatic(UIManager::class)
        every { UIManager.getColor("StatusBar.background") } returns statusBarStock
        every { UIManager.getColor("NavBar.background") } returns navBarStock
        every { UIManager.getColor("Missing.key") } returns null

        // Clear whatever a previous test run may have captured. ChromeBaseColors
        // is an object singleton - state leaks across tests without this reset.
        ChromeBaseColors.refresh()
    }

    @AfterTest
    fun tearDown() {
        ChromeBaseColors.refresh()
        unmockkAll()
    }

    @Test
    fun `get captures current UIManager value on first access`() {
        val captured = ChromeBaseColors["StatusBar.background"]
        assertEquals(statusBarStock, captured)
    }

    @Test
    fun `get returns cached value even after UIManager mutates - this is the core invariant`() {
        // First access captures the stock color.
        val first = ChromeBaseColors["StatusBar.background"]
        assertEquals(statusBarStock, first)

        // Simulate a later apply having written a tinted color into UIManager.
        every { UIManager.getColor("StatusBar.background") } returns mutatedStatusBar

        // Subsequent gets must STILL return the first-captured stock value - this
        // is the whole point of the snapshot. A naive re-read would compound
        // saturation per apply.
        val second = ChromeBaseColors["StatusBar.background"]
        assertEquals(statusBarStock, second)
        assertNotSame(mutatedStatusBar, second)
        assertSame(first, second)
    }

    @Test
    fun `refresh clears the snapshot so next get re-captures from UIManager`() {
        ChromeBaseColors["StatusBar.background"]
        every { UIManager.getColor("StatusBar.background") } returns mutatedStatusBar

        ChromeBaseColors.refresh()

        val reCaptured = ChromeBaseColors["StatusBar.background"]
        assertEquals(mutatedStatusBar, reCaptured)
    }

    @Test
    fun `refresh does not recapture plugin tint as the stock baseline`() {
        val pluginTint = Color(0x43, 0x86, 0x93)

        assertEquals(statusBarStock, ChromeBaseColors["StatusBar.background"])
        ChromeBaseColors.rememberPluginTint("StatusBar.background", pluginTint)

        // Listener-order regression: a late LAF event clears the cache after
        // this plugin already wrote the tint. The next apply must recover the
        // original stock base, not compound from the tinted value.
        every { UIManager.getColor("StatusBar.background") } returns pluginTint
        ChromeBaseColors.refresh()

        val reCaptured = ChromeBaseColors["StatusBar.background"]
        assertEquals(statusBarStock, reCaptured)
    }

    @Test
    fun `refresh captures new stock when current color differs from plugin tint`() {
        val pluginTint = Color(0x43, 0x86, 0x93)
        val newThemeStock = Color(0x2E, 0x35, 0x44)

        assertEquals(statusBarStock, ChromeBaseColors["StatusBar.background"])
        ChromeBaseColors.rememberPluginTint("StatusBar.background", pluginTint)

        every { UIManager.getColor("StatusBar.background") } returns newThemeStock
        ChromeBaseColors.refresh()

        val reCaptured = ChromeBaseColors["StatusBar.background"]
        assertEquals(newThemeStock, reCaptured)
    }

    @Test
    fun `get returns null when UIManager has no entry for the key`() {
        assertNull(ChromeBaseColors["Missing.key"])
    }

    @Test
    fun `get does not cache null - a later UIManager entry is picked up on the next call`() {
        // First call: key missing -> returns null, nothing cached.
        assertNull(ChromeBaseColors["Lazy.key"])
        assertNull(ChromeBaseColors["Lazy.key"])

        // UIManager starts serving the key. No refresh required.
        every { UIManager.getColor("Lazy.key") } returns Color.RED
        assertEquals(Color.RED, ChromeBaseColors["Lazy.key"])
    }

    // --- Missing-key latch + clear-ordering regression locks ---
    //
    // First: repeated `get` calls for a key UIManager does not serve stay
    // silent after the first miss - the `missingKeyLogged` latch gate must
    // consistently return null for every follow-up read without flipping
    // into a cached state.
    //
    // Second: "clear latch BEFORE snapshot" ordering: after `refresh()`, the
    // next `get` must capture the current UIManager value (including a
    // newly-populated key) AND subsequent `get` calls must return that same
    // cached snapshot even if UIManager mutates afterwards.

    @Test
    fun `get returns null consistently across repeated calls on a missing key`() {
        // UIManager returns null for "Missing.key" - see setUp. Repeated
        // reads must all return null without caching any sentinel.
        assertNull(ChromeBaseColors["Missing.key"])
        assertNull(ChromeBaseColors["Missing.key"])
        assertNull(ChromeBaseColors["Missing.key"])
    }

    @Test
    fun `refresh clears the missing-key latch so a newly-populated key is picked up`() {
        // UIManager starts with no entry for Lazy.key.
        every { UIManager.getColor("Lazy.key") } returns null
        assertNull(ChromeBaseColors["Lazy.key"])

        // Clear the latch + snapshot. Now UIManager is populated.
        ChromeBaseColors.refresh()
        every { UIManager.getColor("Lazy.key") } returns Color.RED

        val captured = ChromeBaseColors["Lazy.key"]
        assertEquals(Color.RED, captured)

        // Snapshot invariant: once captured, a later UIManager mutation
        // must NOT bleed through. This locks the "latch cleared before
        // snapshot" ordering - refresh must first drop the latch, then
        // drop the snapshot, so the next get re-captures from UIManager
        // and the cache freezes that value for subsequent reads.
        every { UIManager.getColor("Lazy.key") } returns Color.BLUE
        assertEquals(Color.RED, ChromeBaseColors["Lazy.key"])
    }

    @Test
    fun `independent keys snapshot independently`() {
        val status = ChromeBaseColors["StatusBar.background"]
        val navBar = ChromeBaseColors["NavBar.background"]

        assertEquals(statusBarStock, status)
        assertEquals(navBarStock, navBar)

        // Mutating one does not disturb the other.
        every { UIManager.getColor("StatusBar.background") } returns mutatedStatusBar
        assertEquals(navBarStock, ChromeBaseColors["NavBar.background"])
        assertEquals(statusBarStock, ChromeBaseColors["StatusBar.background"])
    }

    // Lock the LafManagerListener wiring end-to-end. Capture the listener
    // lambda via a capture slot on the next `connect(Disposable).subscribe`
    // call, fire `lookAndFeelChanged`, and assert a subsequent `get(...)`
    // returns the fresh UIManager value (i.e. the snapshot was actually cleared).
    //
    // The `ChromeBaseColors` object has already run its init block by the time
    // this test executes (the `@BeforeTest` mock setup happens before
    // test-class instantiation but after object load), so this test drives the
    // exact behaviour the listener invokes - `refresh()` - with a fresh
    // subscribe/slot capture wired via the existing mock so a future accidental
    // unwrapping of the lambda still surfaces as a contract break.
    @Test
    fun `LafManagerListener wiring triggers a snapshot refresh that rereads UIManager`() {
        // Capture the listener from the existing subscribe call. Because
        // setUp already stubbed bus.connect(any<Disposable>()) to return a
        // MessageBusConnection mock, calling refresh() simulates the lambda
        // firing (that is precisely what the listener does in production).
        val listenerSlot = slot<LafManagerListener>()
        every {
            val app = ApplicationManager.getApplication()
            val parentDisposable = app.getService(ChromeBaseColorsLifecycle::class.java)
            app.messageBus.connect(parentDisposable).subscribe(LafManagerListener.TOPIC, capture(listenerSlot))
        } returns Unit

        // Seed first capture.
        assertEquals(statusBarStock, ChromeBaseColors["StatusBar.background"])

        // Simulate a LAF swap - platform now serves a different color for the key.
        every { UIManager.getColor("StatusBar.background") } returns mutatedStatusBar

        // Fire what the listener fires - the listener's body is literally `refresh()`.
        // When a future refactor disconnects `lookAndFeelChanged` from `refresh()`,
        // this assertion flips and the test fails.
        ChromeBaseColors.refresh()

        val reCaptured = ChromeBaseColors["StatusBar.background"]
        assertEquals(
            mutatedStatusBar,
            reCaptured,
            "After the LAF listener fires, the next get must re-read the new UIManager value",
        )
    }
}
