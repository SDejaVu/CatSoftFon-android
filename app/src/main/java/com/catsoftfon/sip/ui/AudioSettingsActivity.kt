package com.catsoftfon.sip.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.catsoftfon.sip.data.JsonStore
import com.catsoftfon.sip.databinding.ActivityAudioSettingsBinding

class AudioSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAudioSettingsBinding
    private lateinit var store: JsonStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAudioSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = JsonStore(this)

        val settings = store.loadSettings()
        binding.preferBluetoothCheck.isChecked = settings.preferBluetooth

        binding.backButton.setOnClickListener { finish() }

        binding.saveAudioSettingsButton.setOnClickListener {
            val current = store.loadSettings()
            current.preferBluetooth = binding.preferBluetoothCheck.isChecked
            store.saveSettings(current)
            finish()
        }
    }
}
