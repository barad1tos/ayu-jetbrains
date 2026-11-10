package dev.ayuislands.studio

import com.intellij.testFramework.junit5.RunInEdt
import dev.ayuislands.glow.GlowFocusListener
import dev.ayuislands.glow.GlowStyle
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.RestoreStatus
import dev.ayuislands.studio.session.StudioGlowFocusWrite
import dev.ayuislands.studio.session.StudioGlowReferences
import java.awt.Color
import java.time.Instant
import javax.swing.JTextField
import javax.swing.border.EmptyBorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunInEdt
class StudioGlowFocusWriteTest {
    private val references = StudioGlowReferences("transaction", StudioProcess(42, Instant.EPOCH))

    @Test
    fun `focus drift needs fresh captured evidence and restores the unfocused baseline`() {
        val field = FocusField()
        val baseline = field.border
        val listener = listener(field)
        try {
            val write = binding(listener)
            val original = write.receipts
            write.apply()
            val applied = field.border
            write.prepareRestore()
            val first = write.receipts
            assertEquals(original.size + 1, first.size)
            assertSame(applied, field.border)
            field.isFocusedNow = false
            assertFailsWith<IllegalStateException> { write.restore(emptyList()) }
            assertSame(applied, field.border)
            write.prepareRestore()
            val second = write.receipts
            assertEquals(first.size + 1, second.size)
            assertEvidence(first, second)
            write.restore(emptyList())
            assertSame(baseline, field.border)
            assertTrue(write.receipts.all { it.status == RestoreStatus.RESTORED })
            write.prepareRestore()
            assertEquals(second.size, write.receipts.size)
        } finally {
            listener.detach()
        }
    }

    @Test
    fun `foreign border and terminal evidence survive later equal looking ownership`() {
        val field = FocusField()
        val listener = listener(field)
        try {
            val write = binding(listener)
            write.apply()
            val applied = field.border
            write.prepareRestore()
            val foreign = EmptyBorder(7, 6, 5, 4)
            field.border = foreign
            write.restore(emptyList())
            val settled = write.receipts
            assertSame(foreign, field.border)
            val borders = settled.filter { it.identity.field == "glow/focus/BORDER" }
            assertTrue(borders.all { it.status == RestoreStatus.FOREIGN })
            field.border = applied
            write.prepareRestore()
            write.restore(emptyList())
            assertSame(applied, field.border)
            assertEquals(settled, write.receipts)
        } finally {
            listener.detach()
        }
    }

    @Test
    fun `shared component blocker leaves listener appearance independently recoverable`() {
        val field = FocusField()
        val listener = listener(field)
        try {
            val before = field.border
            val write = binding(listener)
            write.apply()
            val applied = field.border
            write.prepareRestore()
            val border = write.receipts.first { it.identity.field == "glow/focus/BORDER" }
            val newer = border.copy(id = "newer", identity = border.identity.copy(ownerToken = "newer"))
            assertFailsWith<IllegalStateException> { write.restore(listOf(newer)) }
            assertSame(applied, field.border)
            assertEquals(
                RestoreStatus.RESTORED,
                write.receipts.single { it.identity.field == "glow/focus/APPEARANCE" }.status,
            )
            write.prepareRestore()
            write.restore(emptyList())
            assertSame(before, field.border)
            assertTrue(write.receipts.all { it.status == RestoreStatus.RESTORED })
        } finally {
            listener.detach()
        }
    }

    private fun listener(field: JTextField): GlowFocusListener =
        GlowFocusListener(field, Color.ORANGE, GlowStyle.SOFT, 30, { true }) {}.also { it.attach() }

    private fun binding(listener: GlowFocusListener): StudioGlowFocusWrite =
        StudioGlowFocusWrite(
            references,
            assertNotNull(listener.capture({ it.copy(color = Color.CYAN, intensity = 70) }) { true }),
        )

    private fun assertEvidence(
        before: List<PortableReceipt>,
        after: List<PortableReceipt>,
    ) {
        for (receipt in before) {
            assertEquals(receipt, after.single { it.id == receipt.id }.copy(status = receipt.status))
        }
    }

    private class FocusField : JTextField() {
        var isFocusedNow = true

        override fun hasFocus(): Boolean = isFocusedNow
    }
}
