package dev.ayuislands.projectview

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.RegistryValue
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RootPathLeaseTest {
    @Test
    fun `last project restores the shared registry baseline`() {
        var currentValue = true
        val registryValue =
            registryValue(
                read = { currentValue },
                write = { currentValue = it },
            )
        val firstProject = mockk<Project>()
        val secondProject = mockk<Project>()
        val lease = RootPathLease()

        lease.acquire(firstProject, registryValue)
        lease.acquire(secondProject, registryValue)
        lease.release(firstProject, registryValue)

        assertEquals(false, currentValue)
        verify(exactly = 0) { registryValue.resetToDefault() }

        lease.release(secondProject, registryValue)

        assertEquals(true, currentValue)
        verify(exactly = 1) { registryValue.resetToDefault() }
    }

    @Test
    fun `manual registry drift is preserved until all projects release ownership`() {
        var currentValue = true
        val registryValue =
            registryValue(
                read = { currentValue },
                write = { currentValue = it },
            )
        val firstProject = mockk<Project>()
        val secondProject = mockk<Project>()
        val lease = RootPathLease()

        lease.acquire(firstProject, registryValue)
        currentValue = true
        lease.acquire(secondProject, registryValue)
        lease.release(firstProject, registryValue)
        lease.release(secondProject, registryValue)

        assertEquals(true, currentValue)
        verify(exactly = 0) { registryValue.resetToDefault() }
    }

    @Test
    fun `failed last project restore retains ownership for retry`() {
        var currentValue = true
        var resetAttempts = 0
        val registryValue =
            registryValue(
                read = { currentValue },
                write = { currentValue = it },
            )
        every { registryValue.resetToDefault() } answers {
            resetAttempts += 1
            if (resetAttempts == 1) error("registry restore failed")
            currentValue = true
        }
        val project = mockk<Project>()
        val lease = RootPathLease()

        lease.acquire(project, registryValue)
        assertFailsWith<IllegalStateException> {
            lease.release(project, registryValue)
        }
        assertEquals(false, currentValue)

        lease.release(project, registryValue)

        assertEquals(true, currentValue)
        assertEquals(2, resetAttempts)
    }

    private fun registryValue(
        read: () -> Boolean,
        write: (Boolean) -> Unit,
    ): RegistryValue {
        val registryValue = mockk<RegistryValue>(relaxed = true)
        every { registryValue.asBoolean() } answers { read() }
        every { registryValue.isChangedFromDefault() } returns false
        every { registryValue.setValue(any<Boolean>()) } answers {
            write(firstArg())
        }
        every { registryValue.resetToDefault() } answers {
            write(true)
        }
        return registryValue
    }
}
