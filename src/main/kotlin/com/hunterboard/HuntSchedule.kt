package com.hunterboard

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * When Tropimon regenerates the weekly hunting board.
 *
 * The board resets **Wednesday at 21:00 Europe/Paris**. Two things need that instant:
 * the countdown on the hunt HUD, and — more importantly — knowing that a saved board has
 * expired. [BoardState] persists the board to disk on purpose, so after a reset the HUD
 * would otherwise keep showing last week's targets, ticks included, until the player
 * happened to reopen Tropimon's board.
 *
 * The schedule is read from Tropimon when its class is reachable, so a change on their
 * side carries over without a HunterBoard release; the same values are hard-coded as a
 * fallback, because the class lives in a nested jar of a proprietary mod and may not
 * resolve at all. Both paths agree today — the constants below were read out of
 * `HuntSchedule`'s own static initialiser.
 */
object HuntSchedule {

    private val ZONE: ZoneId = ZoneId.of("Europe/Paris")
    private val RESET_DAY: DayOfWeek = DayOfWeek.WEDNESDAY
    private val RESET_TIME: LocalTime = LocalTime.of(21, 0)

    private const val TROPIMON_CLASS = "fr.tropimon.tropimoncore.data.hunt.HuntSchedule"

    /** Resolved once: Tropimon's `nextResetEpochMilli()`, or null when it is unavailable. */
    private var tropimonMethod: java.lang.reflect.Method? = null
    private var tropimonResolved = false

    private fun tropimonNextReset(): Long? {
        if (!tropimonResolved) {
            tropimonResolved = true
            // Throwable rather than Exception: a missing class surfaces as
            // NoClassDefFoundError, which is an Error and would escape a catch of Exception.
            tropimonMethod = try {
                Class.forName(TROPIMON_CLASS).getMethod("nextResetEpochMilli").also {
                    HunterBoard.LOGGER.info("[Hunt] using Tropimon's own reset schedule")
                }
            } catch (_: Throwable) {
                HunterBoard.LOGGER.info("[Hunt] Tropimon reset schedule unavailable, using built-in (Wed 21:00 Paris)")
                null
            }
        }
        val method = tropimonMethod ?: return null
        return try {
            (method.invoke(null) as? Long)?.takeIf { it > 0L }
        } catch (_: Throwable) {
            // One failure is enough to stop asking.
            tropimonMethod = null
            null
        }
    }

    /** The first reset strictly after [from], computed locally. */
    private fun nextResetAfter(from: ZonedDateTime): ZonedDateTime {
        val candidate = from
            .with(TemporalAdjusters.nextOrSame(RESET_DAY))
            .with(RESET_TIME)
            .withSecond(0)
            .withNano(0)
        return if (candidate.isAfter(from)) candidate else candidate.plusWeeks(1)
    }

    /** Epoch millis of the next board reset. */
    fun nextResetMillis(): Long =
        tropimonNextReset() ?: nextResetAfter(ZonedDateTime.now(ZONE)).toInstant().toEpochMilli()

    /**
     * Epoch millis of the most recent board reset, i.e. the start of the current week's
     * board. A board last updated before this is from a previous week.
     *
     * Derived by stepping a week back from the next reset *in the Paris zone* rather than
     * by subtracting seven days of milliseconds: across a daylight-saving change those two
     * differ by an hour, and the whole point of this value is to compare timestamps against
     * it.
     */
    fun lastResetMillis(): Long {
        val next = nextResetMillis()
        return Instant.ofEpochMilli(next).atZone(ZONE).minusWeeks(1).toInstant().toEpochMilli()
    }

    /** Milliseconds until the next reset, never negative. */
    fun remainingMillis(): Long = (nextResetMillis() - System.currentTimeMillis()).coerceAtLeast(0L)

    /**
     * The countdown as shown on the HUD: `6d 23h`, `4h07m`, `12m`.
     *
     * The hour and minute units read the same in both languages, as in [RaidTimerOverlay];
     * only the day form is translated, and it goes through [Translations] as a format
     * string so the French version keeps its own spacing.
     */
    fun remainingLabel(): String {
        val seconds = remainingMillis() / 1000
        val days = seconds / 86_400
        val hours = (seconds % 86_400) / 3_600
        val minutes = (seconds % 3_600) / 60
        return when {
            days > 0 -> Translations.tr("%dd %dh").format(days, hours)
            hours > 0 -> "%dh%02dm".format(hours, minutes)
            else -> "%dm".format(minutes)
        }
    }

    /**
     * Every shape [remainingLabel] can take, at its widest, so a panel whose width is
     * cached can reserve room for it. The real label changes every minute; measuring the
     * live value would mean rebuilding the layout on a timer, so the reserve is taken from
     * the bound instead. The cadence is weekly, which caps the day form at six days.
     */
    fun labelSamples(): List<String> = listOf(
        Translations.tr("%dd %dh").format(6, 23),
        "23h59m"
    )
}
