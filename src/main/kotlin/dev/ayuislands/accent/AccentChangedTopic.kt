package dev.ayuislands.accent

import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

/**
 * Receives each usable project's resolved accent after a Studio acceptance is published.
 * The application-scoped topic lets toolbar, status-bar and glow subscribers filter their
 * own project without persisting another copy of the effective accent.
 *
 * The hex is a validated [AccentHex] value class, lifting the `#RRGGBB`
 * contract into the type per Pattern K. Subscribers can decode without
 * re-validating; call [AccentHex.value] to get the raw `String` for
 * downstream [com.intellij.ui.ColorUtil.fromHex] APIs.
 *
 * The single-method contract supports SAM lambdas. Subscribers receive a
 * validated value class; the Kotlin compiler owns its JVM bridge.
 */
fun interface AccentChangeListener {
    fun accentChanged(
        project: Project,
        hex: AccentHex,
        source: AccentResolver.Source,
    )
}

/**
 * Application-scoped accent-change topic. Subscribers connect via either
 * `ApplicationManager.getApplication().messageBus.connect(parentDisposable)`
 * or any `Project.messageBus.connect(parentDisposable)` — the default
 * [Topic.BroadcastDirection] (`TO_CHILDREN`) fans the event out to project-bus
 * subscribers as well as application-bus ones.
 *
 * Differs from [dev.ayuislands.ui.ComponentTreeRefreshedTopic] which uses
 * `BroadcastDirection.NONE` because that topic is intentionally project-scoped
 * (per-project peer refresh). Accent changes need cross-project fan-out so the
 * chip / stripe of project A updates when project B's Settings changes the
 * global accent during focus swap.
 */
object AccentChangedTopic {
    @JvmField
    val TOPIC: Topic<AccentChangeListener> =
        Topic.create(
            "Ayu Accent Changed",
            AccentChangeListener::class.java,
        )
}
