package dev.ayuislands.studio

import com.intellij.openapi.components.BaseState
import com.intellij.util.xmlb.XmlSerializer
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.freshSettingsState
import dev.ayuislands.settings.mappings.AccentMappingsState
import dev.ayuislands.studio.migration.LegacySnapshot
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.FieldOrigin
import dev.ayuislands.studio.model.LegacyArchive
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyFile
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.MigrationSource
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SourcePresence
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioField
import dev.ayuislands.studio.model.StudioFields
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.StudioLegacyProjection
import dev.ayuislands.studio.persistence.StudioLegacyView
import dev.ayuislands.studio.profiles.StudioFactoryProfiles
import dev.ayuislands.syntax.SyntaxIntensityBaseState
import java.util.Base64

internal object StudioFixtures {
    /** Resamples explicit test beans through the same migration and accepted projection as production. */
    fun legacyView(
        settings: AyuIslandsState,
        syntax: SyntaxIntensityBaseState = SyntaxIntensityBaseState(),
        mappings: AccentMappingsState = AccentMappingsState(),
    ): StudioLegacyView =
        checkNotNull(StudioLegacyProjection.project(legacyDocument(settings, syntax, mappings), StudioTarget.Global))

    fun legacyDocument(
        settings: AyuIslandsState,
        syntax: SyntaxIntensityBaseState = SyntaxIntensityBaseState(),
        mappings: AccentMappingsState = AccentMappingsState(),
    ): dev.ayuislands.studio.model.StudioDocument {
        val snapshot = capture(settings).copy(fields = fields(settings, syntax, mappings))
        return checkNotNull(StudioLegacyMigration.plan(snapshot) as? MigrationResult.Ready).document
    }

    /** Reads the legacy XML representation, then round-trips the actual accepted Studio envelope. */
    fun persistedView(
        settings: AyuIslandsState,
        syntax: SyntaxIntensityBaseState = SyntaxIntensityBaseState(),
        mappings: AccentMappingsState = AccentMappingsState(),
    ): StudioLegacyView {
        val imported =
            legacyDocument(
                XmlSerializer.deserialize(XmlSerializer.serialize(settings), AyuIslandsState::class.java),
                XmlSerializer.deserialize(XmlSerializer.serialize(syntax), SyntaxIntensityBaseState::class.java),
                XmlSerializer.deserialize(XmlSerializer.serialize(mappings), AccentMappingsState::class.java),
            )
        val bytes =
            dev.ayuislands.studio.persistence.StudioStateCodec
                .encode(imported)
        val reloaded =
            checkNotNull(
                dev.ayuislands.studio.persistence.StudioStateCodec.decode(
                    bytes,
                ) as? dev.ayuislands.studio.persistence.DecodeResult.Ready,
            )
        return checkNotNull(StudioLegacyProjection.project(reloaded.document, StudioTarget.Global))
    }

    fun fields(
        settings: AyuIslandsState = freshSettingsState(),
        syntax: SyntaxIntensityBaseState = SyntaxIntensityBaseState(),
        mappings: AccentMappingsState = AccentMappingsState(),
        origins: Map<LegacyFieldKey, FieldOrigin> = emptyMap(),
    ): StudioFields {
        val beans: Map<LegacyStore, BaseState> =
            mapOf(
                LegacyStore.SETTINGS to settings,
                LegacyStore.SYNTAX to syntax,
                LegacyStore.MAPPINGS to mappings,
            )
        val records =
            beans.flatMap { (store, bean) ->
                bean.javaClass.declaredFields.filter { it.name.endsWith($$"$delegate") }.map { property ->
                    val name = property.name.removeSuffix($$"$delegate")
                    val getter =
                        if (name.startsWith("is")) {
                            name
                        } else {
                            "get" +
                                name.replaceFirstChar { it.uppercaseChar() }
                        }
                    StudioField(
                        LegacyFieldKey(store, name),
                        rawValue(bean.javaClass.getMethod(getter).invoke(bean)),
                        origins[LegacyFieldKey(store, name)] ?: FieldOrigin(SourcePresence.ABSENT),
                    )
                }
            }
        return StudioFields(StudioList(records))
    }

