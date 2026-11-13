package dev.ayuislands.studio.persistence

import com.intellij.serialization.SerializationException
import com.intellij.util.xmlb.XmlSerializer
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.freshSettingsState
import dev.ayuislands.settings.mappings.AccentMappingsState
import dev.ayuislands.studio.migration.LegacySnapshot
import dev.ayuislands.studio.migration.hasCompleteArchives
import dev.ayuislands.studio.model.FieldOrigin
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyFile
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.MigrationSource
import dev.ayuislands.studio.model.OpaqueRecord
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SourcePresence
import dev.ayuislands.studio.model.StudioFieldCatalog
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.profiles.StudioFactoryProfiles
import dev.ayuislands.syntax.SyntaxIntensityBaseState
import java.nio.charset.CharacterCodingException
import javax.xml.stream.XMLStreamException

internal enum class LegacyCaptureIssue { INVALID_DATA, PATH_CONFLICT }

internal sealed interface LegacyCapture {
    data class Ready(
        val snapshot: LegacySnapshot,
    ) : LegacyCapture

    data class Blocked(
        val source: MigrationSource,
        val issue: LegacyCaptureIssue,
    ) : LegacyCapture
}

/** Reads already captured XML bytes using detached v2 beans. Never opens a file or a settings service. */
internal object StudioLegacyCapture {
    fun read(
        source: MigrationSource,
        transactionId: String,
        userHome: String,
    ): LegacyCapture =
        try {
            require(source.hasCompleteArchives()) { "Incomplete legacy archive" }
            capture(source, transactionId, userHome)
        } catch (_: XMLStreamException) {
            LegacyCapture.Blocked(source, LegacyCaptureIssue.INVALID_DATA)
        } catch (_: CharacterCodingException) {
            LegacyCapture.Blocked(source, LegacyCaptureIssue.INVALID_DATA)
        } catch (_: SerializationException) {
            LegacyCapture.Blocked(source, LegacyCaptureIssue.INVALID_DATA)
        } catch (_: IllegalArgumentException) {
            LegacyCapture.Blocked(source, LegacyCaptureIssue.INVALID_DATA)
        }

    private fun capture(
        source: MigrationSource,
        transactionId: String,
        userHome: String,
    ): LegacyCapture {
        val components =
            source.archives.associate { (store, file) ->
                store to
                    (file as? LegacyFile.Present)?.let {
                        StudioLegacyXml.component(it.bytes.toByteArray(), componentNames.getValue(store))
                    }
            }
        val fresh = freshSettingsState()
        val settings = components[LegacyStore.SETTINGS].decode<AyuIslandsState>() ?: fresh
        val syntax = components[LegacyStore.SYNTAX].decode<SyntaxIntensityBaseState>() ?: SyntaxIntensityBaseState()
        val mappings = components[LegacyStore.MAPPINGS].decode<AccentMappingsState>() ?: AccentMappingsState()
        val options = linkedMapOf<LegacyFieldKey, LegacyXmlElement>()
        val extensions = mutableListOf<OpaqueRecord>()
        for ((store, component) in components) {
            if (component != null) captureOptions(store, component, options, extensions)
        }
        val origins = options.mapValues { (_, option) -> option.origin() }
        val fields = StudioLegacyFields.capture(settings, syntax, mappings, origins)
        val ordered = orderCollections(fields, options)
        val expanded =
            expandProjectPaths(ordered, userHome)
                ?: return LegacyCapture.Blocked(source, LegacyCaptureIssue.PATH_CONFLICT)
        val defaults = StudioLegacyFields.capture(fresh, SyntaxIntensityBaseState(), AccentMappingsState(), emptyMap())
        return LegacyCapture.Ready(
            LegacySnapshot(
                source,
                expanded.copy(extensions = StudioList(extensions)),
                StudioFactoryProfiles.create(defaults),
                transactionId,
            ),
        )
    }

