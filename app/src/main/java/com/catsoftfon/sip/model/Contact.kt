package com.catsoftfon.sip.model

import java.util.UUID

/** Mirrors Models/Contact.cs and Models/ContactSource.cs from the Windows client. */
enum class ContactSource {
    LOCAL,
    DIRECTORY
}

/** Mirrors Services/PresenceService.cs's PresenceState enum from the Windows client. */
enum class PresenceState {
    UNKNOWN,
    IDLE,
    RINGING,
    BUSY,
    OFFLINE
}

data class Contact(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var number: String = "",
    var subscribeToPresence: Boolean = true,
    var source: ContactSource = ContactSource.LOCAL,
    @Transient var presence: PresenceState = PresenceState.UNKNOWN
)
