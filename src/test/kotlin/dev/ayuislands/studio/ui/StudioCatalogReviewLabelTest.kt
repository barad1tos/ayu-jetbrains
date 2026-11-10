package dev.ayuislands.studio.ui

import dev.ayuislands.studio.model.CatalogIntent
import dev.ayuislands.studio.model.CatalogPlan
import dev.ayuislands.studio.model.CatalogReview
import dev.ayuislands.studio.model.ProfileDefinition
import dev.ayuislands.studio.model.ProfileId
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.session.StudioCatalogPolicy
import dev.ayuislands.studio.sessionDocument
import dev.ayuislands.studio.sessionRecord
import org.junit.Test
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class StudioCatalogReviewLabelTest {
    @Test
    fun `duplicate deletion labels identify the exact profile without reordering or renaming it`() {
        SwingUtilities.invokeAndWait {
            val original = sessionDocument()
            val first =
                ProfileDefinition.User(
                    ProfileId("same-prefix-a"),
                    "Evening / вечір",
                    original.currents.global.appearance,
                    1,
                )
            val second = first.copy(id = ProfileId("same-prefix-b"))
            val unique = first.copy(id = ProfileId("unique-profile"), name = "Separate profile")
            val document = original.copy(catalog = StudioList(original.catalog + listOf(first, second, unique)))
            val baseline = sessionRecord(document)
            val pending =
                baseline.copy(
                    work =
                        baseline.work.copy(
                            catalog = StudioList(listOf(second, first, unique).map(CatalogIntent::Delete)),
                        ),
                )
            val plan = assertIs<CatalogPlan.Review>(StudioCatalogPolicy.plan(document, pending))
            val deletions = plan.items.map { assertIs<CatalogReview.ConfirmDelete>(it) }
            val review = StudioCatalogReview(pending, document, onChange = {}, edit = {})
            val rows = deletions.map { assertIs<JPanel>(review.item(it)) }

            assertEquals(
                listOf(
                    "Delete profile: Evening / вечір · User · same-prefix-b",
                    "Delete profile: Evening / вечір · User · same-prefix-a",
                    "Delete profile: Separate profile",
                ),
                rows.map { row ->
                    row.components
                        .filterIsInstance<JLabel>()
                        .first()
                        .text
                },
            )
            assertFalse(review.isResolved())
            rows
                .first()
                .components
                .filterIsInstance<JCheckBox>()
                .single()
                .doClick()
            assertEquals(listOf(deletions.first().impact), review.deletions())
            assertEquals(listOf(second.id, first.id, unique.id), deletions.map { it.impact.definition.id })
            assertEquals(listOf(first, second, unique), document.catalog.takeLast(3))
        }
    }
}
