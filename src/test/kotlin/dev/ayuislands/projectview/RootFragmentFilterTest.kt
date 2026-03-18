package dev.ayuislands.projectview

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RootFragmentFilterTest {
    private val projectName = "my-project"
    private val basePath = "/Users/cloud/Developer/my-project"
    private val tildeBasePath = "~/Developer/my-project"

    @Test
    fun `empty string is not kept`() {
        assertFalse(RootFragmentFilter.isKeptFragment("", projectName, basePath, tildeBasePath))
    }

    @Test
    fun `project name is kept`() {
        assertTrue(RootFragmentFilter.isKeptFragment("my-project", projectName, basePath, tildeBasePath))
    }

    @Test
    fun `absolute base path fragment is kept`() {
        assertTrue(
            RootFragmentFilter.isKeptFragment(
                "[/Users/cloud/Developer/my-project]",
                projectName,
                basePath,
                tildeBasePath,
            ),
        )
    }

    @Test
    fun `tilde base path fragment is kept`() {
        assertTrue(
            RootFragmentFilter.isKeptFragment(
                "[~/Developer/my-project]",
                projectName,
                basePath,
                tildeBasePath,
            ),
        )
    }

    @Test
    fun `VCS branch annotation is not kept`() {
        assertFalse(
            RootFragmentFilter.isKeptFragment(
                "main",
                projectName,
                basePath,
                tildeBasePath,
            ),
        )
    }

    @Test
    fun `changed file count annotation is not kept`() {
        assertFalse(
            RootFragmentFilter.isKeptFragment(
                "3 files",
                projectName,
                basePath,
                tildeBasePath,
            ),
        )
    }

    @Test
    fun `null basePath still matches project name`() {
        assertTrue(RootFragmentFilter.isKeptFragment("my-project", projectName, null, null))
    }

    @Test
    fun `null basePath rejects unrelated text`() {
        assertFalse(RootFragmentFilter.isKeptFragment("feat/feature", projectName, null, null))
    }

    @Test
    fun `whitespace-only after trim is not kept`() {
        assertFalse(RootFragmentFilter.isKeptFragment("", projectName, basePath, tildeBasePath))
    }

    @Test
    fun `exact basePath match is kept`() {
        assertTrue(
            RootFragmentFilter.isKeptFragment(basePath, projectName, basePath, tildeBasePath),
        )
    }
}
