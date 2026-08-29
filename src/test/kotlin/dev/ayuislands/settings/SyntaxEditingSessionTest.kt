package dev.ayuislands.settings

import com.intellij.openapi.progress.ProcessCanceledException
import dev.ayuislands.syntax.SyntaxPresetConfig
import dev.ayuislands.syntax.SyntaxTransactionResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertSame

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
    fun `presentation refresh follows only successful runtime changes`() {
        val runtime = RecordingRuntime()
        val refreshed = mutableListOf<SyntaxPresetConfig>()
        val session =
            SyntaxEditingSession(
                initialCheckpoint = config(50),
                runtime = runtime,
                persist = {},
                onRuntimeApplied = refreshed::add,
                debounceFactory = { callback -> RecordingDebounce().also { it.callback = callback } },
            )

        session.editDiscrete(config(70))
        runtime.previewResult = SyntaxTransactionResult.RolledBack(IllegalStateException("preview"))
        session.editDiscrete(config(80))

        assertEquals(listOf(config(70)), refreshed)
    }

    @Test
    fun `runtime failure is reported without changing the pending config`() {
        val runtime = RecordingRuntime()
        val failures = mutableListOf<RuntimeException>()
        val session =
            SyntaxEditingSession(
                initialCheckpoint = config(50),
                runtime = runtime,
                persist = {},
                onRuntimeFailed = { failures += it },
                debounceFactory = { callback -> RecordingDebounce().also { it.callback = callback } },
            )
        val failure = IllegalStateException("preview")
        runtime.previewResult = SyntaxTransactionResult.RolledBack(failure)

        session.editDiscrete(config(80))

        assertEquals(1, failures.size)
        assertSame(failure, failures.single())
        assertEquals(config(80), session.pendingConfig())
    }

    @Test
    fun `incomplete preview rollback waits for explicit restore before recovery`() {
        val runtime = RecordingRuntime()
        val reportedFailures = mutableListOf<RuntimeException>()
        val session =
            SyntaxEditingSession(
                initialCheckpoint = config(50),
                runtime = runtime,
                persist = {},
                onRuntimeFailed = reportedFailures::add,
                debounceFactory = { callback -> RecordingDebounce().also { it.callback = callback } },
            )
        val failure: RuntimeException = IllegalStateException("preview")
        val rollbackFailure: RuntimeException = IllegalStateException("rollback")
        runtime.previewResult =
            SyntaxTransactionResult.RecoveryRequired(
                cause = failure,
                rollbackFailures = listOf(rollbackFailure),
            )

        session.editDiscrete(config(80))

        assertEquals(config(80), session.pendingConfig())
        assertEquals(listOf(rollbackFailure, failure), reportedFailures)

        session.cancel()

        assertEquals(listOf(config(50)), runtime.restores)
    }

    @Test
    fun `successful restore refreshes presentation with checkpoint`() {
        val runtime = RecordingRuntime()
        val refreshed = mutableListOf<SyntaxPresetConfig>()
        val session =
            SyntaxEditingSession(
                initialCheckpoint = config(50),
                runtime = runtime,
                persist = {},
                onRuntimeApplied = refreshed::add,
                debounceFactory = { callback -> RecordingDebounce().also { it.callback = callback } },
            )
        session.editDiscrete(config(70))

        session.reset()

        assertEquals(listOf(config(70), config(50)), refreshed)
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
        assertEquals(1, runtime.advances)
        assertEquals(false, session.isModified())
    }

    @Test
    fun `failed persistence keeps runtime checkpoint restorable`() {
        val runtime = RecordingRuntime()
        val session =
            SyntaxEditingSession(
                initialCheckpoint = config(50),
                runtime = runtime,
                persist = { throw IllegalStateException("persist") },
                debounceFactory = { callback -> RecordingDebounce().also { it.callback = callback } },
            )
        session.editDiscrete(config(70))

        val result = session.apply()

        val failed = assertIs<SyntaxCommitResult.Failed>(result)
        assertEquals("persist", failed.failure.message)
        assertEquals(0, runtime.advances)
        session.cancel()
        assertEquals(listOf(config(50)), runtime.restores)
    }

    @Test
    fun `failed materialization keeps pending config and skips persistence`() {
        val runtime = RecordingRuntime()
        val persisted = mutableListOf<SyntaxPresetConfig>()
        val session = editingSession(config(50), runtime, persisted)
        session.editDiscrete(config(70))
        runtime.materializeResult = SyntaxTransactionResult.RolledBack(IllegalStateException("materialize"))

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
            listOf(SyntaxSessionEffect.Persist(appliedConfig)),
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
                SyntaxSessionEffect.Restore(appliedConfig),
            ),
            cancel.effects,
        )
    }

    @Test
    fun `cancel restore failure closes without moving recovery into the UI reducer`() {
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
        assertEquals(listOf(SyntaxSessionEffect.Close), transition.effects)
    }

    @Test
    fun `cancel recovery requirement reports failure once before runtime disposal`() {
        val runtime = RecordingRuntime()
        val debounce = RecordingDebounce()
        val session =
            SyntaxEditingSession(
                initialCheckpoint = config(50),
                runtime = runtime,
                persist = {},
                debounceFactory = { callback -> debounce.also { it.callback = callback } },
            )
        session.editDiscrete(config(80))
        runtime.restoreResult =
            SyntaxTransactionResult.RecoveryRequired(
                cause = IllegalStateException("restore failed"),
                rollbackFailures = listOf(IllegalStateException("rollback failed")),
            )

        val result = session.cancel()
        session.dispose()

        assertIs<SyntaxRestoreResult.Failed>(result)
        assertEquals(listOf(config(50)), runtime.restores)
    }

    @Test
    fun `foreign scheme never changes session state or requests a runtime write`() {
        val state = SyntaxSessionState.Live(config(50), config(80))

        val transition = SyntaxSessionReducer.reduce(state, SyntaxSessionEvent.ForeignSchemeActivated)

        assertEquals(state, transition.state)
        assertEquals(listOf(SyntaxSessionEffect.ShowForeignScheme), transition.effects)
    }

    @Test
    fun `active Ayu switch previews latest pending config`() {
        val runtime = RecordingRuntime()
        val session = editingSession(config(50), runtime, mutableListOf())
        session.editDiscrete(config(70))
        runtime.previews.clear()

        session.activeAyuSchemeChanged()

        assertEquals(listOf(config(70)), runtime.previews)
    }

    @Test
    fun `foreign switch reports status without a runtime write`() {
        val runtime = RecordingRuntime()
        val session = editingSession(config(50), runtime, mutableListOf())

        session.foreignSchemeActivated()

        assertEquals(1, runtime.foreignSchemeReports)
        assertEquals(emptyList(), runtime.previews)
    }

    @Test
    fun `repeated cancel after close is idempotent`() {
        val closed = SyntaxSessionState.Closed

        val transition = SyntaxSessionReducer.reduce(closed, SyntaxSessionEvent.CancelRequested)

        assertEquals(closed, transition.state)
        assertEquals(emptyList(), transition.effects)
    }

    @Test
    fun `closed editing session rejects new edits apply and reset`() {
        val runtime = RecordingRuntime()
        val session = editingSession(config(50), runtime, mutableListOf())
        session.editDiscrete(config(70))
        session.cancel()

        assertFails { session.editDiscrete(config(80)) }
        assertFails { session.apply(config(80)) }
        assertFails { session.reset() }
        assertSame(SyntaxRestoreResult.Restored, session.cancel())
    }

    @Test
    fun `dispose stops debounce after restore cancellation`() {
        val runtime = RecordingRuntime()
        val debounce = RecordingDebounce()
        val session =
            SyntaxEditingSession(
                initialCheckpoint = config(50),
                runtime = runtime,
                persist = {},
                debounceFactory = { callback -> debounce.also { it.callback = callback } },
            )
        session.editDiscrete(config(70))
        val cancellation = ProcessCanceledException()
        runtime.restoreException = cancellation

        val thrown = assertFails { session.dispose() }

        assertSame(cancellation, thrown)
        assertEquals(1, debounce.disposals)
    }

    @Test
    fun `cancel restores checkpoint even when stopping debounce fails`() {
        val runtime = RecordingRuntime()
        val debounce = RecordingDebounce(failStopAt = 2)
        val session =
            SyntaxEditingSession(
                initialCheckpoint = config(50),
                runtime = runtime,
                persist = {},
                debounceFactory = { callback -> debounce.also { it.callback = callback } },
            )
        session.editDiscrete(config(70))

        val failure = assertFails { session.cancel() }

        assertEquals("stop failed", failure.message)
        assertEquals(listOf(config(50)), runtime.restores)
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
        var restoreException: RuntimeException? = null
        var advances = 0
        var foreignSchemeReports = 0

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
            restoreException?.let { throw it }
            return restoreResult
        }

        override fun advance() {
            advances++
        }

        override fun showForeignScheme() {
            foreignSchemeReports++
        }

        private fun applied(): SyntaxTransactionResult = SyntaxTransactionResult.Applied(emptySet(), emptySet())
    }

    private class RecordingDebounce(
        private val failStopAt: Int? = null,
    ) : SyntaxDebounce {
        var callback: (() -> Unit)? = null
        var restarts = 0
        var stops = 0
        var disposals = 0

        override fun restart() {
            restarts++
        }

        override fun stop() {
            stops++
            if (stops == failStopAt) error("stop failed")
        }

        override fun dispose() {
            disposals++
        }
    }
}
