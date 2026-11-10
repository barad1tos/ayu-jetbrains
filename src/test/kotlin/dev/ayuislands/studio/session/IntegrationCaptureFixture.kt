package dev.ayuislands.studio.session

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import com.nasller.codeglance.config.CodeGlanceConfig
import com.nasller.codeglance.config.CodeGlanceConfigService
import dev.ayuislands.accent.AccentContext
import dev.ayuislands.accent.AyuVariant
import dev.ayuislands.accent.CgpAccess
import dev.ayuislands.accent.CgpAccessProvider
import dev.ayuislands.accent.CgpPart
import dev.ayuislands.indent.IrAccessProvider
import dev.ayuislands.indent.ResolvedIrState
import dev.ayuislands.integration.codeglance.CodeGlanceAdapter
import dev.ayuislands.integration.indent.IndentRainbowAdapter
import dev.ayuislands.licensing.LicenseChecker
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import dev.ayuislands.studio.StudioFixtures
import dev.ayuislands.studio.migration.MigrationResult
import dev.ayuislands.studio.migration.StudioLegacyMigration
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProcess
import dev.ayuislands.studio.model.StudioRuntimeState
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.persistence.JournalDecodeResult
import dev.ayuislands.studio.persistence.PendingJournal
import dev.ayuislands.studio.persistence.PortableReceipt
import dev.ayuislands.studio.persistence.PreparedToken
import dev.ayuislands.studio.persistence.RecoveryPlan
import dev.ayuislands.studio.persistence.StudioJournalCodec
import dev.ayuislands.theme.EditorSchemeChange
import indent.rainbow.settings.IrCachedData
import indent.rainbow.settings.IrColorsPaletteType
import indent.rainbow.settings.IrConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.time.Instant
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** Only native service discovery and refresh publication are mocked; both persisted config beans are real. */
internal class IntegrationCaptureFixture {
    private val application = mockk<Application>()
    private val service = mockk<CodeGlanceConfigService>()
    var codeGlance = originalCodeGlance()
        private set
    var indent = originalIndent()
        private set
    val context = EmptyCoroutineContext
    val facts = StudioNativeFacts(true, StudioProcess(81, Instant.EPOCH))
    val writes = mutableListOf<String>()
    var borderFailure: RuntimeException? = null
    var beforeSetter: () -> Unit = {}
    var acknowledged: StudioList<PortableReceipt> = StudioList(emptyList())

