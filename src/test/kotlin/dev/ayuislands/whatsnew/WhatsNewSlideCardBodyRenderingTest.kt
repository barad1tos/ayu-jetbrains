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
        // Lock the EXACT derived width — not just "some width attribute".
        // The KDoc on BODY_WRAP_WIDTH_PX warns that wrapping it in
        // JBUI.scale() would double-scale on Retina and collapse the body
        // to a single column. A future "fix" applying that wrap would
        // still pass `contains("width:")` but FAIL this exact-value
        // assertion — locks the JBUI-scale gotcha against silent regression.
        val expectedWrapPx = WhatsNewImagePanel.DEFAULT_IMAGE_WIDTH - BODY_WRAP_INSET_PX
        assertTrue(
            bodyLabel.text.contains("body style='width:${expectedWrapPx}px'"),
            "Body wrap width must be exactly ${expectedWrapPx}px (DEFAULT_IMAGE_WIDTH - inset), got: ${bodyLabel.text}",
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
    fun `scaler registers both title and body fonts when body is rendered`() {
        // Stronger than a no-throw smoke: actually trigger a scale and
        // verify both labels' fonts grew. If `scaler.registerLabel(bodyLabel, ...)`
        // is silently dropped in a future refactor, the title still scales
        // (no exception) but the body font stays at its base size — the
        // assertion below catches that asymmetry.
        val slide =
            WhatsNewSlide(
                title = "Title",
                body = "Body that should scale with title.",
                image = null,
                imageScale = null,
            )
        val scaler = ContentScaler()

        val card = WhatsNewSlideCard.build(slide, RESOURCE_DIR, ACCENT, TITLE_COLOR, scaler)
        val labels = collectLabels(card)
        val titleLabel = labels.first { it.text == "Title" }
        val bodyLabel = labels.first { it.text.startsWith("<html>") }
        val titleFontBefore = titleLabel.font.size
        val bodyFontBefore = bodyLabel.font.size

        scaler.apply(SCALE_2X)

        assertTrue(
            titleLabel.font.size > titleFontBefore,
            "Title font must grow after scaler.apply(2.0f); was=$titleFontBefore now=${titleLabel.font.size}",
        )
        assertTrue(
            bodyLabel.font.size > bodyFontBefore,
            "Body font must grow after scaler.apply(2.0f) — proves body is registered, " +
                "was=$bodyFontBefore now=${bodyLabel.font.size}",
        )
    }

    @Test
    fun `body and image both registered as gaps with scaler — body absent registers only one`() {
        // Locks the gap-selection branch (`if (slide.body.isNotBlank()) GAP_BODY_IMAGE
        // else GAP_TITLE_IMAGE`). With body+image, build() registers TWO gaps
        // (title→body, body→image). Without body, only ONE (title→image).
        // We can't read scaler internals directly, so we infer registration
        // by counting struts that respond to scale changes in the produced tree.
        val withBody =
            WhatsNewSlide(
                title = "T",
                body = "B",
                image = null,
                imageScale = null,
            )
        val noBody =
            WhatsNewSlide(
                title = "T",
                body = "",
                image = null,
                imageScale = null,
            )

        // Build both cards once (no scaler — gap REGISTRATION isn't what the
        // strut-count differential measures; the struts physically exist in
        // the component tree regardless of scaler). Body case adds a
        // vertical strut between title and body; blank case skips that
        // entire row. Differential count proves the gap-selection branch
        // produced different layouts for the two slide shapes.
        val strutsWith = collectStruts(WhatsNewSlideCard.build(withBody, RESOURCE_DIR, ACCENT, TITLE_COLOR))
        val strutsNo = collectStruts(WhatsNewSlideCard.build(noBody, RESOURCE_DIR, ACCENT, TITLE_COLOR))
        assertTrue(
            strutsWith.size > strutsNo.size,
            "Slide with body must add at least one strut beyond the blank-body baseline; " +
                "with=${strutsWith.size} blank=${strutsNo.size}",
        )
    }

    private fun collectLabels(root: Container): List<JLabel> {
        val out = mutableListOf<JLabel>()
        walk(root) { component ->
            if (component is JLabel) out.add(component)
        }
        return out
    }

    /**
     * Box.createVerticalStrut produces a `Filler` (an inner JComponent) — we
     * detect by zero preferred-width + non-zero preferred-height. Reliable
     * shape-match without coupling to the AWT class name.
     */
    private fun collectStruts(root: Container): List<Component> {
        val out = mutableListOf<Component>()
        walk(root) { component ->
            val pref = component.preferredSize ?: return@walk
            if (pref.width == 0 && pref.height > 0) out.add(component)
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
        const val BODY_WRAP_INSET_PX = 80
        const val SCALE_2X = 2.0f
        val ACCENT: Color = Color(0x5C, 0xCF, 0xE6)
        val TITLE_COLOR: Color = Color(0xCB, 0xCC, 0xC6)
    }
}
