package dev.ayuislands.integration

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.testFramework.ApplicationRule
import dev.ayuislands.studio.model.AcceptanceChoices
import dev.ayuislands.studio.model.AcceptanceReview
import dev.ayuislands.studio.model.CatalogCommand
import dev.ayuislands.studio.model.CatalogIntent
import dev.ayuislands.studio.model.CatalogPlan
import dev.ayuislands.studio.model.CatalogReview
import dev.ayuislands.studio.model.ConflictChoice
import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.EffectOwner
import dev.ayuislands.studio.model.FieldAddress
import dev.ayuislands.studio.model.FieldConflict
import dev.ayuislands.studio.model.FieldResolution
import dev.ayuislands.studio.model.FieldValue
import dev.ayuislands.studio.model.LegacyFieldKey
import dev.ayuislands.studio.model.LegacyStore
import dev.ayuislands.studio.model.NativeResolution
import dev.ayuislands.studio.model.NativeSurfaceChange
import dev.ayuislands.studio.model.ProfileDefinition
import dev.ayuislands.studio.model.ProfileId
import dev.ayuislands.studio.model.RawValue
import dev.ayuislands.studio.model.SessionIntent
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.session.StudioCatalogPolicy
import dev.ayuislands.studio.session.StudioDraftPolicy
import dev.ayuislands.studio.sessionDocument
import dev.ayuislands.studio.sessionRecord
import dev.ayuislands.studio.ui.StudioReviewDecision
import dev.ayuislands.studio.ui.StudioReviewDialog
import org.junit.Rule
import org.junit.Test
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StudioReviewDialogTest {
    @get:Rule val applicationRule = ApplicationRule()

    @Test
    fun `Continue keeps latest by default and scopes explicit choices to exact field and native observations`() =
        onHostEdt {
            val fixture = ReviewFixture()
            val first = reviewConflict("softIntensity")
            val second = reviewConflict("gradientIntensity")
            val native = reviewNative("scheme-a/LINE_MODIFIED")
            val otherNative = reviewNative("scheme-b/LINE_MODIFIED")
            val items =
                StudioList(
                    listOf(
                        AcceptanceReview.Field(first),
                        AcceptanceReview.Field(second),
                        AcceptanceReview.Native(native),
                        AcceptanceReview.Native(otherNative),
                    ),
                )
            val dialog = StudioReviewDialog(items, fixture.session, fixture.document)

            assertTrue(
                showReviewControls(dialog) {
                    val choices = reviewCombos(it)
                    assertEquals(4, choices.size)
                    assertTrue(choices.all { choice -> choice.selectedItem == "Keep latest" })
                    choices[0].selectedItem = "Use my change"
                    choices[2].selectedItem = "Use my change"
                    assertEquals(StudioReviewDecision.Cancel, it.decision)
                    reviewButton(it, "Continue").doClick()
                },
            )

            val accepted = assertIs<StudioReviewDecision.Continue>(dialog.decision)
            assertEquals(
                listOf(
                    FieldResolution(first, ConflictChoice.USE_MINE),
                    FieldResolution(second, ConflictChoice.KEEP_LATEST),
                ),
                accepted.choices.fields,
            )
            assertEquals(
                listOf(
                    NativeResolution(native, ConflictChoice.USE_MINE),
                    NativeResolution(otherNative, ConflictChoice.KEEP_LATEST),
                ),
                accepted.choices.native,
            )
            assertTrue(accepted.intents.isEmpty())
            fixture.assertUnchanged()
        }

    @Test
    fun `Cancel discards only review selections and preserves hidden draft and staged catalog work`() =
        onHostEdt {
            val fixture = ReviewFixture()
            val pending =
                fixture.session.copy(
                    work =
                        fixture.session.work.copy(
                            catalog = StudioList(listOf(CatalogIntent.Rename(fixture.user, "Pending name"))),
                        ),
                )
            val before = pending.copy()
            val items =
                StudioList(
                    listOf(
                        AcceptanceReview.HiddenDraft(fixture.hidden.id),
                        AcceptanceReview.Field(reviewConflict("softIntensity")),
                    ),
                )
            val dialog = StudioReviewDialog(items, pending, fixture.document)

            assertFalse(
                showReviewControls(dialog) {
                    val choices = reviewCombos(it)
                    choices[0].selectedItem = "Discard changes"
                    choices[1].selectedItem = "Use my change"
                    assertEquals(StudioReviewDecision.Cancel, it.decision)
                    assertEquals(before, pending)
                    reviewButton(it, "Cancel").doClick()
                },
            )

            assertEquals(StudioReviewDecision.Cancel, dialog.decision)
            assertEquals(before, pending)
            assertEquals(fixture.hidden, pending.work.drafts.single { it.id == fixture.hidden.id })
            fixture.assertUnchanged()
        }

    @Test
    fun `catalog deletion needs explicit confirmation and stale capture retains the exact reviewed snapshot`() =
        onHostEdt {
            val fixture = ReviewFixture()
            val captured =
                ProfileDefinition.User(
                    ProfileId("captured-copy"),
                    "Captured copy",
                    fixture.hidden.tracking.initial,
                    1,
                )
            val pending =
                fixture.session.copy(
                    work =
                        fixture.session.work.copy(
                            catalog =
                                StudioList(
                                    listOf(
                                        CatalogIntent.Delete(fixture.user),
                                        CatalogIntent.Create(captured, fixture.hidden.id),
                                    ),
                                ),
                        ),
                )
            val review = assertIs<CatalogPlan.Review>(StudioCatalogPolicy.plan(fixture.document, pending))
            val deletion =
                review.items
                    .filterIsInstance<CatalogReview.ConfirmDelete>()
                    .single()
                    .impact
            val stale =
                review.items
                    .filterIsInstance<CatalogReview.StaleCapture>()
                    .single()
                    .difference
            val dialog =
                StudioReviewDialog(
                    StudioList(review.items.map(AcceptanceReview::Catalog)),
                    pending,
                    fixture.document,
                )

            assertTrue(
                showReviewControls(dialog) {
                    val confirm = hostDescendants(it.reviewContent).filterIsInstance<JCheckBox>().single()
                    val keep = reviewCombos(it).single()
                    val proceed = reviewButton(it, "Continue", enabled = false)
                    assertEquals("Delete profile", confirm.text)
                    assertFalse(confirm.isSelected)
                    assertEquals("Choose action", keep.selectedItem)
                    confirm.doClick()
                    assertFalse(proceed.isEnabled)
                    keep.selectedItem = "Keep staged snapshot"
                    assertTrue(proceed.isEnabled)
                    assertEquals(StudioReviewDecision.Cancel, it.decision)
                    proceed.doClick()
                },
            )

            val accepted = assertIs<StudioReviewDecision.Continue>(dialog.decision)
            assertEquals(listOf(deletion), accepted.choices.catalog.deletions)
            assertEquals(listOf(stale), accepted.choices.catalog.retained)
            assertTrue(accepted.intents.isEmpty())
            assertEquals(fixture.hidden.appearance, stale.latest)
            assertNotEquals(captured.appearance, stale.latest)
            fixture.assertUnchanged()
        }

    @Test
    fun `hidden user draft actions return typed intents only on Continue while Edit exits without applying them`() =
        onHostEdt {
            val fixture = ReviewFixture()
            val items = StudioList(listOf<AcceptanceReview>(AcceptanceReview.HiddenDraft(fixture.hidden.id)))
            for (action in listOf("Update profile", "Discard changes", "Create profile")) {
                val dialog = StudioReviewDialog(items, fixture.session, fixture.document)
                assertTrue(
                    showReviewControls(dialog) {
                        val choice = reviewCombos(it).single()
                        val name = hostDescendants(it.reviewContent).filterIsInstance<JTextField>().single()
                        assertEquals("Choose action", choice.selectedItem)
                        assertFalse(reviewButton(it, "Continue", enabled = false).isEnabled)
                        assertFalse(name.isVisible)
                        choice.selectedItem = action
                        if (action == "Create profile") {
                            assertTrue(name.isVisible)
                            name.text = "Saved hidden draft"
                        }
                        assertEquals(StudioReviewDecision.Cancel, it.decision)
                        fixture.assertUnchanged()
                        reviewButton(it, "Continue").doClick()
                    },
                )
                val accepted = assertIs<StudioReviewDecision.Continue>(dialog.decision)
                assertEquals(AcceptanceChoices(), accepted.choices)
                val intent = accepted.intents.single()
                when (action) {
                    "Update profile" -> {
                        assertEquals(
                            SessionIntent.Catalog(CatalogCommand.Update(fixture.hidden.id, fixture.user.id)),
                            intent,
                        )
                    }

                    "Discard changes" -> {
                        assertEquals(SessionIntent.Discard(fixture.hidden.id), intent)
                    }

                    else -> {
                        val command = assertIs<CatalogCommand.Create>(assertIs<SessionIntent.Catalog>(intent).command)
                        assertEquals(fixture.hidden.id, command.draft)
                        assertEquals("Saved hidden draft", command.name)
                        assertTrue(command.id.value.isNotBlank())
                        assertTrue(fixture.document.catalog.none { it.id == command.id })
                    }
                }
                fixture.assertUnchanged()
            }
            val edit = StudioReviewDialog(items, fixture.session, fixture.document)
            showReviewControls(edit) {
                reviewCombos(it).single().selectedItem = "Discard changes"
                assertEquals(StudioReviewDecision.Cancel, it.decision)
                reviewButton(it, "Edit").doClick()
            }
            assertEquals(StudioReviewDecision.Edit(fixture.hidden.id), edit.decision)
            fixture.assertUnchanged()
        }
}

