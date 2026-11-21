package com.catsoftfon.sip.sip

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import com.catsoftfon.sip.model.AppSettings
import com.catsoftfon.sip.model.SipTransport
import org.linphone.core.Account
import org.linphone.core.Address
import org.linphone.core.AudioDevice
import org.linphone.core.AuthInfo
import org.linphone.core.Call
import org.linphone.core.Config
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.Factory
import org.linphone.core.Reason
import org.linphone.core.RegistrationState
import org.linphone.core.TransportType
import java.io.File
import java.time.LocalTime

enum class SipRegistrationState { NONE, PROGRESS, OK, FAILED, CLEARED }

data class IncomingCallInfo(val call: Call, val remoteNumber: String, val remoteDisplayName: String)

/**
 * Owns the Linphone Core and is the only place in the app allowed to touch it. Modelled
 * directly on org.linphone.core.CoreContext from the official linphone-android app
 * (github.com/BelledonneCommunications/linphone-android): the Core is created and driven on
 * its own dedicated HandlerThread with autoIterateEnabled, because the SDK requires every
 * call into the Core to happen on the same thread that created it. All public methods here
 * are therefore safe to call from any thread - they hop onto the core thread themselves.
 *
 * Up to [LINE_COUNT] independent SIP accounts/"lines" register and ring/call in parallel, all
 * on this ONE Core - unlike the Windows client (which gives each line its own SIPTransport
 * instance), Linphone's Core is explicitly designed to carry several simultaneously-registered
 * Accounts (Core.addAccount can be called more than once; each Account registers on its own),
 * so running 3 separate Cores/HandlerThreads here would just be 3x the native/battery
 * overhead for no benefit. A line is identified everywhere in this class by a 0-based
 * [lineIndex] in 0 until LINE_COUNT.
 *
 * Every per-line LiveData below (registration/call state) is an Array indexed by lineIndex -
 * see AccountLineViewModel's doc comment on the Windows side for why audio still needs
 * explicit call-waiting coordination even though registration/ringing are fully parallel:
 * exactly one physical mic/speaker exists, so whenever a line's own call is about to actually
 * carry audio (answering, placing a call, or resuming from hold) every OTHER line's connected
 * call is paused first - see [pauseOtherConnectedCalls].
 */
class SipManager(private val context: Context) : HandlerThread("CatSipCoreThread") {

    companion object {
        const val LINE_COUNT = 3
    }

    private lateinit var core: Core
    private lateinit var coreThreadHandler: Handler
    private val accounts = arrayOfNulls<Account>(LINE_COUNT)

    /** The most recent AppSettings applied to each line (via [applyAccountSettings]), kept
     * purely so [shouldAutoReject] can read that line's current Do Not Disturb / night-mode
     * configuration at the moment a call actually arrives - only accessed from the core thread,
     * same as everything else here. */
    private val lineSettings = arrayOfNulls<AppSettings>(LINE_COUNT)

    /** Which line a given live Call belongs to - populated the first time a call is seen
     * (see [lineIndexFor]) and removed once it ends, so this never grows unbounded. */
    private val lineByCall = HashMap<Call, Int>()

    val registrationState = Array(LINE_COUNT) { MutableLiveData(SipRegistrationState.NONE) }
    val registrationMessage = Array(LINE_COUNT) { MutableLiveData("") }
    val activeCall = Array(LINE_COUNT) { MutableLiveData<Call?>(null) }
    val callState = Array(LINE_COUNT) { MutableLiveData<Call.State?>(null) }
    val incomingCall = Array(LINE_COUNT) { MutableLiveData<IncomingCallInfo?>(null) }
    val micMuted = Array(LINE_COUNT) { MutableLiveData(false) }
    /** Whether this line's call is currently routed to the loudspeaker rather than the
     * earpiece/headset/Bluetooth device it would otherwise use - see [toggleSpeaker]. */
    val speakerOn = Array(LINE_COUNT) { MutableLiveData(false) }

