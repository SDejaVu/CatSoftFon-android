package com.catsoftfon.sip.presence

import com.catsoftfon.sip.model.PresenceState

/**
 * Merges the two independent presence signals this line now has for a given number into the
 * one [PresenceState] the UI actually shows:
 *
 * - [onDialogState] - from [PresenceManager]'s RFC 4235 "dialog" SUBSCRIBE: tells Idle/Busy/
 *   Ringing, but (see that class's doc comment) cannot tell a genuinely unregistered/powered-
 *   off extension apart from an idle one - both look identical over that event package.
 * - [onRegistrationState] - from the optional [AriRegistrationClient]: tells real SIP
 *   registration state (online/offline) directly via ARI, with none of that ambiguity, but
 *   says nothing about whether a call is in progress.
 *
 * Rule: a number ARI has told us is offline is shown Offline regardless of what the dialog
 * subscription last reported (a dead subscription's last cached Idle/Busy is stale the moment
 * the phone drops off the PBX). Otherwise (online, or ARI not configured/not yet polled for
 * this number) the dialog-derived state wins, same as before ARI existed - falling back to
 * [PresenceState.UNKNOWN] (shown the same grey as Offline - see ContactsAdapter) only until the
 * first NOTIFY arrives.
 *
 * One instance per line, feeding [com.catsoftfon.sip.data.ContactRepository.updatePresence] -
 * see CatSoftFonApplication's wiring.
 */
class PresenceCombiner(private val onFinalStateChanged: (number: String, state: PresenceState) -> Unit) {

    private val dialogStates = HashMap<String, PresenceState>()
    private val registrationOnline = HashMap<String, Boolean>()

    @Synchronized
    fun onDialogState(number: String, state: PresenceState) {
        dialogStates[number] = state
        emit(number)
    }

    @Synchronized
    fun onRegistrationState(number: String, online: Boolean) {
        registrationOnline[number] = online
        emit(number)
    }

    private fun emit(number: String) {
        val online = registrationOnline[number]
        val final = if (online == false) {
            PresenceState.OFFLINE
        } else {
            dialogStates[number] ?: PresenceState.UNKNOWN
        }
        onFinalStateChanged(number, final)
    }
}
