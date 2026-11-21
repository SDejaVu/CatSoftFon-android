package com.catsoftfon.sip.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import com.google.android.material.tabs.TabLayout
import com.catsoftfon.sip.R
import com.catsoftfon.sip.databinding.ActivityMainBinding
import com.catsoftfon.sip.catApp
import com.catsoftfon.sip.sip.SipManager
import com.catsoftfon.sip.sip.SipRegistrationState

/** Fragment argument key for which line (0-based) a Dialer/Contacts/History fragment should
 * operate on - shared by MainActivity and all 3 fragments (same package, no import needed). */
const val ARG_LINE_INDEX = "lineIndex"

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentLineIndex = 0
    private var currentNavItemId = R.id.nav_dialer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestRuntimePermissions()

        currentLineIndex = intent.getIntExtra(EXTRA_LINE, 0).coerceIn(0, SipManager.LINE_COUNT - 1)

        setupLineTabs()

        if (savedInstanceState == null) {
            showFragment(currentNavItemId)
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            showFragment(item.itemId)
            true
        }

        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val line = intent.getIntExtra(EXTRA_LINE, -1)
        if (line in 0 until SipManager.LINE_COUNT) {
            binding.lineTabs.getTabAt(line)?.select()
        }
    }

    /** Builds the 3 line tabs (custom view: dot + label, see tab_line.xml) and wires each
     * one's dot to that line's own registration state, plus auto-switches to a line the
     * moment it starts ringing - same spirit as the Windows client's auto-switch-to-ringing-
     * line logic in MainWindow.xaml.cs, just driven by LiveData instead of PropertyChanged. */
    private fun setupLineTabs() {
        val sipManager = catApp().sipManager

        for (i in 0 until SipManager.LINE_COUNT) {
            val tabView = layoutInflater.inflate(R.layout.tab_line, binding.lineTabs, false)
            tabView.findViewById<TextView>(R.id.lineLabel).text = getString(R.string.line_label, i + 1)

            val tab = binding.lineTabs.newTab().setCustomView(tabView)
            binding.lineTabs.addTab(tab, i == currentLineIndex)

            val dot = tabView.findViewById<View>(R.id.lineDot)
            val lineIndex = i
            sipManager.registrationState[lineIndex].observe(this) { state ->
                dot.setBackgroundResource(registrationDotRes(state))
            }
            sipManager.incomingCall[lineIndex].observe(this) { info ->
                if (info != null) binding.lineTabs.getTabAt(lineIndex)?.select()
            }
        }

        binding.lineTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentLineIndex = tab.position
                showFragment(currentNavItemId)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun registrationDotRes(state: SipRegistrationState?): Int = when (state) {
        SipRegistrationState.OK -> R.drawable.presence_dot_idle
        SipRegistrationState.PROGRESS -> R.drawable.presence_dot_ringing
        SipRegistrationState.FAILED -> R.drawable.presence_dot_busy
        SipRegistrationState.CLEARED -> R.drawable.presence_dot_offline
        else -> R.drawable.presence_dot_offline
    }

    private fun showFragment(navItemId: Int) {
        currentNavItemId = navItemId
        val fragment = when (navItemId) {
            R.id.nav_contacts -> ContactsFragment()
            R.id.nav_history -> HistoryFragment()
            else -> DialerFragment()
        }
        fragment.arguments = bundleOf(ARG_LINE_INDEX to currentLineIndex)
        supportFragmentManager.beginTransaction()
            .replace(binding.fragmentContainer.id, fragment)
            .commit()
    }

    private fun requestRuntimePermissions() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        }
    }

    companion object {
        const val EXTRA_LINE = "line"
    }
}
