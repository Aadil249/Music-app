package com.custommusicui.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity(), MusicPlaybackService.StateListener {

    private var service: MusicPlaybackService? = null
    private var isBound = false
    private lateinit var prefs: AppPrefs

    private lateinit var webViewHost: FrameLayout
    private lateinit var panelYtm: FrameLayout
    private lateinit var panelDsp: View
    private lateinit var panelLock: View

    private lateinit var switchDspMaster: SwitchCompat
    private lateinit var txtActivePreset: TextView
    private lateinit var seekMainSubBass: SeekBar
    private lateinit var seekMainStage: SeekBar
    private lateinit var eqSlidersContainer: LinearLayout

    private lateinit var switchLockScreen: SwitchCompat
    private lateinit var switchAdSkip: SwitchCompat
    private lateinit var btnOverlayToggle: Button
    private lateinit var txtNowPlayingBar: TextView

    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as MusicPlaybackService.LocalBinder).getService()
            service = s
            isBound = true
            attachWebViewFromService(s)
            s.addListener(this@MainActivity)
            syncDspPanel(s)
            updateOverlayButtonText()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = AppPrefs(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val serviceIntent = Intent(this, MusicPlaybackService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)

        bindViews()
        setupTabs()
        setupDspControls()
        setupLockAndOverlayControls()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = service?.webView
                if (panelYtm.visibility == View.VISIBLE && wv != null && wv.canGoBack()) {
                    wv.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, MusicPlaybackService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        // Keep WebView attached & timers running so Chromium never suspends audio when locked
        service?.ensurePlaybackAlive()
        if (isBound) {
            service?.removeListener(this)
            unbindService(connection)
            isBound = false
        }
    }

    override fun onDestroy() {
        // Safely detach WebView and reset context to applicationContext to prevent Activity leaks
        service?.webView?.let { wv ->
            (wv.context as? MutableContextWrapper)?.baseContext = applicationContext
            (wv.parent as? ViewGroup)?.removeView(wv)
        }
        webViewHost.removeAllViews()
        super.onDestroy()
    }

    private fun attachWebViewFromService(s: MusicPlaybackService) {
        val wv = s.webView
        (wv.context as? MutableContextWrapper)?.baseContext = this
        if (wv.parent !== webViewHost) {
            (wv.parent as? ViewGroup)?.removeView(wv)
            webViewHost.removeAllViews()
            webViewHost.addView(
                wv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        s.ensurePlaybackAlive()
    }

    private fun bindViews() {
        webViewHost = findViewById(R.id.webViewHost)
        panelYtm = findViewById(R.id.panelYtm)
        panelDsp = findViewById(R.id.panelDsp)
        panelLock = findViewById(R.id.panelLock)

        switchDspMaster = findViewById(R.id.switchDspMaster)
        txtActivePreset = findViewById(R.id.txtActivePreset)
        seekMainSubBass = findViewById(R.id.seekMainSubBass)
        seekMainStage = findViewById(R.id.seekMainStage)
        eqSlidersContainer = findViewById(R.id.eqSlidersContainer)

        switchLockScreen = findViewById(R.id.switchLockScreen)
        switchAdSkip = findViewById(R.id.switchAdSkip)
        btnOverlayToggle = findViewById(R.id.btnOverlayToggle)
        txtNowPlayingBar = findViewById(R.id.txtNowPlayingBar)
    }

    private fun setupTabs() {
        val btnYtm = findViewById<Button>(R.id.navYtm)
        val btnDsp = findViewById<Button>(R.id.navDsp)
        val btnLock = findViewById<Button>(R.id.navLock)

        fun selectTab(index: Int) {
            panelYtm.visibility = if (index == 0) View.VISIBLE else View.GONE
            panelDsp.visibility = if (index == 1) View.VISIBLE else View.GONE
            panelLock.visibility = if (index == 2) View.VISIBLE else View.GONE
        }

        btnYtm.setOnClickListener { selectTab(0) }
        btnDsp.setOnClickListener { selectTab(1) }
        btnLock.setOnClickListener { selectTab(2) }
        selectTab(0)
    }

    private fun setupDspControls() {
        switchDspMaster.isChecked = prefs.dspEnabled
        switchDspMaster.setOnCheckedChangeListener { _, isChecked ->
            service?.hiFiEngine?.setDspEnabled(isChecked)
        }

        val presetButtons = listOf(
            R.id.btnPreset0, R.id.btnPreset1, R.id.btnPreset2,
            R.id.btnPreset3, R.id.btnPreset4, R.id.btnPreset5
        )
        presetButtons.forEachIndexed { index, id ->
            findViewById<Button>(id).setOnClickListener {
                service?.hiFiEngine?.selectPreset(index)
                service?.let { syncDspPanel(it) }
            }
        }

        seekMainSubBass.progress = prefs.subBassLevel
        seekMainSubBass.setOnSeekBarChangeListener(simpleSeek {
            service?.hiFiEngine?.setSubBassLevel(it)
        })

        seekMainStage.progress = prefs.soundstageLevel
        seekMainStage.setOnSeekBarChangeListener(simpleSeek {
            service?.hiFiEngine?.setSoundstageLevel(it)
        })

        findViewById<Button>(R.id.btnMainSlowed).setOnClickListener { service?.setPlaybackSpeed(0.85f) }
        findViewById<Button>(R.id.btnMainNormal).setOnClickListener { service?.setPlaybackSpeed(1.0f) }
        findViewById<Button>(R.id.btnMainNightcore).setOnClickListener { service?.setPlaybackSpeed(1.18f) }
    }

    private fun syncDspPanel(s: MusicPlaybackService) {
        switchDspMaster.isChecked = prefs.dspEnabled
        txtActivePreset.text = HiFiAudioEngine.PRESETS.getOrNull(prefs.eqPresetIndex)?.name ?: "Harman Hi-Fi"
        seekMainSubBass.progress = prefs.subBassLevel
        seekMainStage.progress = prefs.soundstageLevel

        eqSlidersContainer.removeAllViews()
        val bands = s.hiFiEngine.getBands()
        val labels = arrayOf("31Hz", "62Hz", "125Hz", "250Hz", "500Hz", "1kHz", "2kHz", "4kHz", "8kHz", "16kHz")

        for (i in 0 until 10) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 6, 0, 6)
            }
            val lbl = TextView(this).apply {
                text = labels[i]
                setTextColor(0xCCFFFFFF.toInt())
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(120, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val dbTxt = TextView(this).apply {
                text = String.format("%+.1f dB", bands[i])
                setTextColor(0xFF93C5FD.toInt())
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(130, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val sb = SeekBar(this).apply {
                max = 200 // -10.0 to +10.0 dB
                progress = ((bands[i] + 10f) * 10f).toInt().coerceIn(0, 200)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnSeekBarChangeListener(simpleSeek { p ->
                    val gain = (p / 10f) - 10f
                    dbTxt.text = String.format("%+.1f dB", gain)
                    s.hiFiEngine.setBandGain(i, gain)
                })
            }
            row.addView(lbl)
            row.addView(sb)
            row.addView(dbTxt)
            eqSlidersContainer.addView(row)
        }
    }

    private fun setupLockAndOverlayControls() {
        switchLockScreen.isChecked = prefs.lockScreenEnabled
        switchLockScreen.setOnCheckedChangeListener { _, checked ->
            prefs.lockScreenEnabled = checked
        }

        switchAdSkip.isChecked = prefs.adSkipEnabled
        switchAdSkip.setOnCheckedChangeListener { _, checked ->
            prefs.adSkipEnabled = checked
            service?.webView?.evaluateJavascript(
                "window.__cmuiAdSkip = ${if (checked) "true" else "false"};",
                null
            )
        }

        findViewById<Button>(R.id.btnPreviewLockScreen).setOnClickListener {
            service?.launchLockScreenActivity()
        }

        findViewById<Button>(R.id.btnGrantOverlayPerm).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }

        findViewById<Button>(R.id.btnBatteryOpt).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }

        btnOverlayToggle.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Grant 'Display over other apps' first", Toast.LENGTH_SHORT).show()
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
                return@setOnClickListener
            }
            service?.toggleFloatingOverlay()
            updateOverlayButtonText()
        }
    }

    private fun updateOverlayButtonText() {
        val showing = service?.isOverlayShowing() == true
        btnOverlayToggle.text = if (showing) "Hide Floating Widget" else "Launch Floating Widget"
    }

    override fun onTrackStateChanged(state: TrackState) {
        val status = if (state.isPlaying) "Playing" else "Paused"
        txtNowPlayingBar.text = "$status • ${state.title} — ${state.artist}"
    }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }
}
