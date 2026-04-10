package dev.ayuislands.licensing

import com.intellij.ui.LicensingFacade
import dev.ayuislands.settings.AyuIslandsSettings
import dev.ayuislands.settings.AyuIslandsState
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LicenseCheckerPropertyTest {
    private lateinit var facade: LicensingFacade

    @BeforeTest
    fun setUp() {
        val state = AyuIslandsState()
        val settings = mockk<AyuIslandsSettings>()
        every { settings.state } returns state

        mockkObject(AyuIslandsSettings.Companion)
        every { AyuIslandsSettings.getInstance() } returns settings

        facade = mockk<LicensingFacade>()
        mockkStatic(LicensingFacade::class)
        every { LicensingFacade.getInstance() } returns facade
        every { facade.isEvaluationLicense } returns true

        System.clearProperty("ayu.islands.dev")
    }

    @AfterTest
    fun tearDown() {
        unmockkAll()
    }

    private fun dateFromNow(offsetDays: Int): Date {
        val localDate = LocalDate.now(ZoneId.of("UTC")).plusDays(offsetDays.toLong())
        return Date.from(localDate.atStartOfDay(ZoneId.of("UTC")).toInstant())
    }

    @Test
    fun `future expiration dates always yield non-null positive remaining days`(): Unit =
        runBlocking {
            checkAll(Arb.int(1..30)) { offset ->
                every { facade.getExpirationDate(LicenseChecker.PRODUCT_CODE) } returns dateFromNow(offset)
                val result = LicenseChecker.getTrialDaysRemaining()
                assertNotNull(result, "Future offset=$offset must return non-null")
                assertTrue(result > 0, "Future offset=$offset must be positive, got $result")
            }
        }

    @Test
    fun `past expiration dates always yield null`(): Unit =
        runBlocking {
            checkAll(Arb.int(-30..-1)) { offset ->
                every { facade.getExpirationDate(LicenseChecker.PRODUCT_CODE) } returns dateFromNow(offset)
                val result = LicenseChecker.getTrialDaysRemaining()
                assertNull(result, "Past offset=$offset must return null, got $result")
            }
        }

    @Test
    fun `trial days remaining is never negative`(): Unit =
        runBlocking {
            checkAll(Arb.int(-30..30)) { offset ->
                every { facade.getExpirationDate(LicenseChecker.PRODUCT_CODE) } returns dateFromNow(offset)
                val result = LicenseChecker.getTrialDaysRemaining()
                if (result != null) {
                    assertTrue(result >= 0, "Remaining days must be >= 0, got $result for offset=$offset")
                }
            }
        }

    @Test
    fun `today expiration yields zero remaining days`() =
        runBlocking {
            every { facade.getExpirationDate(LicenseChecker.PRODUCT_CODE) } returns dateFromNow(0)
            val result = LicenseChecker.getTrialDaysRemaining()
            assertNotNull(result, "Today expiration must return non-null")
            assertEquals(0L, result, "Today expiration must yield 0, got $result")
        }
}
