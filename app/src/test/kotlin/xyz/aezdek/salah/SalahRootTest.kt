package xyz.aezdek.salah

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.aezdek.salah.core.Prayer
import xyz.aezdek.salah.core.PrayerStatus
import xyz.aezdek.salah.core.SalahData
import xyz.aezdek.salah.core.statusOf
import xyz.aezdek.salah.core.withStatus
import xyz.aezdek.salah.ui.SalahRoot
import xyz.aezdek.salah.ui.theme.SalahTheme
import java.time.LocalDate

/** Drives the real screens against in-memory data (a plain Application, so no DataStore or alarms). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SalahRootTest {
    @get:Rule
    val compose = createComposeRule()

    // A Friday, so Dhuhr is shown as Jumu'ah.
    private val today = LocalDate.of(2026, 10, 23)
    private var data by mutableStateOf(SalahData())

    private fun show() {
        compose.setContent {
            SalahTheme(data.settings) {
                SalahRoot(
                    data = data,
                    today = today,
                    onEdit = { transform -> data = transform(data) },
                    backupText = { "" },
                    restore = { data = it },
                )
            }
        }
    }

    @Test
    fun ticking_a_prayer_logs_it_and_tapping_again_clears_it() {
        show()
        compose.onNodeWithTag("prayed-FAJR").performClick()
        compose.onNodeWithTag("missed-ISHA").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(PrayerStatus.PRAYED, data.statusOf(today, Prayer.FAJR))
        assertEquals(PrayerStatus.MISSED, data.statusOf(today, Prayer.ISHA))

        compose.onNodeWithTag("prayed-FAJR").performScrollTo().performClick()
        compose.waitForIdle()
        assertNull(data.statusOf(today, Prayer.FAJR))

        compose.onNodeWithText("Jumu'ah · ${Prayer.DHUHR.arabic}").assertExists()
    }

    @Test
    fun mark_the_rest_prayed_keeps_misses() {
        data = data.withStatus(today, Prayer.ASR, PrayerStatus.MISSED)
        show()
        compose.onNodeWithTag("rest-prayed").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(PrayerStatus.MISSED, data.statusOf(today, Prayer.ASR))
        assertEquals(PrayerStatus.PRAYED, data.statusOf(today, Prayer.ISHA))
    }

    @Test
    fun a_missed_prayer_becomes_qada_and_can_be_made_up_and_undone() {
        data = data.withStatus(today.minusDays(3), Prayer.ISHA, PrayerStatus.MISSED)
        show()
        compose.onNodeWithTag("tab-QADA").performClick()
        compose.onNodeWithTag("remaining-ISHA").performScrollTo().assertTextEquals("1")

        compose.onNodeWithTag("madeup-ISHA").performScrollTo().performClick()
        compose.onNodeWithTag("remaining-ISHA").assertTextEquals("0")
        compose.onNodeWithTag("madeup-ISHA").assertIsNotEnabled()

        compose.onNodeWithTag("undo-ISHA").performClick()
        compose.onNodeWithTag("remaining-ISHA").assertTextEquals("1")
    }

    @Test
    fun adding_old_missed_prayers_by_time() {
        show()
        compose.onNodeWithTag("tab-QADA").performClick()
        compose.onNodeWithTag("add-missed").performClick()
        compose.onNodeWithTag("unit-DAYS").performClick()
        compose.onNodeWithTag("amount").performTextInput("10")
        compose.onNodeWithTag("add-confirm").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(Prayer.entries.associateWith { 10 }, data.owed)
        compose.onNodeWithTag("remaining-total").assertTextEquals("50")
    }

    @Test
    fun adding_old_missed_prayers_per_prayer() {
        show()
        compose.onNodeWithTag("tab-QADA").performClick()
        compose.onNodeWithTag("add-missed").performClick()
        compose.onNodeWithTag("mode-per-prayer").performClick()
        compose.onNodeWithTag("count-FAJR").performScrollTo().performTextInput("30")
        compose.onNodeWithTag("count-ASR").performScrollTo().performTextInput("4")
        compose.onNodeWithTag("add-confirm").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(mapOf(Prayer.FAJR to 30, Prayer.ASR to 4), data.owed)
    }

    @Test
    fun history_opens_a_past_day_and_today_comes_back() {
        show()
        compose.onNodeWithTag("tab-HISTORY").performClick()
        compose.onNodeWithTag("day-2026-10-20").performScrollTo().performClick()
        compose.onNodeWithText("Tuesday, 20 October 2026").assertExists()

        compose.onNodeWithTag("prayed-MAGHRIB").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(PrayerStatus.PRAYED, data.statusOf(LocalDate.of(2026, 10, 20), Prayer.MAGHRIB))

        compose.onNodeWithText("Back to today").performScrollTo().performClick()
        compose.onNodeWithText("Friday, 23 October 2026").assertExists()
        compose.onNodeWithTag("next-day").assertIsNotEnabled()
    }

    @Test
    fun settings_and_history_render() {
        show()
        compose.onNodeWithTag("tab-SETTINGS").performClick()
        compose.onNodeWithText("Daily reminder").assertExists()
        compose.onNodeWithTag("tab-HISTORY").performClick()
        compose.onNodeWithText("October 2026").assertExists()
    }
}
