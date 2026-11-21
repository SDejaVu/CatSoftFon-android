package com.catsoftfon.sip.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import com.catsoftfon.sip.databinding.ActivityIncomingCallBinding
import com.catsoftfon.sip.catApp
import com.catsoftfon.sip.sip.IncomingCallInfo
import com.catsoftfon.sip.sip.SipManager

/**
 * Full-screen incoming-call UI, launched both directly (CallForegroundService.notifyIncomingCall)
 * and via the notification's fullScreenIntent on devices where the direct launch is suppressed
 * (e.g. while the screen is already on and the app is backgrounded).
 *
 * Declared android:launchMode="singleInstance" in the manifest, so if a SECOND line starts
 * ringing while this is already showing a first one, Android reuses this same Activity
 * instance and delivers the new call via [onNewIntent] rather than creating a second window -
 * handled below by just re-binding the UI to whichever line's call is now newest/still ringing.
 */
class IncomingCallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIncomingCallBinding
    private var lineIndex = 0
    private var observedLineIndex: Int? = null
    private var incomingObserver: Observer<IncomingCallInfo?>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        binding = ActivityIncomingCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindToIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        bindToIntent(intent)
    }

    private fun bindToIntent(intent: Intent) {
        lineIndex = intent.getIntExtra(EXTRA_LINE, 0).coerceIn(0, SipManager.LINE_COUNT - 1)
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val number = intent.getStringExtra(EXTRA_NUMBER).orEmpty()
        binding.callerNameText.text = name.ifBlank { number }
        binding.callerNumberText.text = number

        val sipManager = catApp().sipManager

        binding.acceptButton.setOnClickListener {
            sipManager.answer(lineIndex)
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(MainActivity.EXTRA_LINE, lineIndex)
            )
            finish()
        }
        binding.declineButton.setOnClickListener {
            sipManager.declineOrHangup(lineIndex)
            finish()
        }

        // If this Activity instance is being re-bound to a DIFFERENT line (singleInstance
        // launchMode means a 2nd ringing line reuses this same instance via onNewIntent),
        // detach the previous line's observer first - otherwise that old line's call ending
        // later would still call finish() even though we've since switched to showing a
        // different, still-ringing line.
        observedLineIndex?.let { prev -> incomingObserver?.let { sipManager.incomingCall[prev].removeObserver(it) } }
        val observer = Observer<IncomingCallInfo?> { info ->
            // The caller hung up before we answered, or the call state otherwise moved on.
            if (info == null) finish()
        }
        sipManager.incomingCall[lineIndex].observe(this, observer)
        incomingObserver = observer
        observedLineIndex = lineIndex
    }

    companion object {
        const val EXTRA_LINE = "line"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_NUMBER = "number"
    }
}
