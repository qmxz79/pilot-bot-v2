package com.qmxz.pilotbotv2

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.qmxz.pilotbotv2.diagnostics.CrashLog
import com.qmxz.pilotbotv2.safety.DriveScene
import com.qmxz.pilotbotv2.ui.TranscriptAdapter
import com.qmxz.pilotbotv2.ui.WaveformView
import kotlinx.coroutines.launch

/**
 * Thin Activity, by design (v1's 1500-line god activity is the anti-pattern).
 *
 * Responsibilities ONLY:
 * 1. Install the crash logger, inflate the layout, build the ViewModel.
 * 2. Wire buttons -> ViewModel calls (mic, interrupt, scenes, send, settings).
 * 3. Render ViewModel state (transcript rows, waveform, status lines).
 * 4. Own the runtime-permission request and the settings dialog.
 * 5. Converge lifecycles: onPause stops speech, onDestroy releases everything.
 *
 * Everything else (voice pipeline, LLM, memory, persona, nav, health) lives
 * in its own module. If this file ever approaches 400 lines, something has
 * leaked back into the Activity and must move out.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: CopilotViewModel
    private lateinit var transcriptAdapter: TranscriptAdapter
    private lateinit var transcriptScroll: ScrollView
    private lateinit var healthText: TextView
    private lateinit var navText: TextView
    private lateinit var speedText: TextView
    private lateinit var waveform: WaveformView
    private lateinit var micButton: MaterialButton
    private lateinit var interruptButton: Button
    private lateinit var textInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(applicationContext)
        setContentView(R.layout.activity_main)

        viewModel = ViewModelProvider(
            this,
            CopilotViewModelFactory(application)
        )[CopilotViewModel::class.java]

        healthText = findViewById(R.id.health_text)
        navText = findViewById(R.id.nav_text)
        speedText = findViewById(R.id.speed_text)
        waveform = findViewById(R.id.waveform)
        micButton = findViewById(R.id.btn_mic)
        textInput = findViewById(R.id.et_text_input)
        transcriptScroll = findViewById(R.id.transcript_scroll)
        transcriptAdapter = TranscriptAdapter(findViewById(R.id.transcript_container))

        micButton.setOnClickListener { viewModel.onMicPressed() }
        interruptButton = findViewById(R.id.btn_interrupt)
        interruptButton.setOnClickListener {
            viewModel.onInterrupt()
        }
        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            showSettingsDialog()
        }
        findViewById<Button>(R.id.btn_send).setOnClickListener {
            val text = textInput.text.toString().trim()
            if (text.isNotEmpty()) {
                viewModel.onTextInput(text)
                textInput.text.clear()
            }
        }
        findViewById<Button>(R.id.btn_scene_jam).setOnClickListener {
            viewModel.onSceneSelected(DriveScene.JAM)
        }
        findViewById<Button>(R.id.btn_scene_night).setOnClickListener {
            viewModel.onSceneSelected(DriveScene.NIGHT)
        }
        findViewById<Button>(R.id.btn_scene_fatigue).setOnClickListener {
            viewModel.onSceneSelected(DriveScene.FATIGUE)
        }
        findViewById<Button>(R.id.btn_scene_cruise).setOnClickListener {
            viewModel.onSceneSelected(DriveScene.CRUISE)
        }

        requestPermissionsIfNeeded()
        collectFlows()
    }

    private fun collectFlows() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.transcriptEvents.collect { item ->
                        transcriptAdapter.add(item)
                        transcriptScroll.post {
                            transcriptScroll.fullScroll(View.FOCUS_DOWN)
                        }
                    }
                }
                launch {
                    viewModel.uiStatus.collect { status ->
                        renderStatus(status)
                    }
                }
            }
        }
    }

    private fun renderStatus(status: UiStatus) {
        waveform.setSpeaking(status.speaking)
        waveform.setListening(status.listening)
        speedText.text =
            "车速：${status.speedKmh.toInt()} km/h · ${status.scene.displayName}"
        healthText.text = status.healthSummary
        navText.text = status.navSummary
        // Buttons reflect capability, not just state: on a phone without a
        // recognition service the mic button must not invite a tap that can
        // only fail again, and interrupt is meaningless when nothing plays.
        interruptButton.isEnabled = status.speaking || status.listening
        micButton.isEnabled = status.asrAvailable
        micButton.text = when {
            !status.asrAvailable -> getString(R.string.mic_unavailable)
            status.listening -> getString(R.string.mic_listening)
            status.speaking -> getString(R.string.mic_speaking)
            else -> getString(R.string.mic_start)
        }
    }

    private fun requestPermissionsIfNeeded() {
        val needed = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        ).filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) !=
                PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                needed.toTypedArray(),
                REQUEST_PERMISSIONS
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            // Re-run the self-check so the health bar (and voice summary)
            // immediately reflects the granted/denied state.
            if (::viewModel.isInitialized) {
                viewModel.refreshHealth()
            }
        }
    }

    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val etBaseUrl = dialogView.findViewById<EditText>(R.id.et_base_url)
        val etApiKey = dialogView.findViewById<EditText>(R.id.et_api_key)
        val etModel = dialogView.findViewById<EditText>(R.id.et_model)
        val rgPersona = dialogView.findViewById<RadioGroup>(R.id.rg_persona)
        etBaseUrl.setText(viewModel.settings.baseUrl)
        etApiKey.setText(viewModel.settings.apiKey)
        etModel.setText(viewModel.settings.model)
        rgPersona.check(
            when (viewModel.settings.personaId) {
                "steady" -> R.id.rb_persona_steady
                "witty" -> R.id.rb_persona_witty
                else -> R.id.rb_persona_warm
            }
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(dialogView)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                viewModel.settings.baseUrl = etBaseUrl.text.toString()
                viewModel.settings.apiKey = etApiKey.text.toString()
                viewModel.settings.model = etModel.text.toString()
                val personaId = when (rgPersona.checkedRadioButtonId) {
                    R.id.rb_persona_steady -> "steady"
                    R.id.rb_persona_witty -> "witty"
                    else -> "warm"
                }
                viewModel.updatePersona(personaId)
                viewModel.refreshHealth()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    override fun onPause() {
        // Never keep talking or listening while backgrounded: the permission
        // dialog itself pauses the Activity, and a companion that speaks to
        // an empty car is a bug, not a feature.
        if (::viewModel.isInitialized) {
            viewModel.onPaused()
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (::viewModel.isInitialized) {
            viewModel.release()
        }
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 1001
    }
}
