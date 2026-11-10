package dev.ayuislands.accent

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.util.messages.MessageBus
import dev.ayuislands.studio.StudioAccentPublication
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.session.StudioAccentResult
import dev.ayuislands.studio.session.StudioProjectAccent
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.lang.reflect.InvocationTargetException
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** The accepted resolver census drives notifications without re-reading raw preferences. */
class AccentChangedPublishTest {
    private val application = mockk<Application>()
    private val bus = mockk<MessageBus>()
    private val received = mutableListOf<Pair<Project, ResolvedAccent>>()
    private var beforeDelivery: (Project) -> Unit = {}

    init {
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns application
        every { application.messageBus } returns bus
        every { bus.syncPublisher(AccentChangedTopic.TOPIC) } returns
            AccentChangeListener { project, hex, source ->
                check(SwingUtilities.isEventDispatchThread())
                beforeDelivery(project)
                received += project to ResolvedAccent(source, hex.value)
            }
    }

    @AfterTest
    fun close() = unmockkAll()

    @Test
    fun `publication uses each confirmed project color and winning source in census order`() {
        val first = project()
        val second = project()
        val colors =
            listOf(
                StudioProjectAccent(first, ResolvedAccent(AccentResolver.Source.PROJECT_OVERRIDE, "#112233")),
                StudioProjectAccent(second, ResolvedAccent(AccentResolver.Source.LANGUAGE_OVERRIDE, "#AABBCC")),
            )

        SwingUtilities.invokeAndWait { StudioAccentPublication.publish(result(colors)) }

        assertEquals(colors.map { it.project to it.accent }, received)
    }

    @Test
    fun `disposed default and unavailable contexts never manufacture notifications`() {
        val closed = project(disposed = true)
        val template = project(default = true)
        val colors =
            listOf(closed, template).map {
                StudioProjectAccent(it, ResolvedAccent(AccentResolver.Source.GLOBAL, "#112233"))
            }

        SwingUtilities.invokeAndWait {
            StudioAccentPublication.publish(null)
            StudioAccentPublication.publish(result(colors))
        }

        assertEquals(emptyList(), received)
    }

    @Test
    fun `ordinary subscriber failure preserves later project delivery without repeating the first`() {
        val first = project()
        val second = project()
        val failure = IllegalStateException("subscriber failed")
        beforeDelivery = { if (it === first) throw failure }
        val warnings = mutableListOf<Throwable?>()
        val logger =
            object : LoggedErrorProcessor() {
                override fun processWarn(
                    category: String,
                    message: String,
                    t: Throwable?,
                ): Boolean {
                    warnings += t
                    return false
                }
            }
        val colors =
            listOf(first, second).map {
                StudioProjectAccent(it, ResolvedAccent(AccentResolver.Source.GLOBAL, "#112233"))
            }

        LoggedErrorProcessor.executeWith<Throwable>(logger) {
            SwingUtilities.invokeAndWait { StudioAccentPublication.publish(result(colors)) }
        }

        assertEquals(listOf(second to colors.last().accent), received)
        assertEquals(listOf<Throwable?>(failure), warnings)
    }

    @Test
    fun `subscriber cancellation remains a control signal`() {
        val cancelled = ProcessCanceledException()
        beforeDelivery = { throw cancelled }
        val colors = listOf(StudioProjectAccent(project(), ResolvedAccent(AccentResolver.Source.GLOBAL, "#112233")))

        val thrown =
            assertFailsWith<InvocationTargetException> {
                SwingUtilities.invokeAndWait { StudioAccentPublication.publish(result(colors)) }
            }

        assertSame(cancelled, thrown.cause)
        assertEquals(emptyList(), received)
    }

    private fun result(colors: List<StudioProjectAccent>) =
        StudioAccentResult(
            AccentContext.Ayu(AyuVariant.MIRAGE),
            colors.firstOrNull()?.project,
            ResolvedAccent(AccentResolver.Source.GLOBAL, "#FFCC66"),
            StudioList(colors),
        )

    private fun project(
        disposed: Boolean = false,
        default: Boolean = false,
    ): Project =
        mockk {
            every { isDisposed } returns disposed
            every { isDefault } returns default
        }
}
