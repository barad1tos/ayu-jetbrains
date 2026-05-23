package dev.ayuislands.syntax

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Phase 49 mood + axes persistence (D-08). Mirrors [AccentMappingsSettings] but
 * minimal — no path-macro migration, no usePathMacroManager (axes are enum
 * names, not file paths). Storage filename `ayu-islands-syntax.xml` lives
 * under the IDE config dir.
 */
@Service
@State(
    name = "AyuIslandsSyntaxState",
    storages = [Storage(value = "ayu-islands-syntax.xml")],
)
class SyntaxModeState : SimplePersistentStateComponent<SyntaxModeBaseState>(SyntaxModeBaseState()) {
    companion object {
        fun getInstance(): SyntaxModeState {
            val app = ApplicationManager.getApplication()
            return app.getService(SyntaxModeState::class.java)
        }
    }
}

/**
 * Backing [BaseState] subclass. Defaults (D-02): mood null → MAXIMUM via
 * [SyntaxMood.fromName]; axes empty set. Both fields hold strings (enum
 * names) so tampering with the XML cannot crash deserialization — invalid
 * enum names are filtered by callers (T-49-04 mitigation).
 */
class SyntaxModeBaseState : BaseState() {
    var mood by string()
    var axes by stringSet()
}
