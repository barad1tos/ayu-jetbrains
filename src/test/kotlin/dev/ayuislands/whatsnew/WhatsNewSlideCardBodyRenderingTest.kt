package dev.ayuislands.whatsnew

import dev.ayuislands.onboarding.ContentScaler
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Font
import javax.swing.JLabel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the v2.6.0 fix where `WhatsNewSlide.body` is rendered as a wrapped
 * paragraph between the title and the screenshot. Pre-fix the body was
 * silently dropped; both v2.5.0 and v2.6.0 manifests author meaningful
 * prose that this test guarantees reaches the user.
 *
 * Tests walk the JPanel tree and inspect JBLabels directly — no real Swing
 * paint cycle, so they run cleanly in JUnit without an EDT pump.
 */
class WhatsNewSlideCardBodyRenderingTest {
    @BeforeTest
    fun setUp() {
        System.setProperty("java.awt.headless", "true")
    }

    @AfterTest
    fun tearDown() {
        System.clearProperty("java.awt.headless")
    }

    @Test
    fun `non-blank body renders as an HTML-wrapped JBLabel below the title`() {
        val slide =
            WhatsNewSlide(
                title = "Per-project color, on by default",
                body = "If you've ever run VS Code with Peacock, you know the moment.",
                image = null,
                imageScale = null,
            )

        val card = WhatsNewSlideCard.build(slide, RESOURCE_DIR, ACCENT, TITLE_COLOR)

        val labels = collectLabels(card)
        // First label is the title (registered first in build()); subsequent
        // is the body. Assert structure rather than absolute index counts so
        // a future title-styling refactor doesn't trip the test.
        val titleLabel = labels.firstOrNull { it.text == "Per-project color, on by default" }
        assertNotNull(titleLabel, "Title label must be present and unmodified")

        val bodyLabel = labels.firstOrNull { it.text.startsWith("<html>") }
        assertNotNull(bodyLabel, "Body label with <html> wrapper must be added when slide.body is non-blank")
        assertTrue(
            bodyLabel.text.contains("If you've ever run VS Code with Peacock"),
            "Body label must carry the slide.body text",
        )
        assertTrue(
            bodyLabel.text.contains("body style='width:"),
            "Body label must use width-bounded HTML body for word-wrap",
        )
    }

    @Test
    fun `body label uses smaller font than title and PLAIN style`() {
        // The body's visual hierarchy depends on the font being smaller and
        // un-bolded relative to the title. If a refactor accidentally
        // promotes the body to bold or matches title size, the slide reads
        // as two competing headings — exactly the regression to lock.
        val slide =
            WhatsNewSlide(
                title = "Title",
                body = "Body paragraph here.",
                image = null,
                imageScale = null,
            )

        val card = WhatsNewSlideCard.build(slide, RESOURCE_DIR, ACCENT, TITLE_COLOR)
        val labels = collectLabels(card)

        val titleLabel = labels.first { it.text == "Title" }
        val bodyLabel = labels.first { it.text.startsWith("<html>") }

        assertTrue(
            bodyLabel.font.size < titleLabel.font.size,
            "Body font (${bodyLabel.font.size}) must be smaller than title font (${titleLabel.font.size})",
        )
        assertEquals(Font.BOLD, titleLabel.font.style and Font.BOLD, "Title must remain bold")
        assertEquals(0, bodyLabel.font.style and Font.BOLD, "Body must NOT be bold")
    }

    @Test
    fun `blank body skips the prose row entirely`() {
        // v2.5.0 / v2.6.0 schema requires non-blank body, but the renderer
        // must defensively handle a blank value (e.g. an in-progress draft
        // manifest) without leaving an empty <html> placeholder in the tree.
        val slide =
            WhatsNewSlide(
                title = "Title only",
                body = "",
                image = null,
                imageScale = null,
            )

        val card = WhatsNewSlideCard.build(slide, RESOURCE_DIR, ACCENT, TITLE_COLOR)

        val htmlLabel = collectLabels(card).firstOrNull { it.text.startsWith("<html>") }
        assertNull(htmlLabel, "Empty body must NOT render an empty HTML label")
    }

    @Test
    fun `inline HTML markup in body survives into the label text`() {
        // Manifests use <b> / <i> for emphasis. JBLabel's HTML mode preserves
        // these tags inside the wrapper so they render as bold/italic instead
        // of literal angle brackets.
        val slide =
            WhatsNewSlide(
                title = "Title",
                body = "Plain text with <b>bold</b> and <i>italic</i> emphasis.",
                image = null,
                imageScale = null,
            )

        val card = WhatsNewSlideCard.build(slide, RESOURCE_DIR, ACCENT, TITLE_COLOR)
        val bodyLabel = collectLabels(card).first { it.text.startsWith("<html>") }

        assertTrue(bodyLabel.text.contains("<b>bold</b>"), "Bold markup must be preserved")
        assertTrue(bodyLabel.text.contains("<i>italic</i>"), "Italic markup must be preserved")
    }

    @Test
    fun `build accepts scaler parameter without throwing when body is rendered`() {
        // Smoke test that the scaler.registerLabel call inside the body
        // branch survives a real ContentScaler (vs the null path covered
        // implicitly by the other tests). No throw here means the body
        // label was registered with the scaler successfully.
        val slide =
            WhatsNewSlide(
                title = "Title",
                body = "Body that should scale with title.",
                image = null,
                imageScale = null,
            )
        val scaler = ContentScaler()

        // Throws if registration call sites mismatch the scaler API; the
        // implicit assertion is "no exception".
        WhatsNewSlideCard.build(slide, RESOURCE_DIR, ACCENT, TITLE_COLOR, scaler)
    }

    private fun collectLabels(root: Container): List<JLabel> {
        val out = mutableListOf<JLabel>()
        walk(root) { component ->
            if (component is JLabel) out.add(component)
        }
        return out
    }

    private fun walk(
        root: Container,
        action: (Component) -> Unit,
    ) {
        action(root)
        for (child in root.components) {
            action(child)
            if (child is Container) walk(child, action)
        }
    }

    private companion object {
        const val RESOURCE_DIR = "/whatsnew/test/"
        val ACCENT: Color = Color(0x5C, 0xCF, 0xE6)
        val TITLE_COLOR: Color = Color(0xCB, 0xCC, 0xC6)
    }
}
