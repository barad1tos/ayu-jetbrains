package dev.ayuislands.accent

import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

/**
 * Listener fired after a successful [AccentApplicator.apply] OR a same-hex focus
 * swap routed through [dev.ayuislands.settings.mappings.ProjectAccentSwapService].
 *
 * Subscribers (Phase 48 toolbar stripe / chip, future Phase 47 status widget)
 * receive [project], the post-resolution `hex`, and the [AccentResolver.Source]
 * that won the resolution chain. Filter by [project] if you only care about
 * your own window — the topic is application-scoped per D-01 so it fans out
 * to every subscriber regardless of which window's apply triggered it.
 *
 * The hex is always a `#RRGGBB` string already validated by [AccentHex] on the
 * publisher side; subscribers can decode without re-validating.
 */
fun interface AccentChangeListener {
    fun accentChanged(
        project: Project,
        hex: String,
        source: AccentResolver.Source,
    )
}

/**
 * Application-scoped accent-change topic. Phase 48 D-01: subscribers connect via
 * either `ApplicationManager.getApplication().messageBus.connect(parentDisposable)`
 * or any `Project.messageBus.connect(parentDisposable)` — the default
 * [Topic.BroadcastDirection] (`TO_CHILDREN`) fans the event out to project-bus
 * subscribers as well as application-bus ones.
 *
 * Differs from [dev.ayuislands.ui.ComponentTreeRefreshedTopic] which uses
 * `BroadcastDirection.NONE` because that topic is intentionally project-scoped
 * (per-project peer refresh). Phase 48 accent changes need cross-project fan-out
 * so the chip / stripe of project A updates when project B's Settings changes
 * the global accent during focus swap.
 */
object AccentChangedTopic {
    @JvmField
    val TOPIC: Topic<AccentChangeListener> =
        Topic.create(
            "Ayu Accent Changed",
            AccentChangeListener::class.java,
        )
}
