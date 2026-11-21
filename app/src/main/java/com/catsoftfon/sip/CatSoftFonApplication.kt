package com.catsoftfon.sip

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.getSystemService
import com.catsoftfon.sip.data.ContactRepository
import com.catsoftfon.sip.data.DirectoryImportService
import com.catsoftfon.sip.data.JsonStore
import com.catsoftfon.sip.model.CallDirection
import com.catsoftfon.sip.model.CallHistoryEntry
import com.catsoftfon.sip.presence.AriRegistrationClient
import com.catsoftfon.sip.presence.PresenceCombiner
import com.catsoftfon.sip.presence.PresenceManager
import com.catsoftfon.sip.sip.SipManager
import com.catsoftfon.sip.sip.CallForegroundService
import org.linphone.core.Call
import org.linphone.core.Factory
import org.linphone.core.LogCollectionState
import kotlin.concurrent.thread

/**
 * Owns the app-wide singletons - now one per line (up to [SipManager.LINE_COUNT]) for
 * everything that's genuinely separate per SIP account (contacts, history, presence, PBX
 * directory sync), plus the single shared [sipManager] (one Core handles all 3 lines - see
 * its class doc comment for why).
 */
class CatSoftFonApplication : Application() {

    lateinit var sipManager: SipManager
    lateinit var contactRepositories: Array<ContactRepository>
    lateinit var presenceManagers: Array<PresenceManager>
    lateinit var ariClients: Array<AriRegistrationClient>
    private lateinit var presenceCombiners: Array<PresenceCombiner>
    private lateinit var jsonStores: Array<JsonStore>
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Must happen before the Core thread starts - same ordering as the official app's
        // LinphoneApplication.onCreate().
        Factory.instance().setLogCollectionPath(filesDir.absolutePath)
        Factory.instance().enableLogCollection(LogCollectionState.Enabled)

        jsonStores = Array(SipManager.LINE_COUNT) { i -> JsonStore(this, i) }
        contactRepositories = Array(SipManager.LINE_COUNT) { i -> ContactRepository(this, i) }

        sipManager = SipManager(this)
        sipManager.start()

        presenceManagers = Array(SipManager.LINE_COUNT) { i -> PresenceManager(this, sipManager, i) }
        ariClients = Array(SipManager.LINE_COUNT) { i -> AriRegistrationClient(i) }
        presenceCombiners = Array(SipManager.LINE_COUNT) { i ->
            PresenceCombiner { number, state -> contactRepositories[i].updatePresence(number, state) }
        }
        for (i in 0 until SipManager.LINE_COUNT) {
            val lineIndex = i
            // Two independent signals feed the same combiner - see PresenceCombiner's doc
            // comment for how it reconciles them: PresenceManager knows Idle/Busy/Ringing from
            // the SIP "dialog" subscription but can't tell an unregistered extension from an
            // idle one; AriRegistrationClient (optional, off until configured in Settings)
            // knows real online/offline from ARI but nothing about calls in progress.
            presenceManagers[lineIndex].presenceChangedListener = { number, state ->
                presenceCombiners[lineIndex].onDialogState(number, state)
            }
            ariClients[lineIndex].registrationChangedListener = { number, online ->
                presenceCombiners[lineIndex].onRegistrationState(number, online)
            }
            contactRepositories[lineIndex].contacts.observeForever {
                val numbers = it.filter { c -> c.subscribeToPresence }.map { c -> c.number }
                presenceManagers[lineIndex].watch(numbers)
            }
        }

        sipManager.callEndedListener = { lineIndex, call, missed, durationSeconds ->
            recordHistory(lineIndex, call, missed, durationSeconds)
            CallForegroundService.stop(this, lineIndex)
        }

        sipManager.incomingRingListener = { lineIndex, info ->
            CallForegroundService.notifyIncomingCall(
                this, lineIndex, info.remoteDisplayName.ifBlank { info.remoteNumber }, info.remoteNumber
            )
        }

