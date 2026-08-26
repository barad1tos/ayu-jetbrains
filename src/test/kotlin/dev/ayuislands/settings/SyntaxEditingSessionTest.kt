package dev.ayuislands.settings

import dev.ayuislands.syntax.SyntaxPresetConfig
import dev.ayuislands.syntax.SyntaxTransactionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SyntaxEditingSessionTest {
    @Test
    fun `live preview never persists pending config`() {
        val runtime = RecordingRuntime()
        val persisted = mutableListOf<SyntaxPresetConfig>()
        val session = editingSession(config(50), runtime, persisted)

        session.editDiscrete(config(70))

        assertEquals(listOf(config(70)), runtime.previews)
        assertEquals(emptyList(), persisted)
        assertEquals(true, session.isModified())
    }

    @Test
    fun `apply persists only after materialization succeeds`() {
        val runtime = RecordingRuntime()
        val persisted = mutableListOf<SyntaxPresetConfig>()
        val session = editingSession(config(50), runtime, persisted)
        session.editDiscrete(config(70))

        val result = session.apply()

        assertEquals(SyntaxCommitResult.Applied, result)
        assertEquals(listOf(config(70)), runtime.materializations)
        assertEquals(listOf(config(70)), persisted)
        assertEquals(false, session.isModified())
    }

    @Test
    fun `failed materialization keeps pending config and skips persistence`() {
        val runtime = RecordingRuntime()
        val persisted = mutableListOf<SyntaxPresetConfig>()
        val session = editingSession(config(50), runtime, persisted)
        session.editDiscrete(config(70))
        runtime.materializeResult = SyntaxTransactionResult.Failed(IllegalStateException("materialize"), emptyList())

        val result = session.apply()

        val failed = assertIs<SyntaxCommitResult.Failed>(result)
        assertEquals("materialize", failed.failure.message)
        assertEquals(emptyList(), persisted)
        assertEquals(config(70), session.pendingConfig())
        assertEquals(true, session.isModified())
    }

    @Test
    fun `slider debounce coalesces edits and release flushes latest config`() {
        val runtime = RecordingRuntime()
        val debounce = RecordingDebounce()
        val session =
            SyntaxEditingSession(
                initialCheckpoint = config(50),
                runtime = runtime,
                persist = {},
                debounceFactory = { callback -> debounce.also { it.callback = callback } },
            )

        session.editSlider(config(60))
        session.editSlider(config(80))
        assertEquals(2, debounce.restarts)
        assertEquals(emptyList(), runtime.previews)

        session.sliderReleased()

        assertEquals(listOf(config(80)), runtime.previews)
        assertEquals(1, debounce.stops)
    }

    @Test
    fun `cancel restores latest checkpoint and never persists`() {
        val runtime = RecordingRuntime()
        val persisted = mutableListOf<SyntaxPresetConfig>()
        val session = editingSession(config(50), runtime, persisted)
        session.editDiscrete(config(70))
        assertEquals(SyntaxCommitResult.Applied, session.apply())
        session.editDiscrete(config(20))

        val result = session.cancel()

        assertEquals(SyntaxRestoreResult.Restored, result)
        assertEquals(listOf(config(70)), runtime.restores)
        assertEquals(listOf(config(70)), persisted)
    }

    @Test
    fun `discrete edit applies pending config without advancing checkpoint`() {
        val checkpoint = config(50)
        val pending = config(70)

        val transition =
            SyntaxSessionReducer.reduce(
                SyntaxSessionState.Synced(checkpoint),
                SyntaxSessionEvent.EditDiscrete(pending),
            )

        val applying = assertIs<SyntaxSessionState.Applying>(transition.state)
        assertEquals(checkpoint, applying.checkpoint)
        assertEquals(pending, applying.pending)
        assertEquals(SyntaxSessionIntent.PREVIEW, applying.intent)
        assertEquals(
            listOf(
                SyntaxSessionEffect.StopDebounce,
                SyntaxSessionEffect.ApplyRuntime(pending, SyntaxSessionIntent.PREVIEW),
            ),
            transition.effects,
        )
    }

    @Test
    fun `slider edit stays pending until release flushes latest value`() {
        val checkpoint = config(50)
        val first = config(60)
        val latest = config(80)
        val opened = SyntaxSessionState.Synced(checkpoint)

        val afterFirst = SyntaxSessionReducer.reduce(opened, SyntaxSessionEvent.EditSlider(first))
        val afterLatest = SyntaxSessionReducer.reduce(afterFirst.state, SyntaxSessionEvent.EditSlider(latest))
        val flushed = SyntaxSessionReducer.reduce(afterLatest.state, SyntaxSessionEvent.SliderReleased)

        assertEquals(listOf(SyntaxSessionEffect.RestartDebounce), afterFirst.effects)
        assertEquals(listOf(SyntaxSessionEffect.RestartDebounce), afterLatest.effects)
        val applying = assertIs<SyntaxSessionState.Applying>(flushed.state)
        assertEquals(latest, applying.pending)
        assertEquals(checkpoint, applying.lastApplied)
        assertEquals(
            listOf(
                SyntaxSessionEffect.StopDebounce,
                SyntaxSessionEffect.ApplyRuntime(latest, SyntaxSessionIntent.PREVIEW),
            ),
            flushed.effects,
        )
    }

    @Test
    fun `failed preview retains pending input and last applied runtime`() {
        val checkpoint = config(50)
        val pending = config(75)
        val applying =
            SyntaxSessionState.Applying(
                checkpoint = checkpoint,
                pending = pending,
                lastApplied = checkpoint,
                intent = SyntaxSessionIntent.PREVIEW,
            )
        val failure = IllegalStateException("preview failed")

        val transition = SyntaxSessionReducer.reduce(applying, SyntaxSessionEvent.RuntimeFailed(failure))

        val failed = assertIs<SyntaxSessionState.Failed>(transition.state)
        assertEquals(checkpoint, failed.checkpoint)
        assertEquals(pending, failed.pending)
        assertEquals(checkpoint, failed.lastApplied)
        assertEquals(failure, failed.failure)
        assertEquals(emptyList(), transition.effects)
    }

    @Test
    fun `apply advances checkpoint so later cancel restores applied state`() {
        val checkpoint = config(50)
        val appliedConfig = config(70)
        val laterEdit = config(20)
        val live = SyntaxSessionState.Live(checkpoint, appliedConfig)

        val commit = SyntaxSessionReducer.reduce(live, SyntaxSessionEvent.ApplyRequested)
        val commitRuntime = SyntaxSessionReducer.reduce(commit.state, SyntaxSessionEvent.RuntimeSucceeded)
        assertEquals(
            listOf(SyntaxSessionEffect.Persist(appliedConfig, close = false)),
            commitRuntime.effects,
        )
        val persisted = SyntaxSessionReducer.reduce(commitRuntime.state, SyntaxSessionEvent.PersistenceSucceeded)
        val edited = SyntaxSessionReducer.reduce(persisted.state, SyntaxSessionEvent.EditDiscrete(laterEdit))
        val editedLive = SyntaxSessionReducer.reduce(edited.state, SyntaxSessionEvent.RuntimeSucceeded)
        val cancel = SyntaxSessionReducer.reduce(editedLive.state, SyntaxSessionEvent.CancelRequested)

        val restoring = assertIs<SyntaxSessionState.Applying>(cancel.state)
        assertEquals(appliedConfig, restoring.checkpoint)
        assertEquals(SyntaxSessionIntent.RESTORE_AND_CLOSE, restoring.intent)
        assertEquals(
            listOf(
                SyntaxSessionEffect.StopDebounce,
                SyntaxSessionEffect.Restore(appliedConfig, close = true),
            ),
            cancel.effects,
        )
    }

    @Test
    fun `cancel restore failure closes and schedules persisted recovery`() {
        val checkpoint = config(50)
        val pending = config(80)
        val restoring =
            SyntaxSessionState.Applying(
                checkpoint = checkpoint,
                pending = pending,
                lastApplied = pending,
                intent = SyntaxSessionIntent.RESTORE_AND_CLOSE,
            )
        val failure = IllegalStateException("restore failed")

        val transition = SyntaxSessionReducer.reduce(restoring, SyntaxSessionEvent.RestoreFailed(failure))

        assertIs<SyntaxSessionState.Closed>(transition.state)
        assertEquals(
            listOf(
                SyntaxSessionEffect.ScheduleRecovery(checkpoint, failure),
                SyntaxSessionEffect.Close,
            ),
            transition.effects,
        )
    }

    @Test
    fun `foreign scheme never changes session state or requests a runtime write`() {
        val state = SyntaxSessionState.Live(config(50), config(80))

        val transition = SyntaxSessionReducer.reduce(state, SyntaxSessionEvent.ForeignSchemeActivated)

        assertEquals(state, transition.state)
        assertEquals(listOf(SyntaxSessionEffect.ShowForeignScheme), transition.effects)
    }

    @Test
    fun `repeated cancel after close is idempotent`() {
        val closed = SyntaxSessionState.Closed

        val transition = SyntaxSessionReducer.reduce(closed, SyntaxSessionEvent.CancelRequested)

        assertEquals(closed, transition.state)
        assertEquals(emptyList(), transition.effects)
    }

    private fun config(keyword: Int): SyntaxPresetConfig =
        SyntaxPresetConfig(
            selectedPreset = "CUSTOM",
            customOverrides = mapOf("Kotlin" to mapOf("KEYWORD" to keyword)),
        )

    private fun editingSession(
        checkpoint: SyntaxPresetConfig,
        runtime: RecordingRuntime,
        persisted: MutableList<SyntaxPresetConfig>,
    ): SyntaxEditingSession =
        SyntaxEditingSession(
            initialCheckpoint = checkpoint,
            runtime = runtime,
            persist = persisted::add,
            debounceFactory = { callback -> RecordingDebounce().also { it.callback = callback } },
        )

    private class RecordingRuntime : SyntaxEditingRuntime {
        val previews = mutableListOf<SyntaxPresetConfig>()
        val materializations = mutableListOf<SyntaxPresetConfig>()
        val restores = mutableListOf<SyntaxPresetConfig>()
        var previewResult: SyntaxTransactionResult = applied()
        var materializeResult: SyntaxTransactionResult = applied()
        var restoreResult: SyntaxTransactionResult = applied()

        override fun preview(config: SyntaxPresetConfig): SyntaxTransactionResult {
            previews += config
            return previewResult
        }

        override fun materialize(config: SyntaxPresetConfig): SyntaxTransactionResult {
            materializations += config
            return materializeResult
        }

        override fun restore(config: SyntaxPresetConfig): SyntaxTransactionResult {
            restores += config
            return restoreResult
        }

        override fun scheduleRecovery(
            config: SyntaxPresetConfig,
            failure: RuntimeException,
        ) = Unit

        private fun applied(): SyntaxTransactionResult = SyntaxTransactionResult.Applied(emptySet(), emptySet())
    }

    private class RecordingDebounce : SyntaxDebounce {
        var callback: (() -> Unit)? = null
        var restarts = 0
        var stops = 0

        override fun restart() {
            restarts++
        }

        override fun stop() {
            stops++
        }

        override fun dispose() = Unit
    }
}
