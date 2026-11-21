package com.catsoftfon.sip.model

enum class CallDirection {
    INCOMING,
    OUTGOING,
    MISSED
}

data class CallHistoryEntry(
    var number: String = "",
    var displayName: String = "",
    var direction: CallDirection = CallDirection.OUTGOING,
    var timestampMillis: Long = System.currentTimeMillis(),
    var durationSeconds: Int = 0
)
