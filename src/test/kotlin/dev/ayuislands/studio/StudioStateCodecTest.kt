package dev.ayuislands.studio

import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.OpaqueRecord
import dev.ayuislands.studio.model.StudioBytes
import dev.ayuislands.studio.model.StudioDocument
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.DecodeResult
import dev.ayuislands.studio.persistence.StudioStateCodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StudioStateCodecTest {
    @Test
    fun `all source fixtures round trip with exact origins unknown values and archive bytes`() {
        for (fixture in listOf(
            "fresh-absent",
            "legacy-omitted",
            "mixed-user-values",
            "unknown-and-ordered",
            "existing-ownership",
        )) {
            val document = document(fixture)
            val encoded = StudioStateCodec.encode(document)
            val decoded = assertIs<DecodeResult.Ready>(StudioStateCodec.decode(encoded))
            assertEquals(document, decoded.document, fixture)
            assertContentEquals(encoded, StudioStateCodec.encode(decoded.document))
        }
    }

    @Test
    fun `opaque extension payloads retain bytes and encounter order`() {
        val input =
            document().copy(
                extensions =
                    StudioList(
                        listOf(
                            OpaqueRecord("future:paint", StudioBytes(byteArrayOf(0, 1, -1))),
                            OpaqueRecord("future:layout", StudioBytes($$"\u0000  \n $USER_HOME$".encodeToByteArray())),
                        ),
                    ),
            )
        val result = assertIs<DecodeResult.Ready>(StudioStateCodec.decode(StudioStateCodec.encode(input)))
        assertEquals(input, result.document)
    }

    @Test
    fun `future outer schema is read only and duplicate identity is corruption`() {
        val original = document()
        val future = StudioStateCodec.encode(original).decodeToString().replace("schema=\"1\"", "schema=\"2\"")
        assertEquals(DecodeResult.FutureSchema(2), StudioStateCodec.decode(future.encodeToByteArray()))
        val duplicates = original.copy(catalog = StudioList(original.catalog + original.catalog.first()))
        assertIs<DecodeResult.Invalid>(StudioStateCodec.decode(StudioStateCodec.encode(duplicates)))
    }

    @Test
    fun `DTD external entities and malformed framing are rejected`() {
        for (source in listOf(
            "<!DOCTYPE x [<!ENTITY external SYSTEM 'file:///not-a-real-config'>]><ayu-studio>&external;</ayu-studio>",
            "<!DOCTYPE x [<!ENTITY internal 'text'>]><ayu-studio>&internal;</ayu-studio>",
            "<ayu-studio/><ayu-studio/>",
            "",
            "<ayu-studio>",
        )) {
            assertIs<DecodeResult.Invalid>(StudioStateCodec.decode(source.encodeToByteArray()))
        }
    }

    private fun document(fixture: String = "unknown-and-ordered"): StudioDocument =
        assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(StudioFixtures.load(fixture))).document
}
