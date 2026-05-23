package dev.ayuislands.syntax

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [SyntaxModeUpgradeNotifier] (Phase 49, Plan 49-03).
 *
 * Validates the one-shot notification contract per D-10:
 *  - fires once per IDE install on first launch
 *  - subsequent calls are no-ops (flag-gated by `ayu.syntax.notified`)
 *  - notification title / body / action label match D-10 literal strings
 *  - RuntimeException from notify is caught (Pattern B) and does not propagate
 *
 * Plus Pattern L source-regex regression locks:
 *  - flag key literal `"ayu.syntax.notified"` present in the notifier source
 *  - GROUP_ID literal `"Ayu Islands"` binds to a `<notificationGroup>`
 *    registration in plugin.xml (warning #6 fix)
 */
class SyntaxModeUpgradeNotifierTest {
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

    // ---------- one-shot behavior ----------

    @Test
    fun `maybeFire shows notification on first call when flag is false`() {
        every { props.getBoolean("ayu.syntax.notified", false) } returns false

        SyntaxModeUpgradeNotifier.maybeFire(project = null)

        verify(exactly = 1) { Notifications.Bus.notify(any<Notification>(), null) }
    }

    @Test
    fun `maybeFire sets flag to true after first notification`() {
        every { props.getBoolean("ayu.syntax.notified", false) } returns false

        SyntaxModeUpgradeNotifier.maybeFire(project = null)

        verify(exactly = 1) { props.setValue("ayu.syntax.notified", true) }
    }

    @Test
    fun `maybeFire is no-op when flag already true`() {
        every { props.getBoolean("ayu.syntax.notified", false) } returns true

        SyntaxModeUpgradeNotifier.maybeFire(project = null)

        verify(exactly = 0) { Notifications.Bus.notify(any<Notification>(), null) }
        verify(exactly = 0) { props.setValue("ayu.syntax.notified", true) }
    }

    @Test
    fun `maybeFire is idempotent across multiple calls when flag flips after first`() {
        // First call sees flag=false (fires + sets); second call sees flag=true (no-op)
        every { props.getBoolean("ayu.syntax.notified", false) } returnsMany listOf(false, true)

        SyntaxModeUpgradeNotifier.maybeFire(project = null)
        SyntaxModeUpgradeNotifier.maybeFire(project = null)

        verify(exactly = 1) { Notifications.Bus.notify(any<Notification>(), null) }
    }

    // ---------- notification content (D-10) ----------

    @Test
    fun `notification title and body match D-10 literal strings`() {
        every { props.getBoolean("ayu.syntax.notified", false) } returns false
        val captured = slot<Notification>()
        every { Notifications.Bus.notify(capture(captured), null) } just Runs

        SyntaxModeUpgradeNotifier.maybeFire(project = null)

        val notification = captured.captured
        assertEquals("Ayu Islands -- Syntax Moods", notification.title)
        assertTrue(
            notification.content.contains("Minimal / Standard / Rich / Maximum"),
            "body must list the four moods per D-10",
        )
        assertTrue(
            notification.content.contains("Settings -> Ayu Islands -> Syntax"),
            "body must point at the Syntax tab per D-10",
        )
    }

    @Test
    fun `notification action label is Open Syntax tab`() {
        every { props.getBoolean("ayu.syntax.notified", false) } returns false
        val captured = slot<Notification>()
        every { Notifications.Bus.notify(capture(captured), null) } just Runs

        SyntaxModeUpgradeNotifier.maybeFire(project = null)

        val labels = captured.captured.actions.map { it.templateText }
        assertTrue(
            "Open Syntax tab" in labels,
            "expected an action labeled 'Open Syntax tab', got $labels",
        )
    }

    @Test
    fun `notification uses the Ayu Islands group id`() {
        every { props.getBoolean("ayu.syntax.notified", false) } returns false
        val captured = slot<Notification>()
        every { Notifications.Bus.notify(capture(captured), null) } just Runs

        SyntaxModeUpgradeNotifier.maybeFire(project = null)

        assertEquals("Ayu Islands", captured.captured.groupId)
    }

    // ---------- error handling (Pattern B) ----------

    @Test
    fun `RuntimeException from notify is caught and does not propagate`() {
        every { props.getBoolean("ayu.syntax.notified", false) } returns false
        every { Notifications.Bus.notify(any<Notification>(), null) } throws RuntimeException("simulated bus failure")

        // Must not throw — the catch block per Pattern B (RuntimeException only) swallows + logs WARN.
        SyntaxModeUpgradeNotifier.maybeFire(project = null)
    }

    // ---------- Pattern L source-regex regression locks ----------

    @Test
    fun `notifier source contains the exact flag key string ayu syntax notified`() {
        val source = readNotifierSource()
        assertTrue(
            source.contains("\"ayu.syntax.notified\""),
            "notifier must use the exact flag key string 'ayu.syntax.notified'",
        )
    }

    @Test
    fun `notifier source uses RuntimeException not Throwable in catch (Pattern B)`() {
        val source = readNotifierSource()
        assertTrue(
            Regex("""catch\s*\(\s*\w+\s*:\s*RuntimeException""").containsMatchIn(source),
            "notifier must catch RuntimeException (Pattern B), not Throwable",
        )
        assertEquals(
            0,
            Regex("""catch\s*\(\s*\w+\s*:\s*Throwable""").findAll(source).count(),
            "notifier must NOT catch Throwable (Pattern B forbids broad catches)",
        )
    }

    @Test
    fun `GROUP_ID literal binds to plugin xml notificationGroup registration (warning 6)`() {
        val pluginXmlText =
            Files.readString(
                Path.of("src/main/resources/META-INF/plugin.xml"),
            )
        // Pattern L source-regex lock — exactly one Ayu Islands BALLOON registration.
        // The notificationGroup element spans two lines in the source; DOT_MATCHES_ALL
        // matches across line breaks. The invariant is one registration with both
        // id="Ayu Islands" AND displayType="BALLOON".
        val regex =
            Regex(
                """<notificationGroup\b[^/>]*\bid="Ayu Islands"[^/>]*\bdisplayType="BALLOON"[^/>]*/?>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            )
        val matches = regex.findAll(pluginXmlText).count()
        assertEquals(
            1,
            matches,
            "Expected exactly 1 'Ayu Islands' BALLOON notificationGroup registration, got $matches",
        )
    }

    private fun readNotifierSource(): String =
        Files.readString(
            Path.of("src/main/kotlin/dev/ayuislands/syntax/SyntaxModeUpgradeNotifier.kt"),
        )
}
