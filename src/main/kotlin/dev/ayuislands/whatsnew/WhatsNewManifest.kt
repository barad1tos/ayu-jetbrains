package dev.ayuislands.whatsnew

import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.intellij.openapi.diagnostic.logger

/**
 * One feature highlight inside the What's New tab — a captioned screenshot.
 *
 * @param title bold heading shown above the screenshot
 * @param body paragraph describing the feature; HTML allowed (rendered via JBLabel)
 * @param image filename relative to the manifest's resource directory
 *   (e.g. `slide-overrides.png`); resolved against `/whatsnew/v$version/`.
 *   Null means "render title+body without an image".
 * @param imageScale optional per-slide width factor; 1.0 = the default slide
 *   width, 2.0 = double-wide (clamped by WhatsNewImagePanel to its valid
 *   factor range). Use values < 1.0 to shrink a small diagram; use values > 1.0
 *   for dense content that needs more horizontal room (e.g. a collage of three
 *   IDE windows side-by-side). Null falls back to the global default.
 */
data class WhatsNewSlide(
    val title: String,
    val body: String,
    val image: String?,
    val imageScale: Float?,
)

/**
 * Top-level manifest describing one release's What's New tab content.
 *
 * @param title hero header; defaults to "What's New" if absent
 * @param tagline subtitle under the hero header; optional
 * @param heroImage base name (no extension/variant suffix) for the hero SVG;
 *   loader resolves `${heroImage}-{mirage|dark|light}.svg`. Null → plain accent
 *   gradient header.
 * @param slides ordered list of feature highlights. Empty list = no body content,
 *   only header + footer; usually means the manifest is malformed and the loader
 *   returns null instead.
 * @param ctaOpenSettingsLabel label for the "Open settings" footer button;
 *   if null, the button is hidden.
 * @param ctaOpenSettingsTargetId target id passed to `ShowSettingsUtil` (e.g.
 *   `"Ayu Islands"`); required when [ctaOpenSettingsLabel] is set.
 */
data class WhatsNewManifest(
    val title: String,
    val tagline: String?,
    val heroImage: String?,
    val slides: List<WhatsNewSlide>,
    val ctaOpenSettingsLabel: String?,
    val ctaOpenSettingsTargetId: String?,
)

/**
 * Loads What's New manifests from plugin resources.
 *
 * Convention: `src/main/resources/whatsnew/v{X.Y.Z}/manifest.json` plus sibling
 * PNG/SVG assets. Maintainer drops files into the directory and edits one JSON
 * file per release; no Kotlin recompile needed for content tweaks.
 *
 * Returns null silently when the manifest is absent (not all versions ship one —
 * patch releases typically don't). Returns null and logs WARN on parse failure
 * so a malformed manifest is visible in `idea.log` without crashing the plugin.
 */
internal object WhatsNewManifestLoader {
    private val LOG = logger<WhatsNewManifestLoader>()

    /**
     * Resolve and parse the manifest for [version].
     *
     * Version normalization: strips `-SNAPSHOT` / `-RC1` / etc. suffixes so dev
     * sandbox builds whose `descriptor.version` reads `"2.5.0-SNAPSHOT"` still
     * pick up the released `whatsnew/v2.5.0/` directory. Production releases
     * publish exact semver, so the strip is a no-op there.
     */
    fun load(version: String): WhatsNewManifest? {
        val normalized = normalizeVersion(version)
        val resourcePath = "/whatsnew/v$normalized/manifest.json"
        val stream =
            WhatsNewManifestLoader::class.java.getResourceAsStream(resourcePath)
                ?: return null

        val raw =
            try {
                stream.use { it.readBytes() }.toString(Charsets.UTF_8)
            } catch (exception: java.io.IOException) {
                LOG.warn("What's New: failed to read manifest at $resourcePath", exception)
                return null
            }

        return try {
            parse(raw)
        } catch (exception: JsonSyntaxException) {
            LOG.warn("What's New: malformed JSON in $resourcePath", exception)
            null
        } catch (exception: IllegalStateException) {
            // Gson throws ISE on type mismatch (asJsonObject on non-object etc.)
            LOG.warn("What's New: schema mismatch in $resourcePath", exception)
            null
        }
    }

    /**
     * Returns true when a manifest resource exists for [version]. Used as a
     * cheap eligibility probe before scheduling work — does NOT validate the
     * manifest's contents (a malformed manifest still returns true here, then
     * fails in [load]; the launcher handles both paths).
     */
    fun manifestExists(version: String): Boolean {
        val normalized = normalizeVersion(version)
        val resourcePath = "/whatsnew/v$normalized/manifest.json"
        return WhatsNewManifestLoader::class.java.getResource(resourcePath) != null
    }

    /**
     * Returns the resource directory prefix for [version] (e.g. `/whatsnew/v2.5.0/`).
     * Used by the panel to resolve relative image paths from manifest entries.
     */
    fun resourceDir(version: String): String = "/whatsnew/v${normalizeVersion(version)}/"

    /**
     * Strip pre-release suffix so `2.5.0-SNAPSHOT` resolves to the `v2.5.0/`
     * directory in dev sandbox. Production versions are bare semver and pass
     * through untouched.
     */
    internal fun normalizeVersion(version: String): String = version.substringBefore('-').trim()

    @Suppress("ReturnCount")
    private fun parse(raw: String): WhatsNewManifest? {
        val root = JsonParser.parseString(raw).asJsonObject

        // `slides` is the only required field — without it there's nothing to
        // render. Returning null surfaces this as "no manifest" so the balloon
        // path takes over without further drama.
        val slidesArray = root.get("slides")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null

        val slides =
            slidesArray.mapNotNull { entry ->
                if (!entry.isJsonObject) return@mapNotNull null
                val obj = entry.asJsonObject
                val title = readString(obj, "title") ?: return@mapNotNull null
                val body = readString(obj, "body") ?: return@mapNotNull null
                val image = readString(obj, "image")
                val imageScale = readFloat(obj, "imageScale")?.takeIf { it > 0f }
                WhatsNewSlide(title = title, body = body, image = image, imageScale = imageScale)
            }

        if (slides.isEmpty()) return null

        return WhatsNewManifest(
            title = readString(root, "title") ?: "What's New",
            tagline = readString(root, "tagline"),
            heroImage = readString(root, "heroImage"),
            slides = slides,
            ctaOpenSettingsLabel = readString(root, "ctaOpenSettingsLabel"),
            ctaOpenSettingsTargetId = readString(root, "ctaOpenSettingsTargetId"),
        )
    }

    /**
     * Reads a string field that may be absent, JSON null, or empty/blank. Any
     * of those cases return null. Calling `.asString` directly on a JsonNull
     * throws `UnsupportedOperationException`, so the null-checks need to happen
     * structurally before touching the value.
     */
    private fun readString(
        obj: com.google.gson.JsonObject,
        key: String,
    ): String? {
        val element = obj.get(key) ?: return null
        if (element.isJsonNull) return null
        if (!element.isJsonPrimitive) return null
        val str = element.asString
        return str.takeIf { it.isNotBlank() }
    }

    /**
     * Reads an optional numeric field. Returns null for absent / JSON null /
     * non-number so the caller can apply a sensible default.
     */
    private fun readFloat(
        obj: com.google.gson.JsonObject,
        key: String,
    ): Float? {
        val element = obj.get(key) ?: return null
        if (element.isJsonNull) return null
        if (!element.isJsonPrimitive) return null
        return try {
            element.asFloat
        } catch (exception: NumberFormatException) {
            null
        }
    }
}
