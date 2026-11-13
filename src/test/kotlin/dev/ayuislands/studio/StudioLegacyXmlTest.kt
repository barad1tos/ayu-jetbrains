package dev.ayuislands.studio

import dev.ayuislands.studio.persistence.StudioLegacyXml
import dev.ayuislands.studio.persistence.StudioXml
import org.jdom.Element
import org.jdom.Text
import javax.xml.stream.XMLStreamException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class StudioLegacyXmlTest {
    @Test
    fun `legacy component keeps original bytes separately from parsed values`() {
        val option = """<option value='  ā &amp; &gt;  ' name="z" />"""
        val unknown = """<future flag='>'><![CDATA[<raw>&]]><child /></future>"""
        val component = """<component name='Settings'>$option<!-- retain me -->$unknown</component>"""
        val source = "<?xml version='1.0'?><application>$component</application>"

        val parsed = StudioLegacyXml.component(source.encodeToByteArray(), "Settings")

        assertContentEquals(component.encodeToByteArray(), parsed.lexeme.toByteArray())
        assertEquals(listOf("option", "future"), parsed.children.map { it.node.name })
        assertContentEquals(option.encodeToByteArray(), parsed.children[0].lexeme.toByteArray())
        assertContentEquals(unknown.encodeToByteArray(), parsed.children[1].lexeme.toByteArray())
        val element = parsed.toElement()
        val future = element.getChild("future")
        assertEquals("  ā & >  ", element.getChild("option").getAttributeValue("value"))
        assertEquals("<raw>&", future.text)
        val child = future.children.single()
        assertEquals("child", child.name)
    }

    @Test
    fun `ambiguous or missing component does not look like an empty settings file`() {
        val component = "<component name='Settings'/>"
        for (source in listOf("<application/>", "<application>$component$component</application>", component)) {
            assertFailsWith<IllegalArgumentException> {
                StudioLegacyXml.component(source.encodeToByteArray(), "Settings")
            }
        }
    }

    @Test
    fun `legacy XML preserves text between child elements`() {
        val mixed = "<component name='Settings'>head<a/>tail<b/>end</component>"
        val source = "<application>$mixed</application>".encodeToByteArray()
        val parsed = StudioLegacyXml.component(source, "Settings")
        val roundTrip = StudioXml.parse(StudioXml.write(parsed.node))
        val native = parsed.toElement().content

        assertEquals(parsed.node, roundTrip)
        assertEquals(5, native.size)
        assertEquals("head", assertIs<Text>(native[0]).text)
        assertEquals("a", assertIs<Element>(native[1]).name)
        assertEquals("tail", assertIs<Text>(native[2]).text)
        assertEquals("b", assertIs<Element>(native[3]).name)
        assertEquals("end", assertIs<Text>(native[4]).text)
    }

    @Test
    fun `legacy XML preserves escaped whitespace through serialization`() {
        val prefix = "x".repeat(4095) + "🏝"
        val source =
            "<application><component name='Settings'>" +
                "<option name='installedFontFiles' value='a&#10;&#9;&#13;b'/>" +
                "<future>$prefix&#13;&amp;&#9;&#10;<child/>right</future>" +
                "</component></application>"
        val parsed = StudioLegacyXml.component(source.encodeToByteArray(), "Settings")
        val decoded = StudioXml.parse(StudioXml.write(parsed.node))

        assertEquals("a\n\t\rb", decoded.child("option").attribute("value"))
        assertEquals("$prefix\r&\t\nright", decoded.child("future").text)
        assertEquals(parsed.node, decoded)
    }

    @Test
    fun `DTD and entity inputs are rejected before lexemes are processed`() {
        val documents =
            listOf(
                "<!DOCTYPE application SYSTEM 'file:///not-a-real-config'><application/>",
                "<!DOCTYPE application [<!ENTITY x 'value'>]><application>&x;</application>",
            )
        for (source in documents) {
            assertFailsWith<XMLStreamException> { StudioXml.parse(source.encodeToByteArray()) }
        }
    }
}
