package com.catsoftfon.sip.presence

import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import com.catsoftfon.sip.model.AppSettings
import com.google.gson.Gson
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Polls Asterisk's ARI (Asterisk REST Interface) `/ari/endpoints` periodically, purely to learn
 * each PJSIP extension's actual SIP registration state (online/offline) - the one thing the
 * RFC 4235 "dialog" event-package subscription in [PresenceManager] can never tell apart (see
 * that class's doc comment): Asterisk's dialog-info translation reports a hinted-but-currently
 * -unregistered extension identically to a hinted-and-idle one (both "terminated", 0 dialogs).
 *
 * Confirmed against this PBX's own ARI (chat with the user, Nov 2026): GET /ari/endpoints
 * returns, for every PJSIP extension, `{"technology":"PJSIP","resource":"<extension>","state":
 * "online"|"offline"}` - a clean registration signal. This is deliberately NOT the same as
 * `core show hints`' combined per-extension state, which also folds in FreePBX's DND and manual
 * Presence features (`PJSIP/<ext>&Custom:DND<ext>,CustomPresence:<ext>`) and reported
 * "Unavailable" for extensions whose DND/Presence custom device-state had simply never been
 * set - unrelated to whether the phone itself was registered. `/ari/endpoints` has none of
 * that noise.
 *
 * Entirely optional: [updateConfig] with `ariEnabled = false` (the default) or blank
 * host/username just stops the poll loop. Without it, [PresenceManager]'s dialog-based state is
 * the only signal, exactly as before this was added - green/red still work, only the "genuinely
 * offline" grey distinction is unavailable (an unregistered extension shows the same as an idle
 * one, since the protocol genuinely can't tell).
 *
 * The ARI endpoint is Asterisk's own mini-HTTPS server, normally running with the self-signed
 * certificate FreePBX generates for it - the user confirmed this exact setup themselves with
 * `curl -k`, so the trust-all TrustManager below is intentional, and scoped to only this one
 * HTTPS client (nothing else in the app relaxes certificate checking).
 *
 * One instance per line, like [PresenceManager] - [lineIndex] is used only for log tags, since
 * the poll result (a flat list of every PJSIP extension on that line's PBX) is matched against
 * contacts by number downstream, in [PresenceCombiner].
 */
class AriRegistrationClient(private val lineIndex: Int) {

    companion object {
        private const val TAG = "AriRegistrationClient"
        private const val POLL_INTERVAL_MS = 20_000L
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 8_000
    }

    /** Invoked with (number, isOnline) for every PJSIP endpoint ARI reports on each poll -
     * regardless of whether [number] happens to be a watched contact right now. The receiving
     * end ([PresenceCombiner]) just ignores numbers it doesn't recognise as a contact. */
    @Volatile
    var registrationChangedListener: ((number: String, online: Boolean) -> Unit)? = null

    private val thread = HandlerThread("AriPoll-$lineIndex").apply { start() }
    private val handler = Handler(thread.looper)
    private val gson = Gson()

    @Volatile private var host: String = ""
    @Volatile private var port: Int = 8089
    @Volatile private var username: String = ""
    @Volatile private var password: String = ""
    @Volatile private var enabled: Boolean = false

    @Volatile private var pollLoopStarted = false

    /** Call whenever this line's settings are loaded or saved. Safe to call repeatedly with the
     * same values; starts the poll loop the first time it sees a usable (enabled + host +
     * username) configuration and simply updates the values used by later polls after that. */
    @Synchronized
    fun updateConfig(settings: AppSettings) {
        host = settings.sipServer.trim()
        port = settings.ariPort
        username = settings.ariUsername.trim()
        password = settings.ariPassword
        enabled = settings.ariEnabled && host.isNotEmpty() && username.isNotEmpty()

        if (!pollLoopStarted) {
            pollLoopStarted = true
            schedulePoll(0)
        }
    }

    private fun schedulePoll(delayMs: Long) {
        handler.postDelayed({
            poll()
            schedulePoll(POLL_INTERVAL_MS)
        }, delayMs)
    }

    private fun poll() {
        // Snapshot the volatile config once, so it can't change mid-request.
        val currentHost = host
        val currentPort = port
        val currentUser = username
        val currentPass = password
        if (!enabled || currentHost.isEmpty() || currentUser.isEmpty()) return

        try {
            val url = URL("https://$currentHost:$currentPort/ari/endpoints")
            val connection = (url.openConnection() as HttpsURLConnection).apply {
                sslSocketFactory = trustAllSslContext.socketFactory
                hostnameVerifier = HostnameVerifier { _, _ -> true }
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                val auth = Base64.encodeToString(
                    "$currentUser:$currentPass".toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP
                )
                setRequestProperty("Authorization", "Basic $auth")
            }
            try {
                val body = connection.inputStream.use { stream ->
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
                }
                val endpoints = gson.fromJson(body, Array<AriEndpoint>::class.java) ?: return
                for (endpoint in endpoints) {
                    if (endpoint.resource.isBlank()) continue
                    val online = endpoint.state.equals("online", ignoreCase = true)
                    registrationChangedListener?.invoke(endpoint.resource, online)
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            // Best-effort: a failed poll (PBX briefly unreachable, wrong credentials, a
            // transient network blip) just leaves registration state as whatever it last was
            // until the next cycle - never crashes the app, never affects the dialog-based
            // green/red signal, which keeps working independently of this.
            Log.w(TAG, "line=$lineIndex ARI poll failed: ${e.message}")
        }
    }

    private data class AriEndpoint(
        val technology: String = "",
        val resource: String = "",
        val state: String = ""
    )

    // Trust-all SSLContext, scoped to this one HTTPS client only - see the class doc comment.
    private val trustAllSslContext: SSLContext by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustAll), SecureRandom()) }
    }

    fun stop() {
        enabled = false
        thread.quitSafely()
    }
}
