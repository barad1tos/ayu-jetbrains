package dev.ayuislands.whatsnew

import com.intellij.testFramework.LoggedErrorProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhatsNewManifestLoaderTest {
    @Test
    fun `load parses well-formed manifest with all fields`() {
        // src/test/resources/whatsnew/v9.9.0/manifest.json contains a complete
        // manifest with title, tagline, heroImage, two slides (one with image,
        // one without), and CTA. Locks the canonical happy-path round-trip.
        val manifest = WhatsNewManifestLoader.load("9.9.0")
        assertNotNull(manifest)
        assertEquals("Test Release", manifest.title)
        assertEquals("Tagline for the loader test", manifest.tagline)
        assertEquals("hero", manifest.heroImage)
        assertEquals(2, manifest.slides.size)
        assertEquals("First slide", manifest.slides[0].title)
        assertEquals("Body of slide one.", manifest.slides[0].body)
        assertEquals("slide-1.png", manifest.slides[0].image)
        assertEquals("Second slide", manifest.slides[1].title)
        assertNull(manifest.slides[1].image, "explicit null in JSON must round-trip as null")
        assertEquals("Open settings", manifest.ctaOpenSettingsLabel)
        assertEquals("Test", manifest.ctaOpenSettingsTargetId)
    }

    @Test
    fun `load returns null silently for missing manifest`() {
        // No resource at /whatsnew/v0.0.0/. Patch releases without rich content
        // hit this path constantly — must NOT log a WARN to keep idea.log clean.
        val capturedWarns = mutableListOf<String>()
        val processor = warnCollector(capturedWarns)
        var result: WhatsNewManifest? = null
        LoggedErrorProcessor.executeWith<RuntimeException>(processor) {
            result = WhatsNewManifestLoader.load("0.0.0")
        }
        assertNull(result)
        assertTrue(
            capturedWarns.none { it.contains("What's New") },
            "missing manifest must NOT produce a WARN; got: $capturedWarns",
        )
    }

    @Test
    fun `load returns null with WARN for malformed JSON`() {
        // src/test/resources/whatsnew/v9.9.1/manifest.json is intentionally
        // syntactically invalid. Plugin must not crash; surface the issue via
        // a WARN so the maintainer can find it in idea.log.
        val capturedWarns = mutableListOf<String>()
        val processor = warnCollector(capturedWarns)
        var result: WhatsNewManifest? = null
        LoggedErrorProcessor.executeWith<RuntimeException>(processor) {
            result = WhatsNewManifestLoader.load("9.9.1")
        }
        assertNull(result)
        assertTrue(
            capturedWarns.any { it.contains("malformed JSON") },
            "malformed JSON must produce a 'malformed JSON' WARN; got: $capturedWarns",
        )
    }

    @Test
    fun `load returns null when slides field is absent`() {
        // src/test/resources/whatsnew/v9.9.2/manifest.json has title + tagline
        // but no slides array. Without slides there's nothing to render — loader
        // returns null so the balloon path takes over.
        val result = WhatsNewManifestLoader.load("9.9.2")
        assertNull(result)
    }

    @Test
    fun `load discards malformed slides with WARN but keeps valid ones`() {
        // src/test/resources/whatsnew/v9.9.3/manifest.json mixes one good slide
        // with three malformed entries (missing title, non-object, missing body).
        // The good slide must survive; each malformed entry must produce a
        // WARN so a maintainer can spot the manifest typo in idea.log.
        val capturedWarns = mutableListOf<String>()
        val processor = warnCollector(capturedWarns)
        var result: WhatsNewManifest? = null
        LoggedErrorProcessor.executeWith<RuntimeException>(processor) {
            result = WhatsNewManifestLoader.load("9.9.3")
        }
        val parsed = assertNotNull(result)
        assertEquals(1, parsed.slides.size, "only the well-formed slide should survive")
        assertEquals("Good slide", parsed.slides[0].title)
        assertTrue(
            capturedWarns.any { it.contains("slide[1]") && it.contains("title") },
            "missing-title slide must produce a slide[1] WARN naming 'title'; got: $capturedWarns",
        )
        assertTrue(
            capturedWarns.any { it.contains("slide[2]") && it.contains("expected JSON object") },
            "non-object entry must produce a slide[2] WARN; got: $capturedWarns",
        )
        assertTrue(
            capturedWarns.any { it.contains("slide[3]") && it.contains("body") },
            "missing-body slide must produce a slide[3] WARN naming 'body'; got: $capturedWarns",
        )
    }

    @Test
    fun `load returns null with WARN when every slide is malformed`() {
        // src/test/resources/whatsnew/v9.9.4/manifest.json has three slides,
        // all malformed. Loader collapses to "no usable slides" and returns
        // null so the balloon path takes over — but logs WHY so the maintainer
        // doesn't waste time wondering why the tab silently disappeared.
        val capturedWarns = mutableListOf<String>()
        val processor = warnCollector(capturedWarns)
        var result: WhatsNewManifest? = null
        LoggedErrorProcessor.executeWith<RuntimeException>(processor) {
            result = WhatsNewManifestLoader.load("9.9.4")
        }
        assertNull(result)
        assertTrue(
            capturedWarns.any { it.contains("no usable slides") },
            "all-malformed manifest must produce a 'no usable slides' WARN; got: $capturedWarns",
        )
    }

    @Test
    fun `load parses imageScale variants - keeps valid, drops null-or-non-positive`() {
        // src/test/resources/whatsnew/v9.9.5/manifest.json mixes seven slides
        // covering every imageScale parser branch. Read the per-slide scale to
        // confirm the parser drops anything that wouldn't render meaningfully
        // (null, non-primitive, ≤ 0) while keeping valid positive numbers as-is.
        // The clamp into the panel's [0.3, 2.0] range happens later in
        // WhatsNewImagePanel.computeMaxLogicalImageWidth — the manifest stores
        // the user's intent, the panel decides what's renderable.
        val manifest = WhatsNewManifestLoader.load("9.9.5")
        assertNotNull(manifest)
        assertEquals(7, manifest.slides.size, "all slides parse — only the imageScale field varies")
        assertNull(manifest.slides[0].imageScale, "absent imageScale is null")
        assertNull(manifest.slides[1].imageScale, "JSON null imageScale is null")
        assertNull(manifest.slides[2].imageScale, "object imageScale coerces to null")
        assertNull(manifest.slides[3].imageScale, "negative imageScale below the gate is null")
        assertNull(manifest.slides[4].imageScale, "zero imageScale below the gate is null")
        assertEquals(1.5f, manifest.slides[5].imageScale, "positive in-range value passes through")
        assertEquals(99.0f, manifest.slides[6].imageScale, "huge positive value passes through (panel clamps)")
    }

    @Test
    fun `load handles string field edge cases - null-blank-non-primitive coerce to null or drop`() {
        // src/test/resources/whatsnew/v9.9.6/manifest.json:
        //  - tagline = "   " → blank, coerces to null
        //  - heroImage = ""  → empty, coerces to null
        //  - slide[0]: valid → kept
        //  - slide[1]: title = ""    → discarded (missing required)
        //  - slide[2]: title = "   " → discarded (missing required)
        //  - slide[3]: title = null  → discarded (missing required)
        //  - slide[4]: image = {}   → image coerces to null, slide is kept
        val capturedWarns = mutableListOf<String>()
        val processor = warnCollector(capturedWarns)
        var result: WhatsNewManifest? = null
        LoggedErrorProcessor.executeWith<RuntimeException>(processor) {
            result = WhatsNewManifestLoader.load("9.9.6")
        }
        val parsed = assertNotNull(result)
        assertNull(parsed.tagline, "blank tagline must coerce to null")
        assertNull(parsed.heroImage, "empty heroImage must coerce to null")
        assertEquals(2, parsed.slides.size, "valid + non-primitive-image slides survive")
        assertEquals("Valid slide", parsed.slides[0].title)
        assertEquals("trim me", parsed.slides[1].title)
        assertNull(parsed.slides[1].image, "non-primitive image coerces to null without dropping the slide")
        // Three discards: empty, blank, null-titled
        assertTrue(
            capturedWarns.count { it.contains("title") } >= 3,
            "three title-related discard WARNs expected; got: $capturedWarns",
        )
    }

    @Test
    fun `load WARNs when CTA label is set without a target id (or vice versa)`() {
        // The renderer hides the button when only one half of the CTA pair is
        // present — without this WARN the maintainer ships a "missing button"
        // and gets no signal in idea.log. Use the v9.9.7 fixture which sets
        // ctaOpenSettingsLabel but omits ctaOpenSettingsTargetId.
        val capturedWarns = mutableListOf<String>()
        val processor = warnCollector(capturedWarns)
        var result: WhatsNewManifest? = null
        LoggedErrorProcessor.executeWith<RuntimeException>(processor) {
            result = WhatsNewManifestLoader.load("9.9.7")
        }
        val parsed = assertNotNull(result)
        // Manifest still loads — the half-CTA isn't a fatal error, just a
        // surface-level mistake. The renderer hides the button and we move on.
        assertEquals(1, parsed.slides.size)
        assertTrue(
            capturedWarns.any { it.contains("ctaOpenSettingsLabel") && it.contains("ctaOpenSettingsTargetId") },
            "half-CTA must produce a WARN naming both fields; got: $capturedWarns",
        )
    }

    @Test
    fun `load WARNs when imageScale is non-positive or non-numeric`() {
        // v9.9.5 fixture has zero, negative, non-primitive imageScale entries.
        // Each should produce a WARN so a maintainer who ships imageScale=0
        // or imageScale=-1 has a paper trail (parser silently substitutes the
        // default 1.0 — without the WARN the slide just renders smaller than
        // expected and the cause is invisible).
        val capturedWarns = mutableListOf<String>()
        val processor = warnCollector(capturedWarns)
        LoggedErrorProcessor.executeWith<RuntimeException>(processor) {
            WhatsNewManifestLoader.load("9.9.5")
        }
        // Three rejection branches: object (non-primitive), negative, zero.
        val rejectionWarns = capturedWarns.count { it.contains("imageScale") }
        assertTrue(
            rejectionWarns >= 3,
            "expected at least 3 imageScale rejection WARNs (object/negative/zero); " +
                "got $rejectionWarns: $capturedWarns",
        )
    }

    @Test
    fun `manifestExists returns true for present resource`() {
        assertTrue(WhatsNewManifestLoader.manifestExists("9.9.0"))
    }

    @Test
    fun `manifestExists returns false for absent resource`() {
        assertTrue(!WhatsNewManifestLoader.manifestExists("0.0.0"))
    }

    @Test
    fun `manifestExists treats malformed manifest as present`() {
        // The cheap probe checks for resource presence, NOT validity. Malformed
        // manifest still says "exists" — load() then handles the parse failure.
        // This split keeps the eligibility check fast (no parse on every focus
        // swap during startup).
        assertTrue(WhatsNewManifestLoader.manifestExists("9.9.1"))
    }

    @Test
    fun `normalizeVersion strips SNAPSHOT and other suffixes`() {
        assertEquals("9.9.0", WhatsNewManifestLoader.normalizeVersion("9.9.0-SNAPSHOT"))
        assertEquals("9.9.0", WhatsNewManifestLoader.normalizeVersion("9.9.0-RC1"))
        assertEquals("9.9.0", WhatsNewManifestLoader.normalizeVersion("9.9.0-beta.2"))
        assertEquals("9.9.0", WhatsNewManifestLoader.normalizeVersion("9.9.0"))
    }

    @Test
    fun `load resolves SNAPSHOT version to release directory`() {
        // Dev sandbox builds whose descriptor.version reads "9.9.0-SNAPSHOT"
        // must still pick up the released v9.9.0/ directory — otherwise the
        // tab never fires in dev runs.
        val manifest = WhatsNewManifestLoader.load("9.9.0-SNAPSHOT")
        assertNotNull(manifest)
        assertEquals("Test Release", manifest.title)
    }

    @Test
    fun `resourceDir matches normalized version`() {
        assertEquals("/whatsnew/v9.9.0/", WhatsNewManifestLoader.resourceDir("9.9.0"))
        assertEquals("/whatsnew/v9.9.0/", WhatsNewManifestLoader.resourceDir("9.9.0-SNAPSHOT"))
    }

    private fun warnCollector(into: MutableList<String>): LoggedErrorProcessor =
        object : LoggedErrorProcessor() {
            override fun processWarn(
                category: String,
                message: String,
                throwable: Throwable?,
            ): Boolean {
                if (message.contains("What's New") || message.contains("malformed")) {
                    into += message
                    return false
                }
                return true
            }
        }
}
