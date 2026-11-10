package dev.ayuislands.studio.session

import dev.ayuislands.studio.model.StudioProcess

/** Reads only the current JVM's identity, retaining unsupported or denied observations as failures. */
internal fun observeStudioProcess(readHandle: () -> ProcessHandle = ProcessHandle::current): Result<StudioProcess> =
    try {
        val process = readHandle()
        Result.success(StudioProcess(process.pid(), process.info().startInstant().orElse(null)))
    } catch (failure: SecurityException) {
        Result.failure(failure)
    } catch (failure: UnsupportedOperationException) {
        Result.failure(failure)
    }
