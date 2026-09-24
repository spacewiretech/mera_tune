package com.spacewire.meratune.calltheme

/** Where a Set tap came from, for `ringtone_set_started`. Captured at tap time by the caller. */
data class SetEntryContext(
    /** 1-based position in the list the tune was tapped from. */
    val rank: Int? = null,
    val wasPreviewed: Boolean? = null,
)