    fun load(name: String): LegacySnapshot {
        val prefix = "/studio/legacy/$name/"
        val settingsUrl = javaClass.getResource(prefix + "settings.xml")
        val syntaxUrl = javaClass.getResource(prefix + "syntax.xml")
        val mappingsUrl = javaClass.getResource(prefix + "mappings.xml")
        val settings =
            settingsUrl?.let { XmlSerializer.deserialize(it, AyuIslandsState::class.java) } ?: freshSettingsState()
        val syntax =
            syntaxUrl?.let { XmlSerializer.deserialize(it, SyntaxIntensityBaseState::class.java) }
                ?: SyntaxIntensityBaseState()
        val mappings =
            mappingsUrl?.let { XmlSerializer.deserialize(it, AccentMappingsState::class.java) }
                ?: AccentMappingsState()
        val origins =
            resourceLines(name, "source-records.tsv").associate { line ->
                val parts = line.split('\t')
                LegacyFieldKey(LegacyStore.valueOf(parts[0]), parts[1]) to
                    FieldOrigin(
                        SourcePresence.valueOf(parts[2]),
                        StudioBytes(Base64.getDecoder().decode(parts[3])),
                    )
            }
        val archives =
            LegacyStore.entries.map { store ->
                val resource = javaClass.getResourceAsStream(prefix + store.name.lowercase() + ".xml")
                val source = resource?.use { LegacyFile.Present(StudioBytes(it.readBytes())) } ?: LegacyFile.Absent
                LegacyArchive(store, source)
            }
        return LegacySnapshot(
            MigrationSource("fixture-$name", StudioList(archives)),
            fields(settings, syntax, mappings, origins),
            StudioFactoryProfiles.create(fields()),
            transactionId = "transaction-$name",
        )
    }

    fun resourceLines(
        fixture: String,
        file: String,
    ): List<String> =
        requireNotNull(javaClass.getResourceAsStream("/studio/legacy/$fixture/$file"))
            .bufferedReader()
            .use { it.readLines().filter(String::isNotBlank) }

    fun capture(
        settings: AyuIslandsState = AyuIslandsState(),
        fresh: Boolean = false,
    ): LegacySnapshot {
        val archives =
            LegacyStore.entries.map { store ->
                val source =
                    if (fresh) {
                        LegacyFile.Absent
                    } else {
                        LegacyFile.Present(
                            StudioBytes("<state/>".encodeToByteArray()),
                        )
                    }
                LegacyArchive(store, source)
            }
        return LegacySnapshot(
            MigrationSource("migration-1", StudioList(archives)),
            fields(settings),
            StudioFactoryProfiles.create(fields()),
            transactionId = "transaction-1",
        )
    }

    // Test-only reflection samples public getters of our own beans; Java invocation returns Object.
    private fun rawValue(value: Any?): RawValue =
        when (value) {
            null -> RawValue.Null
            is String -> RawValue.Text(value)
            is Boolean -> RawValue.BooleanValue(value)
            is Int -> RawValue.Integer(value)
            is Long -> RawValue.LongValue(value)
            is Float -> RawValue.FloatBits(value.toRawBits())
            is Map<*, *> ->
                RawValue.OrderedMap(
                    StudioList(
                        value.entries.map { entry ->
                            require(entry.key is String && entry.value is String)
                            RawValue.Entry(entry.key.toString(), RawValue.Text(entry.value.toString()))
                        },
                    ),
                )
            is Set<*> ->
                RawValue.OrderedSet(
                    StudioList(
                        value.map { item ->
                            require(item is String)
                            item
                        },
                    ),
                )
            else -> error("Unsupported test source type: ${value.javaClass.name}")
        }
}

internal fun StudioFields.value(name: String): RawValue = records.single { it.key.name == name }.value
