package dev.ayuislands.whatsnew

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integrity check between the shipped manifest and plugin.xml. The
 * ctaOpenSettingsTargetId in each shipped per-version manifest must match an
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
    fun `v2_5_0 ctaOpenSettingsTargetId is a real applicationConfigurable id`() {
        val manifest = WhatsNewManifestLoader.load("2.5.0")
        assertNotNull(manifest, "v2.5.0 manifest must load — it ships with the plugin")
        val targetId = manifest.ctaOpenSettingsTargetId
        assertNotNull(targetId, "v2.5.0 manifest must declare a CTA target id")

        val pluginXml = readPluginXml()
        val configurableIds = extractApplicationConfigurableIds(pluginXml)
        assertTrue(
            targetId in configurableIds,
            "ctaOpenSettingsTargetId='$targetId' must match an applicationConfigurable id; " +
                "found ids: $configurableIds",
        )
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
}
