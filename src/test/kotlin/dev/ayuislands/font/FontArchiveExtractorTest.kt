package dev.ayuislands.font

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FontArchiveExtractorTest {
    private val tmpRoot: File = createTempDirectory("ayu-font-extract-test").toFile()

    @AfterTest
    fun cleanup() {
        tmpRoot.deleteRecursively()
    }

    private fun makeZip(
        name: String,
        entries: Map<String, ByteArray>,
    ): File {
        val file = File(tmpRoot, name)
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            for ((entryName, data) in entries) {
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `extracts only matching files`() {
        val payload = "abc".toByteArray()
        val zip =
            makeZip(
                "maple.zip",
                mapOf(
                    "MapleMono-Regular.ttf" to payload,
                    "MapleMono-Bold.ttf" to payload,
                    "README.md" to "docs".toByteArray(),
                ),
            )
        val dest = File(tmpRoot, "out-match").apply { mkdirs() }

        val result =
            FontArchiveExtractor.extract(
                zipFile = zip,
                destDir = dest,
                filesToKeep = listOf(".*MapleMono-Regular\\.ttf$".toRegex()),
            )

        assertEquals(1, result.size)
        assertEquals("MapleMono-Regular.ttf", result[0].name)
        assertTrue(result[0].exists())
        assertEquals(payload.size.toLong(), result[0].length())
    }

    @Test
    fun `rejects path traversal entries`() {
        val zip =
            makeZip(
                "evil.zip",
                mapOf("../evil.ttf" to "x".toByteArray()),
            )
        val dest = File(tmpRoot, "out-evil").apply { mkdirs() }

        assertFailsWith<FontArchiveException> {
            FontArchiveExtractor.extract(
                zipFile = zip,
                destDir = dest,
                filesToKeep = listOf(".*\\.ttf$".toRegex()),
            )
        }
    }

    @Test
    fun `throws when no matching files found`() {
        val zip =
            makeZip(
                "empty.zip",
                mapOf("README.md" to "docs".toByteArray()),
            )
        val dest = File(tmpRoot, "out-empty").apply { mkdirs() }

        val ex =
            assertFailsWith<FontArchiveException> {
                FontArchiveExtractor.extract(
                    zipFile = zip,
                    destDir = dest,
                    filesToKeep = listOf(".*\\.ttf$".toRegex()),
                )
            }
        assertTrue(ex.message!!.contains("No matching files"))
    }

    @Test
    fun `extracts nested entries matching regex`() {
        val payload = "ttfdata".toByteArray()
        val zip =
            makeZip(
                "monaspace.zip",
                mapOf(
                    "fonts/ttf/MonaspaceNeonVarVF.ttf" to payload,
                    "fonts/otf/MonaspaceXenonVarVF.otf" to payload,
                    "fonts/ttf/UnrelatedFont.ttf" to payload,
                ),
            )
        val dest = File(tmpRoot, "out-nested").apply { mkdirs() }

        val result =
            FontArchiveExtractor.extract(
                zipFile = zip,
                destDir = dest,
                filesToKeep = listOf(".*MonaspaceNeonVarVF\\.(ttf|otf)$".toRegex()),
            )

        assertEquals(1, result.size)
        assertTrue(result[0].path.endsWith("MonaspaceNeonVarVF.ttf"))
        assertTrue(result[0].exists())
    }
}
