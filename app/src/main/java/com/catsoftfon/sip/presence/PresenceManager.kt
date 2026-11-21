package com.catsoftfon.sip.presence

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Xml
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import com.catsoftfon.sip.model.PresenceState
import com.catsoftfon.sip.sip.SipManager
import org.linphone.core.Content
import org.linphone.core.Core
import org.linphone.core.Event
import org.linphone.core.EventListenerStub
import org.linphone.core.Factory
import org.linphone.core.Reason
import org.linphone.core.SubscriptionState
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BLF (busy-lamp-field) presence for contacts: SUBSCRIBEs to the "dialog" event package
 * (RFC 4235) on each contact's extension and parses the dialog-info+xml NOTIFY bodies
 * Asterisk sends back - exactly the same subscription and parsing this app's Windows sibling
 * uses (Services/PresenceService.cs), so the two clients agree on what "online/busy/ringing"
 * means for the same PBX.
 *
 * An earlier version of this class used the Linphone SDK's higher-level Friend/FriendList API,
 * which only exposes the RFC 3856 SIP SIMPLE presence package (self-reported online/busy/dnd/
 * offline). That turned out to be the wrong event package for this PBX: Asterisk's dialplan
 * `hint`-based BLF - confirmed against real captured NOTIFY bodies on the Windows client - only
 * ever publishes the "dialog" package (RFC 4235), which the Friend API has no way to subscribe
 * to. So this now subscribes directly with Core.createSubscribe(address, "dialog", expires)
 * instead, the same generic event-subscription API the SDK itself uses internally for its own
 * non-presence event packages. The Core auto-refreshes an active generic subscription before
 * its expiry on its own (see `[sip]/refresh_generic_subscribe` in the SDK's default config) -
 * no manual resubscribe timer needed here for the common case, same as the Windows client's
 * SIPNotifierClient.
 *
 * Two bugs fixed here, both confirmed against the proven-working Windows implementation
 * (Services/PresenceService.cs):
 *
 * 1. CRITICAL - the previous version passed `event.addListener(object : EventListenerStub()
 *    {...})` straight in-line, with nothing on the Kotlin side holding a reference to that
 *    listener object afterwards (only the `Event` itself was kept, in [eventsByNumber]). The
 *    SDK's native side does not guarantee that registering a listener keeps the Kotlin/JVM
 *    object alive by itself - with nothing else referencing it, the garbage collector is free
 *    to collect that anonymous listener at any time, after which its NOTIFY callback silently
 *    stops firing forever (no exception, no error - the subscription itself stays alive, it
 *    just has no listener left to tell). That explains every symptom reported: contacts stuck
 *    permanently on whatever state their very first NOTIFY happened to report (usually idle/
 *    green, since Asterisk sends that immediately on a successful SUBSCRIBE), never updating
 *    to Busy/red for an actual call, and never resolving to Offline/grey either.
 *    [listenersByNumber] below keeps a strong reference to every listener for exactly as long
 *    as its subscription is active, so this can't happen again.
 *
 * 2. The SubscriptionFailed-equivalent path (state == Error) used to just report Offline once
 *    and leave that subscription dead forever. The Windows class's own doc comment explains why
 *    that's wrong: the underlying library does not retry a subscription after any failure on
 *    its own, and real captured logs showed a routine resubscribe occasionally coming back
 *    rejected (e.g. right after a call to/from that extension) even for a perfectly normal,
 *    reachable contact - so without an explicit retry, that contact would wrongly stay grey
 *    forever instead of recovering. [scheduleResubscribe] below re-subscribes with a fresh
 *    Event (a new dialog, so a stale-dialog-shaped rejection like 481 won't just repeat
 *    immediately) after a short delay, for anything except the two genuinely permanent
 *    failures (Forbidden, BadEvent) - identical logic to PresenceService.cs's
 *    SubscriptionFailed handler.
 *
 * One instance per line/account (see SipManager's class doc comment for why all 3 lines share
 * a single Core/HandlerThread) - [lineIndex] picks which line's own identity domain this
 * instance's SUBSCRIBEs are built against, and each instance owns its own independent
 * eventsByNumber map so the 3 lines' presence subscriptions never interfere with each other.
 *
 * TEMPORARY diagnostic, same as the Windows client's own PresenceService.cs went through
 * (see its class doc comment: "two rounds of guessing the dialog-info shape... got it wrong"):
 * every raw NOTIFY body and every subscription failure is appended to
 * filesDir/presence_debug.log (readable from Settings > "Показать диагностику presence" or via
 * `adb exec-out run-as com.catsoftfon.sip cat files/presence_debug.log`), so the next round of
 * fixes can be based on what this PBX actually sends rather than another guess. Safe to strip
 * out (the two log* calls below, this class doc paragraph, and the Settings button) once
 * presence is confirmed solid.
 */
class PresenceManager(private val context: Context, private val sipManager: SipManager, private val lineIndex: Int) {

    companion object {
        private const val EVENT_NAME = "dialog"
        private const val SUBSCRIBE_EXPIRY_SECONDS = 300
        private const val RESUBSCRIBE_AFTER_FAILURE_DELAY_MS = 10_000L
    }

    private val eventsByNumber = HashMap<String, Event>()

    /** Keeps every active subscription's listener alive for as long as its Event is in
     * [eventsByNumber] - see the class doc comment, point 1. Without this, NOTIFY callbacks
     * can silently stop firing once the garbage collector decides to reclaim the (otherwise
     * unreferenced) anonymous listener object. */
    private val listenersByNumber = HashMap<String, EventListenerStub>()

    /** The full set [watch] was last asked to track - a retry after a failure (see
     * [scheduleResubscribe]) checks this before resubscribing, in case that contact was removed
     * or presence was turned off for it in the meantime. */
    private var wantedNumbers: Set<String> = emptySet()

    private val mainHandler = Handler(Looper.getMainLooper())

    var presenceChangedListener: ((number: String, state: PresenceState) -> Unit)? = null

    /** Replaces the whole watched-contacts set with the given SIP numbers. */
    @AnyThread
    fun watch(numbers: List<String>) {
        wantedNumbers = numbers.toSet()
        sipManager.postOnCoreThread { core ->
            val wanted = wantedNumbers

            val toRemove = eventsByNumber.keys.filterNot { it in wanted }
            for (number in toRemove) {
                terminateAndForget(number)
            }

            val domain = sipManager.domainForLine(lineIndex)
            for (number in wanted) {
                if (eventsByNumber.containsKey(number)) continue
                subscribe(core, number, domain)
            }
        }
    }

    @WorkerThread
    private fun subscribe(core: Core, number: String, domain: String?) {
        try {
            val uri = if (number.contains("@")) "sip:$number" else "sip:$number@${domain ?: ""}"
            val address = Factory.instance().createAddress(uri) ?: return
            val event = core.createSubscribe(address, EVENT_NAME, SUBSCRIBE_EXPIRY_SECONDS) ?: return

            val listener = object : EventListenerStub() {
                @WorkerThread
                override fun onNotifyReceived(event: Event, content: Content?) {
                    val body = content?.utf8Text
                    val state = parseDialogState(body)
                    logRawNotify(number, body, state)
                    if (state != null) presenceChangedListener?.invoke(number, state)
                }

                @WorkerThread
                override fun onSubscribeStateChanged(event: Event, state: SubscriptionState?) {
                    // A SUBSCRIBE Asterisk explicitly rejects (403/404/etc.) or that otherwise
                    // errors out is as close as this event package gets to "недоступен" for a
                    // number this PBX won't track at all - the same signal the Windows client's
                    // SubscriptionFailed handler treats as Offline. Mirrors PresenceService.cs's
                    // handler, including the retry.
                    //
                    // state is also logged unconditionally below (every transition, not just
                    // Error) - unlike Windows' SIPNotifierClient, which only calls back on
                    // failure, this SDK's EventListenerStub reports every state change, so this
                    // is also our only visibility into whether a subscription ever reaches
                    // Active at all.
                    logSubscriptionState(number, state)
                    if (state == SubscriptionState.Error) {
                        val reason = try { event.reason } catch (e: Exception) { null }
                        logSubscriptionFailed(number, reason)
                        presenceChangedListener?.invoke(number, PresenceState.OFFLINE)
                        terminateAndForget(number)
                        if (reason != Reason.Forbidden && reason != Reason.BadEvent) {
                            scheduleResubscribe(number)
                        }
                    }
                }
            }

            event.addListener(listener)
            event.sendSubscribe(null)
            eventsByNumber[number] = event
            listenersByNumber[number] = listener
        } catch (e: Exception) {
            // Best-effort feature: a failed SUBSCRIBE just leaves that contact's presence as
            // whatever it last was instead of crashing the app.
        }
    }

    /** Re-subscribes to a number a short while after its previous subscription died (see
     * onSubscribeStateChanged above) - a fresh Event means a new dialog, so a transient
     * "the PBX forgot this dialog" rejection won't just repeat immediately. The delay is to
     * avoid hot-looping if the PBX itself is genuinely down. Mirrors PresenceService.cs's
     * ScheduleResubscribe. */
    @WorkerThread
    private fun scheduleResubscribe(number: String) {
        mainHandler.postDelayed({
            if (number in wantedNumbers) {
                sipManager.postOnCoreThread { core ->
                    if (!eventsByNumber.containsKey(number)) {
                        subscribe(core, number, sipManager.domainForLine(lineIndex))
                    }
                }
            }
        }, RESUBSCRIBE_AFTER_FAILURE_DELAY_MS)
    }

    @WorkerThread
    private fun terminateAndForget(number: String) {
        try {
            eventsByNumber.remove(number)?.terminate()
        } catch (e: Exception) {
            // Best-effort teardown.
        }
        listenersByNumber.remove(number)
    }

    @AnyThread
    fun stop() {
        wantedNumbers = emptySet()
        sipManager.postOnCoreThread {
            for (number in eventsByNumber.keys.toList()) {
                terminateAndForget(number)
            }
        }
    }

    // ---- Diagnostic logging (see the class doc comment - temporary) ----

    private val logTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private fun appendToLog(line: String) {
        try {
            val file = File(context.filesDir, "presence_debug.log")
            // No in-app button reads this any more (removed from Settings, Nov 2026 - still
            // reachable via `adb exec-out run-as com.catsoftfon.sip cat files/presence_debug.log`
            // if ever needed again), so nothing else trims it either - cap it here instead of
            // letting it grow forever. Keeps only the tail once it crosses ~2 MB.
            if (file.length() > 2_000_000L) {
                file.writeText(file.readText(Charsets.UTF_8).takeLast(500_000))
            }
            file.appendText(line)
        } catch (e: Exception) {
            // Diagnostics must never crash the app.
        }
    }

    private fun logRawNotify(number: String, body: String?, parsed: PresenceState?) {
        appendToLog("[${logTimeFormat.format(Date())}] line=$lineIndex number=$number parsed=$parsed\n${body}\n\n")
    }

    private fun logSubscriptionState(number: String, state: SubscriptionState?) {
        appendToLog("[${logTimeFormat.format(Date())}] line=$lineIndex number=$number state=$state\n")
    }

    private fun logSubscriptionFailed(number: String, reason: Reason?) {
        appendToLog("[${logTimeFormat.format(Date())}] line=$lineIndex number=$number SUBSCRIPTION FAILED reason=$reason\n\n")
    }

    /**
     * Mirrors ParseDialogState in the Windows client's PresenceService.cs exactly: a
     * dialog-info body with no <dialog> children, or one whose <state> is "terminated", means
     * idle/free; "confirmed" is an active call (Busy); "early" is ringing/offered. Namespace
     * stripped (compares local tag names only), same as the Windows side's ns-agnostic lookup.
     *
     * Note this genuinely cannot tell "a valid, hinted extension that just isn't registered
     * right now" apart from "idle and registered" - RFC 4235's dialog-info has no state for
     * "unavailable", and Asterisk's hint-to-dialog-info translation reports both the same way
     * (zero <dialog> children), confirmed against real captured NOTIFY bodies on the Windows
     * client. Offline/grey for a number only ever comes from an explicit SUBSCRIBE rejection
     * (see onSubscribeStateChanged above) - which only happens for a number Asterisk has no
     * hint for at all (e.g. a bad/external number), not for a real extension that's simply
     * powered off. Telling those two apart would need PBX-side info (AMI/ARI) the user has
     * already ruled out requiring.
     */
    private fun parseDialogState(body: String?): PresenceState? {
        if (body.isNullOrBlank()) return null
        return try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(body))

            var dialogCount = 0
            var stateText: String? = null
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    when (parser.name.substringAfterLast(':')) {
                        "dialog" -> dialogCount++
                        "state" -> if (stateText == null) stateText = parser.nextText().trim()
                    }
                }
                eventType = parser.next()
            }

            if (dialogCount == 0) return PresenceState.IDLE

            when (stateText?.lowercase()) {
                "confirmed" -> PresenceState.BUSY
                "early" -> PresenceState.RINGING
                "terminated" -> PresenceState.IDLE
                else -> PresenceState.UNKNOWN
            }
        } catch (e: Exception) {
            null
        }
    }
}
