package com.example.voicelauncher

/** No Amharic TTS exists on Android, so [clip] is a human recording in res/raw and [amharic] is its script. */
data class Announcement(val clip: String, val amharic: String, val english: String)

object Announcements {
    val PENDING = Announcement(
        "am_pending",
        "ገና አልተመዘገቡም። እባክዎ ትንሽ ይጠብቁ።",
        "You are not registered yet. Please wait."
    )
    val BUSY = Announcement(
        "am_busy",
        "አሁን በሌላ ስልክ ላይ ክፍት ነው። እባክዎ ትንሽ ቆይተው ይሞክሩ።",
        "It is open on another phone right now. Please wait and try again."
    )
    val PAUSED = Announcement(
        "am_paused",
        "አገልግሎቱ ለጊዜው ቆሟል። እባክዎ ድጋፍን ያግኙ።",
        "The service is paused for now. Please contact support."
    )
    val REVOKED = Announcement(
        "am_revoked",
        "ይህን መተግበሪያ መጠቀም አይችሉም። እባክዎ ድጋፍን ያግኙ።",
        "You cannot use this app. Please contact support."
    )
    val IDENTITY_PROBLEM = Announcement(
        "am_identity_problem",
        "የስልክ መታወቂያ ችግር አጋጥሟል። እባክዎ ድጋፍን ያግኙ።",
        "There is a problem with this phone's identity. Please contact support."
    )
    val UNAVAILABLE = Announcement(
        "am_unavailable",
        "አገልግሎቱ አሁን አይገኝም። እባክዎ ትንሽ ቆይተው ይሞክሩ።",
        "The service is not available right now. Please wait and try again."
    )
    val CONNECTION_LOST = Announcement(
        "am_connection_lost",
        "ግንኙነቱ ተቋርጧል። እባክዎ እንደገና ይሞክሩ።",
        "The connection was lost. Please try again."
    )
    val NOTHING_PENDING = Announcement(
        "am_nothing_pending",
        "ምንም የሚጠበቅ እርምጃ የለም።",
        "There is no action waiting."
    )
    val CANCELLED = Announcement(
        "am_cancelled",
        "ተሰርዟል።",
        "Cancelled."
    )
    val CALL_PLACED = Announcement(
        "am_call_placed",
        "ጥሪው ተደርጓል።",
        "The call was placed."
    )
    val CALL_NOT_PLACED = Announcement(
        "am_call_not_placed",
        "ጥሪው አልተደረገም። እባክዎ እንደገና ይሞክሩ።",
        "The call was not placed. Please try again."
    )
    val SMS_SENT = Announcement(
        "am_sms_sent",
        "መልእክቱ ተልኳል።",
        "The message was sent."
    )
    val SMS_FAILED = Announcement(
        "am_sms_failed",
        "መልእክቱ አልተላከም። እባክዎ እንደገና ይሞክሩ።",
        "The message was not sent. Please try again."
    )
    val SMS_UNCONFIRMED = Announcement(
        "am_sms_unconfirmed",
        "መልእክቱ መላኩ አልተረጋገጠም። እባክዎ ያረጋግጡ።",
        "Sending was not confirmed. Please verify."
    )

    val ALL = listOf(
        PENDING, BUSY, PAUSED, REVOKED, IDENTITY_PROBLEM, UNAVAILABLE,
        CONNECTION_LOST, NOTHING_PENDING, CANCELLED, CALL_PLACED, CALL_NOT_PLACED,
        SMS_SENT, SMS_FAILED, SMS_UNCONFIRMED
    )
}
