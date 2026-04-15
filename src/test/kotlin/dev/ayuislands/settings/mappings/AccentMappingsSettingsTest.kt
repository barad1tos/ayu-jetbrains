package dev.ayuislands.settings.mappings

import com.intellij.testFramework.LoggedErrorProcessor
import java.util.ConcurrentModificationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the `$USER_HOME$` path-macro migration in [AccentMappingsSettings.loadState].
 *
 * IntelliJ's storage layer historically wrote project paths with the `$USER_HOME$` macro
 * substituted, but did not expand them on load for arbitrary map keys. Without the
 * migration, `AccentResolver.projectKey(project)` (returning an absolute canonical path)
 * never matches the macro-prefixed stored key and every override silently falls through
 * to the global accent. The settings storage annotation now pins `usePathMacroManager = false`,
 * but legacy XML files from older builds still contain the macro — this suite ensures the
 * load-time rewrite survives.
 */
class AccentMappingsSettingsTest {
    private var originalUserHome: String? = null

    @BeforeTest
    fun setUp() {
        originalUserHome = System.getProperty("user.home")
    }

    @AfterTest
    fun tearDown() {
        val saved = originalUserHome
        if (saved != null) {
            System.setProperty("user.home", saved)
        } else {
            System.clearProperty("user.home")
        }
    }

    @Test
    fun `loadState expands USER_HOME macro in projectAccents keys`() {
        System.setProperty("user.home", "/Users/alice")
        val settings = AccentMappingsSettings()
        val state =
            AccentMappingsState().apply {
                projectAccents["\$USER_HOME\$/dev/foo"] = "#FFCD66"
            }

        settings.loadState(state)

        assertNull(settings.state.projectAccents["\$USER_HOME\$/dev/foo"], "macro key must be rewritten, not retained")
        assertEquals("#FFCD66", settings.state.projectAccents["/Users/alice/dev/foo"])
    }

    @Test
    fun `loadState expands USER_HOME macro in projectDisplayNames keys`() {
        System.setProperty("user.home", "/Users/alice")
        val settings = AccentMappingsSettings()
        val state =
            AccentMappingsState().apply {
                projectDisplayNames["\$USER_HOME\$/dev/foo"] = "Foo"
            }

        settings.loadState(state)

        assertEquals("Foo", settings.state.projectDisplayNames["/Users/alice/dev/foo"])
        assertNull(settings.state.projectDisplayNames["\$USER_HOME\$/dev/foo"])
    }

    @Test
    fun `loadState leaves absolute paths untouched`() {
        System.setProperty("user.home", "/Users/alice")
        val settings = AccentMappingsSettings()
        val state =
            AccentMappingsState().apply {
                projectAccents["/tmp/already-absolute"] = "#FFCD66"
                projectDisplayNames["/tmp/already-absolute"] = "AbsoluteProj"
            }

        settings.loadState(state)

        assertEquals("#FFCD66", settings.state.projectAccents["/tmp/already-absolute"])
        assertEquals("AbsoluteProj", settings.state.projectDisplayNames["/tmp/already-absolute"])
    }

    @Test
    fun `loadState does not touch languageAccents even if a key starts with the macro-like prefix`() {
        System.setProperty("user.home", "/Users/alice")
        val settings = AccentMappingsSettings()
        val state =
            AccentMappingsState().apply {
                languageAccents["kotlin"] = "#D5FF80"
                projectAccents["\$USER_HOME\$/dev/foo"] = "#FFCD66"
            }

        settings.loadState(state)

        assertEquals("#D5FF80", settings.state.languageAccents["kotlin"])
        assertEquals("#FFCD66", settings.state.projectAccents["/Users/alice/dev/foo"])
    }

