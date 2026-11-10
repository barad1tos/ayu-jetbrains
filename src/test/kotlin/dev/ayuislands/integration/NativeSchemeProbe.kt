package dev.ayuislands.integration

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ex.ApplicationEx
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.impl.AbstractColorsScheme
import com.intellij.openapi.editor.colors.impl.EditorColorsSchemeImpl
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.options.Scheme
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.AyuPlugin
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.theme.AyuSchemeResource
import dev.ayuislands.theme.SchemeAttributeFacts
import dev.ayuislands.theme.SchemeCellFacts
import dev.ayuislands.theme.SchemePersistence
import dev.ayuislands.theme.SchemeStoredCell
import dev.ayuislands.theme.captureAttributeFacts
import dev.ayuislands.theme.captureColorFacts
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.awt.Color
import java.awt.Font
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/** Actual native persistence in a temporary test host; the launcher supplies every writable IDE path. */
internal object NativeSchemeProbe {
    private const val SCHEME_NAME = "Ayu Persistence Probe"
    private const val METADATA_KEY = "dev.ayuislands.probe.owner"
    private const val COLOR_KEY = "AYU_PERSISTENCE_PROCESS_COLOR"
    private const val ABSENT_KEY = "AYU_PERSISTENCE_PROCESS_ABSENT"
    private const val NULL_KEY = "AYU_PERSISTENCE_PROCESS_NULL"
    private const val ATTRIBUTES_KEY = "AYU_PERSISTENCE_PROCESS_ATTRIBUTES"
    private const val RAW_METADATA_KEY = "dev.ayuislands.probe.raw"
    private const val CONTROL_NAME = Scheme.EDITABLE_COPY_PREFIX + "Ayu C1 Control"
    private const val DEPENDENT_NAME = Scheme.EDITABLE_COPY_PREFIX + "Ayu C1 Hidden"

    @JvmStatic
    fun main(args: Array<String>) {
        val result = runCatching { execute(args) }
        result.exceptionOrNull()?.printStackTrace()
        // The standalone host owns JVM shutdown after the platform's leak/error checks finish.
        exitProcess(if (result.isSuccess) 0 else 1)
    }

    private fun execute(args: Array<String>) {
        val isRecovery = args[0].startsWith("RECOVERY_")
        val isBaseChange = args[0].startsWith("C1_")
        val isEditableChange = args[0].startsWith("EDITABLE_")
        val mode = Mode.valueOf(if (isRecovery) "READ" else args[0].removePrefix("C1_").removePrefix("EDITABLE_"))
        val root = Path.of(args[1]).toRealPath()
        if (args[2].toBooleanStrict()) {
            checkNotNull(EditorColors.STICKY_LINES_HOVERED_COLOR.fallbackColorKey)
        }
        for (property in listOf("idea.config.path", "idea.system.path", "idea.log.path", "java.io.tmpdir")) {
            check(Path.of(requireNotNull(System.getProperty(property))).toRealPath().startsWith(root)) {
                "Native probe writable path is not isolated: $property"
            }
        }
        val statement =
            object : Statement() {
                override fun evaluate() =
                    when {
                        isRecovery -> NativeSchemeRecoveryProbe.execute(args[0].removePrefix("RECOVERY_"), root)
                        isBaseChange -> probeBaseChange(mode, root)
                        isEditableChange -> probeBaseChange(mode, root, editableTarget = true)
                        else -> probe(mode, root)
                    }
            }
        try {
            ApplicationRule()
                .apply(
                    statement,
                    Description.createTestDescription(NativeSchemeProbe::class.java, mode.name),
                ).evaluate()
        } finally {
            NativeTestCleanup.closeApplication()
        }
        println("PROBE:COMPLETED:${args[0]}")
    }

