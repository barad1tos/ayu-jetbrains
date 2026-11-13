package dev.ayuislands.studio

import dev.ayuislands.studio.persistence.CommitEvidence
import dev.ayuislands.studio.persistence.CommitResolution
import dev.ayuislands.studio.persistence.PreparedToken
import dev.ayuislands.studio.persistence.resolveCommit
import kotlin.test.Test
import kotlin.test.assertEquals

class StudioCommitEvidenceTest {
    @Test
    fun `readback recognizes only the exact prior or candidate generation`() {
        val token = PreparedToken("tx", 4, "base", 5)
        val cases =
            listOf(
                CommitEvidence.Valid("tx", 5, "candidate") to CommitResolution.Committed,
                CommitEvidence.Valid("previous", 4, "base") to CommitResolution.NotCommitted,
                CommitEvidence.Valid("tx", 5, "other") to CommitResolution.Unknown,
                CommitEvidence.Valid("tx", 6, "candidate") to CommitResolution.Unknown,
                CommitEvidence.Valid("other", 5, "candidate") to CommitResolution.Unknown,
                CommitEvidence.Valid("previous", 4, "other") to CommitResolution.Unknown,
                CommitEvidence.Absent to CommitResolution.Unknown,
                CommitEvidence.Unreadable to CommitResolution.Unknown,
            )
        for ((readback, expected) in cases) assertEquals(expected, resolveCommit(token, "candidate", readback))
    }

    @Test
    fun `missing file proves an uncommitted first write only when there was no base`() {
        val first = PreparedToken("first", -1, null, 0)
        assertEquals(CommitResolution.NotCommitted, resolveCommit(first, "new", CommitEvidence.Absent))
        assertEquals(CommitResolution.Unknown, resolveCommit(first, "new", CommitEvidence.Unreadable))
    }
}
