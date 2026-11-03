package com.qmxz.pilotbotv2

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
import com.qmxz.pilotbotv2.service.CompanionService
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
 * 5. Converge lifecycles: onPause stops speech (unless the companion
 *    service is keeping voice alive during Amap navigation), onDestroy
 *    releases everything.
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
    private lateinit var routeText: TextView
    private lateinit var speedText: TextView
    private lateinit var partialText: TextView
    private lateinit var waveform: WaveformView
    private lateinit var micButton: MaterialButton
    private lateinit var interruptButton: Button
    private lateinit var textInput: EditText

    /** The companion service ended itself; clear the ViewModel's keep-alive flag. */
    private val companionStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (::viewModel.isInitialized) {
                viewModel.onCompanionStopped()
            }
        }
    }

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
        routeText = findViewById(R.id.route_text)
        speedText = findViewById(R.id.speed_text)
        partialText = findViewById(R.id.partial_text)
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
        findViewById<Button>(R.id.btn_nav).setOnClickListener {
            showNavigationDialog()
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

        val companionFilter = IntentFilter(CompanionService.ACTION_COMPANION_STOPPED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                companionStoppedReceiver,
                companionFilter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            registerReceiver(companionStoppedReceiver, companionFilter)
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
        routeText.text = status.routeSummary
        // Buttons reflect capability, not just state: on a phone without a
        // recognition service the mic button must not invite a tap that can
        // only fail again, and interrupt is meaningless when nothing plays.
        interruptButton.isEnabled = status.speaking || status.listening
        micButton.isEnabled = status.asrAvailable
        if (status.partial.isEmpty()) {
            partialText.visibility = View.GONE
        } else {
            partialText.visibility = View.VISIBLE
            partialText.text = getString(R.string.partial_prefix) + status.partial
        }
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
            // immediately reflects the granted/denied state, and restart
            // location so a freshly granted permission takes effect
            // without an app restart.
            if (::viewModel.isInitialized) {
                viewModel.refreshLocation()
                viewModel.refreshHealth()
            }
        }
    }

    /**
     * Destination dialog. One field, three actions: jump to Amap
     * navigation for the typed place, end background companionship, or
     * cancel. Kept here (not in a layout file) because it is a single
     * input, like the text fallback.
     */
    private fun showNavigationDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.nav_dialog_hint)
            textSize = 16f
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.nav_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.nav_start) { _, _ ->
                val destination = input.text.toString().trim()
                if (destination.isNotEmpty()) {
                    viewModel.startAmapNavigation(destination)
                }
            }
            .setNeutralButton(R.string.nav_end) { _, _ ->
                viewModel.stopCompanion()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /**
     * Crash-log viewer. The log file is private to the app and nothing
     * uploads automatically: sharing only happens when the driver taps
     * 分享, and 清空 deletes the file (a later crash starts a fresh one).
     */
    private fun showCrashLogDialog() {
        val logText = TextView(this).apply {
            text = CrashLog.readRecent(this@MainActivity)
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(32, 32, 32, 32)
        }
        val scroll = ScrollView(this).apply {
            addView(logText)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.crash_log_title)
            .setView(scroll)
            .setPositiveButton(R.string.crash_log_share) { _, _ ->
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(
                        Intent.EXTRA_SUBJECT,
                        getString(R.string.crash_log_share_subject)
                    )
                    putExtra(Intent.EXTRA_TEXT, logText.text.toString())
                }
                startActivity(
                    Intent.createChooser(share, getString(R.string.crash_log_share))
                )
            }
            .setNeutralButton(R.string.crash_log_clear) { _, _ ->
                CrashLog.clear(this)
                Toast.makeText(
                    this,
                    R.string.crash_log_cleared,
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(R.string.crash_log_close, null)
            .show()
    }

    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val etBaseUrl = dialogView.findViewById<EditText>(R.id.et_base_url)
        val etApiKey = dialogView.findViewById<EditText>(R.id.et_api_key)
        val etModel = dialogView.findViewById<EditText>(R.id.et_model)
        val rgPersona = dialogView.findViewById<RadioGroup>(R.id.rg_persona)
        val rgVoiceEngine = dialogView.findViewById<RadioGroup>(R.id.rg_voice_engine)
        val etXfyunAppId = dialogView.findViewById<EditText>(R.id.et_xfyun_app_id)
        val etXfyunApiKey = dialogView.findViewById<EditText>(R.id.et_xfyun_api_key)
        val etXfyunApiSecret =
            dialogView.findViewById<EditText>(R.id.et_xfyun_api_secret)
        val etXfyunVoice = dialogView.findViewById<EditText>(R.id.et_xfyun_voice)
        etBaseUrl.setText(viewModel.settings.baseUrl)
        etApiKey.setText(viewModel.settings.apiKey)
        etModel.setText(viewModel.settings.model)
        rgVoiceEngine.check(
            if (viewModel.voiceSettings.engine == "xfyun") {
                R.id.rb_voice_xfyun
            } else {
                R.id.rb_voice_system
            }
        )
        etXfyunAppId.setText(viewModel.voiceSettings.xfyunAppId)
        etXfyunApiKey.setText(viewModel.voiceSettings.xfyunApiKey)
        etXfyunApiSecret.setText(viewModel.voiceSettings.xfyunApiSecret)
        etXfyunVoice.setText(viewModel.voiceSettings.xfyunVoice)
        // Diagnostics entry: opens the viewer without saving or closing
        // the settings form, so unsaved edits are not lost.
        dialogView.findViewById<Button>(R.id.btn_crash_log).setOnClickListener {
            showCrashLogDialog()
        }
        // Voiceprint enrollment, same pattern: saves immediately,
        // independent of Settings OK/Cancel.
        val tvVoiceprintStatus =
            dialogView.findViewById<TextView>(R.id.tv_voiceprint_status)
        val btnVoiceprintEnroll =
            dialogView.findViewById<Button>(R.id.btn_voiceprint_enroll)
        val btnVoiceprintClear =
            dialogView.findViewById<Button>(R.id.btn_voiceprint_clear)
        fun refreshVoiceprint() {
            tvVoiceprintStatus.text = if (viewModel.isVoiceprintEnrolled) {
                getString(R.string.voiceprint_status_on)
            } else {
                getString(R.string.voiceprint_status_off)
            }
            btnVoiceprintClear.isEnabled = viewModel.isVoiceprintEnrolled
        }
        refreshVoiceprint()
        btnVoiceprintEnroll.setOnClickListener {
            btnVoiceprintEnroll.isEnabled = false
            btnVoiceprintClear.isEnabled = false
            viewModel.startVoiceprintEnrollment(
                onProgress = { msg -> tvVoiceprintStatus.text = msg },
                onDone = { _, msg ->
                    tvVoiceprintStatus.text = msg
                    btnVoiceprintEnroll.isEnabled = true
                    refreshVoiceprint()
                }
            )
        }
        btnVoiceprintClear.setOnClickListener {
            viewModel.clearVoiceprint { msg ->
                tvVoiceprintStatus.text = msg
                refreshVoiceprint()
            }
        }
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
                viewModel.voiceSettings.engine =
                    if (rgVoiceEngine.checkedRadioButtonId == R.id.rb_voice_xfyun) {
                        "xfyun"
                    } else {
                        "system"
                    }
                viewModel.voiceSettings.xfyunAppId = etXfyunAppId.text.toString()
                viewModel.voiceSettings.xfyunApiKey = etXfyunApiKey.text.toString()
                viewModel.voiceSettings.xfyunApiSecret =
                    etXfyunApiSecret.text.toString()
                viewModel.voiceSettings.xfyunVoice = etXfyunVoice.text.toString()
                viewModel.onVoiceSettingsSaved()
                viewModel.refreshHealth()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // Re-arm auto scene switches (onPause disarmed them).
        if (::viewModel.isInitialized) {
            viewModel.onResumed()
        }
    }

    override fun onPause() {
        // Never keep talking or listening while backgrounded — except
        // during an Amap-nav session, where the companion service keeps
        // voice alive and the driver is still there (see onPaused).
        if (::viewModel.isInitialized) {
            viewModel.onPaused()
        }
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(companionStoppedReceiver)
        if (::viewModel.isInitialized) {
            viewModel.release()
        }
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_PERMISSIONS = 1001
    }
}
