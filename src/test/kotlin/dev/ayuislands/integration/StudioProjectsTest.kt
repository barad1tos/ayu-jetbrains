package dev.ayuislands.integration

import com.intellij.openapi.project.Project
import dev.ayuislands.studio.StudioProjects
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.model.TargetPresence
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioProjectsTest {
    @Test
    fun `focus epochs change while each open project keeps its captured incarnation`() =
        onHostEdt {
            val first = project()
            val second = project()
            var active = first
            val census =
                StudioProjects({ listOf(first, second) }, { active }, {
                    if (it ===
                        first
                    ) {
                        "first"
                    } else {
                        "second"
                    }
                })
            val captured = assertNotNull(census.capture(first))
            val initial = assertNotNull(census.focus())
            active = second
            val switched = assertNotNull(census.focus())
            assertEquals(TargetPresence.INACTIVE, census.observe(captured.target).presence)
            active = first
            val returned = assertNotNull(census.focus())
            assertEquals(captured, census.capture(first))
            assertTrue(initial.runtime.generation < switched.runtime.generation)
            assertTrue(switched.runtime.generation < returned.runtime.generation)
        }

    @Test
    fun `reopening the same key invalidates old captured generation without changing another project`() =
        onHostEdt {
            val first = project()
            val second = project()
            var opened = listOf(first, second)
            var active = first
            val census = StudioProjects({ opened }, { active }, { if (it === second) "second" else "first" })
            val captured = assertNotNull(census.capture(first))
            val unaffected = assertNotNull(census.capture(second))
            opened = listOf(second)
            active = second
            assertEquals(TargetPresence.UNAVAILABLE, census.observe(captured.target).presence)
            val replacement = project()
            opened = listOf(replacement, second)
            active = replacement
            assertTrue(assertNotNull(census.capture(replacement)).generation > captured.generation)
            assertEquals(unaffected.generation, assertNotNull(census.capture(second)).generation)
            assertNull(census.capture(first))
        }

    @Test
    fun `ambiguous or missing keys never impersonate the global target`() =
        onHostEdt {
            val first = project()
            val second = project()
            val ambiguous = StudioProjects({ listOf(first, second) }, { first }, { "same" })
            assertNull(ambiguous.capture(first))
            assertNull(ambiguous.focus())
            assertEquals(
                TargetPresence.UNAVAILABLE,
                ambiguous.observe(StudioTarget.Project(StudioProjectKey("same"))).presence,
            )
            val missing = StudioProjects({ listOf(first) }, { first }, { null })
            assertNull(missing.capture(first))
            assertNull(missing.focus())
        }

    @Test
    fun `global identity survives project activity and becomes active after the last close`() =
        onHostEdt {
            val first = project()
            var opened = emptyList<Project>()
            var active: Project? = null
            val census = StudioProjects({ opened }, { active }, { "first" })
            val before = assertNotNull(census.capture(null))
            assertEquals(TargetPresence.ACTIVE, before.presence)
            opened = listOf(first)
            active = first
            assertEquals(TargetPresence.INACTIVE, assertNotNull(census.capture(null)).presence)
            opened = emptyList()
            active = null
            assertEquals(before, census.capture(null))
            assertEquals(StudioTarget.Global, assertNotNull(census.focus()).runtime.target)
        }

    private fun project(): Project =
        mockk<Project>().also {
            every { it.isDisposed } returns false
            every { it.isDefault } returns false
        }
}
