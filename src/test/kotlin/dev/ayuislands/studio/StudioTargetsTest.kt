package dev.ayuislands.studio

import dev.ayuislands.studio.model.DraftId
import dev.ayuislands.studio.model.ProfileDefinition
import dev.ayuislands.studio.model.ProfileReference
import dev.ayuislands.studio.model.StudioList
import dev.ayuislands.studio.model.StudioProjectKey
import dev.ayuislands.studio.model.StudioProjectRecord
import dev.ayuislands.studio.model.StudioTarget
import dev.ayuislands.studio.session.StudioSessionRecords
import dev.ayuislands.studio.session.StudioTargets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class StudioTargetsTest {
    @Test
    fun `accepting a project and global appearance updates only the claimed target`() {
        val initial = catalogTestDocument()
        val global = initial.currents.global
        val project = StudioTarget.Project(StudioProjectKey("work"))
        val personal = StudioProjectRecord(StudioProjectKey("personal"), "Personal", global.copy(revision = 2))
        val work = StudioProjectRecord(project.key, "Work", global.copy(revision = 3))
        val latest = initial.copy(currents = initial.currents.copy(projects = StudioList(listOf(personal, work))))
        val projectChange = work.current.copy(revision = 4)
        val changed = StudioTargets.replace(latest, project, projectChange)

        assertEquals(global, changed.currents.global)
        assertEquals(listOf(personal, work.copy(current = projectChange)), changed.currents.projects)
        assertEquals(latest.copy(currents = changed.currents), changed)

        val globalChange = global.copy(revision = 5)
        val globallyChanged = StudioTargets.replace(changed, StudioTarget.Global, globalChange)
        assertEquals(changed.currents.copy(global = globalChange), globallyChanged.currents)
        assertEquals(projectChange, StudioTargets.current(globallyChanged, project))
        val closed = StudioTarget.Project(StudioProjectKey("closed"))
        assertNull(StudioTargets.current(globallyChanged, closed))
        assertFailsWith<IllegalArgumentException> { StudioTargets.replace(globallyChanged, closed, projectChange) }
    }

    @Test
    fun `a project opens its own saved profile draft and an absent target gets no draft`() {
        val initial = catalogTestDocument()
        val profile = initial.catalog.filterIsInstance<ProfileDefinition.User>().single()
        val link = ProfileReference(profile.id, profile.revision)
        val key = StudioProjectKey("work")
        val accepted = initial.currents.global.copy(source = link, appearance = profile.appearance)
        val project = StudioProjectRecord(key, "Work", accepted)
        val document = initial.copy(currents = initial.currents.copy(projects = StudioList(listOf(project))))

        val id = DraftId("draft")
        val missing = StudioTarget.Project(StudioProjectKey("missing"))
        assertNull(StudioSessionRecords.current(document, missing, "missing", id))
        val target = StudioTarget.Project(key)
        val session = assertNotNull(StudioSessionRecords.current(document, target, "settings", id))
        assertEquals(target, session.target)
        assertEquals(id, session.work.selected)
        val draft = session.work.drafts.single()
        assertEquals(accepted, draft.base)
        assertEquals(link, draft.source)
        assertEquals(profile.name, draft.sourceName)
    }
}
