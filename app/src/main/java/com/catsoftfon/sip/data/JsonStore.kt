package com.catsoftfon.sip.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.catsoftfon.sip.model.AppSettings
import com.catsoftfon.sip.model.CallHistoryEntry
import com.catsoftfon.sip.model.Contact
import java.io.File
import java.util.concurrent.locks.ReentrantLock

/**
 * Plain local JSON persistence, mirroring the Windows client's JsonStore (which keeps
 * contacts.json / history.json / settings.json under %AppData%\CatSoftFon). Here the
 * equivalent is context.filesDir ("/data/data/com.catsoftfon.sip/files/") - private to
 * the app, no extra permission needed, survives app updates, wiped on uninstall.
 *
 * [lineIndex] (0-based, one of up to SipManager.LINE_COUNT lines) picks which account slot's
 * files this instance reads/writes - contacts and history are kept separate per line (each
 * line is its own SIP account, so its own address book), same as Services/ContactService.cs /
 * Services/CallHistoryService.cs on the Windows client. Slot 0 keeps the original unsuffixed
 * filenames for upgrade compatibility with installs from before multi-line support existed.
 */
class JsonStore(context: Context, lineIndex: Int = 0) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val dir: File = context.filesDir
    private val lock = ReentrantLock()

    private val suffix = if (lineIndex <= 0) "" else (lineIndex + 1).toString()
    private val contactsFile = File(dir, "contacts$suffix.json")
    private val historyFile = File(dir, "history$suffix.json")
    private val settingsFile = File(dir, "settings$suffix.json")

    // ---- Contacts ----

    fun loadContacts(): MutableList<Contact> = lock.withLockCompat {
        readList(contactsFile, object : TypeToken<MutableList<Contact>>() {}.type) ?: mutableListOf()
    }

    fun saveContacts(contacts: List<Contact>) = lock.withLockCompat {
        write(contactsFile, contacts)
    }

    // ---- History ----

    private val maxHistoryEntries = 500

    fun loadHistory(): MutableList<CallHistoryEntry> = lock.withLockCompat {
        readList(historyFile, object : TypeToken<MutableList<CallHistoryEntry>>() {}.type) ?: mutableListOf()
    }

    fun saveHistory(history: List<CallHistoryEntry>) = lock.withLockCompat {
        val trimmed = if (history.size > maxHistoryEntries) {
            history.sortedByDescending { it.timestampMillis }.take(maxHistoryEntries)
        } else {
            history
        }
        write(historyFile, trimmed)
    }

    // ---- Settings ----

    fun loadSettings(): AppSettings = lock.withLockCompat {
        if (!settingsFile.exists()) return@withLockCompat AppSettings()
        try {
            gson.fromJson(settingsFile.readText(Charsets.UTF_8), AppSettings::class.java) ?: AppSettings()
        } catch (e: Exception) {
            AppSettings()
        }
    }

    fun saveSettings(settings: AppSettings) = lock.withLockCompat {
        write(settingsFile, settings)
    }

    // ---- Helpers ----

    private fun <T> readList(file: File, type: java.lang.reflect.Type): T? {
        if (!file.exists()) return null
        return try {
            gson.fromJson(file.readText(Charsets.UTF_8), type)
        } catch (e: Exception) {
            null
        }
    }

    private fun write(file: File, value: Any) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(gson.toJson(value), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            // renameTo can fail across some filesystems; fall back to a direct write.
            file.writeText(gson.toJson(value), Charsets.UTF_8)
            tmp.delete()
        }
    }

    private inline fun <T> ReentrantLock.withLockCompat(block: () -> T): T {
        lock()
        try {
            return block()
        } finally {
            unlock()
        }
    }
}
