package dev.ayuislands.accent

/**
 * Typed description of the live Swing peer a chrome element tints.
 *
 * Phase 40.3c Refactor 2 — before this type existed, [LiveChromeRefresher]
 * exposed six flat entry points (`refreshStatusBar`, `clearStatusBar`,
 * `refreshByClassName`, `clearByClassName`, `refreshByClassNameInsideAncestorClass`,
 * `clearByClassNameInsideAncestorClass`). Every chrome element had to remember
 * which of the six matched its peer topology and call it correctly.
 *
 * Modeling the target as a sealed type collapses the six-entry API into two
 * (`refresh(target, color)` + `clear(target)`), forces call sites to name the
 * peer strategy explicitly, and makes adding a new topology a compile-time
 * pattern-match failure instead of a silent new overload.
 *
 * ### Variants
 *
 *  - [StatusBar] — resolved via the public [com.intellij.openapi.wm.WindowManager]
 *    API per open [com.intellij.openapi.project.Project]. The only peer type not
 *    looked up via runtime class-name string match.
 *  - [ByClassName] — walks every showing AWT window and mutates any [javax.swing.JComponent]
 *    whose runtime class name equals [ByClassName.fqn]. Used for package-private
 *    platform peers (`com.intellij.toolWindow.Stripe`, internal `MainToolbar`,
 *    `MyNavBarWrapperPanel`) whose types we cannot import.
 *  - [ByClassNameInside] — ancestor-scoped variant. Mutates a matching [ByClassNameInside.target]
 *    only when its container chain includes a component whose runtime class name equals
 *    [ByClassNameInside.ancestor]. Used for `OnePixelDivider` (shared IDE-wide) so we don't
 *    leak panel-border tinting into editor splitters, diff gutter, Settings dialog, etc.
 *    See Phase 40 review Round 2 A-1.
 */
sealed interface ChromeTarget {
    /** StatusBar peer resolved via public [com.intellij.openapi.wm.WindowManager] API. */
    object StatusBar : ChromeTarget

    /** Peer located by runtime class-name string match across every showing window. */
    data class ByClassName(
        val fqn: ClassFqn,
    ) : ChromeTarget

    /**
     * Ancestor-constrained class-name lookup — [target] must sit inside a [ancestor].
     *
     * **Private constructor — use the companion `invoke(target = ..., ancestor = ...)`
     * factory and pass named arguments.** Both fields are [ClassFqn], so positional
     * construction (`ByClassNameInside(foo, bar)`) silently compiles with `foo` and
     * `bar` swapped — which would walk the wrong ancestor chain and either over-tint
     * every matching component across the IDE or under-tint (never find ancestor).
     * The private ctor forces all call sites through [Companion.invoke], and the
     * named-arg convention there is our last line of defense.
     */
    data class ByClassNameInside private constructor(
        val target: ClassFqn,
        val ancestor: ClassFqn,
    ) : ChromeTarget {
        companion object {
            /**
             * Factory requiring named arguments at the call site. The `data class`
             * copy/equals/hashCode contract is unchanged — only `new` construction
             * is routed through this factory.
             */
            operator fun invoke(
                target: ClassFqn,
                ancestor: ClassFqn,
            ): ByClassNameInside = ByClassNameInside(target = target, ancestor = ancestor)
        }
    }
}
