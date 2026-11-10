package dev.ayuislands.studio.session

import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.project.Project
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.glow.GlowOverlayManager
import dev.ayuislands.licensing.LicenseEntitlement
import dev.ayuislands.rotation.RuntimeOwner
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.runtime.FocusSnapshot
import java.util.UUID
import javax.swing.LookAndFeel

internal data class StudioNativeFocus(
    val runtime: FocusSnapshot,
    val project: Project?,
)

/** Native identities and confirmed inputs captured together on the owner's EDT. */
internal data class StudioNativeObservation(
    val lookAndFeel: LookAndFeel,
    val scheme: EditorColorsScheme,
    val focus: StudioNativeFocus,
    val entitlement: LicenseEntitlement,
    val variant: AyuVariant?,
    val accent: StudioAccentResult?,
    val managers: StudioList<GlowOverlayManager>,
    val process: StudioProcess?,
)

/** Automatic admission never impersonates a user transaction or advances its completion twice. */
internal sealed interface StudioNativeAdmission {
    data class User(
        val transactionId: String,
    ) : StudioNativeAdmission

    data class Automatic(
        val operationId: UUID,
        val owner: RuntimeOwner,
    ) : StudioNativeAdmission
}