    /** Hidden, unsaved native dependents are not discoverable through the visible manager inventory or disk. */
    private fun probeBaseChange(
        mode: Mode,
        root: Path,
        editableTarget: Boolean = false,
    ) {
        val key = EditorColors.CARET_ROW_COLOR
        val userColor = Color(0xFF135724.toInt(), true)
        val result = Properties()
        if (mode == Mode.SAVE) {
            lateinit var canonical: AbstractColorsScheme
            lateinit var editable: AbstractColorsScheme
            lateinit var control: EditorColorsSchemeImpl
            lateinit var affected: EditorColorsSchemeImpl
            SwingUtilities.invokeAndWait {
                val manager = EditorColorsManager.getInstance()
                canonical = checkNotNull(manager.getScheme("Ayu Islands Mirage") as? AbstractColorsScheme)
                checkNotNull(AyuSchemeResource.capture(AyuVariant.MIRAGE, canonical))
                editable =
                    checkNotNull(manager.getScheme(AyuVariant.MIRAGE.editorSchemeResource) as? AbstractColorsScheme)
                check(editable !== canonical && editable.original === canonical)
                result.setProperty("canonical.before", checkNotNull(canonical.getColor(key)).rgb.toString())
                check(canonical.getColor(key)?.rgb != userColor.rgb)
                control = createHiddenCopy(CONTROL_NAME, canonical, userColor)
                check(manager.globalScheme !== control && manager.allSchemes.none { it === control })
            }
            saveSchemes()
            val controlFile = checkNotNull(findSchemeFile(CONTROL_NAME))
            result.setProperty("control.before.option", storedCaretColor(controlFile).orEmpty())
            check(storedCaretColor(controlFile) == "135724")
            SwingUtilities.invokeAndWait {
                val manager = EditorColorsManager.getInstance()
                affected = createHiddenCopy(DEPENDENT_NAME, canonical, userColor)
                check(manager.getScheme(DEPENDENT_NAME) === affected)
                check(manager.allSchemes.none { it === affected || it === control })
                check(manager.globalScheme !== affected && manager.globalScheme !== control)
                check(affected.original === canonical && affected.parentScheme === canonical.parentScheme)
                result.setProperty("inventory.omitsHidden", "true")
                result.setProperty(
                    "hidden.local.before",
                    checkNotNull(affected.directlyDefinedColors[key]).rgb.toString(),
                )
            }
            check(
                findSchemeFile(DEPENDENT_NAME) == null,
            ) { "The pending hidden scheme unexpectedly already has a file" }
            result.setProperty("hidden.unsaved", "true")
            SwingUtilities.invokeAndWait {
                val before = affected.directlyDefinedColors[key]
                val target = if (editableTarget) editable else canonical
                target.setSaveNeeded(true)
                target.setColor(key, userColor)
                check(affected.directlyDefinedColors[key] === before)
                check(affected.original === canonical && affected.parentScheme === canonical.parentScheme)
                result.setProperty("canonical.after", checkNotNull(canonical.getColor(key)).rgb.toString())
                result.setProperty("editable.after", checkNotNull(editable.getColor(key)).rgb.toString())
            }
            saveSchemes()
            val affectedFile = checkNotNull(findSchemeFile(DEPENDENT_NAME))
            check(storedCaretColor(controlFile) == "135724") { "The unchanged control was unexpectedly rewritten" }
            val expectedColor = if (editableTarget) "135724" else null
            check(storedCaretColor(affectedFile) == expectedColor) { "Unexpected dependent serialization" }
            result.setProperty("control.after.option", storedCaretColor(controlFile).orEmpty())
            result.setProperty("hidden.after.option", storedCaretColor(affectedFile).orEmpty())
            if (editableTarget) {
                val editableFile = checkNotNull(findSchemeFile(editable.name))
                result.setProperty("editable.after.option", storedCaretColor(editableFile).orEmpty())
            }
            SwingUtilities.invokeAndWait {
                result.setProperty(
                    "hidden.local.after",
                    checkNotNull(affected.directlyDefinedColors[key]).rgb.toString(),
                )
                check(affected.directlyDefinedColors[key] === userColor)
                check(EditorColorsManager.getInstance().allSchemes.none { it === affected })
            }
        } else {
            SwingUtilities.invokeAndWait {
                result.putAll(observeDependents(userColor, editableTarget))
            }
        }
        val process = ProcessHandle.current()
        result.setProperty("process", "${process.pid()}:${process.info().startInstant().orElseThrow()}")
        val prefix = if (editableTarget) "EDITABLE" else "C1"
        Files
            .newOutputStream(
                root.resolve("${prefix}_${mode.name}.properties"),
            ).use { result.store(it, "C1 native observation") }
    }

