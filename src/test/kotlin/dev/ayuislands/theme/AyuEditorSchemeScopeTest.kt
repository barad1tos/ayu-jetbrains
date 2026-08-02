package dev.ayuislands.theme

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import dev.ayuislands.accent.AyuVariant
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class AyuEditorSchemeScopeTest {
    private val editorColorsManager = mockk<EditorColorsManager>()

    @BeforeTest
    fun setUp() {
        AyuEditorSchemeScope.resetClaims()
        mockkObject(AyuVariant.Companion)
        mockkStatic(EditorColorsManager::class)
        every { AyuVariant.detect() } returns AyuVariant.MIRAGE
        every { EditorColorsManager.getInstance() } returns editorColorsManager
    }

    @AfterTest
    fun tearDown() {
        AyuEditorSchemeScope.resetClaims()
        unmockkAll()
    }

    @Test
    fun `activeScheme returns matching canonical and editable schemes`() {
        listOf("Ayu Islands Mirage", "_@user_Ayu Islands Mirage").forEach { schemeName ->
            val scheme = scheme(schemeName)
            every { editorColorsManager.globalScheme } returns scheme

            assertSame(scheme, AyuEditorSchemeScope.activeScheme())
        }
    }

    @Test
    fun `activeScheme rejects foreign and different variant schemes`() {
        listOf("Solarized Dark", "_@user_Solarized Dark", "Ayu Islands Dark").forEach { schemeName ->
            every { editorColorsManager.globalScheme } returns scheme(schemeName)

            assertNull(AyuEditorSchemeScope.activeScheme(), "Scheme '$schemeName' must remain outside accent scope")
        }
    }

    @Test
    fun `activeScheme does not read editor manager when Ayu is inactive`() {
        every { AyuVariant.detect() } returns null

        assertNull(AyuEditorSchemeScope.activeScheme())
        verify(exactly = 0) { EditorColorsManager.getInstance() }
    }

    @Test
    fun `currentAyuScheme returns Ayu scheme when Ayu is inactive`() {
        val scheme = scheme("_@user_Ayu Islands Mirage")
        every { AyuVariant.detect() } returns null
        every { editorColorsManager.globalScheme } returns scheme

        assertSame(scheme, AyuEditorSchemeScope.currentAyuScheme())
    }

    @Test
    fun `currentAyuScheme rejects foreign scheme`() {
        every { editorColorsManager.globalScheme } returns scheme("Solarized Dark")

        assertNull(AyuEditorSchemeScope.currentAyuScheme())
    }

    @Test
    fun `accent claim retains exact scheme after global scheme changes`() {
        val claimed = scheme("Ayu Islands Mirage")
        every { editorColorsManager.globalScheme } returns claimed
        assertSame(claimed, AyuEditorSchemeScope.claimActiveScheme())

        every { editorColorsManager.globalScheme } returns scheme("Ayu Islands Dark")

        assertSame(claimed, AyuEditorSchemeScope.claimedAccentSchemes().single())
    }

    @Test
    fun `cleanup releases successful scheme and retains only failed identity`() {
        val failedScheme = scheme("Ayu Islands Mirage")
        val cleanedScheme = scheme("Ayu Islands Mirage")
        every { editorColorsManager.globalScheme } returns failedScheme
        AyuEditorSchemeScope.claimActiveScheme()
        every { editorColorsManager.globalScheme } returns cleanedScheme
        AyuEditorSchemeScope.claimActiveScheme()
        AyuEditorSchemeScope.beginAccentCleanup()

        assertFailsWith<IllegalStateException> {
            AyuEditorSchemeScope.cleanClaimedAccentSchemes { scheme ->
                if (scheme === failedScheme) error("cleanup failed")
            }
        }
        AyuEditorSchemeScope.releaseCleanAccentClaims()

        assertEquals(1, AyuEditorSchemeScope.claimedAccentSchemes().size)
        assertSame(failedScheme, AyuEditorSchemeScope.claimedAccentSchemes().single())
    }

    private fun scheme(name: String): EditorColorsScheme =
        mockk {
            every { this@mockk.name } returns name
        }
}
