package com.example.voicelauncher

object RelayClosePolicy {
    const val UNPAIRED = 4000
    const val REVOKED = 4001
    const val ACTIVE_ELSEWHERE = 4002
    const val PAUSED = 4003
    const val IDENTITY_MISMATCH = 4004
    const val UNAVAILABLE = 4005

    fun isRefusal(code: Int): Boolean = code in UNPAIRED..UNAVAILABLE

    // A lease expires and a host approves, so those refusals heal themselves; the rest need a
    // human, and reconnecting forever would bury the reason under noise.
    fun retryDelayMs(code: Int): Long? = when (code) {
        UNPAIRED -> 60_000L
        ACTIVE_ELSEWHERE -> 90_000L
        UNAVAILABLE -> 30_000L
        else -> null
    }

    fun isTerminal(code: Int): Boolean = isRefusal(code) && retryDelayMs(code) == null

    fun announcement(code: Int): Announcement = when (code) {
        UNPAIRED -> Announcements.PENDING
        REVOKED -> Announcements.REVOKED
        ACTIVE_ELSEWHERE -> Announcements.BUSY
        PAUSED -> Announcements.PAUSED
        IDENTITY_MISMATCH -> Announcements.IDENTITY_PROBLEM
        UNAVAILABLE -> Announcements.UNAVAILABLE
        else -> Announcements.CONNECTION_LOST
    }
}