    private fun observeDependents(
        userColor: Color,
        editableTarget: Boolean,
    ): Properties {
        val manager = EditorColorsManager.getInstance()
        val canonical = checkNotNull(manager.getScheme("Ayu Islands Mirage") as? AbstractColorsScheme)
        val editable = checkNotNull(manager.getScheme(AyuVariant.MIRAGE.editorSchemeResource) as? AbstractColorsScheme)
        check(editable !== canonical && editable.original === canonical)
        val control = checkNotNull(manager.getScheme(CONTROL_NAME) as? AbstractColorsScheme)
        val affected = checkNotNull(manager.getScheme(DEPENDENT_NAME) as? AbstractColorsScheme)
        check(control.original === canonical && affected.original === canonical)
        check(control.parentScheme === canonical.parentScheme && affected.parentScheme === canonical.parentScheme)
        val key = EditorColors.CARET_ROW_COLOR
        check(control.directlyDefinedColors[key]?.rgb == userColor.rgb)
        check(affected.directlyDefinedColors.containsKey(key) == editableTarget)
        check((affected.getColor(key)?.rgb == userColor.rgb) == editableTarget)
        check(affected.metaProperties.getProperty(METADATA_KEY) == "c1-user-owner")
        return Properties().apply {
            setProperty("canonical", checkNotNull(canonical.getColor(key)).rgb.toString())
            setProperty("editable", checkNotNull(editable.getColor(key)).rgb.toString())
            setProperty("control.local", checkNotNull(control.directlyDefinedColors[key]).rgb.toString())
            setProperty("hidden.local.present", affected.directlyDefinedColors.containsKey(key).toString())
            setProperty("hidden.effective", affected.getColor(key)?.rgb.toString())
            setProperty(
                "parent.effective",
                affected.parentScheme
                    .getColor(key)
                    ?.rgb
                    .toString(),
            )
        }
    }

    private fun createHiddenCopy(
        name: String,
        canonical: AbstractColorsScheme,
        color: Color,
    ): EditorColorsSchemeImpl =
        EditorColorsSchemeImpl(canonical.parentScheme).apply {
            this.name = name
            metaProperties.setProperty(AbstractColorsScheme.META_INFO_ORIGINAL, canonical.name)
            metaProperties.setProperty(METADATA_KEY, "c1-user-owner")
            setColor(EditorColors.CARET_ROW_COLOR, color)
            isVisible = false
            setSaveNeeded(true)
            EditorColorsManager.getInstance().addColorScheme(this)
        }

    fun saveSchemes() {
        check(!SwingUtilities.isEventDispatchThread())
        val application = ApplicationManager.getApplication() as ApplicationEx
        application.isSaveAllowed = true
        try {
            application.saveSettings()
        } finally {
            application.isSaveAllowed = false
        }
    }

    private fun findSchemeFile(name: String): Path? {
        val colors = Path.of(System.getProperty("idea.config.path"), "colors")
        if (!Files.isDirectory(colors)) return null
        return Files.list(colors).use { paths ->
            paths
                .filter { path ->
                    path.toString().endsWith(".icls") &&
                        JDOMUtil.load(Files.readString(path)).getAttributeValue("name") == name
                }.findFirst()
                .orElse(null)
        }
    }

    private fun storedCaretColor(path: Path): String? =
        JDOMUtil
            .load(Files.readString(path))
            .getChild("colors")
            ?.getChildren("option")
            ?.singleOrNull { it.getAttributeValue("name") == "CARET_ROW_COLOR" }
            ?.getAttributeValue("value")