    @Test
    fun `loadState is a no-op migration when user home is blank`() {
        // Setting the property to a blank string triggers the `takeIf(isNotBlank)` guard
        // — the migration must not attempt string concatenation with an empty home.
        System.setProperty("user.home", "")
        val settings = AccentMappingsSettings()
        val state =
            AccentMappingsState().apply {
                projectAccents["\$USER_HOME\$/dev/foo"] = "#FFCD66"
            }

        settings.loadState(state)

        // Macro stays as-is; migration skipped.
        assertEquals("#FFCD66", settings.state.projectAccents["\$USER_HOME\$/dev/foo"])
    }

    @Test
    fun `loadState migration is idempotent on already-migrated state`() {
        System.setProperty("user.home", "/Users/alice")
        val settings = AccentMappingsSettings()
        val state =
            AccentMappingsState().apply {
                projectAccents["\$USER_HOME\$/dev/foo"] = "#FFCD66"
            }
        settings.loadState(state)

        // Second load of the already-rewritten state should leave it alone.
        val afterFirstLoad = settings.state.projectAccents.toMap()
        settings.loadState(settings.state)

        assertEquals(afterFirstLoad, settings.state.projectAccents.toMap())
    }

    @Test
    fun `loadState warns when legacy keys exist but user home is unavailable`() {
        // Red-green guard for the actionable breadcrumb commit b1c1e4e added: without
        // user.home, stored `$USER_HOME$/...` keys can never be migrated, so the user's
        // overrides invisibly fall through to the global accent. The warn log is the only
        // signal an affected user has — removing it would silently regress the migration UX.
        System.setProperty("user.home", "")
        val settings = AccentMappingsSettings()
        val state =
            AccentMappingsState().apply {
                projectAccents["\$USER_HOME\$/dev/foo"] = "#FFCD66"
            }

        val capturedMessages = mutableListOf<String>()
        val processor =
            object : LoggedErrorProcessor() {
                override fun processWarn(
                    category: String,
                    message: String,
                    throwable: Throwable?,
                ): Boolean {
                    capturedMessages += message
                    return false // suppress promotion to AssertionError in test environment
                }
            }

        LoggedErrorProcessor.executeWith<RuntimeException>(processor) {
            settings.loadState(state)
        }

        assertTrue(
            capturedMessages.any { it.contains("legacy") && it.contains("user.home") },
            "Expected a warn about legacy \$USER_HOME\$ keys + missing user.home, " +
                "got: $capturedMessages",
        )
    }

    @Test
    fun `loadState does NOT warn when user home is blank but no legacy keys exist`() {
        // Symmetric guard: the warn is gated on legacy keys being present. A user with a
        // blank user.home and no legacy keys should NOT see the breadcrumb (false-positive
        // would cry wolf and dilute the signal for actually-affected users).
        System.setProperty("user.home", "")
        val settings = AccentMappingsSettings()
        val state =
            AccentMappingsState().apply {
                projectAccents["/Users/alice/dev/foo"] = "#FFCD66"
            }

        val capturedMessages = mutableListOf<String>()
        val processor =
            object : LoggedErrorProcessor() {
                override fun processWarn(
                    category: String,
                    message: String,
                    throwable: Throwable?,
                ): Boolean {
                    capturedMessages += message
                    return false // suppress promotion to AssertionError in test environment
                }
            }

        LoggedErrorProcessor.executeWith<RuntimeException>(processor) {
            settings.loadState(state)
        }

        assertTrue(
            capturedMessages.none { it.contains("legacy") },
            "No legacy-key warn should fire when no \$USER_HOME\$ keys are present, " +
                "got: $capturedMessages",
        )
    }

    @Test
    fun `getInstance returns a usable service — companion wired correctly`() {
        // Defensive: constructor should not throw and companion accessor should compile.
        val settings = AccentMappingsSettings()
        assertNotNull(settings.state)
        assertTrue(settings.state.projectAccents.isEmpty())
    }