private class ReviewFixture {
    private val initial = sessionDocument()
    val user = ProfileDefinition.User(ProfileId("review-user"), "Personal", initial.currents.global.appearance, 1)
    val document = initial.copy(catalog = StudioList(initial.catalog + user))
    private val baseline = sessionRecord(document)
    private val selected =
        StudioDraftPolicy.select(baseline.work, user, DraftId("hidden-user"), document.currents.global)
    val hidden =
        StudioDraftPolicy.edit(
            selected.drafts.single { it.id == selected.selected },
            FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, "softIntensity")),
            FieldValue.Present(RawValue.Integer(88)),
        )
    val session =
        baseline.copy(
            work =
                selected.copy(
                    selected = baseline.work.selected,
                    drafts = StudioList(selected.drafts.map { if (it.id == hidden.id) hidden else it }),
                ),
        )
    private val before = session.copy()
    private val accepted = document.copy()

    fun assertUnchanged() {
        assertEquals(before, session)
        assertEquals(accepted, document)
        assertTrue(StudioDraftPolicy.isDirty(hidden))
        assertNotEquals(hidden.id, session.work.selected)
    }
}

private fun reviewConflict(name: String): FieldConflict =
    FieldConflict(
        FieldAddress(LegacyFieldKey(LegacyStore.SETTINGS, name)),
        FieldValue.Present(RawValue.Integer(10)),
        FieldValue.Present(RawValue.Integer(20)),
        FieldValue.Present(RawValue.Integer(30)),
    )

