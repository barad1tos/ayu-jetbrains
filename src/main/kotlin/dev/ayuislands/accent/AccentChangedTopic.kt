package dev.ayuislands.accent

import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

/**
 * Listener fired after a successful [AccentApplicator.apply] OR a same-hex focus
 * swap routed through [dev.ayuislands.settings.mappings.ProjectAccentSwapService].
 *
 * Subscribers (toolbar stripe / chip today, the planned IDE status-bar widget
 * tomorrow) receive the focused `project`, the post-resolution [AccentHex], and
 * the [AccentResolver.Source] that won the resolution chain. Filter by `project`
 * if you only care about your own window — the topic is application-scoped so
 * it fans out to every subscriber regardless of which window's apply triggered
 * it.
 *
 * The hex is a validated [AccentHex] value class, lifting the `#RRGGBB`
 * contract into the type per Pattern K. Subscribers can decode without
 * re-validating; call [AccentHex.value] to get the raw `String` for
 * downstream [com.intellij.ui.ColorUtil.fromHex] APIs.
 *
 * This is a `fun interface` because IntelliJ's MessageBus listener shape is a
 * single-method contract. Subscribers that receive [AccentHex] should still use
 * object expressions instead of SAM lambdas: Kotlin's SAM conversion of a
 * value-class parameter mangles the JVM name with a hash
 * (`accentChanged-Czfobf0`) while the metafactory-generated lambda implements
 * the un-mangled name, producing a runtime [AbstractMethodError] when the
 * topic fan-out invokes the SAM. Object expressions (`object : ...`) work
 * because the compiler emits both the mangled member AND a `String`-typed
 * bridge for the public surface. Pattern K — type lift discipline takes
 * precedence over lambda ergonomics.
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
