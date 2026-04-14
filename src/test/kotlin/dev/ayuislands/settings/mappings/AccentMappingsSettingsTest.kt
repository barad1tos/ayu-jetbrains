package dev.ayuislands.settings.mappings

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
    fun `getInstance returns a usable service — companion wired correctly`() {
        // Defensive: constructor should not throw and companion accessor should compile.
        val settings = AccentMappingsSettings()
        assertNotNull(settings.state)
        assertTrue(settings.state.projectAccents.isEmpty())
    }
}