    @Test
    fun `migrateUserHomeMacro links both rewrite failures via addSuppressed`() {
        // When both map rewrites throw (e.g., concurrent structural modification during
        // startup deserialization), triage must see BOTH causes — not just the first.
        // Without addSuppressed linkage, collapsing to `primary ?: secondary` loses the
        // second failure mode, which matters when the two exceptions carry different
        // context (different map, different mutating thread, different stack).
        //
        // BaseState-delegated maps on the real `AccentMappingsState` can't easily be
        // swapped for throwing ones — instead, we go through the `@internal` seam and
        // hand it maps whose iteration deliberately throws.
        System.setProperty("user.home", "/Users/alice")
        val settings = AccentMappingsSettings()
        val accentsBoom = ConcurrentModificationException("accents map mutated during load")
        val namesBoom = ConcurrentModificationException("names map mutated during load")
        val throwingAccents = ThrowingMap(accentsBoom)
        val throwingNames = ThrowingMap(namesBoom)

        val captured = mutableListOf<Pair<String, Throwable?>>()
        val processor =
            object : LoggedErrorProcessor() {
                override fun processWarn(
                    category: String,
                    message: String,
                    throwable: Throwable?,
                ): Boolean {
                    captured += message to throwable
                    return false
                }
            }

        LoggedErrorProcessor.executeWith<RuntimeException>(processor) {
            settings.migrateUserHomeMacro(throwingAccents, throwingNames)
        }

        val migrationWarn =
            captured.firstOrNull { it.first.contains("Failed to migrate") }
                ?: error("Expected a 'Failed to migrate' warn, got: $captured")
        val primary =
            assertNotNull(
                migrationWarn.second,
                "warn must carry the primary exception for triage",
            )
        assertEquals(
            accentsBoom,
            primary,
            "accents cause must be the primary exception (not the names cause)",
        )
        assertEquals(
            listOf(namesBoom),
            primary.suppressed.toList(),
            "names cause must be linked via addSuppressed so both failure modes are " +
                "visible in the stack trace; got suppressed=${primary.suppressed.toList()}",
        )
    }

    @Test
    fun `migrateUserHomeMacro logs only the names cause when accents rewrite succeeds`() {
        // Mirror of the accents-fails-only asymmetry test: proves the `primary =
        // accentsCause ?: namesCause` precedence correctly promotes `namesCause` when
        // it's the only failure. A regression flipping the precedence (e.g.
        // `namesCause ?: accentsCause`) would pass the accents-only test but fail this one.
        System.setProperty("user.home", "/Users/alice")
        val settings = AccentMappingsSettings()
        val namesBoom = ConcurrentModificationException("names-only boom")
        val healthyAccents = mutableMapOf<String, String>("/tmp/proj" to "#FFCD66")
        val throwingNames = ThrowingMap(namesBoom)

        val capturedThrowables = mutableListOf<Throwable?>()
        val processor =
            object : LoggedErrorProcessor() {
                override fun processWarn(
                    category: String,
                    message: String,
                    throwable: Throwable?,
                ): Boolean {
                    if (message.contains("Failed to migrate")) {
                        capturedThrowables += throwable
                    }
                    return false
                }
            }

        LoggedErrorProcessor.executeWith<RuntimeException>(processor) {
            settings.migrateUserHomeMacro(healthyAccents, throwingNames)
        }

        val primary =
            assertNotNull(
                capturedThrowables.single(),
                "single-failure warn must carry a non-null cause",
            )
        assertEquals(namesBoom, primary, "single-failure warn must carry the names cause")
        assertTrue(
            primary.suppressed.isEmpty(),
            "no addSuppressed linkage when only one rewrite failed; got suppressed=${primary.suppressed.toList()}",
        )
    }