    init {
        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns application
        every { application.getService(CodeGlanceConfigService::class.java) } returns service
        every { service.state } answers { codeGlance }
        every { application.getService(IrConfig::class.java) } answers { indent }
        mockkObject(EditorSchemeChange)
        every { EditorSchemeChange.publish() } returns Unit
        mockkObject(LicenseChecker)
        every { LicenseChecker.isLicensedOrGrace() } throws AssertionError("Use explicit lease entitlement")
        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } throws AssertionError("Do not read or swap live legacy settings")
    }

    fun close() {
        IrCachedData.update(originalIndent())
        unmockkAll()
    }

    fun capture(): StudioIntegrationCapture = StudioIntegrationCapture(codeGlanceProvider(), indentProvider(), context)

    suspend fun prepare(
        input: StudioEffectInput,
        licensed: Boolean = true,
    ): StudioEffectOperation =
        capture().prepare(input, AccentContext.Ayu(AyuVariant.MIRAGE), "#112233", facts.copy(isLicensed = licensed))

    fun input(requested: AyuIslandsState = settings()): StudioEffectInput {
        val migrated = StudioLegacyMigration.plan(StudioFixtures.capture(requested))
        val document = assertIs<MigrationResult.Ready>(migrated).document
        return StudioEffectInput(document, StudioTarget.Global, StudioList(emptyList()))
    }

    fun settings(): AyuIslandsState =
        AyuIslandsState().apply {
            cgpIntegrationEnabled = true
            irIntegrationEnabled = true
            isCgpOwnershipMigrated = true
            isIrOwnershipMigrated = true
            indentPresetName = "NEON"
            indentCustomAlpha = 217
            irErrorHighlightEnabled = false
            irFailedVersion = "retained-old-observation"
            externalThemeAccentSource = "future-accent-source"
            externalThemeAccent = " raw external accent "
            softWidth = 19
            fontPresetCustomizations = linkedMapOf("future-b" to "raw B", "future-a" to "raw A")
        }

    fun acknowledge(
        input: StudioEffectInput,
        operation: StudioEffectOperation,
    ) {
        acknowledged = roundTrip(input, operation.receipts)
    }

    fun roundTrip(
        input: StudioEffectInput,
        records: StudioList<PortableReceipt>,
    ): StudioList<PortableReceipt> {
        val document = input.candidate
        val journal =
            PendingJournal(
                PreparedToken(document.header.transactionId, -1, null, 0),
                null,
                document.copy(header = document.header.copy(revision = 0)),
                RecoveryPlan(input.target, StudioList(listOf(EffectOwner.ACCENT))),
                records,
            )
        return assertIs<JournalDecodeResult.Ready>(StudioJournalCodec.decode(StudioJournalCodec.encode(journal)))
            .journal.receipts
    }

    suspend fun restore(operation: StudioEffectOperation) {
        val blockers = StudioList<PortableReceipt>(emptyList())
        operation.prepareRestore(blockers)
        assertEquals(StudioRestoreContinuation.NONE, operation.restore(blockers))
    }

    fun value(
        runtime: StudioRuntimeState,
        name: String,
    ): RawValue =
        runtime.legacy.records
            .single { it.key.name == name }
            .value

    fun snapshot(): Pair<String, IrConfig> = xml(codeGlance) to indent.copy()

    fun reload() {
        codeGlance = XmlSerializer.deserialize(XmlSerializer.serialize(codeGlance), CodeGlanceConfig::class.java)
        indent = XmlSerializer.deserialize(XmlSerializer.serialize(indent), IrConfig::class.java)
    }

    fun assertOriginal(
        codeGlanceBorder: String = "654321",
        indentPalette: String = "FF123456, 80654321",
    ) {
        val expectedCodeGlance = originalCodeGlance().apply { viewportBorderColor = codeGlanceBorder }
        assertEquals(xml(expectedCodeGlance), xml(codeGlance))
        assertEquals(originalIndent().copy(customPalette = indentPalette), indent)
    }

    private fun codeGlanceProvider(): CgpAccessProvider =
        CgpAccessProvider {
            val native = assertNotNull(CodeGlanceAdapter().resolve())
            CgpAccess(
                read = native::readViewport,
                write = { part, target ->
                    beforeSetter()
                    writes += "cgp/$part"
                    native.writePart(part, target)
                    if (part == CgpPart.BORDER) {
                        borderFailure?.let { failure ->
                            borderFailure = null
                            throw failure
                        }
                    }
                },
                targetMatches = native::isCurrent,
            )
        }

    private fun indentProvider(): IrAccessProvider =
        IrAccessProvider {
            val native = assertNotNull(IndentRainbowAdapter().resolve())
            val targetConfig = indent
            ResolvedIrState(
                paletteTypes = native.paletteTypes,
                read = native::readPalette,
                write = { part, target ->
                    beforeSetter()
                    writes += "ir/$part"
                    native.writePart(part, target)
                },
                update = { IrCachedData.update(targetConfig) },
                current = native::isCurrent,
            )
        }

    private fun xml(config: CodeGlanceConfig): String = JDOMUtil.writeElement(XmlSerializer.serialize(config))

    private fun originalCodeGlance(): CodeGlanceConfig =
        CodeGlanceConfig().apply {
            viewportColor = "123456"
            viewportBorderColor = "654321"
            viewportBorderThickness = 3
            disabled = true
            mainWidth = 137
            markersScaleFactor = 2.25f
        }

    private fun originalIndent(): IrConfig =
        IrConfig().apply {
            paletteType = IrColorsPaletteType.PASTEL
            customPalette = "FF123456, 80654321"
            customPaletteNumberColors = 4
            enabled = false
            highlightEmptyLines = true
            opacityMultiplier = 0.37f
        }
}
