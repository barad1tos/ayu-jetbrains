package dev.ayuislands.studio.persistence

import dev.ayuislands.studio.model.EffectOwner

internal object StudioReceiptXml {
    private const val OWNER_TOKEN = "owner-token"
    private const val LAST_WRITTEN = "last-written"

    fun write(receipt: PortableReceipt): StudioXmlNode =
        StudioXmlNode(
            "receipt",
            mapOf(
                "id" to StudioXml.encodeText(receipt.id),
                "owner" to receipt.owner.name,
                "status" to receipt.status.name,
            ),
            listOf(
                StudioXmlNode(
                    "identity",
                    mapOf(
                        "resource" to StudioXml.encodeText(receipt.identity.resource),
                        "field" to StudioXml.encodeText(receipt.identity.field),
                        OWNER_TOKEN to StudioXml.encodeText(receipt.identity.ownerToken),
                    ),
                ),
                writeNative("before", receipt.before),
                writeNative(LAST_WRITTEN, receipt.lastWritten),
            ),
        )

    fun read(node: StudioXmlNode): PortableReceipt {
        require(node.name == "receipt")
        node.requireShape(setOf("id", "owner", "status"), setOf("identity", "before", LAST_WRITTEN))
        val identity = node.child("identity").requireShape(setOf("resource", "field", OWNER_TOKEN))
        return PortableReceipt(
            StudioXml.decodeText(node.attribute("id")),
            EffectOwner.valueOf(node.attribute("owner")),
            NativeFieldIdentity(
                StudioXml.decodeText(identity.attribute("resource")),
                StudioXml.decodeText(identity.attribute("field")),
                StudioXml.decodeText(identity.attribute(OWNER_TOKEN)),
            ),
            readNative(node.child("before")),
            readNative(node.child(LAST_WRITTEN)),
            RestoreStatus.valueOf(node.attribute("status")),
        ).also(::validateReceipt)
    }

    private fun writeNative(
        name: String,
        value: NativeValue,
    ): StudioXmlNode =
        when (value) {
            NativeValue.Absent -> {
                StudioXmlNode(name, mapOf("presence" to "absent"))
            }

            is NativeValue.Present -> {
                StudioXmlNode(
                    name,
                    mapOf("presence" to "value"),
                    listOf(StudioValueXml.writeValue(value.value)),
                )
            }
        }

    private fun readNative(node: StudioXmlNode): NativeValue =
        when (node.attribute("presence")) {
            "absent" -> {
                node.requireShape(setOf("presence"))
                NativeValue.Absent
            }

            "value" -> {
                node.requireShape(setOf("presence"), setOf("value"))
                NativeValue.Present(StudioValueXml.readValue(node.child("value")))
            }

            else -> {
                throw IllegalArgumentException("Invalid native value presence")
            }
        }

    private fun validateReceipt(receipt: PortableReceipt) {
        require(receipt.id.isNotBlank())
        require(receipt.identity.resource.isNotBlank() && receipt.identity.field.isNotBlank())
        require(receipt.identity.ownerToken.isNotBlank())
        require(
            receipt.status != RestoreStatus.KEPT_AFTER_RESTART ||
                StudioReceiptPolicy.canRetain(receipt.owner, receipt.identity.field),
        ) {
            "Retained geometry receipt has an unsupported native owner"
        }
    }
}
