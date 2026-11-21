package com.catsoftfon.sip.data

import android.content.Context
import androidx.lifecycle.MutableLiveData
import com.catsoftfon.sip.model.CallDirection
import com.catsoftfon.sip.model.CallHistoryEntry
import com.catsoftfon.sip.model.Contact
import com.catsoftfon.sip.model.ContactSource
import com.catsoftfon.sip.model.PresenceState

/**
 * In-memory contact/history state backed by JsonStore, mirroring Services/ContactService.cs
 * and Services/HistoryService.cs from the Windows client. Single source of truth for the UI;
 * every mutation re-saves to disk immediately (same "no explicit save button" behaviour as
 * the Windows app).
 *
 * One instance per line (see JsonStore's doc comment) - [lineIndex] is just forwarded to pick
 * that line's own contacts/history files.
 */
class ContactRepository(context: Context, lineIndex: Int = 0) {

    private val store = JsonStore(context, lineIndex)

    val contacts = MutableLiveData<List<Contact>>(store.loadContacts())
    val history = MutableLiveData<List<CallHistoryEntry>>(store.loadHistory())

    @Synchronized
    fun addOrUpdateContact(contact: Contact) {
        val list = contacts.value.orEmpty().toMutableList()
        val idx = list.indexOfFirst { it.id == contact.id }
        if (idx >= 0) list[idx] = contact else list.add(contact)
        contacts.postValue(list)
        store.saveContacts(list)
    }

    @Synchronized
    fun deleteContact(contactId: String) {
        val list = contacts.value.orEmpty().filterNot { it.id == contactId }
        contacts.postValue(list)
        store.saveContacts(list)
    }

    @Synchronized
    fun updatePresence(number: String, state: PresenceState) {
        val list = contacts.value.orEmpty()
        var changed = false
        for (c in list) {
            if (numbersMatch(c.number, number) && c.presence != state) {
                c.presence = state
                changed = true
            }
        }
        if (changed) contacts.postValue(list)
    }

    /**
     * Merges a freshly-fetched PBX directory (see DirectoryImportService) into the local
     * list: directory-sourced contacts are replaced wholesale (so renames/removals on the
     * PBX side are reflected), local contacts the user added by hand are always kept, and a
     * directory entry never overwrites a local contact that already has the same number.
     * Mirrors Services/ContactService.cs's MergeFromDirectory.
     */
    @Synchronized
    fun mergeDirectoryContacts(directoryContacts: List<Contact>) {
        val current = contacts.value.orEmpty()
        val local = current.filter { it.source == ContactSource.LOCAL }
        val localNumbers = local.map { normalizeNumber(it.number) }.toSet()

        val merged = mutableListOf<Contact>()
        merged.addAll(local)
        for (d in directoryContacts) {
            if (normalizeNumber(d.number) in localNumbers) continue
            d.source = ContactSource.DIRECTORY
            merged.add(d)
        }
        contacts.postValue(merged)
        store.saveContacts(merged)
    }

    @Synchronized
    fun addHistoryEntry(entry: CallHistoryEntry) {
        val list = (listOf(entry) + history.value.orEmpty()).toMutableList()
        history.postValue(list)
        store.saveHistory(list)
    }

    @Synchronized
    fun clearHistory() {
        history.postValue(emptyList())
        store.saveHistory(emptyList())
    }

    fun displayNameFor(number: String): String {
        val contact = contacts.value.orEmpty().firstOrNull { numbersMatch(it.number, number) }
        return contact?.name?.takeIf { it.isNotBlank() } ?: number
    }

    companion object {
        fun normalizeNumber(number: String): String {
            // Strip a leading "sip:" / trailing "@domain" and any non-digit/plus characters,
            // so "sip:101@10.0.0.5:5060" and "101" are recognised as the same extension - the
            // same loose matching used by the Windows app's BLF/presence lookups.
            var n = number.trim()
            if (n.startsWith("sip:", ignoreCase = true)) n = n.substring(4)
            if (n.startsWith("sips:", ignoreCase = true)) n = n.substring(5)
            val at = n.indexOf('@')
            if (at >= 0) n = n.substring(0, at)
            return n.filter { it.isDigit() || it == '+' }
        }

        fun numbersMatch(a: String, b: String): Boolean {
            val na = normalizeNumber(a)
            val nb = normalizeNumber(b)
            return na.isNotEmpty() && na == nb
        }

        fun directionLabel(direction: CallDirection): String = when (direction) {
            CallDirection.INCOMING -> "incoming"
            CallDirection.OUTGOING -> "outgoing"
            CallDirection.MISSED -> "missed"
        }
    }
}
