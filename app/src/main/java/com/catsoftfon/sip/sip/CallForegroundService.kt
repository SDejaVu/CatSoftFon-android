package com.catsoftfon.sip.sip

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.catsoftfon.sip.R
import com.catsoftfon.sip.ui.IncomingCallActivity
import com.catsoftfon.sip.ui.MainActivity

/**
 * Foreground service that keeps the process (and therefore the Linphone Core / SIP
 * registrations) alive, and owns the call-related notifications. Required on modern Android
 * for any app doing real-time audio in the background - without it the OS can kill the process
 * mid-call.
 *
 * Has two states, both expressed as the same single "foreground promotion" (Android only lets
 * a Service be foreground via one promoted notification at a time):
 *
 * - KEEP-ALIVE (idle): started once, right when the app starts (see [startKeepAlive], called
 *   from CatSoftFonApplication.onCreate and whenever Settings are saved) and left running for
 *   as long as the app is configured - a quiet, low-priority "CatSoftFon на связи" notification
 *   (foregroundServiceType `remoteMessaging` - deliberately not `dataSync`: Android 15+
 *   force-stops a `dataSync`/`mediaProcessing` foreground service after ~6h of continuous use
 *   per rolling 24h, which would just reintroduce this exact bug a few hours in; `remoteMessaging`
 *   - "maintains a connection to a remote server to receive messages for the user" - is both a
 *   closer semantic match for an idle SIP registration and isn't subject to that timeout). This
 *   is the actual fix for "closing the app means it can't be reached any more": before this existed,
 *   the service - and with it the whole process, including the Linphone Core's registration
 *   thread - only ran DURING a call, so closing the app (swiping it from Recents) while merely
 *   registered-and-idle left nothing keeping the process alive, and Android was free to kill it
 *   outright. A genuine foreground service, in contrast, is specifically exempt from that -
 *   Android does not kill a foreground service just because its task was removed from Recents.
 * - IN CALL: unchanged from before - up to 3 lines can be ringing/in-call at once, each with
 *   its OWN detailed notification (line 1 in a call, line 2 ringing, etc., posted/cancelled
 *   independently via NotificationManagerCompat.notify()/cancel()), while [ensureCallForeground]
 *   (re)promotes the one shared summary notification to the more specific `microphone|
 *   phoneCall` type and "звонок выполняется" text. Once every line's call ends (tracked by
 *   [busyLines]), it demotes back to the quiet keep-alive notification instead of stopping -
 *   the service itself keeps running as long as keep-alive is active.
 */
class CallForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_KEEP_ALIVE -> {
                keepAliveActive = true
                if (busyLines.isEmpty()) ensureIdleForeground()
                // If a call is already in progress, the call notification is already the
                // promoted one - nothing to do, keepAliveActive just means "don't fully stop
                // when that call ends" (see ACTION_STOP below).
            }
            ACTION_INCOMING -> {
                val lineIndex = intent.getIntExtra(EXTRA_LINE, 0)
                val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
                val number = intent.getStringExtra(EXTRA_NUMBER).orEmpty()
                busyLines.add(lineIndex)
                ensureCallForeground()
                NotificationManagerCompat.from(this).notify(lineNotificationId(lineIndex), buildIncomingNotification(this, lineIndex, name, number))
            }
            ACTION_ONGOING -> {
                val lineIndex = intent.getIntExtra(EXTRA_LINE, 0)
                val label = intent.getStringExtra(EXTRA_NAME).orEmpty()
                busyLines.add(lineIndex)
                ensureCallForeground()
                NotificationManagerCompat.from(this).notify(lineNotificationId(lineIndex), buildOngoingNotification(this, lineIndex, label))
            }
            ACTION_STOP -> {
                val lineIndex = intent.getIntExtra(EXTRA_LINE, 0)
                busyLines.remove(lineIndex)
                NotificationManagerCompat.from(this).cancel(lineNotificationId(lineIndex))
                if (busyLines.isEmpty()) {
                    if (keepAliveActive) {
                        // Demote back to the quiet "на связи" notification rather than fully
                        // stopping - the whole point of keep-alive is that the service (and the
                        // process/SIP registration with it) stays alive between calls too.
                        ensureIdleForeground()
                    } else {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        // START_STICKY: if the OS kills this process under memory pressure, ask it to restart
        // the service - CatSoftFonApplication.onCreate() re-runs as part of that (a fresh
        // process start), and it calls startKeepAlive() itself, so keep-alive re-establishes
        // on its own either way; this just gives the keep-alive promotion a chance to survive
        // more aggressive kills too, not just the "swiped from Recents" case this was added for.
        return START_STICKY
    }

    private fun ensureIdleForeground() {
        ServiceCompat.startForeground(
            this, SUMMARY_NOTIFICATION_ID, buildIdleNotification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        )
    }

    private fun ensureCallForeground() {
        ServiceCompat.startForeground(
            this, SUMMARY_NOTIFICATION_ID, buildCallSummaryNotification(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        )
    }

    companion object {
        const val CHANNEL_INCOMING_CALL = "incoming_call"
        const val CHANNEL_ONGOING_CALL = "ongoing_call"

        private const val SUMMARY_NOTIFICATION_ID = 40
        private fun lineNotificationId(lineIndex: Int) = 41 + lineIndex

        /** Lines currently showing a call-related notification - process-wide (there's only
         * ever one CallForegroundService instance), used to decide when it's safe to fully
         * stop the service rather than just drop one line's notification. */
        private val busyLines = mutableSetOf<Int>()

        /** Whether [startKeepAlive] has been requested - process-wide, same reasoning as
         * [busyLines]. True for essentially the whole time the app has at least one configured
         * line; see the class doc comment. */
        @Volatile private var keepAliveActive = false

        private const val ACTION_KEEP_ALIVE = "com.catsoftfon.sip.action.KEEP_ALIVE"
        private const val ACTION_INCOMING = "com.catsoftfon.sip.action.INCOMING"
        private const val ACTION_ONGOING = "com.catsoftfon.sip.action.ONGOING"
        private const val ACTION_STOP = "com.catsoftfon.sip.action.STOP"
        private const val EXTRA_LINE = "line"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_NUMBER = "number"

        /** Starts (or confirms) the persistent "на связи" foreground promotion - see the class
         * doc comment for why this needs to run continuously rather than only during calls.
         * Safe to call repeatedly (CatSoftFonApplication.onCreate and SettingsActivity.saveAll
         * both call it - once at app start, once whenever accounts are saved); a second call
         * while already active is a harmless no-op. */
        fun startKeepAlive(context: Context) {
            context.startForegroundService(Intent(context, CallForegroundService::class.java).apply {
                action = ACTION_KEEP_ALIVE
            })
        }

        fun notifyIncomingCall(context: Context, lineIndex: Int, name: String, number: String) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                action = ACTION_INCOMING
                putExtra(EXTRA_LINE, lineIndex)
                putExtra(EXTRA_NAME, name)
                putExtra(EXTRA_NUMBER, number)
            }
            context.startForegroundService(intent)

            // Also launch the full-screen incoming-call UI directly - relying solely on the
            // notification's fullScreenIntent can be delayed by the OS on some devices.
            val callIntent = Intent(context, IncomingCallActivity::class.java).apply {
                putExtra(EXTRA_LINE, lineIndex)
                putExtra(EXTRA_NAME, name)
                putExtra(EXTRA_NUMBER, number)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(callIntent)
        }

        fun notifyOngoingCall(context: Context, lineIndex: Int, label: String) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                action = ACTION_ONGOING
                putExtra(EXTRA_LINE, lineIndex)
                putExtra(EXTRA_NAME, label)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context, lineIndex: Int) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_LINE, lineIndex)
            }
            context.startService(intent)
        }

        private fun lineLabel(lineIndex: Int) = "Линия ${lineIndex + 1}"

        /** The quiet "idle, just staying connected" notification - see the class doc comment.
         * Tapping it opens the app, same as the in-call summary below. */
        private fun buildIdleNotification(context: Context): Notification {
            val contentIntent = PendingIntent.getActivity(
                context, 3000,
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Builder(context, CHANNEL_ONGOING_CALL)
                .setSmallIcon(R.drawable.ic_call)
                .setContentTitle(context.getString(R.string.keep_alive_notification_title))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setContentIntent(contentIntent)
                .build()
        }

        private fun buildCallSummaryNotification(context: Context): Notification =
            NotificationCompat.Builder(context, CHANNEL_ONGOING_CALL)
                .setSmallIcon(R.drawable.ic_call)
                .setContentTitle(context.getString(R.string.call_in_progress))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .build()

        private fun buildIncomingNotification(context: Context, lineIndex: Int, name: String, number: String): Notification {
            val fullScreenIntent = Intent(context, IncomingCallActivity::class.java).apply {
                putExtra(EXTRA_LINE, lineIndex)
                putExtra(EXTRA_NAME, name)
                putExtra(EXTRA_NUMBER, number)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val fullScreenPendingIntent = PendingIntent.getActivity(
                context, 1000 + lineIndex, fullScreenIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Builder(context, CHANNEL_INCOMING_CALL)
                .setSmallIcon(R.drawable.ic_call)
                .setContentTitle(context.getString(R.string.incoming_call_from, name.ifBlank { number }))
                .setContentText("${lineLabel(lineIndex)} · $number")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setOngoing(true)
                .build()
        }

        private fun buildOngoingNotification(context: Context, lineIndex: Int, label: String): Notification {
            val contentIntent = PendingIntent.getActivity(
                context, 2000 + lineIndex,
                Intent(context, MainActivity::class.java).apply {
                    putExtra(EXTRA_LINE, lineIndex)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Builder(context, CHANNEL_ONGOING_CALL)
                .setSmallIcon(R.drawable.ic_call)
                .setContentTitle(context.getString(R.string.call_in_progress))
                .setContentText("${lineLabel(lineIndex)}: $label")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setContentIntent(contentIntent)
                .build()
        }
    }
}
