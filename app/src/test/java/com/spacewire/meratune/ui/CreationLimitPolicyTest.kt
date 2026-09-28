package com.spacewire.meratune.ui

import com.spacewire.meratune.analytics.CreationLimitType
import com.spacewire.meratune.data.GenerationQuota
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Locale

class CreationLimitPolicyTest {

    /** 2026-09-28 12:00 IST. */
    private val now = Instant.parse("2026-09-28T06:30:00Z").toEpochMilli()

    /** Next IST midnight (2026-09-29 00:00 IST). */
    private val nextMidnight = "2026-09-28T18:30:00.000Z"

    /** 00:00 IST on 1 October. */
    private val nextMonth = "2026-09-30T18:30:00.000Z"

    private fun trial(used: Int = 2, limit: Int = 2, resetsAt: String? = nextMidnight) = GenerationQuota(
        usedToday = used,
        dailyLimit = limit,
        plan = GenerationQuota.PLAN_TRIAL,
        period = GenerationQuota.PERIOD_DAY,
        used = used,
        limit = limit,
        resetsAt = resetsAt,
        memberMonthlyLimit = 50,
    )

    private fun member(used: Int = 50, limit: Int = 50, exceeded: String? = null) = GenerationQuota(
        usedToday = used,
        dailyLimit = limit,
        plan = GenerationQuota.PLAN_MEMBER,
        period = GenerationQuota.PERIOD_MONTH,
        used = used,
        limit = limit,
        resetsAt = nextMonth,
        memberMonthlyLimit = 50,
        exceeded = exceeded,
    )

    @Test
    fun `used up with a future reset is exhausted`() {
        assertTrue(CreationLimitPolicy.isExhausted(trial(), now))
        assertTrue(CreationLimitPolicy.isExhausted(trial(used = 3), now))
        assertTrue(CreationLimitPolicy.isExhausted(member(), now))
    }

    @Test
    fun `room left is not exhausted`() {
        assertFalse(CreationLimitPolicy.isExhausted(trial(used = 1), now))
        assertFalse(CreationLimitPolicy.isExhausted(member(used = 49), now))
    }

    @Test
    fun `unknown quota lets the server decide`() {
        assertFalse(CreationLimitPolicy.isExhausted(null, now))
        assertFalse(CreationLimitPolicy.isExhausted(GenerationQuota(), now))
        assertFalse(CreationLimitPolicy.isExhausted(GenerationQuota(used = 2, resetsAt = nextMidnight), now))
        assertFalse(CreationLimitPolicy.isExhausted(GenerationQuota(limit = 2, resetsAt = nextMidnight), now))
        // A zero limit is not a plan the app can reason about.
        assertFalse(CreationLimitPolicy.isExhausted(trial(used = 0, limit = 0), now))
    }

    @Test
    fun `legacy fields count when the plan fields are missing`() {
        val legacy = GenerationQuota(usedToday = 5, dailyLimit = 5, resetsAt = nextMidnight)
        assertTrue(CreationLimitPolicy.isExhausted(legacy, now))
        assertEquals(CreationLimitPolicy.Variant.DAILY, CreationLimitPolicy.variant(legacy))
        assertEquals(CreationLimitType.DAILY, CreationLimitPolicy.limitType(legacy))
        // A server before plans sends no resets_at: never blocked on the app side.
        assertFalse(CreationLimitPolicy.isExhausted(GenerationQuota(usedToday = 5, dailyLimit = 5), now))
    }

    @Test
    fun `plan fields win over the legacy ones`() {
        val quota = GenerationQuota(usedToday = 0, dailyLimit = 10, used = 2, limit = 2, resetsAt = nextMidnight)
        assertEquals(2, quota.usedCount)
        assertEquals(2, quota.limitCount)
        assertTrue(CreationLimitPolicy.isExhausted(quota, now))
    }

    @Test
    fun `missing or garbage resets_at is not exhausted`() {
        assertFalse(CreationLimitPolicy.isExhausted(trial(resetsAt = null), now))
        assertFalse(CreationLimitPolicy.isExhausted(trial(resetsAt = ""), now))
        assertFalse(CreationLimitPolicy.isExhausted(trial(resetsAt = "tomorrow"), now))
        assertFalse(CreationLimitPolicy.isExhausted(trial(resetsAt = "2026-09-29"), now))
        assertNull(CreationLimitPolicy.resetsAtMs(trial(resetsAt = "garbage")))
        assertNull(CreationLimitPolicy.resetsAtMs(null))
    }

    @Test
    fun `a reset already passed is not exhausted`() {
        val stale = trial(resetsAt = "2026-09-27T18:30:00.000Z")
        assertFalse(CreationLimitPolicy.isExhausted(stale, now))
        val atNow = trial(resetsAt = Instant.ofEpochMilli(now).toString())
        assertFalse(CreationLimitPolicy.isExhausted(atNow, now))
    }

    @Test
    fun `resetsAtMs parses the ISO instant`() {
        val expected = Instant.parse(nextMidnight).toEpochMilli()
        assertEquals(expected, CreationLimitPolicy.resetsAtMs(trial()))
        assertEquals(expected, CreationLimitPolicy.resetsAtMs(trial(resetsAt = " $nextMidnight ")))
    }

    @Test
    fun `variant follows the plan, then the period`() {
        assertEquals(CreationLimitPolicy.Variant.TRIAL, CreationLimitPolicy.variant(trial()))
        assertEquals(CreationLimitPolicy.Variant.MEMBER, CreationLimitPolicy.variant(member()))
        assertEquals(
            CreationLimitPolicy.Variant.MEMBER,
            CreationLimitPolicy.variant(GenerationQuota(period = GenerationQuota.PERIOD_MONTH)),
        )
        val defaultPlan = GenerationQuota(plan = GenerationQuota.PLAN_DEFAULT, period = GenerationQuota.PERIOD_DAY)
        assertEquals(CreationLimitPolicy.Variant.DAILY, CreationLimitPolicy.variant(defaultPlan))
        assertEquals(CreationLimitPolicy.Variant.TRIAL, CreationLimitPolicy.variant(GenerationQuota(plan = " Trial ")))
        assertEquals(CreationLimitPolicy.Variant.DAILY, CreationLimitPolicy.variant(null))
    }

    @Test
    fun `limit type is monthly for the member month unless the attempt cap was hit`() {
        assertEquals(CreationLimitType.MONTHLY, CreationLimitPolicy.limitType(member()))
        val planHit = member(exceeded = GenerationQuota.EXCEEDED_PLAN)
        val attemptsHit = member(exceeded = GenerationQuota.EXCEEDED_ATTEMPTS)
        assertEquals(CreationLimitType.MONTHLY, CreationLimitPolicy.limitType(planHit))
        assertEquals(CreationLimitType.DAILY, CreationLimitPolicy.limitType(attemptsHit))
        assertEquals(CreationLimitType.DAILY, CreationLimitPolicy.limitType(trial()))
        assertEquals(CreationLimitType.DAILY, CreationLimitPolicy.limitType(null))
    }

    @Test
    fun `reset date is the IST calendar day in the given locale`() {
        assertEquals("1 October", CreationLimitPolicy.formatResetDate(member(), Locale.ENGLISH))
        // 18:30 UTC is already the next day in India.
        assertEquals("29 September", CreationLimitPolicy.formatResetDate(trial(), Locale.ENGLISH))
        assertNull(CreationLimitPolicy.formatResetDate(trial(resetsAt = "soon"), Locale.ENGLISH))
        assertNull(CreationLimitPolicy.formatResetDate(null, Locale.ENGLISH))
    }
}
