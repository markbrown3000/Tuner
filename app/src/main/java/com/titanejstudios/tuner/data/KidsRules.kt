package com.titanejstudios.tuner.data

import java.util.Calendar

/** Bedtime and "time nearly up" rules for kids profiles. Pure logic, so it can be unit tested. */
object KidsRules {
    /** TV is allowed again from this time in the morning. */
    const val MORNING_MINUTES = 6 * 60

    /** Choices offered in the profile editor (minutes after midnight; -1 = none). */
    val bedtimeChoices = listOf(-1, 18 * 60, 18 * 60 + 30, 19 * 60, 19 * 60 + 30, 20 * 60, 20 * 60 + 30, 21 * 60)

    fun minutesOfDay(t: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    /** True between bedtime and 6am. */
    fun isBedtime(bedtimeMinutes: Int, minuteOfDay: Int): Boolean {
        if (bedtimeMinutes < 0) return false
        return if (bedtimeMinutes > MORNING_MINUTES) {
            minuteOfDay >= bedtimeMinutes || minuteOfDay < MORNING_MINUTES
        } else {
            // A bedtime after midnight (not offered, but handle it): between it and 6am.
            minuteOfDay in bedtimeMinutes until MORNING_MINUTES
        }
    }

    /** Minutes until bedtime starts (null if there's no bedtime or it's already bedtime). */
    fun minutesToBedtime(bedtimeMinutes: Int, minuteOfDay: Int): Int? {
        if (bedtimeMinutes < 0 || isBedtime(bedtimeMinutes, minuteOfDay)) return null
        val d = bedtimeMinutes - minuteOfDay
        return if (d >= 0) d else d + 24 * 60
    }

    fun label(bedtimeMinutes: Int): String =
        if (bedtimeMinutes < 0) "No bedtime" else String.format(java.util.Locale.UK, "%d:%02d", bedtimeMinutes / 60, bedtimeMinutes % 60)

    /**
     * Which warning (if any) to show now. [secondsLeft] is today's remaining time (Long.MAX_VALUE
     * when there's no limit). Returns minutes for a "5 minutes left" style warning, at 5 and 1.
     */
    fun warningMinutes(secondsLeft: Long, toBedtime: Int?): Int? {
        val byLimit = if (secondsLeft == Long.MAX_VALUE) null else ((secondsLeft + 59) / 60).toInt()
        val left = listOfNotNull(byLimit, toBedtime).minOrNull() ?: return null
        return when (left) {
            in 4..5 -> 5
            in 1..1 -> 1
            else -> null
        }
    }
}
