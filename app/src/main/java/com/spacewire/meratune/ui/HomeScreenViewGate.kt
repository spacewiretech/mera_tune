package com.spacewire.meratune.ui

/**
 * Decides whether `Home.onNewIntent` should track `screen_viewed(home)` (plan P7, review #2).
 *
 * A Home record that was never launched (for example the lazily created bottom of a
 * `startActivities` stack) receives `onCreate` and then `onNewIntent` before its first resume.
 * `AnalyticsLifecycleCallbacks` already tracked that `onCreate`, so the `onNewIntent` must not
 * track it again. Once Home has resumed, every later CLEAR_TOP re-entry tracks as before.
 */
class HomeScreenViewGate {

    private var trackedByLifecycle = false
    private var resumedSinceCreate = false

    /** [restored] = `savedInstanceState != null`; the lifecycle tracker only tracks fresh creates. */
    fun onCreate(restored: Boolean) {
        trackedByLifecycle = !restored
        resumedSinceCreate = false
    }

    fun onResume() {
        resumedSinceCreate = true
    }

    /** True when this `onNewIntent` is a real re-entry that nobody has tracked yet. */
    fun shouldTrackOnNewIntent(): Boolean = !trackedByLifecycle || resumedSinceCreate
}
