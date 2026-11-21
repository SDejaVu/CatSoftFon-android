package com.catsoftfon.sip.ui

import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import com.google.android.material.tabs.TabLayout
import com.catsoftfon.sip.R
import com.catsoftfon.sip.data.DirectoryImportService
import com.catsoftfon.sip.data.JsonStore
import com.catsoftfon.sip.databinding.AccountFieldsBinding
import com.catsoftfon.sip.databinding.ActivitySettingsBinding
import com.catsoftfon.sip.catApp
import com.catsoftfon.sip.model.AppSettings
import com.catsoftfon.sip.model.SipTransport
import com.catsoftfon.sip.sip.CallForegroundService
import com.catsoftfon.sip.sip.SipManager
import kotlin.concurrent.thread

/**
 * Configures all [SipManager.LINE_COUNT] lines - one inner tab per account, each a full copy
 * of the same fields (account_fields.xml inflated 3 times, one AccountFieldsBinding per line -
 * see that layout's doc comment). Only account 1 needs to be filled in.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var stores: Array<JsonStore>
    private lateinit var pages: Array<AccountFieldsBinding>

    // Night mode's start/end times aren't backed by an EditText (a TimePickerDialog sets them),
    // so they're tracked here per line - updateNightModeButtons() keeps the button labels in
    // sync, readFromForm() reads from here on save.
    private val nightStartMinutes = IntArray(SipManager.LINE_COUNT)
    private val nightEndMinutes = IntArray(SipManager.LINE_COUNT)

    private val transports = listOf(SipTransport.UDP, SipTransport.TCP, SipTransport.TLS)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        stores = Array(SipManager.LINE_COUNT) { i -> JsonStore(this, i) }
        pages = Array(SipManager.LINE_COUNT) { i -> AccountFieldsBinding.inflate(layoutInflater, binding.accountFieldsContainer, false) }

        for (i in 0 until SipManager.LINE_COUNT) {
            val lineIndex = i
            val page = pages[i]
            page.transportSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, transports.map { it.name })
            loadIntoForm(lineIndex, page, stores[i].loadSettings())
            binding.accountFieldsContainer.addView(page.root)
            page.root.visibility = if (i == 0) android.view.View.VISIBLE else android.view.View.GONE

            page.syncNowButton.setOnClickListener { runDirectorySync(i, page) }

            page.nightModeStartButton.setOnClickListener {
                pickTime(nightStartMinutes[lineIndex]) { minutes ->
                    nightStartMinutes[lineIndex] = minutes
                    updateNightModeButtons(page, lineIndex)
                }
            }
            page.nightModeEndButton.setOnClickListener {
                pickTime(nightEndMinutes[lineIndex]) { minutes ->
                    nightEndMinutes[lineIndex] = minutes
                    updateNightModeButtons(page, lineIndex)
                }
            }

            binding.accountTabs.addTab(binding.accountTabs.newTab().setText(getString(R.string.account_label, i + 1)), i == 0)
        }

        binding.accountTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                for (i in pages.indices) pages[i].root.visibility = if (i == tab.position) android.view.View.VISIBLE else android.view.View.GONE
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        binding.backButton.setOnClickListener { finish() }

        binding.saveSettingsButton.setOnClickListener { saveAll() }

        binding.audioSettingsButton.setOnClickListener {
            startActivity(Intent(this, AudioSettingsActivity::class.java))
        }

        binding.backgroundPermissionButton.setOnClickListener { requestIgnoreBatteryOptimizations() }

        binding.fullScreenIntentButton.setOnClickListener { requestFullScreenIntentPermission() }
    }

    override fun onResume() {
        super.onResume()
        // Also covers coming back from the system "Allow" dialog this button itself launches,
        // or the user having granted it earlier from Android's own battery settings directly.
        updateBackgroundButtonState()
        updateFullScreenIntentButtonState()
    }

    /** Opens the given time (minutes-since-midnight) in a standard 24h TimePickerDialog;
     * [onPicked] is called with the new minutes-since-midnight value if the user confirms. */
    private fun pickTime(currentMinutes: Int, onPicked: (minutes: Int) -> Unit) {
        TimePickerDialog(
            this,
            { _, hour, minute -> onPicked(hour * 60 + minute) },
            currentMinutes / 60, currentMinutes % 60,
            true // 24-hour format
        ).show()
    }

    private fun formatMinutes(minutes: Int): String = String.format("%02d:%02d", minutes / 60, minutes % 60)

    private fun updateNightModeButtons(page: AccountFieldsBinding, lineIndex: Int) {
        page.nightModeStartButton.text = formatMinutes(nightStartMinutes[lineIndex])
        page.nightModeEndButton.text = formatMinutes(nightEndMinutes[lineIndex])
    }

    /**
     * Android 14+ ("use full-screen intent" behavior change): this permission is OFF by
     * default for every app except the current default dialer, even though it's declared in
     * the manifest - without the user granting it explicitly here, the notification's
     * fullScreenIntent is silently ignored and the incoming-call screen may not pop up over the
     * lock screen / other apps on its own (it would still show as a normal heads-up
     * notification, just not take over the screen automatically). No equivalent restriction
     * exists before API 34, where this is simply always allowed.
     */
    private fun canUseFullScreenIntent(): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        return getSystemService<NotificationManager>()?.canUseFullScreenIntent() ?: true
    }

    private fun requestFullScreenIntentPermission() {
        if (canUseFullScreenIntent()) {
            updateFullScreenIntentButtonState()
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            Toast.makeText(this, R.string.allow_background_toast_unsupported, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateFullScreenIntentButtonState() {
        val granted = canUseFullScreenIntent()
        binding.fullScreenIntentButton.isEnabled = !granted
        binding.fullScreenIntentButton.text =
            if (granted) getString(R.string.full_screen_intent_button_done) else getString(R.string.full_screen_intent_button)
    }

    /**
     * Asks the OS to stop battery-optimizing this app - on stock Android this is what actually
     * keeps SIP registration alive and incoming calls arriving while the app is backgrounded;
     * without it, Doze/App Standby can suspend the network connection after a while. Shows the
     * system's own confirmation dialog (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) rather than
     * sending the user to a settings screen to find it themselves.
     *
     * Some OEMs (MIUI, EMUI/Magic UI, ColorOS, One UI, etc.) layer their own, separate
     * "autostart"/"battery saver per app" restriction on top of stock Android's, with no common
     * API to request it - those can only be disabled by the user themselves, in that
     * manufacturer's own Settings app, which this button cannot reach for them.
     */
    private fun requestIgnoreBatteryOptimizations() {
        val powerManager = getSystemService<PowerManager>()
        if (powerManager == null || powerManager.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, R.string.allow_background_toast_unsupported, Toast.LENGTH_SHORT).show()
            updateBackgroundButtonState()
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            // A handful of OEM builds strip this action out entirely - fall back to the
            // general battery-optimization list, where the user can find the app manually.
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, R.string.allow_background_toast_unsupported, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateBackgroundButtonState() {
        val ignoring = getSystemService<PowerManager>()?.isIgnoringBatteryOptimizations(packageName) ?: true
        binding.backgroundPermissionButton.isEnabled = !ignoring
        binding.backgroundPermissionButton.text =
            if (ignoring) getString(R.string.allow_background_button_done) else getString(R.string.allow_background_button)
    }

    private fun loadIntoForm(lineIndex: Int, page: AccountFieldsBinding, settings: AppSettings) {
        page.serverField.setText(settings.sipServer)
        page.portField.setText(settings.sipPort.toString())
        page.transportSpinner.setSelection(transports.indexOf(settings.transport).coerceAtLeast(0))
        page.usernameField.setText(settings.username)
        page.passwordField.setText(settings.password)
        page.displayNameField.setText(settings.displayName)
        page.outboundProxyField.setText(settings.outboundProxy)
        page.directorySyncUrlField.setText(settings.directorySyncUrl)
        page.directorySyncEnabledCheck.isChecked = settings.directorySyncEnabled
        page.directorySyncIntervalField.setText(settings.directorySyncIntervalMinutes.toString())
        page.startOnBootCheck.isChecked = settings.startOnBoot
        page.ariEnabledCheck.isChecked = settings.ariEnabled
        page.ariPortField.setText(settings.ariPort.toString())
        page.ariUsernameField.setText(settings.ariUsername)
        page.ariPasswordField.setText(settings.ariPassword)
        page.dndEnabledCheck.isChecked = settings.doNotDisturbEnabled
        page.nightModeEnabledCheck.isChecked = settings.nightModeEnabled
        nightStartMinutes[lineIndex] = settings.nightModeStartMinutes
        nightEndMinutes[lineIndex] = settings.nightModeEndMinutes
        updateNightModeButtons(page, lineIndex)
    }

    /** Reads one line's form into an AppSettings, or null if that line's required fields are
     * missing (account 1 must be filled in; 2/3 are optional - blank Server AND Username just
     * means "don't use this line", but only one of the two filled in is a mistake). Shows a
     * Toast and returns null on a validation failure so the caller can abort the whole save. */
    private fun readFromForm(lineIndex: Int, page: AccountFieldsBinding): AppSettings? {
        val previous = stores[lineIndex].loadSettings()
        val serverFilled = page.serverField.text.toString().isNotBlank()
        val usernameFilled = page.usernameField.text.toString().isNotBlank()

        val required = lineIndex == 0 || serverFilled || usernameFilled
        if (required && (!serverFilled || !usernameFilled)) {
            Toast.makeText(this, getString(R.string.account_validation_error, lineIndex + 1), Toast.LENGTH_LONG).show()
            binding.accountTabs.getTabAt(lineIndex)?.select()
            return null
        }

        if (!serverFilled && !usernameFilled) {
            // Unused line - a clean, empty AppSettings rather than leftover display name/port/
            // directory URL for a line nobody actually wants to connect.
            return AppSettings()
        }

        return previous.copy(
            sipServer = page.serverField.text.toString().trim(),
            sipPort = page.portField.text.toString().toIntOrNull() ?: 5060,
            transport = transports.getOrElse(page.transportSpinner.selectedItemPosition) { SipTransport.UDP },
            username = page.usernameField.text.toString().trim(),
            password = page.passwordField.text.toString(),
            displayName = page.displayNameField.text.toString().trim(),
            outboundProxy = page.outboundProxyField.text.toString().trim(),
            directorySyncUrl = page.directorySyncUrlField.text.toString().trim(),
            directorySyncEnabled = page.directorySyncEnabledCheck.isChecked,
            directorySyncIntervalMinutes = page.directorySyncIntervalField.text.toString().toIntOrNull() ?: 60,
            startOnBoot = page.startOnBootCheck.isChecked,
            firstRunCompleted = true,
            ariEnabled = page.ariEnabledCheck.isChecked,
            ariPort = page.ariPortField.text.toString().toIntOrNull() ?: 8089,
            ariUsername = page.ariUsernameField.text.toString().trim(),
            ariPassword = page.ariPasswordField.text.toString(),
            doNotDisturbEnabled = page.dndEnabledCheck.isChecked,
            nightModeEnabled = page.nightModeEnabledCheck.isChecked,
            nightModeStartMinutes = nightStartMinutes[lineIndex],
            nightModeEndMinutes = nightEndMinutes[lineIndex]
        )
    }

    private fun saveAll() {
        val results = arrayOfNulls<AppSettings>(SipManager.LINE_COUNT)
        for (i in 0 until SipManager.LINE_COUNT) {
            results[i] = readFromForm(i, pages[i]) ?: return
        }

        val sipManager = catApp().sipManager
        var anyLineConfigured = false
        for (i in 0 until SipManager.LINE_COUNT) {
            val settings = results[i] ?: continue
            stores[i].saveSettings(settings)
            sipManager.applyAccountSettings(i, settings)
            catApp().ariClients[i].updateConfig(settings)
            if (settings.sipServer.isNotBlank() && settings.username.isNotBlank()) anyLineConfigured = true
        }
        if (anyLineConfigured) {
            // Covers the first-ever save (app just installed, keep-alive never started yet) -
            // see CallForegroundService's class doc comment. A no-op if it's already running.
            CallForegroundService.startKeepAlive(this)
        }
        Toast.makeText(this, getString(R.string.save), Toast.LENGTH_SHORT).show()
    }

    private fun runDirectorySync(lineIndex: Int, page: AccountFieldsBinding) {
        val url = page.directorySyncUrlField.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, R.string.directory_sync_url, Toast.LENGTH_SHORT).show()
            return
        }
        thread {
            try {
                val contacts = DirectoryImportService.fetchAndParse(url)
                runOnUiThread {
                    catApp().contactRepositories[lineIndex].mergeDirectoryContacts(contacts)
                    Toast.makeText(this, "${contacts.size}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, e.message ?: "Sync failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
