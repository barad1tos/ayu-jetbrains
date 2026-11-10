package dev.ayuislands.studio.session

import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.colors.TextAttributesKey
import dev.ayuislands.font.FontIdentityRules
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.syntax.SyntaxIntensityService
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeEntry
import dev.ayuislands.theme.EditorSchemeOverrides.SchemeValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Captures the canonical Syntax computation against editable targets without a legacy apply or child commit. */
internal class StudioSyntaxParticipant(
    private val host: StudioSchemeHost = IdeFontHost(),
    private val service: SyntaxIntensityService = SyntaxIntensityService.getInstance(),
    private val nativeContext: CoroutineContext = Dispatchers.EDT,
) : StudioEffectParticipant {
    override val owner: EffectOwner = EffectOwner.SYNTAX

    override suspend fun prepare(
        input: StudioEffectInput,
        runtime: StudioEffectRuntime,
    ): StudioEffectOperation =
        withContext(nativeContext) {
            val current = checkNotNull(StudioTargets.current(input.candidate, input.target))
            val config = StudioSyntaxProjection.config(current.appearance)
            val ignore = StudioPluginsEditing.settings(input.candidate.shared.plugins).integrations.ignoreColorsEnabled
            val facts = runtime.native
            val schemes = StudioSchemeOperation.prepare(host, nativeContext, owner, input, facts)
            val prepared = service.prepare(config, facts.isLicensed, ignore)
            prepared.changes.forEach { change ->
                val requested: Map<SchemeEntry, SchemeValue> =
                    change.attributes.entries.associate { (key, value) ->
                        SchemeEntry.AttributesEntry(key) to SchemeValue.AttributesValue(value)
                    }
                val managed =
                    change.materializedKeys.mapTo(
                        mutableSetOf(),
                    ) { SchemeEntry.AttributesEntry(TextAttributesKey.find(it)) }
                schemes.capture(change.scheme, requested, managed)
            }
            val token = input.candidate.header.transactionId
            val fonts =
                StudioSyntaxFonts(
                    service.fontState.prepare(prepared.replacementFonts),
                    token,
                    checkNotNull(facts.currentProcess),
                    StudioNativeTokens("$token/syntax-fonts"),
                )
            StudioSyntaxOperation(schemes, nativeContext, fonts)
        }

    override suspend fun recover(
        input: StudioEffectInput,
        receipts: StudioList<PortableReceipt>,
        runtime: StudioEffectRuntime,
    ): StudioEffectOperation =
        withContext(nativeContext) {
            val token = input.candidate.header.transactionId
            require(receipts.all { it.owner == owner && it.identity.ownerToken == token })
            val facts = runtime.native
            val native = receipts.filter { FontIdentityRules.canonicalId(it.identity.resource) != null }
            val semantic = receipts.filterNot { it in native }
            val schemes = StudioSchemeOperation(host, nativeContext, owner, input, native, facts)
            StudioSyntaxOperation(
                schemes,
                nativeContext,
                null,
                StudioSyntaxFonts.recoverLost(semantic, facts.currentProcess),
            )
        }
}
