package com.spacewire.meratune.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyCapPolicyTest {

    private val today = "2026-09-24"
    private val userId = 42L

    @Test
    fun firstAcquireStartsCounter() {
        val decision = DailyCapPolicy.acquire(null, today, userId, limit = 10)
        assertTrue(decision.allowed)
        assertEquals(DailyCapPolicy.State(today, userId, 1), decision.state)
    }

    @Test
    fun allowsExactlyLimitPerDay() {
        var state: DailyCapPolicy.State? = null
        repeat(10) { index ->
            val decision = DailyCapPolicy.acquire(state, today, userId, limit = 10)
            assertTrue("acquire #${index + 1}", decision.allowed)
            state = decision.state
        }
        val denied = DailyCapPolicy.acquire(state, today, userId, limit = 10)
        assertFalse(denied.allowed)
        assertEquals(10, denied.state.count)
    }

    @Test
    fun deniedAcquireDoesNotGrowCount() {
        val full = DailyCapPolicy.State(today, userId, 10)
        repeat(3) {
            val decision = DailyCapPolicy.acquire(full, today, userId, limit = 10)
            assertFalse(decision.allowed)
            assertEquals(full, decision.state)
        }
    }

    @Test
    fun newDayResetsCounter() {
        val yesterday = DailyCapPolicy.State("2026-09-23", userId, 10)
        val decision = DailyCapPolicy.acquire(yesterday, today, userId, limit = 10)
        assertTrue(decision.allowed)
        assertEquals(DailyCapPolicy.State(today, userId, 1), decision.state)
    }

    @Test
    fun otherUserResetsCounter() {
        val otherUser = DailyCapPolicy.State(today, 7L, 10)
        val decision = DailyCapPolicy.acquire(otherUser, today, userId, limit = 10)
        assertTrue(decision.allowed)
        assertEquals(DailyCapPolicy.State(today, userId, 1), decision.state)
    }

    @Test
    fun zeroLimitNeverAllows() {
        assertFalse(DailyCapPolicy.acquire(null, today, userId, limit = 0).allowed)
    }

    @Test
    fun countAboveLimitStaysDenied() {
        val over = DailyCapPolicy.State(today, userId, 25)
        assertFalse(DailyCapPolicy.acquire(over, today, userId, limit = 10).allowed)
    }
}
