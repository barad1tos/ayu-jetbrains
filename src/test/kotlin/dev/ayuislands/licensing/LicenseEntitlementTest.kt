package dev.ayuislands.licensing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LicenseEntitlementTest {
    @Test
    fun `confirmed license becomes licensed and advances the timestamp`() {
        val result =
            resolveEntitlement(
                rawLicense = true,
                lastLicensedMs = NOW_MS - 1,
                nowMs = NOW_MS,
            )

        assertEquals(LicenseEntitlement.LICENSED, result.entitlement)
        assertEquals(NOW_MS, result.lastLicensedMs)
        assertFalse(result.isStampReset)
    }

    @Test
    fun `confirmed license preserves a future timestamp for the next rollback check`() {
        val futureStamp = NOW_MS + 1

        val result =
            resolveEntitlement(
                rawLicense = true,
                lastLicensedMs = futureStamp,
                nowMs = NOW_MS,
            )

        assertEquals(LicenseEntitlement.LICENSED, result.entitlement)
        assertEquals(futureStamp, result.lastLicensedMs)
        assertFalse(result.isStampReset)
    }

    @Test
    fun `uninitialized marketplace state stays unknown without changing the timestamp`() {
        val result =
            resolveEntitlement(
                rawLicense = null,
                lastLicensedMs = NOW_MS - HOUR_MS,
                nowMs = NOW_MS,
            )

        assertEquals(LicenseEntitlement.UNKNOWN, result.entitlement)
        assertEquals(NOW_MS - HOUR_MS, result.lastLicensedMs)
        assertFalse(result.isStampReset)
    }

    @Test
    fun `unlicensed marketplace state inside offline grace remains licensed`() {
        val result =
            resolveEntitlement(
                rawLicense = false,
                lastLicensedMs = NOW_MS - OFFLINE_GRACE_MS + 1,
                nowMs = NOW_MS,
            )

        assertEquals(LicenseEntitlement.LICENSED, result.entitlement)
        assertEquals(NOW_MS - OFFLINE_GRACE_MS + 1, result.lastLicensedMs)
        assertFalse(result.isStampReset)
    }

    @Test
    fun `unlicensed marketplace state at grace boundary is unlicensed`() {
        val result =
            resolveEntitlement(
                rawLicense = false,
                lastLicensedMs = NOW_MS - OFFLINE_GRACE_MS,
                nowMs = NOW_MS,
            )

        assertEquals(LicenseEntitlement.UNLICENSED, result.entitlement)
        assertEquals(NOW_MS - OFFLINE_GRACE_MS, result.lastLicensedMs)
        assertFalse(result.isStampReset)
    }

    @Test
    fun `unlicensed fresh install is unlicensed without a grace timestamp`() {
        val result =
            resolveEntitlement(
                rawLicense = false,
                lastLicensedMs = 0,
                nowMs = NOW_MS,
            )

        assertEquals(LicenseEntitlement.UNLICENSED, result.entitlement)
        assertEquals(0, result.lastLicensedMs)
        assertFalse(result.isStampReset)
    }

    @Test
    fun `future timestamp revokes grace and records the reset`() {
        val result =
            resolveEntitlement(
                rawLicense = false,
                lastLicensedMs = NOW_MS + 1,
                nowMs = NOW_MS,
            )

        assertEquals(LicenseEntitlement.UNLICENSED, result.entitlement)
        assertEquals(0, result.lastLicensedMs)
        assertTrue(result.isStampReset)
    }

    private companion object {
        const val NOW_MS = 1_700_000_000_000L
        const val HOUR_MS = 3_600_000L
        const val OFFLINE_GRACE_MS = 48L * HOUR_MS
    }
}
