package dev.ayuislands.whatsnew

import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.intellij.openapi.diagnostic.logger

/**
 * One feature highlight inside the What's New tab — a captioned screenshot.
 *
 * @param title bold heading shown above the screenshot; rendered in a
 *   per-slide palette color (lavender / gold / cyan cycle) by [WhatsNewPanel]
 * @param body paragraph from the manifest; currently NOT rendered — the
 *   v2.5.0 design is "developer-targeted audience doesn't need prose, the
 *   title + screenshot carry the feature". Field is preserved in the schema
 *   so a future release can opt prose back in without a manifest migration.
 * @param image filename relative to the manifest's resource directory
 *   (e.g. `slide-overrides.png`); resolved against `/whatsnew/v$version/`.
 *   Null means "render title without an image".
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
 * @param ctaOpenSettingsTargetId target id passed to `ShowSettingsUtil.showSettingsDialog`
 *   — must match an `applicationConfigurable` `id` attribute in `plugin.xml`
 *   (currently `dev.ayuislands.settings.AyuIslandsConfigurable`); required when
 *   [ctaOpenSettingsLabel] is set. NOTE: the configurable's `displayName` is
 *   not the id — passing "Ayu Islands" silently no-ops the button.
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
        val slidesArray = root["slides"]?.takeIf { it.isJsonArray }?.asJsonArray ?: return null

        val slides =
            slidesArray.mapIndexedNotNull { index, entry ->
                if (!entry.isJsonObject) {
                    val type = entry::class.simpleName
                    LOG.warn("What's New: discarding slide[$index] — expected JSON object, got $type")
                    return@mapIndexedNotNull null
                }
                val obj = entry.asJsonObject
                val title = readString(obj, "title")
                val body = readString(obj, "body")
                if (title == null || body == null) {
                    val missing =
                        listOfNotNull(
                            "title".takeIf { title == null },
                            "body".takeIf { body == null },
                        ).joinToString()
                    LOG.warn("What's New: discarding slide[$index] — missing required field(s): $missing")
                    return@mapIndexedNotNull null
                }
                val image = readString(obj, "image")
                val imageScale = readImageScale(obj)
                WhatsNewSlide(title = title, body = body, image = image, imageScale = imageScale)
            }

        if (slides.isEmpty()) {
            LOG.warn("What's New: manifest contains no usable slides — falling back to balloon")
            return null
        }

        val ctaLabel = readString(root, "ctaOpenSettingsLabel")
        val ctaTargetId = readString(root, "ctaOpenSettingsTargetId")
        if ((ctaLabel == null) != (ctaTargetId == null)) {
            // The renderer only shows the button when BOTH are present — surface
            // the manifest typo so a maintainer who set just one half doesn't
            // ship a "missing button" without any log signal to debug.
            LOG.warn(
                "What's New: ctaOpenSettingsLabel and ctaOpenSettingsTargetId must " +
                    "both be set or both be absent; got label='$ctaLabel' targetId='$ctaTargetId'. " +
                    "Button hidden.",
            )
        }
        return WhatsNewManifest(
            title = readString(root, "title") ?: "What's New",
            tagline = readString(root, "tagline"),
            heroImage = readString(root, "heroImage"),
            slides = slides,
            ctaOpenSettingsLabel = ctaLabel,
            ctaOpenSettingsTargetId = ctaTargetId,
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
        val element = obj[key] ?: return null
        if (element.isJsonNull) return null
        if (!element.isJsonPrimitive) return null
        val str = element.asString
        return str.takeIf { it.isNotBlank() }
    }

    /**
     * Reads the optional `imageScale` field. Returns null for absent / JSON null /
     * non-number / non-positive so the caller can apply a sensible default.
     * Inlined for the single call site — Gson's `JsonObject.get` returns
     * `JsonElement?` and asFloat can throw on non-numeric primitives.
     */
    private fun readImageScale(obj: com.google.gson.JsonObject): Float? {
        val element = obj["imageScale"] ?: return null
        if (element.isJsonNull) return null
        if (!element.isJsonPrimitive) {
            LOG.warn("What's New: ignoring non-primitive imageScale (got $element); using default")
            return null
        }
        val raw =
            try {
                element.asFloat
            } catch (exception: NumberFormatException) {
                LOG.warn("What's New: ignoring non-numeric imageScale '$element'; using default", exception)
                return null
            }
        if (raw <= 0f || raw.isNaN()) {
            LOG.warn("What's New: ignoring imageScale '$raw' (must be > 0); using default")
            return null
        }
        return raw
    }
}