    private fun probe(
        mode: Mode,
        root: Path,
    ) {
        lateinit var scheme: AbstractColorsScheme
        lateinit var editable: AbstractColorsScheme
        SwingUtilities.invokeAndWait {
            val manager = EditorColorsManager.getInstance()
            val bundled = requireNotNull(manager.getScheme("Ayu Islands Mirage") as? AbstractColorsScheme)
            editable =
                requireNotNull(manager.getScheme(Scheme.EDITABLE_COPY_PREFIX + bundled.name) as? AbstractColorsScheme)
            check(editable.original === bundled) { "Native editable copy did not bind its bundled original" }
            scheme =
                when (mode) {
                    Mode.SAVE -> {
                        populate(editable)
                        EditorColorsSchemeImpl(bundled).apply {
                            name = SCHEME_NAME
                            populate(this)
                            manager.addColorScheme(this)
                        }
                    }

                    Mode.READ -> {
                        requireNotNull(manager.getScheme(SCHEME_NAME) as? AbstractColorsScheme)
                    }
                }
            check(scheme.parentScheme === manager.getScheme("Ayu Islands Mirage")) {
                "Native scheme did not bind its registered bundled parent"
            }
        }
        if (mode == Mode.SAVE) {
            check(!SwingUtilities.isEventDispatchThread())
            val application = ApplicationManager.getApplication() as ApplicationEx
            application.isSaveAllowed = true
            try {
                application.saveSettings()
            } finally {
                application.isSaveAllowed = false
            }
            val colors = Path.of(System.getProperty("idea.config.path"), "colors")
            check(
                Files.isDirectory(colors) &&
                    Files.list(colors).use { paths -> paths.anyMatch { it.toString().endsWith(".icls") } },
            ) {
                "Native save did not persist a scheme file"
            }
        }
        lateinit var result: Properties
        SwingUtilities.invokeAndWait {
            result = observe(scheme)
            val editableResult = observe(editable)
            for (key in editableResult.stringPropertyNames()) {
                result.setProperty("editable.$key", requireNotNull(editableResult.getProperty(key)))
            }
            val keyword = DefaultLanguageHighlighterColors.KEYWORD.externalName
            result.setProperty("editable.keyword", (keyword in editable.directlyDefinedAttributes).toString())
        }
        val process = ProcessHandle.current()
        result.setProperty("process", "${process.pid()}:${process.info().startInstant().orElseThrow()}")
        if (mode == Mode.READ) {
            SwingUtilities.invokeAndWait {
                checkResourceReplacement()
                result.setProperty("replacementRejected", "true")
            }
        }
        Files
            .newOutputStream(
                root.resolve("${mode.name}.properties"),
            ).use { result.store(it, "Native scheme observation") }
    }

    /** The disposable reader never saves; a same-name replacement must not inherit bundled provenance. */
    private fun checkResourceReplacement() {
        val manager = EditorColorsManager.getInstance()
        val canonicalName = "Ayu Islands Mirage"
        val resourcePath = "/themes/AyuIslandsMirage.xml"
        val bundled = checkNotNull(manager.getScheme(canonicalName) as? AbstractColorsScheme)
        val editable = checkNotNull(manager.getScheme(resourcePath) as? AbstractColorsScheme)
        check(editable.original === bundled)
        val replacement =
            EditorColorsSchemeImpl(bundled.parentScheme).apply {
                name = canonicalName
                metaProperties.setProperty(AbstractColorsScheme.META_INFO_PLUGIN_ID, AyuPlugin.ID_STRING)
            }
        manager.addColorScheme(replacement)
        // Base and Islands providers register distinct same-name objects. Native replacement selects the last;
        // named lookup selects the first. Qualify the actual object, never its name or copied plugin metadata.
        check(manager.getScheme(canonicalName) === bundled)
        check(manager.allSchemes.any { it === replacement })
        check(manager.getScheme(resourcePath) === editable && editable.original === bundled)
        checkNotNull(AyuSchemeResource.capture(AyuVariant.MIRAGE, bundled))
        check(AyuSchemeResource.capture(AyuVariant.MIRAGE, replacement) == null) {
            "A replacement inherited bundled resource provenance"
        }
    }

    private fun populate(scheme: AbstractColorsScheme) {
        scheme.setColor(ColorKey.createColorKey(COLOR_KEY), Color(0x44123456, true))
        scheme.setColor(ColorKey.createColorKey(NULL_KEY), AbstractColorsScheme.NULL_COLOR_MARKER)
        scheme.setAttributes(TextAttributesKey.createTextAttributesKey(ATTRIBUTES_KEY), layeredAttributes())
        scheme.setAttributes(DefaultLanguageHighlighterColors.CONSTANT, AbstractColorsScheme.INHERITED_ATTRS_MARKER)
        scheme.setColor(EditorColors.STICKY_LINES_HOVERED_COLOR, AbstractColorsScheme.INHERITED_COLOR_MARKER)
        scheme.metaProperties.setProperty(METADATA_KEY, "probe-owner")
        scheme.metaProperties.setProperty(RAW_METADATA_KEY, " \towner\nЖ \t")
        scheme.setSaveNeeded(true)
    }

