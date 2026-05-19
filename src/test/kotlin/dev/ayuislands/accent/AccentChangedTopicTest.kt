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
 * SAM lambdas without ceremony. The topic is application-scoped so subscribers
 * connected on either `Application.messageBus` or any `Project.messageBus`
 * receive the event.
 *
 * `Topic.getListenerClass()` is `@ApiStatus.Internal` — used here only as a
 * reflective contract lock for the topic's listener type. No public equivalent
 * exists; the alternative is no regression coverage on the SAM identity.
 */
@Suppress("UnstableApiUsage")
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
        // CRIT-6: Kotlin mangles the JVM name of functions that take a value
        // class parameter (`AccentHex` → `accentChanged-Czfobf0`). The Kotlin
        // source-level name is still `accentChanged`; the JVM-visible mangled
        // suffix is the value-class fingerprint. Assert prefix so the test
        // tolerates the documented mangle but still locks the source identifier.
        assertTrue(
            method.name.startsWith("accentChanged"),
            "The single abstract method's JVM name must start with `accentChanged` " +
                "(mangle suffix is value-class fingerprint); got: ${method.name}",
        )
        // Payload signature: (project, AccentHex erased to String, source) — load-bearing for D-01 / D-02 / D-03.
        // CRIT-6: hex parameter lifted from raw String to the validated [AccentHex]
        // value class. JVM bytecode erases value classes to their underlying type
        // (`String`), but the mangled method name (`accentChanged-Czfobf0`,
        // asserted above) carries the value-class fingerprint and prevents an
        // accidental rewind to a raw-String signature.
        val params = method.parameterTypes
        assertEquals(3, params.size, "accentChanged must take exactly three parameters; got: ${params.toList()}")
        assertEquals(Project::class.java, params[0], "First parameter must be Project")
        assertEquals(
            String::class.java,
            params[1],
            "Second parameter is `AccentHex` at Kotlin source, erased to `String` at JVM " +
                "(see mangled method-name check above for the value-class lock)",
        )
        assertEquals(
            AccentResolver.Source::class.java,
            params[2],
            "Third parameter must be AccentResolver.Source so subscribers can filter by resolution layer",
        )
    }

    @Test
    fun `accentChanged JVM name carries value-class mangle suffix (Pattern K lock)`() {
        // Pattern K source-level regression lock — a casual refactor that
        // changes the hex parameter back to a raw String would also remove
        // the `-Czfobf0` mangle suffix from the method name, because Kotlin
        // mangles JVM names for any function taking a value-class parameter.
        // Locking on `contains("-")` proves at least one value-class parameter
        // remains in the signature.
        val method =
            AccentChangeListener::class.java.declaredMethods
                .single { Modifier.isAbstract(it.modifiers) }
        assertTrue(
            method.name.contains("-"),
            "accentChanged must carry a Kotlin value-class mangle suffix " +
                "(proves at least one parameter is an AccentHex/value class); got: ${method.name}",
        )
    }
}
