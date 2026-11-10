package dev.ayuislands.font

import com.intellij.openapi.application.ApplicationManager
import dev.ayuislands.studio.EmptyEffectRuntime
import dev.ayuislands.studio.StudioFixtures
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.session.FontSchemeView
import dev.ayuislands.studio.session.StudioEffectInput
import dev.ayuislands.studio.session.StudioFontHost
import dev.ayuislands.studio.session.StudioFontParticipant
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The owner now injects its native context; the removed writer's ambient scheduling fallback is unnecessary. */
class StudioFontDispatchTest {
    @AfterTest fun cleanup() = unmockkAll()

    @Test fun `background preparation and settlement stay on the injected native dispatcher`() =
        runBlocking {
            mockkStatic(ApplicationManager::class)
            every { ApplicationManager.getApplication() } throws
                AssertionError("Injected native ownership must not guess an application")
            val calls = mutableListOf<Thread>()
            val host = host(calls)
            lateinit var nativeThread: Thread
            Executors
                .newSingleThreadExecutor { action ->
                    Thread(action, "Font native owner").also { nativeThread = it }
                }.asCoroutineDispatcher()
                .use { native ->
                    val operation = StudioFontParticipant(host, native).prepare(input(), EmptyEffectRuntime)
                    operation.apply()
                    operation.prepareRestore(StudioList(emptyList()))
                    operation.restore(StudioList(emptyList()))
                    operation.accept()
                }
            assertTrue(calls.isNotEmpty())
            assertTrue(calls.all { it === nativeThread })
        }

    @Test fun `cancelled native dispatch never falls back to an unrelated UI queue`() =
        runBlocking {
            val calls = mutableListOf<Thread>()
            val cancelled =
                object : CoroutineDispatcher() {
                    override fun dispatch(
                        context: CoroutineContext,
                        block: Runnable,
                    ): Unit = throw CancellationException("Native owner disposed")
                }
            assertFailsWith<CancellationException> {
                StudioFontParticipant(host(calls), cancelled).prepare(input(), EmptyEffectRuntime)
            }
            assertEquals(emptyList(), calls)
        }

    private fun host(calls: MutableList<Thread>): StudioFontHost =
        object : StudioFontHost {
            override fun observe(): FontSchemeView {
                calls.add(Thread.currentThread())
                return FontSchemeView(emptyList(), mockk(), null)
            }

            override suspend fun resolveFamily(settings: FontSettings): String =
                error("Disabled request cannot resolve fonts")

            override fun newIdentity(): String = error("Disabled request cannot acquire identity")

            override fun refresh() {
                calls.add(Thread.currentThread())
            }
        }

    private fun input(): StudioEffectInput {
        val document = assertIs<MigrationResult.Ready>(StudioLegacyMigration.plan(StudioFixtures.capture())).document
        return StudioEffectInput(document, StudioTarget.Global, StudioList(emptyList()))
    }
}