    private inline fun <reified T : Any> LegacyXmlElement?.decode(): T? =
        this?.let { XmlSerializer.deserialize(it.toElement(), T::class.java) }

    private fun captureOptions(
        store: LegacyStore,
        component: LegacyXmlElement,
        options: MutableMap<LegacyFieldKey, LegacyXmlElement>,
        extensions: MutableList<OpaqueRecord>,
    ) {
        val definitions = StudioFieldCatalog.fields.filter { it.source == store }.associateBy { it.xmlName }
        component.children.forEachIndexed { index, child ->
            val definition = definitions[child.node.attributes["name"]].takeIf { child.node.name == "option" }
            if (definition == null) {
                extensions.add(OpaqueRecord("legacy/${store.name}/$index", child.lexeme))
            } else {
                val key = LegacyFieldKey(store, definition.name)
                require(options.put(key, child) == null) { "Duplicate legacy option" }
            }
        }
    }

    private fun LegacyXmlElement.origin(): FieldOrigin {
        val presence =
            if ("value" in node.attributes ||
                children.isNotEmpty()
            ) {
                SourcePresence.VALUE
            } else {
                SourcePresence.NULL
            }
        return FieldOrigin(presence, lexeme)
    }

    private fun orderCollections(
        fields: StudioFields,
        options: Map<LegacyFieldKey, LegacyXmlElement>,
    ): StudioFields =
        fields.copy(
            records =
                StudioList(
                    fields.records.map { field ->
                        val option = options[field.key]?.node ?: return@map field
                        val ordered = orderCollection(field.value, option)
                        if (ordered == null) field else field.copy(value = ordered)
                    },
                ),
        )

    private fun orderCollection(
        value: RawValue,
        option: StudioXmlNode,
    ): RawValue? =
        when (value) {
            is RawValue.OrderedMap ->
                option.optionalChild("map")?.let { map ->
                    val decoded = value.entries.associateBy { it.key }
                    val keys = map.children.map { it.attribute("key") }
                    require(keys.size == decoded.size && keys.toSet() == decoded.keys) { "Invalid legacy map keys" }
                    RawValue.OrderedMap(StudioList(keys.map(decoded::getValue)))
                }
            is RawValue.OrderedSet ->
                option.optionalChild("set")?.let { set ->
                    val members = set.children.map { it.attribute("value") }
                    require(
                        members.size == value.values.size && members.toSet() == value.values.toSet(),
                    ) { "Invalid legacy set" }
                    RawValue.OrderedSet(StudioList(members))
                }
            else -> null
        }

    private fun expandProjectPaths(
        fields: StudioFields,
        userHome: String,
    ): StudioFields? {
        val records =
            fields.records.map { field ->
                if (field.key.source != LegacyStore.MAPPINGS || field.key.name !in projectMaps) return@map field
                val entries =
                    (field.value as RawValue.OrderedMap).entries.map entryMap@{ entry ->
                        if (!entry.key.startsWith(USER_HOME_MACRO)) return@entryMap entry
                        if (userHome.isBlank()) return null
                        entry.copy(key = userHome + entry.key.removePrefix(USER_HOME_MACRO))
                    }
                if (entries.map { it.key }.toSet().size != entries.size) return null
                field.copy(value = RawValue.OrderedMap(StudioList(entries)))
            }
        return fields.copy(records = StudioList(records))
    }

    private const val USER_HOME_MACRO = $$"$USER_HOME$"
    private val projectMaps =
        setOf("projectAccents", "projectDisplayNames", "projectFallbackAccents", "forcedProjectLanguages")
    private val componentNames =
        mapOf(
            LegacyStore.SETTINGS to "AyuIslandsSettings",
            LegacyStore.SYNTAX to "AyuIslandsSyntaxIntensityState",
            LegacyStore.MAPPINGS to "AyuIslandsAccentMappings",
        )
}
