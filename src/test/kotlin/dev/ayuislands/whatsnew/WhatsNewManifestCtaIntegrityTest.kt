package dev.ayuislands.whatsnew

import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integrity check between shipped manifests and plugin.xml. The
 * ctaOpenSettingsTargetId in each per-version manifest must match an
 * applicationConfigurable id declared in plugin.xml — otherwise the
 * "Open settings" CTA in the What's New tab silently no-ops
 * (ShowSettingsUtil.showSettingsDialog looks up by id, not by displayName).
 *
 * This test catches the regression: the v2.5.0 manifest originally shipped
 * "Ayu Islands" (the displayName), which renders the CTA button visually
 * but does nothing on click. Future maintainers adding CTAs to new release
 * manifests get a CI failure if they confuse displayName and id again.
 */
class WhatsNewManifestCtaIntegrityTest {
    @Test
    fun `all shipped manifest ctaOpenSettingsTargetIds are real applicationConfigurable ids`() {
        val pluginXml = readPluginXml()
        val configurableIds = extractApplicationConfigurableIds(pluginXml)

        forEachShippedManifest { version, manifest ->
            val targetId = manifest.ctaOpenSettingsTargetId
            assertNotNull(targetId, "v$version manifest must declare a CTA target id")

            assertTrue(
                targetId in configurableIds,
                "v$version ctaOpenSettingsTargetId='$targetId' must match an applicationConfigurable id; " +
                    "found ids: $configurableIds",
            )
        }
    }

    @Test
    fun `all shipped manifest slide images exist and decode`() {
        forEachShippedManifest { version, manifest ->
            for (slide in manifest.slides) {
                val image = slide.image ?: continue
                val resourcePath = "whatsnew/v$version/$image"
                val stream = javaClass.classLoader.getResourceAsStream(resourcePath)
                assertNotNull(stream, "v$version slide '${slide.title}' image must exist at $resourcePath")

                val decoded = stream.use { ImageIO.read(it) }
                assertNotNull(decoded, "v$version slide '${slide.title}' image must decode as a PNG")
                assertTrue(
                    decoded.width > 0 && decoded.height > 0,
                    "v$version slide '${slide.title}' image must have non-zero dimensions",
                )
            }
        }
    }

    private fun readPluginXml(): String {
        val stream = javaClass.classLoader.getResourceAsStream("META-INF/plugin.xml")
        assertNotNull(stream, "META-INF/plugin.xml must be on the test classpath")
        return stream.use { it.readBytes() }.toString(Charsets.UTF_8)
    }

    private fun extractApplicationConfigurableIds(xml: String): List<String> {
        // Cheap regex over the XML — avoids pulling in a full XML parser for
        // a meta-test. The pattern matches both <applicationConfigurable id="X"...>
        // and the multi-attribute form with id on a different line.
        val pattern =
            Regex(
                """<applicationConfigurable\b[^>]*?\bid="([^"]+)"""",
                RegexOption.DOT_MATCHES_ALL,
            )
        return pattern.findAll(xml).map { it.groupValues[1] }.toList()
    }

    private fun forEachShippedManifest(block: (String, WhatsNewManifest) -> Unit) {
        for (version in SHIPPED_MANIFEST_VERSIONS) {
            val manifest = WhatsNewManifestLoader.load(version)
            assertNotNull(manifest, "v$version manifest must load — it ships with the plugin")
            block(version, manifest)
        }
    }

    private companion object {
        val SHIPPED_MANIFEST_VERSIONS = listOf("2.5.0", "2.6.0", "2.7.0")
    }
}
