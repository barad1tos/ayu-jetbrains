package dev.ayuislands.syntax

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.util.io.FileUtil
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [SyntaxIntensityMigrationNotifier]. Uses the same
 * upgrade-notifier MockK harness as prior notifiers — `mockkStatic` for
 * `PropertiesComponent` + `Notifications.Bus`, `slot<Notification>()` for
 * payload capture, and the `notificationGroup` plugin.xml registration binding.
 */
class SyntaxIntensityMigrationNotifierTest {
    private lateinit var props: PropertiesComponent

    @BeforeTest
    fun setUp() {
        props = mockk(relaxed = true)
        mockkStatic(PropertiesComponent::class)
        every { PropertiesComponent.getInstance() } returns props
        mockkStatic(Notifications.Bus::class)
        every { Notifications.Bus.notify(any<Notification>(), null) } just Runs
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    // ---------- one-shot behaviour ----------

    @Test
    fun `maybeFire publishes notification on first call when flag is false`() {
        every { props.getBoolean("ayu.syntax.intensity.notified", false) } returns false

        SyntaxIntensityMigrationNotifier.maybeFire(project = null)

        verify(exactly = 1) { Notifications.Bus.notify(any<Notification>(), null) }
        verify(exactly = 1) { props.setValue("ayu.syntax.intensity.notified", true) }
    }

    @Test
    fun `maybeFire is no-op when flag already true`() {
        every { props.getBoolean("ayu.syntax.intensity.notified", false) } returns true

        SyntaxIntensityMigrationNotifier.maybeFire(project = null)

        verify(exactly = 0) { Notifications.Bus.notify(any<Notification>(), null) }
        verify(exactly = 0) { props.setValue("ayu.syntax.intensity.notified", true) }
    }

    @Test
    fun `maybeFire is idempotent across multiple calls when flag flips after first`() {
        every {
            props.getBoolean("ayu.syntax.intensity.notified", false)
        } returnsMany listOf(false, true)

        SyntaxIntensityMigrationNotifier.maybeFire(project = null)
        SyntaxIntensityMigrationNotifier.maybeFire(project = null)

        verify(exactly = 1) { Notifications.Bus.notify(any<Notification>(), null) }
    }

    // ---------- notification content ----------

    @Test
    fun `notification title body and group id describe the syntax customization migration`() {
        every { props.getBoolean("ayu.syntax.intensity.notified", false) } returns false
        val captured = slot<Notification>()
        every { Notifications.Bus.notify(capture(captured), null) } just Runs

        SyntaxIntensityMigrationNotifier.maybeFire(project = null)

        val notification = captured.captured
        assertEquals("Ayu Islands", notification.groupId)
        assertTrue(
            notification.title.contains("Syntax customization"),
            "title must describe the syntax customization migration, got '${notification.title}'",
        )
        assertTrue(
            notification.content.contains("Whisper", ignoreCase = true) &&
                notification.content.contains("Ambient", ignoreCase = true) &&
                notification.content.contains("Neon", ignoreCase = true) &&
                notification.content.contains("Cyberpunk", ignoreCase = true),
            "body must list the four available presets, got '${notification.content}'",
        )
        assertTrue(
            notification.content.contains("Settings -> Ayu Islands -> Syntax"),
            "body must point at the Syntax tab, got '${notification.content}'",
        )
    }

    @Test
    fun `notification action label is Open Syntax tab`() {
        every { props.getBoolean("ayu.syntax.intensity.notified", false) } returns false
        val captured = slot<Notification>()
        every { Notifications.Bus.notify(capture(captured), null) } just Runs

        SyntaxIntensityMigrationNotifier.maybeFire(project = null)

        val labels = captured.captured.actions.map { it.templateText }
        assertTrue(
            "Open Syntax tab" in labels,
            "expected an action labeled 'Open Syntax tab', got $labels",
        )
    }

    // ---------- error handling (Pattern B) ----------

    @Test
    fun `RuntimeException from notify is caught and does not propagate`() {
        every { props.getBoolean("ayu.syntax.intensity.notified", false) } returns false
        every {
            Notifications.Bus.notify(any<Notification>(), null)
        } throws RuntimeException("simulated bus failure")

        // Must not throw — the catch block per Pattern B (RuntimeException only)
        // swallows and logs WARN.
        SyntaxIntensityMigrationNotifier.maybeFire(project = null)
    }

    @Test
    fun `notify failure leaves one-shot flag unset for retry`() {
        every { props.getBoolean("ayu.syntax.intensity.notified", false) } returns false
        every {
            Notifications.Bus.notify(any<Notification>(), null)
        } throws RuntimeException("simulated bus failure")

        SyntaxIntensityMigrationNotifier.maybeFire(project = null)

        verify(exactly = 0) { props.setValue("ayu.syntax.intensity.notified", true) }
    }

    // ---------- plugin registration ----------

    @Test
    fun `plugin xml registers Ayu Islands notification group`() {
        val pluginXmlText =
            FileUtil.loadFile(File("src/main/resources/META-INF/plugin.xml"))
        val regex =
            Regex(
                """<notificationGroup\b[^/>]*\bid="Ayu Islands"[^/>]*\bdisplayType="BALLOON"[^/>]*/?>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            )
        val matches = regex.findAll(pluginXmlText).count()
        assertEquals(
            1,
            matches,
            "expected exactly 1 'Ayu Islands' BALLOON notificationGroup registration, got $matches",
        )
    }
}
