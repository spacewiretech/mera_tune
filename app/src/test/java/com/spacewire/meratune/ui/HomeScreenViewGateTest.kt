package com.spacewire.meratune.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeScreenViewGateTest {

    @Test
    fun freshCreateThenNewIntentBeforeResumeIsNotTrackedTwice() {
        val gate = HomeScreenViewGate()
        gate.onCreate(restored = false)
        assertFalse(gate.shouldTrackOnNewIntent())
    }

    @Test
    fun newIntentAfterResumeIsTracked() {
        val gate = HomeScreenViewGate()
        gate.onCreate(restored = false)
        gate.onResume()
        assertTrue(gate.shouldTrackOnNewIntent())
        // Every later CLEAR_TOP re-entry of the same instance keeps tracking.
        assertTrue(gate.shouldTrackOnNewIntent())
    }

    @Test
    fun restoredHomeIsTrackedOnNewIntentEvenBeforeResume() {
        val gate = HomeScreenViewGate()
        gate.onCreate(restored = true)
        assertTrue(gate.shouldTrackOnNewIntent())
    }

    @Test
    fun recreatedInstanceResetsTheResumeFlag() {
        val gate = HomeScreenViewGate()
        gate.onCreate(restored = false)
        gate.onResume()
        gate.onCreate(restored = false)
        assertFalse(gate.shouldTrackOnNewIntent())
    }
}