        for (i in 0 until SipManager.LINE_COUNT) {
            val lineIndex = i
            sipManager.callState[lineIndex].observeForever { state ->
                if (state == Call.State.Connected || state == Call.State.StreamsRunning) {
                    val call = sipManager.activeCall[lineIndex].value
                    val label = call?.remoteAddress?.let { it.displayName?.takeIf(String::isNotBlank) ?: it.username } ?: ""
                    CallForegroundService.notifyOngoingCall(this, lineIndex, label.orEmpty())
                }
            }
        }

        createNotificationChannels()

        // Register with the PBX right away for every line that was already configured.
        var anyLineConfigured = false
        for (i in 0 until SipManager.LINE_COUNT) {
            val settings = jsonStores[i].loadSettings()
            if (settings.sipServer.isNotBlank() && settings.username.isNotBlank()) {
                sipManager.applyAccountSettings(i, settings)
                anyLineConfigured = true
            }
            ariClients[i].updateConfig(settings)
        }
        if (anyLineConfigured) {
            // The actual fix for "closing the app means it can't be reached any more" - see
            // CallForegroundService's class doc comment. Without this, the process (and the
            // Linphone Core's registration thread with it) only stayed alive during an actual
            // call, so swiping the app away from Recents while merely registered-and-idle let
            // Android kill it outright.
            CallForegroundService.startKeepAlive(this)
        }

        scheduleDirectorySyncCheck()
    }

    /**
     * Best-effort periodic PBX directory sync, now per line: re-checks every minute on the
     * main thread whether it's time to sync each line's own directory (per that line's
     * AppSettings.directorySyncIntervalMinutes) and, if so, fetches on a background thread.
     * Only runs while the app process is alive - same best-effort spirit as the Windows
     * client's in-process timer.
     */
    private val lastDirectorySyncMillis = LongArray(SipManager.LINE_COUNT)
    private fun scheduleDirectorySyncCheck() {
        mainHandler.postDelayed({
            for (i in 0 until SipManager.LINE_COUNT) {
                val lineIndex = i
                val settings = jsonStores[lineIndex].loadSettings()
                if (settings.directorySyncEnabled && settings.directorySyncUrl.isNotBlank()) {
                    val intervalMillis = settings.directorySyncIntervalMinutes.coerceAtLeast(5) * 60_000L
                    val now = System.currentTimeMillis()
                    if (now - lastDirectorySyncMillis[lineIndex] >= intervalMillis) {
                        lastDirectorySyncMillis[lineIndex] = now
                        thread {
                            try {
                                val contacts = DirectoryImportService.fetchAndParse(settings.directorySyncUrl)
                                contactRepositories[lineIndex].mergeDirectoryContacts(contacts)
                            } catch (e: Exception) {
                                // Best-effort background sync - silently retry next cycle.
                            }
                        }
                    }
                }
            }
            scheduleDirectorySyncCheck()
        }, 60_000L)
    }

    private fun recordHistory(lineIndex: Int, call: Call, missed: Boolean, durationSeconds: Int) {
        val remote = call.remoteAddress
        val number = remote.username ?: remote.asStringUriOnly()
        val direction = when {
            missed -> CallDirection.MISSED
            call.dir == Call.Dir.Incoming -> CallDirection.INCOMING
            else -> CallDirection.OUTGOING
        }
        contactRepositories[lineIndex].addHistoryEntry(
            CallHistoryEntry(
                number = number,
                displayName = contactRepositories[lineIndex].displayNameFor(number),
                direction = direction,
                timestampMillis = System.currentTimeMillis(),
                durationSeconds = durationSeconds
            )
        )
    }

    private fun createNotificationChannels() {
        val manager = getSystemService<NotificationManager>() ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CallForegroundService.CHANNEL_INCOMING_CALL,
                getString(R.string.notification_channel_incoming_call),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CallForegroundService.CHANNEL_ONGOING_CALL,
                getString(R.string.notification_channel_ongoing_call),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    companion object {
        lateinit var instance: CatSoftFonApplication
            private set
    }
}

fun Context.catApp(): CatSoftFonApplication = applicationContext as CatSoftFonApplication
