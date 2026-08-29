package dev.ayuislands.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeStatusTest {
    @Test
    fun `runtime failure stays actionable until a successful Ayu update`() {
        val status = RuntimeStatus()

        status.failed(IllegalStateException("preview failed"))

        assertTrue(status.component.isVisible)
        assertTrue(status.component.text.contains("saved choices were not changed"))
        assertTrue(status.component.text.contains("click Apply to retry"))
        status.applied()
        assertFalse(status.component.isVisible)
    }

    @Test
    fun `environment reports explain how live preview resumes`() {
        val status = RuntimeStatus()

        status.foreignScheme()

        assertTrue(status.component.text.contains("Select an Ayu scheme to resume"))
        status.relinquished("SWIFT_OPERATOR")
        assertTrue(status.component.text.contains("Change its control again"))
    }
}
