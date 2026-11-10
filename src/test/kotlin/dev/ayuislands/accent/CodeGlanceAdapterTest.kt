package dev.ayuislands.accent

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import com.nasller.codeglance.config.CodeGlanceConfig
import com.nasller.codeglance.config.CodeGlanceConfigService
import dev.ayuislands.integration.codeglance.CodeGlanceAdapter
import dev.ayuislands.settings.AyuIslandsState
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CodeGlanceAdapterTest {
    private val application = mockk<Application>()
    private val service = mockk<CodeGlanceConfigService>()
    private val config = originalConfig()

    @BeforeTest
    fun setUp() {
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns application
        every { application.getService(CodeGlanceConfigService::class.java) } returns service
        every { service.state } returns config
    }

    @AfterTest
    fun tearDown() = unmockkAll()

    @Test
    fun `typed pinned config restores owned parts and preserves the foreign border and unrelated choices`() {
        val before = xml(config)
        val access = assertNotNull(CodeGlanceAdapter().resolve())
        val state = AyuIslandsState().apply { indentCustomAlpha = 37 }
        val checkpoint = CgpCheckpoint(state, { true }, { it.isCurrent() }, { error("Unexpected retry") })
        access.checkpoint = checkpoint

        assertEquals(CgpViewport("123456", "654321", 3), access.readViewport())
        assertEquals(before, xml(config), "Capture must not mutate any plugin field")
        access.writeViewport(CgpViewport("112233", "445566", 1))
        assertEquals(CgpViewport("112233", "445566", 1), access.readViewport())
        state.cgpOwnership = "OWNED"
        checkpoint.captureMetadata()
        config.viewportBorderColor = "foreign border"

        checkpoint.restore()

        val expected = originalConfig().apply { viewportBorderColor = "foreign border" }
        assertEquals(xml(expected), xml(config))
        assertEquals("SUSPENDED", state.cgpOwnership)
        assertEquals(37, state.indentCustomAlpha)
        assertFalse(checkpoint.hasPending)
    }

    @Test
    fun `replacement native config fences the captured checkpoint without rebinding`() {
        val access = assertNotNull(CodeGlanceAdapter().resolve())
        val state = AyuIslandsState()
        val checkpoint = CgpCheckpoint(state, { true }, { it.isCurrent() }, { error("Unexpected retry") })
        access.checkpoint = checkpoint
        access.writeViewport(CgpViewport("112233", "445566", 1))
        state.cgpOwnership = "OWNED"
        checkpoint.captureMetadata()
        val applied = xml(config)
        val replacement = originalConfig().apply { viewportColor = "replacement" }
        val replacementBefore = xml(replacement)
        every { service.state } returns replacement

        assertFalse(access.isCurrent())
        checkpoint.restore()

        assertEquals(applied, xml(config))
        assertEquals(replacementBefore, xml(replacement))
        assertEquals("SUSPENDED", state.cgpOwnership)
        val current = assertNotNull(CodeGlanceAdapter().resolve())
        assertTrue(current.isCurrent())
        assertEquals("replacement", current.readViewport().color)
    }

    private fun xml(settings: CodeGlanceConfig): String = JDOMUtil.writeElement(XmlSerializer.serialize(settings))

    private fun originalConfig(): CodeGlanceConfig =
        CodeGlanceConfig().apply {
            viewportColor = "123456"
            viewportBorderColor = "654321"
            viewportBorderThickness = 3
            disabled = true
            mainWidth = 137
            markersScaleFactor = 2.25f
        }
}
