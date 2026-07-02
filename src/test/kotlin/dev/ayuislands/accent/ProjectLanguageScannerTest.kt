package dev.ayuislands.accent

import com.intellij.lang.Language
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ContentIterator
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VirtualFile
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectLanguageScannerTest {
    private lateinit var application: Application
    private lateinit var dumbService: DumbService
    private lateinit var fileIndex: ProjectFileIndex
    private lateinit var project: Project
    private var isDisposed = false

    @BeforeTest
    fun setUp() {
        mockkStatic(ApplicationManager::class)
        application = mockk()
        every { ApplicationManager.getApplication() } returns application
        every { application.runReadAction<Map<String, Long>>(any()) } answers {
            firstArg<Computable<Map<String, Long>>>().compute()
        }

        mockkStatic(ProjectFileIndex::class)
        fileIndex = mockk()
        project = mockk(relaxed = true)
        isDisposed = false
        every { project.isDisposed } answers { isDisposed }
        dumbService = mockk()
        every { dumbService.isDumb } returns false
        every { project.getService(DumbService::class.java) } returns dumbService
        every { ProjectFileIndex.getInstance(project) } returns fileIndex
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `scan returns null for disposed project without touching project index`() {
        isDisposed = true

        assertNull(
            ProjectLanguageScanner.scan(project),
            "Closed projects must not produce cacheable language weights",
        )
        verify(exactly = 0) { ProjectFileIndex.getInstance(project) }
    }

    @Test
    fun `scan returns null during indexing without touching project index`() {
        every { dumbService.isDumb } returns true

        assertNull(
            ProjectLanguageScanner.scan(project),
            "Indexing projects must retry later instead of caching a pre-indexing guess",
        )
        verify(exactly = 0) { ProjectFileIndex.getInstance(project) }
    }

    @Test
    fun `scan propagates progress cancellation instead of caching fallback`() {
        mockkStatic(ProgressManager::class)
        every { ProgressManager.checkCanceled() } throws ProcessCanceledException()

        assertFailsWith<ProcessCanceledException> {
            ProjectLanguageScanner.scan(project)
        }
    }

    @Test
    fun `scan returns null when read action fails before a stable project answer`() {
        every { application.runReadAction<Map<String, Long>>(any()) } throws RuntimeException("roots changed")

        assertNull(
            ProjectLanguageScanner.scan(project),
            "Read-action failures must leave the detector free to retry on the next lookup",
        )
    }

    @Test
    fun `scan returns language weights when content iteration completes`() {
        val kotlinFile = sourceFile("/repo/src/Main.kt", "KOTLIN", 1_200L)
        val javaFile = sourceFile("/repo/src/App.java", "JAVA", 300L)
        val secondKotlinFile = sourceFile("/repo/src/Feature.kt", "KOTLIN", 800L)
        every { fileIndex.iterateContent(any()) } answers {
            val iterator = firstArg<ContentIterator>()
            assertTrue(iterator.processFile(kotlinFile))
            assertTrue(iterator.processFile(javaFile))
            iterator.processFile(secondKotlinFile)
        }

        assertEquals(
            mapOf("kotlin" to 2_000L, "java" to 300L),
            ProjectLanguageScanner.scan(project),
            "Small stable scans should sample every eligible file exactly",
        )
        verify(exactly = 1) { kotlinFile.fileType }
        verify(exactly = 1) { javaFile.fileType }
        verify(exactly = 1) { secondKotlinFile.fileType }
    }

    @Test
    fun `large scan stops sampling at sample cap while traversal continues to hard ceiling`() {
        var currentFileIndex = 0
        val sourceFile = sourceFile("/repo/src/File0.kt", "KOTLIN", 1L)
        every { sourceFile.path } answers { "/repo/src/File$currentFileIndex.kt" }
        var attemptedFiles = 0
        every { fileIndex.iterateContent(any()) } answers {
            val iterator = firstArg<ContentIterator>()
            for (index in 0..LanguageDetectionRules.MAX_FILES_SCANNED) {
                currentFileIndex = index
                attemptedFiles++
                if (!iterator.processFile(sourceFile)) return@answers false
            }
            true
        }

        assertEquals(
            mapOf("kotlin" to LanguageDetectionRules.PROJECT_LANGUAGE_MAX_SAMPLED_FILES.toLong()),
            ProjectLanguageScanner.scan(project),
            "Large scans should cap expensive language sampling independently from traversal",
        )
        assertEquals(
            LanguageDetectionRules.MAX_FILES_SCANNED + 1,
            attemptedFiles,
            "Traversal should continue until the existing hard file ceiling stops iteration",
        )
        verify(exactly = LanguageDetectionRules.PROJECT_LANGUAGE_MAX_SAMPLED_FILES) { sourceFile.fileType }
        verify(exactly = LanguageDetectionRules.PROJECT_LANGUAGE_MAX_SAMPLED_FILES) { sourceFile.length }
    }

    @Test
    fun `large scan sampling is deterministic and keeps warmup signal`() {
        val sourceFile = sourceFile("/repo/src/File0.kt", "KOTLIN", 1L)
        var currentFileIndex = 0
        every { sourceFile.path } answers { "/repo/src/File$currentFileIndex.kt" }
        every { sourceFile.fileType } answers {
            if (currentFileIndex == 0) {
                languageFileType("KOTLIN")
            } else {
                languageFileType("JAVA")
            }
        }
        every { fileIndex.iterateContent(any()) } answers {
            val iterator = firstArg<ContentIterator>()
            for (index in 0 until LanguageDetectionRules.MAX_FILES_SCANNED) {
                currentFileIndex = index
                assertTrue(iterator.processFile(sourceFile))
            }
            true
        }

        val firstScan = ProjectLanguageScanner.scan(project)
        val secondScan = ProjectLanguageScanner.scan(project)

        val expectedJavaSamples = LanguageDetectionRules.PROJECT_LANGUAGE_MAX_SAMPLED_FILES.toLong() - 1L
        assertEquals(
            mapOf("kotlin" to 1L, "java" to expectedJavaSamples),
            firstScan,
            "Warmup sampling should include the first eligible source file before stride sampling",
        )
        assertEquals(firstScan, secondScan, "Sampling must be deterministic for the same traversal order")
    }

    @Test
    fun `large scan sampling skips first post-warmup files until stride boundary`() {
        val warmupBoundaryFile = sourceFile("/repo/src/File128.kt", "KOTLIN", 1L)
        val firstPostWarmupFile = sourceFile("/repo/src/File129.java", "JAVA", 100L)
        val firstStrideFile = sourceFile("/repo/src/File138.py", "Python", 10L)
        val fillerFile = sourceFile("/repo/src/Filler.txt", "TEXT", 1L)
        every { fileIndex.iterateContent(any()) } answers {
            val iterator = firstArg<ContentIterator>()
            repeat(LanguageDetectionRules.PROJECT_LANGUAGE_WARMUP_SAMPLE_FILES - 1) {
                assertTrue(iterator.processFile(fillerFile))
            }
            assertTrue(iterator.processFile(warmupBoundaryFile))
            assertTrue(iterator.processFile(firstPostWarmupFile))
            repeat(LanguageDetectionRules.PROJECT_LANGUAGE_SAMPLE_STRIDE - 2) {
                assertTrue(iterator.processFile(fillerFile))
            }
            iterator.processFile(firstStrideFile)
        }

        assertEquals(
            mapOf("text" to 127L, "kotlin" to 1L, "python" to 10L),
            ProjectLanguageScanner.scan(project),
            "Sampling must include the warmup boundary, skip file 129, and sample the first stride boundary",
        )
        verify(exactly = 0) { firstPostWarmupFile.fileType }
    }

    @Test
    fun `scan returns null when project closes after read action completes`() {
        every { fileIndex.iterateContent(any()) } answers {
            firstArg<ContentIterator>().processFile(sourceFile("/repo/src/Main.kt", "KOTLIN", 1_200L))
        }
        every { application.runReadAction<Map<String, Long>>(any()) } answers {
            val weights = firstArg<Computable<Map<String, Long>>>().compute()
            isDisposed = true
            weights
        }

        assertNull(
            ProjectLanguageScanner.scan(project),
            "A scan that races with project close must not return cacheable language weights",
        )
    }

    @Test
    fun `scan stops visiting files when project closes during content iteration`() {
        val scannedBeforeClose = sourceFile("/repo/src/Main.kt", "KOTLIN", 1_200L)
        val skippedAfterClose = sourceFile("/repo/src/Generated.kt", "KOTLIN", 900L)
        every { fileIndex.iterateContent(any()) } answers {
            val iterator = firstArg<ContentIterator>()
            assertTrue(iterator.processFile(scannedBeforeClose))
            isDisposed = true
            assertFalse(iterator.processFile(skippedAfterClose))
            false
        }

        assertNull(
            ProjectLanguageScanner.scan(project),
            "Closing the project mid-scan must make the result non-cacheable",
        )
        verify(exactly = 0) { skippedAfterClose.fileType }
    }

    private fun sourceFile(
        path: String,
        languageId: String,
        length: Long,
    ): VirtualFile {
        val fileType = languageFileType(languageId)
        return mockk<VirtualFile>().also { file ->
            every { file.path } returns path
            every { file.isDirectory } returns false
            every { file.length } returns length
            every { file.fileType } returns fileType
        }
    }

    private fun languageFileType(languageId: String): LanguageFileType {
        val language = mockk<Language>()
        every { language.id } returns languageId
        val fileType = mockk<LanguageFileType>()
        every { fileType.language } returns language
        return fileType
    }
}
