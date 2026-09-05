package dev.ayuislands.font

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.JDOMUtil
import org.jdom.Element
import org.jdom.JDOMException
import org.jdom.Text
import java.io.IOException

/** Strict versioned snapshots; unrecognized persisted data is never rewritten as guessed defaults. */
internal object FontOwnershipCodec {
    private val LOG = logger<FontOwnershipCodec>()
    private const val TEMPLATE_SIZE = "template-size"

    fun encode(record: FontOwnershipRecord): String =
        JDOMUtil.writeElement(
            Element("font-ownership")
                .setAttribute("version", FontOwnership.VERSION.toString())
                .setAttribute("status", record.status.name)
                .addContent(encodeSnapshot("baseline", record.baseline))
                .addContent(encodeSnapshot("applied", record.applied)),
        )

    fun decode(raw: String): FontOwnershipRecord? =
        try {
            val root = JDOMUtil.load(raw)
            root.requireAttributes("version", "status")
            require(
                root.name == "font-ownership" && root.getAttributeValue("version") == FontOwnership.VERSION.toString(),
            )
            require(root.children.map(Element::getName) == listOf("baseline", "applied"))
            FontOwnershipRecord(
                FontOwnershipStatus.valueOf(root.required("status")),
                decodeSnapshot(root.children[0]),
                decodeSnapshot(root.children[1]),
            )
        } catch (exception: IllegalArgumentException) {
            invalidSnapshot(exception)
        } catch (exception: JDOMException) {
            invalidSnapshot(exception)
        } catch (exception: IOException) {
            invalidSnapshot(exception)
        }

    private fun invalidSnapshot(exception: Exception): FontOwnershipRecord? {
        LOG.warn("Font ownership snapshot is unreadable; preserving current preferences and stored snapshot", exception)
        return null
    }

    private fun encodeSnapshot(
        name: String,
        snapshot: FontSnapshot,
    ): Element {
        val element = Element(name)
        when (snapshot) {
            FontSnapshot.Inherited -> element.setAttribute("mode", "inherited")
            is FontSnapshot.Explicit -> {
                val data = snapshot.preferences
                element.setAttribute("mode", "explicit")
                element.setAttribute(TEMPLATE_SIZE, data.templateSize.toString())
                element.setAttribute("spacing", data.lineSpacing.toString())
                element.setAttribute("ligatures", data.ligatures.toString())
                data.regularSubFamily?.let { element.setAttribute("regular", it) }
                data.boldSubFamily?.let { element.setAttribute("bold", it) }
                for ((family, size) in data.families) {
                    val child = Element("family").setAttribute("name", family)
                    size?.let { child.setAttribute("size", it.toString()) }
                    element.addContent(child)
                }
                for (family in data.effectiveFamilies) {
                    element.addContent(
                        Element("effective").setAttribute("name", family),
                    )
                }
            }
        }
        return element
    }

    private fun decodeSnapshot(element: Element): FontSnapshot =
        when (element.required("mode")) {
            "inherited" -> {
                element.requireAttributes("mode")
                require(element.children.isEmpty())
                FontSnapshot.Inherited
            }
            "explicit" -> {
                element.requireAttributes("mode", TEMPLATE_SIZE, "spacing", "ligatures", "regular", "bold")
                require(element.children.all { it.name == "family" || it.name == "effective" })
                for (child in element.children) {
                    if (child.name ==
                        "family"
                    ) {
                        child.requireAttributes("name", "size")
                    } else {
                        child.requireAttributes("name")
                    }
                    require(child.children.isEmpty())
                }
                FontSnapshot.Explicit(
                    FontData(
                        effectiveFamilies = element.getChildren("effective").map { it.required("name") },
                        families =
                            element.getChildren("family").map {
                                FontFamily(it.required("name"), it.getAttributeValue("size")?.positiveFloat())
                            },
                        templateSize = element.required(TEMPLATE_SIZE).positiveFloat(),
                        lineSpacing = element.required("spacing").positiveFloat(),
                        ligatures = element.required("ligatures").toBooleanStrict(),
                        regularSubFamily = element.getAttributeValue("regular"),
                        boldSubFamily = element.getAttributeValue("bold"),
                    ),
                )
            }
            else -> throw IllegalArgumentException("Unknown font snapshot mode")
        }

    private fun Element.required(name: String): String =
        requireNotNull(getAttributeValue(name)) {
            "Missing font snapshot attribute $name"
        }

    private fun Element.requireAttributes(vararg allowed: String) {
        require(namespaceURI.isEmpty() && additionalNamespaces.isEmpty()) { "Unrecognized font snapshot namespace" }
        require(
            attributes.all { it.namespaceURI.isEmpty() && it.name in allowed },
        ) { "Unrecognized font snapshot attributes" }
        require(
            content.all { it is Element || it is Text && it.text.isBlank() },
        ) { "Unrecognized font snapshot content" }
    }

    private fun String.positiveFloat(): Float =
        toFloat().also {
            require(it.isFinite() && it > 0f) { "Invalid font snapshot number" }
        }
}
