package dev.ayuislands.studio

import dev.ayuislands.glow.GlowIslandIds
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.settings.GlowSettings
import dev.ayuislands.settings.WaveformSettingsValue
import dev.ayuislands.studio.fields.StudioRuntimeFields
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.persistence.StudioStateCodec
import dev.ayuislands.studio.profiles.StudioFactoryProfiles
import dev.ayuislands.studio.profiles.StudioProjectState
import dev.ayuislands.studio.session.StudioDraftRecovery
import dev.ayuislands.studio.session.StudioFontProjection
import dev.ayuislands.studio.session.StudioGlowProjection
import dev.ayuislands.studio.session.StudioJournalPolicy
import dev.ayuislands.studio.session.StudioProcessPolicy
import dev.ayuislands.studio.session.StudioSessionReducer
import dev.ayuislands.studio.session.StudioVcsProjection
import dev.ayuislands.vcs.VcsColorCategory
import dev.ayuislands.vcs.VcsColorPreset
import dev.ayuislands.vcs.VcsColorRules
import dev.ayuislands.vcs.VcsColorSnapshot
import dev.ayuislands.vcs.VcsIntensity
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StudioDependencyRulesTest {
    @Test
    fun `codec permits in memory buffers but cannot access files or platform state`() {
        val pending = ArrayDeque(listOf(StudioStateCodec::class.java.name.replace('.', '/')))
        val visited = mutableSetOf<String>()
        val memoryStreamTypes =
            setOf(
                "java/io/ByteArrayInputStream",
                "java/io/ByteArrayOutputStream",
                // StAX accepts the abstract types; the only constructed streams are byte-array buffers.
                "java/io/InputStream",
                "java/io/OutputStream",
            )
        while (pending.isNotEmpty()) {
            val name = pending.removeFirst()
            if (!visited.add(name)) continue
            val references = classReferences(name)
            val forbidden =
                references.filter { reference ->
                    reference.startsWith("com/intellij/") ||
                        reference.startsWith("javax/swing/") ||
                        reference.startsWith("java/nio/file/") ||
                        (reference.startsWith("java/io/") && reference !in memoryStreamTypes)
                }
            assertTrue(forbidden.isEmpty(), "$name depends on $forbidden")
            pending.addAll(references.filter { it.startsWith("dev/ayuislands/studio/") && it !in visited })
        }
        assertTrue(visited.size > 20)
    }

    @Test
    fun `model migration and recipe closure has no platform services IO or ambient identity`() {
        val pending =
            ArrayDeque(
                listOf(
                    StudioDocument::class.java.name,
                    StudioLegacyMigration::class.java.name,
                    StudioFactoryProfiles::class.java.name,
                    StudioProjectState::class.java.name,
                    StudioSessionReducer::class.java.name,
                    StudioJournalPolicy::class.java.name,
                    StudioProcessPolicy::class.java.name,
                    StudioDraftRecovery::class.java.name,
                    StudioRuntimeFields::class.java.name,
                    StudioFontProjection::class.java.name,
                    StudioGlowProjection::class.java.name,
                    StudioVcsProjection::class.java.name,
                    GlowSettings::class.java.name,
                    GlowIslandIds::class.java.name,
                    WaveformSettingsValue::class.java.name,
                    VcsColorCategory::class.java.name,
                    VcsColorPreset::class.java.name,
                    VcsColorRules::class.java.name,
                    VcsColorSnapshot::class.java.name,
                    VcsIntensity::class.java.name,
                ).map { it.replace('.', '/') },
            )
        val visited = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val name = pending.removeFirst()
            if (!visited.add(name)) continue
            val references = classReferences(name)
            val forbidden =
                references.filter { reference ->
                    listOf(
                        "com/intellij/",
                        "javax/swing/",
                        "java/awt/",
                        "java/nio/file/",
                        "java/io/",
                        "java/util/UUID",
                        "java/time/Clock",
                        "java/lang/ProcessHandle",
                    ).any { reference.startsWith(it) }
                }
            assertTrue(forbidden.isEmpty(), "$name depends on $forbidden")
            pending.addAll(references.filter { it.startsWith("dev/ayuislands/studio/") && it !in visited })
            if (name.contains("/model/")) {
                assertFalse(references.any { it.startsWith("kotlin/jvm/functions/") }, "$name stores behavior")
                val model = Class.forName(name.replace('/', '.'), false, javaClass.classLoader)
                assertTrue(
                    model.declaredFields.all { Modifier.isFinal(it.modifiers) || Modifier.isStatic(it.modifiers) },
                )
            }
        }
        assertTrue(visited.size > 20, "The model graph was not inspected")
        assertTrue(
            classReferences(AyuIslandsState::class.java.name.replace('.', '/')).any { it.startsWith("com/intellij/") },
        )
    }

    private fun classReferences(name: String): Set<String> {
        val resource = requireNotNull(javaClass.classLoader.getResourceAsStream("$name.class"))
        val bytecode = resource.use { it.readBytes().toString(Charsets.ISO_8859_1) }
        return Regex(
            "(?:dev/ayuislands/studio|com/intellij|javax/swing|java/awt|java/nio/file|java/io|" +
                "java/util|java/time|java/lang|kotlin/jvm/functions)/[A-Za-z0-9_$/]+",
        ).findAll(bytecode)
            .map { it.value }
            .toSet()
    }
}
