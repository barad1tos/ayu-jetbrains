package dev.ayuislands.studio

import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.CommitResult
import dev.ayuislands.studio.persistence.DecodeResult
import dev.ayuislands.studio.persistence.JournalResult
import dev.ayuislands.studio.persistence.PrepareResult
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.StoreLoad
import dev.ayuislands.studio.persistence.StudioAppearanceStore
import dev.ayuislands.studio.persistence.StudioFiles
import dev.ayuislands.studio.persistence.StudioStateCodec
import dev.ayuislands.studio.persistence.openStudioFiles
import java.nio.file.Files
import java.nio.file.Path

internal enum class StoreCrashPoint { PREPARED, DECISION, CLEANUP }

/** Child process uses only temporary paths supplied by its parent; it is killed while holding the store lock. */
internal object StudioStoreProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = Path.of(args[0])
        val bytes = Files.newInputStream(Path.of(args[1])).use { it.readAllBytes() }
        val decoded = StudioStateCodec.decode(bytes)
        check(decoded is DecodeResult.Ready)
        val candidate = decoded.document
        val point = StoreCrashPoint.valueOf(args[2])
        val actual = openStudioFiles(root)
        val instrumented =
            object : StudioFiles by actual {
                override fun replace(
                    source: String,
                    destination: String,
                ) {
                    actual.replace(source, destination)
                    if (destination == "state.xml") stopAt(point, StoreCrashPoint.DECISION)
                }

                override fun remove(name: String) {
                    actual.remove(name)
                    if (name == "pending.xml") stopAt(point, StoreCrashPoint.CLEANUP)
                }
            }
        StudioAppearanceStore(instrumented).use { store ->
            check(store.load() == StoreLoad.Empty)
            check(store.preserveSource(candidate.header.migration) == JournalResult.Acknowledged)
            val prepared = store.prepare(-1, candidate, RecoveryPlan(StudioTarget.Global, StudioList(emptyList())))
            check(prepared is PrepareResult.Prepared)
            stopAt(point, StoreCrashPoint.PREPARED)
            check(store.commit(prepared.token, candidate.runtime) is CommitResult.Committed)
            store.finishAccepted(prepared.token)
        }
        error("Crash marker was not reached")
    }

    private fun stopAt(
        requested: StoreCrashPoint,
        reached: StoreCrashPoint,
    ) {
        if (requested != reached) return
        println("READY:$reached")
        System.out.flush()
        System.`in`.read()
        error("Parent must terminate this process at $reached")
    }
}