    @Test
    fun `migrateUserHomeMacro swallows exceptions from legacy-key probe when user home is blank`() {
        // The defensive `runCatching` in `logBlankUserHomeIfLegacyKeysPresent` exists because
        // probing `.keys` on a platform-backed BaseState map during startup deserialization
        // could theoretically race with a concurrent write. Exercise it directly: blank
        // user.home drives the no-migration branch, and a throwing map simulates the CME so
        // settings loading must still complete without propagating.
        System.setProperty("user.home", "")
        val settings = AccentMappingsSettings()
        val probeBoom = ConcurrentModificationException("keys probed mid-write")
        val throwingAccents = ThrowingMap(probeBoom)
        val throwingNames = ThrowingMap(probeBoom)

        val capturedMessages = mutableListOf<String>()
        val processor =
            object : LoggedErrorProcessor() {
                override fun processWarn(
                    category: String,
                    message: String,
                    throwable: Throwable?,
                ): Boolean {
                    capturedMessages += message
                    return false
                }
            }

        LoggedErrorProcessor.executeWith<RuntimeException>(processor) {
            settings.migrateUserHomeMacro(throwingAccents, throwingNames)
        }

        // The "legacy keys" warn must NOT fire — the probe returned false by default after
        // catching the CME. If the runCatching were removed, the probe would propagate and
        // the migration path would escalate out of loadState.
        assertTrue(
            capturedMessages.none { it.contains("Cannot migrate legacy") },
            "Blank user.home + throwing probe must not fire the 'legacy keys' warn " +
                "(probe's runCatching swallows the CME to default=false); got: $capturedMessages",
        )
    }

    @Test
    fun `migrateUserHomeMacro logs only the accents cause when names rewrite succeeds`() {
        // Asymmetry check: when ONLY the accents rewrite throws, the warn must carry the
        // accents cause unchanged — no spurious `addSuppressed` wiring when there's no
        // secondary cause to link.
        System.setProperty("user.home", "/Users/alice")
        val settings = AccentMappingsSettings()
        val accentsBoom = ConcurrentModificationException("accents-only boom")
        val throwingAccents = ThrowingMap(accentsBoom)
        val healthyNames = mutableMapOf<String, String>("/tmp/proj" to "Foo")

        val capturedThrowables = mutableListOf<Throwable?>()
        val processor =
            object : LoggedErrorProcessor() {
                override fun processWarn(
                    category: String,
                    message: String,
                    throwable: Throwable?,
                ): Boolean {
                    if (message.contains("Failed to migrate")) {
                        capturedThrowables += throwable
                    }
                    return false
                }
            }

        LoggedErrorProcessor.executeWith<RuntimeException>(processor) {
            settings.migrateUserHomeMacro(throwingAccents, healthyNames)
        }

        val primary =
            assertNotNull(
                capturedThrowables.single(),
                "single-failure warn must carry a non-null cause",
            )
        assertEquals(accentsBoom, primary, "single-failure warn must carry the accents cause")
        assertTrue(
            primary.suppressed.isEmpty(),
            "no addSuppressed linkage when only one rewrite failed; got suppressed=${primary.suppressed.toList()}",
        )
    }

    /**
     * Throws a caller-specified exception on every iteration-shaped accessor
     * (`entries`, `keys`, `values`). `size` / `isEmpty` / `containsKey` delegate to a
     * non-empty backing map so unrelated probes inside `rewriteKeys` or the stdlib's
     * collection extensions don't surface a surprise throw before the intended iteration
     * reaches `entries`. (`Map.none(predicate)` itself has no `isEmpty` short-circuit —
     * only the no-arg `Map.none()` does — but keeping the non-empty backing keeps the
     * helper well-behaved as a general-purpose `MutableMap` for any future caller.)
     * Exercises the `runCatching { rewriteKeys(...) }` branch in
     * [AccentMappingsSettings.migrateUserHomeMacro] without reflection or instrumentation.
     */
    private class ThrowingMap(
        private val boom: RuntimeException,
        private val backing: MutableMap<String, String> = mutableMapOf("/seed" to "value"),
    ) : MutableMap<String, String> by backing {
        override val entries: MutableSet<MutableMap.MutableEntry<String, String>>
            get() = throw boom
        override val keys: MutableSet<String>
            get() = throw boom
        override val values: MutableCollection<String>
            get() = throw boom
    }
}