private fun reviewNative(identity: String): NativeSurfaceChange =
    NativeSurfaceChange(
        EffectOwner.VCS,
        identity,
        FieldValue.Present(RawValue.Text("#123456")),
        FieldValue.Present(RawValue.Text("#654321")),
        FieldValue.Present(RawValue.Text("#ABCDEF")),
    )

private fun reviewCombos(dialog: StudioReviewDialog): List<JComboBox<*>> =
    hostDescendants(dialog.reviewContent).filterIsInstance<JComboBox<*>>()

internal fun reviewButton(
    dialog: StudioReviewDialog,
    label: String,
    enabled: Boolean = true,
): JButton =
    (hostDescendants(dialog.reviewContent).filterIsInstance<JButton>() + dialog.reviewButtons)
        .single { it.text == label }
        .also {
            assertEquals(enabled, it.isEnabled, label)
            assertTrue(it.isShowing, label)
        }

/** Hosts actual SDK-created controls; ApplicationRule's peer cannot create a modal dialog window. */
internal fun showReviewControls(
    dialog: StudioReviewDialog,
    interact: (StudioReviewDialog) -> Unit,
): Boolean {
    check(SwingUtilities.isEventDispatchThread())
    val frame =
        JFrame("Studio review controls test").apply {
            isAutoRequestFocus = false
            setLocation(-10_000, -10_000)
            setSize(800, 700)
            contentPane =
                JPanel(BorderLayout()).apply {
                    add(dialog.reviewContent, BorderLayout.CENTER)
                    add(
                        JPanel(FlowLayout(FlowLayout.TRAILING)).apply {
                            dialog.reviewButtons.forEach(::add)
                        },
                        BorderLayout.SOUTH,
                    )
                }
        }
    return try {
        frame.isVisible = true
        frame.validate()
        assertTrue(dialog.reviewContent.isShowing)
        assertFalse(dialog.isDisposed)
        interact(dialog)
        assertTrue(dialog.isDisposed, "The selected action did not dispose the dialog")
        dialog.isOK
    } finally {
        try {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
        } finally {
            frame.dispose()
        }
    }
}
