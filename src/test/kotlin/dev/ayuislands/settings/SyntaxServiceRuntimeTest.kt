package dev.ayuislands.settings

import dev.ayuislands.syntax.SyntaxIntensityService
import dev.ayuislands.syntax.SyntaxPresetConfig
import dev.ayuislands.syntax.SyntaxTransactionResult
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyntaxServiceRuntimeTest {
    @Test
    fun `runtime write guard covers synchronous preview publication`() {
        val session = mockk<SyntaxIntensityService.SyntaxRuntimeSession>()
        lateinit var runtime: SyntaxServiceRuntime
        every { session.preview(any()) } answers {
            assertTrue(runtime.isWriting)
            applied()
        }
        runtime = serviceRuntime(session)

        runtime.preview(config())

        assertFalse(runtime.isWriting)
    }

    @Test
    fun `environment callbacks stay presentation-only`() {
        val relinquished = mutableListOf<String>()
        var foreignReports = 0
        val runtime =
            SyntaxServiceRuntime(
                runtime = mockk(relaxed = true),
                recover = { _, _ -> },
                onRelinquished = relinquished::add,
                onForeignScheme = { foreignReports++ },
            )

        runtime.recordRelinquishment("SWIFT_OPERATOR")
        runtime.showForeignScheme()

        assertEquals(listOf("SWIFT_OPERATOR"), relinquished)
        assertEquals(1, foreignReports)
    }

    private fun serviceRuntime(session: SyntaxIntensityService.SyntaxRuntimeSession): SyntaxServiceRuntime =
        SyntaxServiceRuntime(
            runtime = session,
            recover = { _, _ -> },
            onRelinquished = {},
            onForeignScheme = {},
        )

    private fun config(): SyntaxPresetConfig =
        SyntaxPresetConfig(selectedPreset = "AMBIENT", customOverrides = emptyMap())

    private fun applied(): SyntaxTransactionResult = SyntaxTransactionResult.Applied(emptySet(), emptySet())
}