    private fun observe(scheme: AbstractColorsScheme): Properties =
        Properties().apply {
            setProperty("name", scheme.name)
            setProperty("parent", requireNotNull(scheme.parentScheme).name)
            setProperty("original", scheme.original?.name.orEmpty())
            val base = if (scheme.name.startsWith(Scheme.EDITABLE_COPY_PREFIX)) scheme.original else scheme.parentScheme
            val directBase =
                (base as? AbstractColorsScheme)?.getDirectlyDefinedColor(
                    EditorColors.STICKY_LINES_HOVERED_COLOR,
                )
            setProperty("baseColor", directBase?.rgb.toString())
            setProperty(
                "fallback",
                EditorColors.STICKY_LINES_HOVERED_COLOR.fallbackColorKey
                    ?.externalName
                    .orEmpty(),
            )
            val storedKey =
                scheme.directlyDefinedColors.keys.single {
                    it.externalName == EditorColors.STICKY_LINES_HOVERED_COLOR.externalName
                }
            setProperty("storedFallback", storedKey.fallbackColorKey?.externalName.orEmpty())
            setProperty("registeredKey", (storedKey === EditorColors.STICKY_LINES_HOVERED_COLOR).toString())
            val storedColor = requireNotNull(scheme.directlyDefinedColors[storedKey])
            val projectedColor =
                checkNotNull(
                    SchemePersistence.project(
                        SchemeCellFacts.Color(captureColorFacts(storedColor)),
                        SchemeCellFacts.Color(captureColorFacts(directBase)),
                        SchemeCellFacts.Color(captureColorFacts(directBase)),
                        storedKey.fallbackColorKey?.externalName,
                    ),
                )
            setProperty("projection.inherited", (projectedColor is SchemeStoredCell.InheritedColor).toString())
            setProperty(
                "color.raw",
                if (storedColor ===
                    AbstractColorsScheme.INHERITED_COLOR_MARKER
                ) {
                    "inherited"
                } else {
                    storedColor.rgb.toString()
                },
            )
            setProperty(
                "color",
                requireNotNull(scheme.directlyDefinedColors[ColorKey.createColorKey(COLOR_KEY)]).rgb.toString(),
            )
            setProperty("metadata", requireNotNull(scheme.metaProperties.getProperty(METADATA_KEY)))
            setProperty("rawMetadata", requireNotNull(scheme.metaProperties.getProperty(RAW_METADATA_KEY)))
            setProperty(
                "absent",
                scheme.directlyDefinedColors.containsKey(ColorKey.createColorKey(ABSENT_KEY)).toString(),
            )
            setProperty(
                "nullMarker",
                (
                    scheme.directlyDefinedColors[ColorKey.createColorKey(NULL_KEY)] ===
                        AbstractColorsScheme.NULL_COLOR_MARKER
                ).toString(),
            )
            val attributes = requireNotNull(scheme.directlyDefinedAttributes[ATTRIBUTES_KEY])
            val projectedAttributes =
                SchemePersistence.project(
                    SchemeCellFacts.Attributes(
                        checkNotNull(captureAttributeFacts(attributes) as? SchemeAttributeFacts.Native),
                    ),
                    SchemeCellFacts.Attributes(null),
                    SchemeCellFacts.Attributes(null),
                    null,
                ) as SchemeStoredCell.Attributes
            setProperty("projection.foreground", checkNotNull(projectedAttributes.foregroundRgb).toString())
            setProperty("attributes.foreground", requireNotNull(attributes.foregroundColor).rgb.toString())
            setProperty("attributes.font", attributes.fontType.toString())
            setProperty("attributes.effect", requireNotNull(attributes.effectType).name)
            setProperty(
                "attributes.effects",
                attributes.flyweight.allEffects.size
                    .toString(),
            )
            setProperty(
                "attributes.inherited",
                (
                    scheme.directlyDefinedAttributes[DefaultLanguageHighlighterColors.CONSTANT.externalName] ===
                        AbstractColorsScheme.INHERITED_ATTRS_MARKER
                ).toString(),
            )
            setProperty(
                "color.inherited",
                (
                    scheme.directlyDefinedColors[EditorColors.STICKY_LINES_HOVERED_COLOR] ===
                        AbstractColorsScheme.INHERITED_COLOR_MARKER
                ).toString(),
            )
        }

    private fun layeredAttributes(): TextAttributes =
        requireNotNull(
            TextAttributes.merge(
                TextAttributes(Color(0x44223355, true), null, Color.RED, EffectType.BOXED, Font.BOLD),
                TextAttributes(null, null, Color.GREEN, EffectType.LINE_UNDERSCORE, Font.PLAIN),
            ),
        )

    private enum class Mode { SAVE, READ }
}
