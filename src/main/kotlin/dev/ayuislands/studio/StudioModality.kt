package dev.ayuislands.studio

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Captures one native callback's modality; injected controller lanes also work without an IDE application. */
internal fun captureStudioModality(): CoroutineContext =
    if (ApplicationManager.getApplication() == null) {
        EmptyCoroutineContext
    } else {
        ModalityState.current().asContextElement()
    }
