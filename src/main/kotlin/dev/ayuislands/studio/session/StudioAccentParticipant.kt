package dev.ayuislands.studio.session

import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.colors.ColorKey
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AccentEditorPalette
import dev.ayuislands.accent.AccentHex
import dev.ayuislands.accent.elements.AbstractChromeElement
import dev.ayuislands.font.FontIdentityRules
import dev.ayuislands.glow.GlowTabMode
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.RuntimeFieldChange
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.theme.AyuEditorSchemeBinder
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeEntry
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeValue
import dev.ayuislands.theme.EditorSchemeOwner
import dev.ayuislands.theme.SchemeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Color
import javax.swing.UIManager
import kotlin.coroutines.CoroutineContext

/** One Accent owner retains UI, editor and optional integration effects until the outer commit decision. */
internal class StudioAccentParticipant(
    private val host: StudioAccentHost = IdeAccentHost(),
    private val integrations: StudioIntegrationCapture,
    private val nativeContext: CoroutineContext = Dispatchers.EDT,
) : StudioEffectParticipant {
    override val owner: EffectOwner = EffectOwner.ACCENT

    override suspend fun prepare(
        input: StudioEffectInput,
        runtime: StudioEffectRuntime,
    ): StudioEffectOperation =
        withContext(nativeContext) {
            val observed = checkNotNull(runtime.observation) { "Accent native observation is unavailable" }
            val resolver = StudioAccentResolver(input, observed.variant, host.systemAccent(), observed.entitlement)
            val result = resolve(resolver, observed)
            val schemes = StudioSchemeOperation.prepare(host.schemes, nativeContext, owner, input, runtime.native)
            val references =
                StudioAccentReferences(
                    input.candidate.header.transactionId,
                    checkNotNull(runtime.native.currentProcess) { "Accent process identity is unconfirmed" },
                )
            val fields = capture(input, result, observed, references, schemes)
            val optional = integrations.prepare(input, result.context, result.focused?.hex, runtime.native)
            val projects = observed.managers.map { it.project }
            StudioAccentOperation(
                fields,
                schemes,
                optional,
                nativeContext,
                refresh = { host.refresh(projects, repaintWindows = result.context != null) },
                confirm = { runtime.confirmAccent(result) },
                preparedChanges = publication(input, result),
            )
        }

    override suspend fun recover(
        input: StudioEffectInput,
        receipts: StudioList<PortableReceipt>,
        runtime: StudioEffectRuntime,
    ): StudioEffectOperation =
        withContext(nativeContext) {
            require(receipts.all { it.owner == owner })
            val native = receipts.filter { FontIdentityRules.canonicalId(it.identity.resource) != null }
            val optional =
                receipts.filter { receipt ->
                    IntegrationSurface.entries.any {
                        it.resource ==
                            receipt.identity.resource
                    }
                }
            val ephemeral = receipts.filter { it !in native && it !in optional }
            require(
                ephemeral.all { it.identity.ownerToken.startsWith("accent/${input.candidate.header.transactionId}/") },
            ) {
                "Accent field receipt belongs to another operation"
            }
            StudioAccentOperation(
                emptyList(),
                StudioSchemeOperation(host.schemes, nativeContext, owner, input, native, runtime.native),
                integrations.recover(input, StudioList(optional), runtime.native),
                nativeContext,
                refresh = {},
                confirm = null,
                preparedChanges = StudioList(emptyList()),
                lostFields = StudioAccentReceipts.recoverLost(ephemeral, runtime.native.currentProcess),
            )
        }

    private fun resolve(
        resolver: StudioAccentResolver,
        observed: StudioNativeObservation,
    ): StudioAccentResult = resolver.resolve(observed.focus.project, observed.managers.map { it.project })

    private fun capture(
        input: StudioEffectInput,
        result: StudioAccentResult,
        observed: StudioNativeObservation,
        references: StudioAccentReferences,
        schemes: StudioSchemeOperation,
    ): List<StudioAccentWrite> {
        val view = host.schemes.observe()
        check(view.active === observed.scheme && UIManager.getLookAndFeel() === observed.lookAndFeel) {
            "Accent appearance changed during capture"
        }
        val appearance = checkNotNull(StudioTargets.current(input.candidate, input.target)).appearance
        val settings = StudioAccentEditing.settings(appearance, input.candidate.shared)
        val ui =
            StudioAccentProjection.ui(
                appearance,
                input.candidate.shared.plugins,
                result.context,
                observed.entitlement,
            )
        val color = result.focused?.hex?.let { AccentHex.require(it).toColor() }
        val extensions = host.elements().toList()
        require(extensions.map { it.id }.distinct().size == extensions.size) { "Duplicate Accent extension" }
        val chrome =
            StudioChromeCapture(references).capture(
                StudioAccentProjection.chrome(
                    appearance,
                    input.candidate.shared.plugins,
                    result.context,
                    observed.entitlement,
                ),
                color,
                extensions,
            )
        val orphanPeers =
            StudioChromeOrphans.capture(
                references,
                extensions.filterIsInstance<AbstractChromeElement>(),
                chrome.elements.receipts,
            )
        val elementWrites =
            StudioAccentElements(
                references,
                schemes,
                extensions,
            ).capture(settings.elements, result.context, color, view, observed.entitlement)
        captureAlwaysOn(schemes, view, result.context, color, ui.tabMode)
        return buildList {
            add(
                if (color ==
                    null
                ) {
                    StudioAccentFields.release(references)
                } else {
                    StudioAccentFields.colors(references, ui, color)
                },
            )
            add(chrome.membershipBarrier)
            add(elementWrites)
            add(chrome.elements)
            addAll(orphanPeers)
            if (result.context != null) add(StudioAccentFields.tabs(references, ui))
        }
    }

    private fun captureAlwaysOn(
        schemes: StudioSchemeOperation,
        view: FontSchemeView,
        context: AccentContext?,
        accent: Color?,
        tabMode: GlowTabMode,
    ) {
        if (context == null) {
            view.schemes.forEach { scheme ->
                schemes.capture(scheme, emptyMap(), emptySet(), schemeOwner = EditorSchemeOwner.AlwaysOn)
            }
            return
        }
        val variant = context.variant ?: return
        if (!AyuEditorSchemeBinder.matchesVariant(view.active.name, variant)) return
        val requested = AccentEditorPalette.entries(view.active, checkNotNull(accent)).toMap().toMutableMap()
        if (tabMode == GlowTabMode.OFF) {
            requested[SchemeEntry.ColorEntry(ColorKey.find("TAB_UNDERLINE"))] =
                SchemeValue.ColorValue(Color.decode(variant.neutralGray))
        }
        schemes.capture(view.active, requested, requested.keys, SchemeSource.Active, EditorSchemeOwner.AlwaysOn)
    }

    private fun publication(
        input: StudioEffectInput,
        result: StudioAccentResult,
    ): StudioList<RuntimeFieldChange> {
        val hex = result.focused?.hex ?: return StudioList(emptyList())
        val values = mapOf("lastAppliedAccentHex" to RawValue.Text(hex), "lastApplyOk" to RawValue.BooleanValue(true))
        return StudioList(
            input.candidate.runtime.legacy.records.mapNotNull { field ->
                val value = values[field.key.name] ?: return@mapNotNull null
                if (value ==
                    field.value
                ) {
                    null
                } else {
                    RuntimeFieldChange(
                        FieldAddress(field.key),
                        FieldValue.Present(field.value),
                        FieldValue.Present(value),
                    )
                }
            },
        )
    }
}