    /** Set by the owner (CatSoftFonApplication) so SipManager can report finished/missed calls
     * and ringing, per line. */
    var callEndedListener: ((lineIndex: Int, call: Call, missed: Boolean, durationSeconds: Int) -> Unit)? = null
    var incomingRingListener: ((lineIndex: Int, info: IncomingCallInfo) -> Unit)? = null

    private val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onAccountRegistrationStateChanged(
            core: Core,
            account: Account,
            state: RegistrationState?,
            message: String
        ) {
            val lineIndex = accounts.indexOf(account)
            if (lineIndex < 0) return
            registrationMessage[lineIndex].postValue(message)
            registrationState[lineIndex].postValue(
                when (state) {
                    RegistrationState.Ok -> SipRegistrationState.OK
                    RegistrationState.Failed -> SipRegistrationState.FAILED
                    RegistrationState.Cleared -> SipRegistrationState.CLEARED
                    RegistrationState.Progress -> SipRegistrationState.PROGRESS
                    else -> SipRegistrationState.NONE
                }
            )
        }

        @WorkerThread
        override fun onCallStateChanged(core: Core, call: Call, state: Call.State?, message: String) {
            val lineIndex = lineIndexFor(call) ?: return
            callState[lineIndex].postValue(state)
            when (state) {
                Call.State.IncomingReceived -> {
                    if (shouldAutoReject(lineSettings[lineIndex])) {
                        // Do Not Disturb / night mode - decline immediately, silently: no
                        // ringing, no notification, no IncomingCallActivity. The caller gets a
                        // normal SIP busy response, same as a phone that's actually busy; the
                        // call still reaches Call.State.End below and is logged to История as
                        // missed, same as any other unanswered call.
                        try {
                            call.decline(Reason.Busy)
                        } catch (e: Exception) {
                            // Best-effort - if decline() itself fails, just let the call sit;
                            // never crash the Core thread over this.
                        }
                    } else {
                        val remote = call.remoteAddress
                        val info = IncomingCallInfo(
                            call = call,
                            remoteNumber = remote.username ?: remote.asStringUriOnly(),
                            remoteDisplayName = remote.displayName ?: ""
                        )
                        incomingCall[lineIndex].postValue(info)
                        activeCall[lineIndex].postValue(call)
                        incomingRingListener?.invoke(lineIndex, info)
                    }
                }
                Call.State.Connected -> {
                    activeCall[lineIndex].postValue(call)
                }
                Call.State.End, Call.State.Error -> {
                    val wasMissed = call.dir == Call.Dir.Incoming &&
                        (call.duration == 0 && state == Call.State.End)
                    callEndedListener?.invoke(lineIndex, call, wasMissed, call.duration)
                    if (incomingCall[lineIndex].value?.call == call) incomingCall[lineIndex].postValue(null)
                    if (activeCall[lineIndex].value == call) {
                        activeCall[lineIndex].postValue(null)
                        micMuted[lineIndex].postValue(false)
                        speakerOn[lineIndex].postValue(false)
                    }
                    lineByCall.remove(call)
                }
                Call.State.Released -> {
                    if (activeCall[lineIndex].value == call) activeCall[lineIndex].postValue(null)
                    lineByCall.remove(call)
                }
                else -> {
                    activeCall[lineIndex].postValue(call)
                }
            }
        }
    }

    @WorkerThread
    override fun run() {
        Looper.prepare()
        val looper = Looper.myLooper() ?: return
        coreThreadHandler = Handler(looper)

        val config = createOrLoadConfig()
        core = Factory.instance().createCoreWithConfig(config, context)
        core.isAutoIterateEnabled = true
        core.addListener(coreListener)
        core.start()

        Looper.loop()
    }

    @AnyThread
    fun postOnCoreThread(block: (core: Core) -> Unit) {
        if (!::coreThreadHandler.isInitialized) {
            // Core thread not ready yet (HandlerThread.start() hasn't finished spinning up);
            // retry shortly on the main thread rather than dropping the call.
            Handler(Looper.getMainLooper()).postDelayed({ postOnCoreThread(block) }, 50)
            return
        }
        coreThreadHandler.post { if (::core.isInitialized) block(core) }
    }

    @WorkerThread
    private fun createOrLoadConfig(): Config {
        val filesDir = context.filesDir.absolutePath
        val configPath = "$filesDir/.catrc"
        val factoryConfigPath = "$filesDir/catrc_factory"

        copyAssetIfMissing("linphonerc_default", configPath)
        copyAssetIfMissing("linphonerc_factory", factoryConfigPath)

        Factory.instance().setLogCollectionPath(filesDir)
        Factory.instance().setCacheDir(context.cacheDir.absolutePath)

        return Factory.instance().createConfigWithFactory(configPath, factoryConfigPath)
    }

    private fun copyAssetIfMissing(assetName: String, destPath: String) {
        val dest = File(destPath)
        if (dest.exists()) return
        context.assets.open(assetName).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }

    /**
     * Figures out which of the (up to 3) configured lines a Call belongs to, and remembers the
     * answer in [lineByCall] for the rest of that call's lifetime. For an outgoing call this is
     * unambiguous (CallParams.account was set explicitly in [call]); for an incoming call the
     * Core resolves CallParams.account itself by matching the call's To-address against each
     * registered Account's identity - confirmed against the official liblinphone 5.4 Java API
     * docs (CallParams.getAccount() / Address.weakEqual()). The explicit To-address match below
     * is kept as a fallback in case that auto-resolution doesn't happen for some SDK version.
     */
    @WorkerThread
    private fun lineIndexFor(call: Call): Int? {
        lineByCall[call]?.let { return it }

        call.params?.account?.let { viaAccount ->
            val idx = accounts.indexOf(viaAccount)
            if (idx >= 0) {
                lineByCall[call] = idx
                return idx
            }
        }

        val to = call.toAddress
        for (i in accounts.indices) {
            val identity = accounts[i]?.params?.identityAddress
            if (identity != null && identity.weakEqual(to)) {
                lineByCall[call] = i
                return i
            }
        }
        return null
    }

    private fun callFor(lineIndex: Int): Call? =
        activeCall[lineIndex].value ?: incomingCall[lineIndex].value?.call

    /** Pauses every OTHER line's connected call before this line's own audio goes live -
     * see the class doc comment. Safe to call even if no other line is in a call. */
    @WorkerThread
    private fun pauseOtherConnectedCalls(core: Core, exceptLineIndex: Int) {
        for (call in core.calls) {
            val idx = lineByCall[call] ?: continue
            if (idx == exceptLineIndex) continue
            if (call.state == Call.State.StreamsRunning || call.state == Call.State.Connected) {
                try {
                    call.pause()
                } catch (e: Exception) {
                    // Best-effort - a call that's mid-transition may legitimately refuse pause().
                }
            }
        }
    }

    /** Domain of the identity configured for this line, or null if it isn't configured -
     * used by PresenceManager to build each line's own SUBSCRIBE URIs. Must be called from
     * the core thread (i.e. from inside a postOnCoreThread block). */
    @WorkerThread
    fun domainForLine(lineIndex: Int): String? = accounts[lineIndex]?.params?.identityAddress?.domain

    // ---- Account management ----

    /**
     * (Re)configures line [lineIndex]'s SIP account from AppSettings and registers with the
     * PBX. Any previously-registered account on that same line is removed first. Mirrors the
     * account-creation sequence verified against org.linphone.core.CoreContext /
     * AccountLoginViewModel / ThirdPartySipAccountLoginViewModel in the official
     * linphone-android app - now just parametrized by which line slot it's for.
     */
    @AnyThread
    fun applyAccountSettings(lineIndex: Int, settings: AppSettings) {
        postOnCoreThread { core ->
            lineSettings[lineIndex] = settings

            accounts[lineIndex]?.let { old ->
                core.removeAccount(old)
                old.findAuthInfo()?.let { core.removeAuthInfo(it) }
            }
            accounts[lineIndex] = null

            if (settings.sipServer.isBlank() || settings.username.isBlank()) {
                registrationState[lineIndex].postValue(SipRegistrationState.NONE)
                return@postOnCoreThread
            }

            val identityUri = "sip:${settings.username}@${settings.sipServer}"
            val identityAddress = Factory.instance().createAddress(identityUri)
            if (identityAddress == null) {
                registrationState[lineIndex].postValue(SipRegistrationState.FAILED)
                registrationMessage[lineIndex].postValue("Invalid SIP identity")
                return@postOnCoreThread
            }
            if (settings.displayName.isNotBlank()) {
                identityAddress.displayName = settings.displayName
            }

            val authInfo = Factory.instance().createAuthInfo(
                settings.username, null, settings.password, null, null, settings.sipServer
            )
            core.addAuthInfo(authInfo)

            val transportType = when (settings.transport) {
                SipTransport.UDP -> TransportType.Udp
                SipTransport.TCP -> TransportType.Tcp
                SipTransport.TLS -> TransportType.Tls
            }

            val serverAddress = Factory.instance().createAddress("sip:${settings.sipServer}:${settings.sipPort}")
            serverAddress?.transport = transportType

            val accountParams = core.createAccountParams()
            accountParams.identityAddress = identityAddress
            accountParams.serverAddress = serverAddress

            if (settings.outboundProxy.isNotBlank()) {
                val proxyUri = if (settings.outboundProxy.startsWith("sip:")) settings.outboundProxy else "sip:${settings.outboundProxy}"
                val proxyAddress = Factory.instance().createAddress(proxyUri)
                proxyAddress?.transport = transportType
                if (proxyAddress != null) {
                    accountParams.setRoutesAddresses(arrayOf(proxyAddress))
                }
            }

            val account = core.createAccount(accountParams)
            accounts[lineIndex] = account
            registrationState[lineIndex].postValue(SipRegistrationState.PROGRESS)
            core.addAccount(account)
            // The first line to ever get configured becomes the Core's default account (used
            // only as a last-resort fallback by SDK internals) - later lines never steal it
            // away from an already-registered earlier line.
            if (core.defaultAccount == null) core.defaultAccount = account
        }
    }

    @AnyThread
    fun logout(lineIndex: Int) {
        postOnCoreThread { core ->
            accounts[lineIndex]?.let { account ->
                core.removeAccount(account)
                account.findAuthInfo()?.let { core.removeAuthInfo(it) }
                if (core.defaultAccount == account) core.defaultAccount = core.accountList.firstOrNull()
            }
            accounts[lineIndex] = null
            registrationState[lineIndex].postValue(SipRegistrationState.NONE)
        }
    }

    // ---- Calls ----

    @AnyThread
    fun call(lineIndex: Int, number: String) {
        postOnCoreThread { core ->
            val account = accounts[lineIndex] ?: return@postOnCoreThread
            val domain = account.params?.identityAddress?.domain
            val uri = if (number.contains("@")) "sip:$number" else "sip:$number@${domain ?: ""}"
            val address: Address? = Factory.instance().createAddress(uri)
            if (address == null) return@postOnCoreThread

            pauseOtherConnectedCalls(core, lineIndex)

            val params = core.createCallParams(null)
            if (params != null) {
                params.account = account
                core.inviteAddressWithParams(address, params)
            } else {
                core.inviteAddress(address)
            }
        }
    }

    @AnyThread
    fun answer(lineIndex: Int) {
        postOnCoreThread { core ->
            val call = incomingCall[lineIndex].value?.call ?: return@postOnCoreThread
            pauseOtherConnectedCalls(core, lineIndex)
            call.accept()
        }
    }

    @AnyThread
    fun declineOrHangup(lineIndex: Int) {
        postOnCoreThread { core ->
            val call = callFor(lineIndex) ?: return@postOnCoreThread
            if (call.dir == Call.Dir.Incoming && isRingingState(call.state)) {
                call.decline(Reason.Declined)
            } else {
                call.terminate()
            }
        }
    }

    private fun isRingingState(state: Call.State?): Boolean =
        state == Call.State.IncomingReceived || state == Call.State.IncomingEarlyMedia

    /** True if an incoming call on this line should be auto-declined right now - either Do Not
     * Disturb is simply switched on, or night mode is on and the current time falls inside its
     * configured window. [settings] is null for a line that's never had [applyAccountSettings]
     * called (nothing configured yet) - never auto-reject in that case, there's nothing to ring
     * for either way. */
    @WorkerThread
    private fun shouldAutoReject(settings: AppSettings?): Boolean {
        if (settings == null) return false
        if (settings.doNotDisturbEnabled) return true
        if (!settings.nightModeEnabled) return false
        val now = LocalTime.now()
        val nowMinutes = now.hour * 60 + now.minute
        return isWithinWindow(nowMinutes, settings.nightModeStartMinutes, settings.nightModeEndMinutes)
    }

    /** Minutes-since-midnight window check that handles wrapping past midnight (e.g.
     * start=23:00, end=07:00 means "in window" from 23:00 through 06:59 the next day). A
     * start equal to end is treated as "always on" (a 24h window), not "never" - same
     * convention most alarm/scheduling UIs use for an all-day range. */
    private fun isWithinWindow(now: Int, start: Int, end: Int): Boolean {
        if (start == end) return true
        return if (start < end) now in start until end else now >= start || now < end
    }

    @AnyThread
    fun toggleHold(lineIndex: Int) {
        postOnCoreThread { core ->
            val call = callFor(lineIndex) ?: return@postOnCoreThread
            if (call.state == Call.State.Paused || call.state == Call.State.Pausing) {
                // Resuming is this line's audio going live again - clear the way first.
                pauseOtherConnectedCalls(core, lineIndex)
                call.resume()
            } else {
                call.pause()
            }
        }
    }

    @AnyThread
    fun toggleMute(lineIndex: Int) {
        postOnCoreThread { core ->
            val call = callFor(lineIndex) ?: return@postOnCoreThread
            val newState = !call.microphoneMuted
            call.microphoneMuted = newState
            micMuted[lineIndex].postValue(newState)
        }
    }

    /**
     * Switches this line's call between the loudspeaker and whatever device it would
     * otherwise use (earpiece, wired headset or Bluetooth) - the "громкая связь" button.
     * Mirrors how the official linphone-android app's CallViewModel.toggleSpeaker() picks a
     * device: match by [AudioDevice.Type] out of Core.audioDevices (the actual physical
     * devices available right now on this phone) rather than hard-coding one, and apply it to
     * the Call specifically (Call.outputAudioDevice), not the whole Core, so it only affects
     * this one line.
     */
    @AnyThread
    fun toggleSpeaker(lineIndex: Int) {
        postOnCoreThread { core ->
            val call = callFor(lineIndex) ?: return@postOnCoreThread
            val turningSpeakerOn = call.outputAudioDevice?.type != AudioDevice.Type.Speaker

            val targetDevice = if (turningSpeakerOn) {
                core.audioDevices.firstOrNull { it.type == AudioDevice.Type.Speaker }
            } else {
                // Prefer Bluetooth/wired headset over the earpiece if one is actually
                // connected right now - going back to "not speaker" should mean "whatever was
                // in use before", not always the earpiece.
                core.audioDevices.firstOrNull { it.type == AudioDevice.Type.Bluetooth }
                    ?: core.audioDevices.firstOrNull { it.type == AudioDevice.Type.Headset }
                    ?: core.audioDevices.firstOrNull { it.type == AudioDevice.Type.Headphones }
                    ?: core.audioDevices.firstOrNull { it.type == AudioDevice.Type.Earpiece }
            }

            if (targetDevice != null) {
                call.outputAudioDevice = targetDevice
                speakerOn[lineIndex].postValue(targetDevice.type == AudioDevice.Type.Speaker)
            }
        }
    }

    @AnyThread
    fun sendDtmf(lineIndex: Int, digit: Char) {
        postOnCoreThread { core ->
            callFor(lineIndex)?.sendDtmf(digit)
        }
    }

    // Named shutdown(), not destroy(): java.lang.Thread (HandlerThread's own superclass)
    // already declares a public destroy() of its own (deprecated/inert since Java 9 - it always
    // throws if actually called), so a same-named method here needs "override" and would
    // shadow that unrelated inherited one. shutdown() avoids the clash entirely.
    @AnyThread
    fun shutdown() {
        postOnCoreThread { core ->
            core.removeListener(coreListener)
            core.stop()
        }
        quitSafely()
    }
}
