package dev.ayuislands.accent

import com.intellij.openapi.project.Project
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Contract lock for [AccentChangedTopic] and [AccentChangeListener].
 *
 * Mirrors the `ComponentTreeRefreshedTopicTest`-style shape of locking topic
 * identity at class-load time. The topic object MUST NOT throw on first touch
 * (subscribers compile against a static reference, so a `Topic.create` regression
 * would surface as `NoClassDefFoundError` deep inside a subscriber's `init {}`),
 * the `displayName` is the human-readable tag JetBrains' bus diagnostics use,
 * and the listener class must be a real `fun interface` so consumers can use
 * SAM lambdas without ceremony.
 *
 * Phase 48 D-01: application-scoped fan-out so subscribers connected on either
 * `Application.messageBus` or any `Project.messageBus` receive the event.
 */
class AccentChangedTopicTest {
    @Test
    fun `topic exists with stable display name`() {
        val topic = AccentChangedTopic.TOPIC
        assertNotNull(topic, "AccentChangedTopic.TOPIC must be non-null")
        assertEquals(
            "Ayu Accent Changed",
            topic.displayName,
            "Topic display name must be the D-01 contractual string so bus diagnostics stay grep-able",
        )
    }

    @Test
    fun `topic listener class equals AccentChangeListener`() {
        assertEquals(
            AccentChangeListener::class.java,
            AccentChangedTopic.TOPIC.listenerClass,
            "Listener class must be AccentChangeListener — downstream subscribers compile against this contract",
        )
    }

    @Test
    fun `AccentChangeListener is a fun interface with a single accentChanged method`() {
        val klass = AccentChangeListener::class.java
        assertTrue(klass.isInterface, "AccentChangeListener must be an interface")
        val abstractMethods =
            klass.declaredMethods.filter { Modifier.isAbstract(it.modifiers) }
        assertEquals(
            1,
            abstractMethods.size,
            "AccentChangeListener must expose exactly one abstract method; got: $abstractMethods",
        )
        val method = abstractMethods.single()
        assertEquals(
            "accentChanged",
            method.name,
            "The single abstract method must be named accentChanged so the topic fan-out signature is stable",
        )
        // Payload signature: (project, hex, source) — load-bearing for D-01 / D-02 / D-03.
        val params = method.parameterTypes
        assertEquals(3, params.size, "accentChanged must take exactly three parameters; got: ${params.toList()}")
        assertEquals(Project::class.java, params[0], "First parameter must be Project")
        assertEquals(String::class.java, params[1], "Second parameter must be the hex String")
        assertEquals(
            AccentResolver.Source::class.java,
            params[2],
            "Third parameter must be AccentResolver.Source so subscribers can filter by resolution layer",
        )
    }
}
