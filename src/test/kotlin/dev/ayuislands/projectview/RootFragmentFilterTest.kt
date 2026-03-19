package dev.ayuislands.projectview

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RootFragmentFilterTest {
    private val projectName = "my-project"
    private val basePath = "/Users/cloud/Developer/my-project"
    private val tildeBasePath = "~/Developer/my-project"

    private fun context() =
        RootNodeContext(
            projectName = projectName,
            basePath = basePath,
            tildeBasePath = tildeBasePath,
        )

    private fun isPath(trimmed: String): Boolean =
        RootFragmentFilter.isPathFragment(
            trimmed,
            context(),
        )

    @Test
    fun `empty string is not a path fragment`() {
        assertFalse(isPath(""))
    }

    @Test
    fun `project name is not a path fragment`() {
        assertFalse(isPath("my-project"))
    }

    @Test
    fun `tilde basePath is a path fragment`() {
        assertTrue(isPath("[~/Developer/my-project]"))
    }

    @Test
    fun `absolute basePath is a path fragment`() {
        assertTrue(isPath("[/Users/cloud/Developer/my-project]"))
    }

    @Test
    fun `exact basePath is a path fragment`() {
        assertTrue(isPath(basePath))
    }

    @Test
    fun `unrelated text is not a path fragment`() {
        assertFalse(isPath("main"))
        assertFalse(isPath("3 files"))
        assertFalse(isPath("feat/feature"))
    }

    @Test
    fun `null basePath never matches path`() {
        val ctx = RootNodeContext(projectName, null, null)
        assertFalse(RootFragmentFilter.isPathFragment("anything", ctx))
        assertFalse(RootFragmentFilter.isPathFragment(projectName, ctx))
        assertFalse(RootFragmentFilter.isPathFragment("", ctx))
    }
}
