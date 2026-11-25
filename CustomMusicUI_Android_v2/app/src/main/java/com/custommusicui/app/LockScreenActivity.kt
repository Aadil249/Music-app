package com.custommusicui.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class LockScreenActivity : AppCompatActivity(), MusicPlaybackService.StateListener {

    private var service: MusicPlaybackService? = null
    private var isBound = false
    private lateinit var prefs: AppPrefs

    private lateinit var imgBackdrop: ImageView
    private lateinit var viewBackdropScrim: View
    private lateinit var txtClock: TextView
    private lateinit var txtDate: TextView
    private lateinit var txtTimerBadge: TextView

    private lateinit var cardGlassWidget: LinearLayout
    private lateinit var cardAlbumContainer: CardView
    private lateinit var imgLockArt: ImageView
    private lateinit var txtLockTitle: TextView
    private lateinit var txtLockArtist: TextView
    private lateinit var seekLockProgress: SeekBar
    private lateinit var txtLockElapsed: TextView
    private lateinit var txtLockTotal: TextView
    private lateinit var btnLockLike: ImageButton
    private lateinit var btnLockPlayPause: ImageButton
    private lateinit var btnLockAod: ImageButton

    private lateinit var drawerContainer: LinearLayout
    private lateinit var drawerEq: LinearLayout
    private lateinit var drawerVibe: LinearLayout
    private lateinit var drawerTheme: LinearLayout
    private lateinit var drawerTimer: LinearLayout

    private lateinit var btnEqToggle: Button
    private lateinit var txtEqPresetLabel: TextView
    private lateinit var seekLockSubBass: SeekBar
    private lateinit var seekLockStage: SeekBar

    private var activeDrawer: Int = -1
    private var isAodMode: Boolean = false
    private var isUserSeeking: Boolean = false
    private var currentDurationMs: Long = 0L
    private lateinit var gestureDetector: GestureDetector

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTicker = object : Runnable {
        override fun run() {
            updateClockText()
            clockHandler.postDelayed(this, 1000L)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as MusicPlaybackService.LocalBinder).getService()
            service = s
            isBound = true
            s.addListener(this@LockScreenActivity)
            syncDspDrawerUI()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        setContentView(R.layout.activity_lock_screen)
        prefs = AppPrefs(this)

        bindViews()
        setupInteractions()
        applyClockStyle()
        applyTheme()
        applyWidgetLayout()
        updateClockText()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::gestureDetector.isInitialized) {
            val cardRect = Rect()
            val seekRect = Rect()
            cardGlassWidget.getGlobalVisibleRect(cardRect)
            seekLockProgress.getGlobalVisibleRect(seekRect)
            val x = ev.rawX.toInt()
            val y = ev.rawY.toInt()
            if (cardRect.contains(x, y) && !seekRect.contains(x, y)) {
                gestureDetector.onTouchEvent(ev)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onStart() {
        super.onStart()
        clockHandler.post(clockTicker)
        val intent = Intent(this, MusicPlaybackService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        clockHandler.removeCallbacks(clockTicker)
        if (isBound) {
            service?.removeListener(this)
            unbindService(connection)
            isBound = false
        }
    }

    private fun bindViews() {
        imgBackdrop = findViewById(R.id.imgBackdrop)
        viewBackdropScrim = findViewById(R.id.viewBackdropScrim)
        txtClock = findViewById(R.id.txtClock)
        txtDate = findViewById(R.id.txtDate)
        txtTimerBadge = findViewById(R.id.txtTimerBadge)

        cardGlassWidget = findViewById(R.id.cardGlassWidget)
        cardAlbumContainer = findViewById(R.id.cardAlbumContainer)
        imgLockArt = findViewById(R.id.imgLockArt)
        txtLockTitle = findViewById(R.id.txtLockTitle)
        txtLockArtist = findViewById(R.id.txtLockArtist)
        seekLockProgress = findViewById(R.id.seekLockProgress)
        txtLockElapsed = findViewById(R.id.txtLockElapsed)
        txtLockTotal = findViewById(R.id.txtLockTotal)
        btnLockLike = findViewById(R.id.btnLockLike)
        btnLockPlayPause = findViewById(R.id.btnLockPlayPause)
        btnLockAod = findViewById(R.id.btnLockAod)

        drawerContainer = findViewById(R.id.drawerContainer)
        drawerEq = findViewById(R.id.drawerEq)
        drawerVibe = findViewById(R.id.drawerVibe)
        drawerTheme = findViewById(R.id.drawerTheme)
        drawerTimer = findViewById(R.id.drawerTimer)

        btnEqToggle = findViewById(R.id.btnEqToggle)
        txtEqPresetLabel = findViewById(R.id.txtEqPresetLabel)
        seekLockSubBass = findViewById(R.id.seekLockSubBass)
        seekLockStage = findViewById(R.id.seekLockStage)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            imgBackdrop.setRenderEffect(
                RenderEffect.createBlurEffect(42f, 42f, Shader.TileMode.CLAMP)
            )
        }
    }

    private fun setupInteractions() {
        // Tap clock to cycle clock style
        txtClock.setOnClickListener {
            prefs.lockClockStyle = (prefs.lockClockStyle + 1) % 4
            applyClockStyle()
        }

        // Layout switcher button (Max Glass -> Big Cover -> Compact Pill)
        findViewById<ImageButton>(R.id.btnLockLayout).setOnClickListener {
            prefs.lockWidgetLayout = (prefs.lockWidgetLayout + 1) % 3
            applyWidgetLayout()
        }

        // AOD Pure AMOLED Black dim toggle
        btnLockAod.setOnClickListener {
            isAodMode = !isAodMode
            applyTheme()
        }

        // Transport controls
        findViewById<ImageButton>(R.id.btnLockPrev).setOnClickListener { service?.previousTrack() }
        btnLockPlayPause.setOnClickListener { service?.togglePlayPause() }
        findViewById<ImageButton>(R.id.btnLockNext).setOnClickListener { service?.nextTrack() }
        btnLockLike.setOnClickListener { service?.toggleLike() }

        // Dismiss lock screen widget cleanly
        findViewById<TextView>(R.id.txtDismissLock).setOnClickListener { finish() }

        // Swipe Left/Right on widget to skip tracks + Double-tap cover to like
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || isUserSeeking) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) > 110 && abs(dx) > abs(dy) * 1.5f && abs(vx) > 180) {
                    if (dx < 0) service?.nextTrack() else service?.previousTrack()
                    return true
                }
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val artRect = Rect()
                cardAlbumContainer.getGlobalVisibleRect(artRect)
                if (artRect.contains(e.rawX.toInt(), e.rawY.toInt())) {
                    service?.toggleLike()
                    return true
                }
                return false
            }
        })

        // Seekbar
        seekLockProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && currentDurationMs > 0) {
                    txtLockElapsed.text = formatTime((currentDurationMs * progress) / 1000L)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { isUserSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isUserSeeking = false
                if (currentDurationMs > 0 && sb != null) {
                    service?.seekToMs((currentDurationMs * sb.progress) / 1000L)
                }
            }
        })

        // Feature Drawer Tabs
        findViewById<Button>(R.id.btnTabEq).setOnClickListener { toggleDrawer(0) }
        findViewById<Button>(R.id.btnTabVibe).setOnClickListener { toggleDrawer(1) }
        findViewById<Button>(R.id.btnTabTheme).setOnClickListener { toggleDrawer(2) }
        findViewById<Button>(R.id.btnTabTimer).setOnClickListener { toggleDrawer(3) }

        // Hi-Fi EQ Drawer Controls
        btnEqToggle.setOnClickListener {
            val s = service ?: return@setOnClickListener
            s.hiFiEngine.setDspEnabled(!prefs.dspEnabled)
            syncDspDrawerUI()
        }
        findViewById<Button>(R.id.btnEqCyclePreset).setOnClickListener {
            val s = service ?: return@setOnClickListener
            val next = (prefs.eqPresetIndex + 1) % HiFiAudioEngine.PRESETS.size
            s.hiFiEngine.selectPreset(next)
            syncDspDrawerUI()
        }
        seekLockSubBass.setOnSeekBarChangeListener(simpleSeek { service?.hiFiEngine?.setSubBassLevel(it) })
        seekLockStage.setOnSeekBarChangeListener(simpleSeek { service?.hiFiEngine?.setSoundstageLevel(it) })

        // Vibe FX Speed Controls
        findViewById<Button>(R.id.btnSpeedSlowed).setOnClickListener { service?.setPlaybackSpeed(0.85f) }
        findViewById<Button>(R.id.btnSpeedNormal).setOnClickListener { service?.setPlaybackSpeed(1.0f) }
        findViewById<Button>(R.id.btnSpeedNightcore).setOnClickListener { service?.setPlaybackSpeed(1.18f) }

        // Theme Picker Controls
        findViewById<Button>(R.id.btnThemeBlur).setOnClickListener { setThemeIdx(0) }
        findViewById<Button>(R.id.btnThemeMesh).setOnClickListener { setThemeIdx(1) }
        findViewById<Button>(R.id.btnThemeAmoled).setOnClickListener { setThemeIdx(2) }
        findViewById<Button>(R.id.btnThemeAurora).setOnClickListener { setThemeIdx(3) }

        // Sleep Timer Controls
        findViewById<Button>(R.id.btnTimerOff).setOnClickListener { service?.setSleepTimerMinutes(0) }
        findViewById<Button>(R.id.btnTimer15).setOnClickListener { service?.setSleepTimerMinutes(15) }
        findViewById<Button>(R.id.btnTimer30).setOnClickListener { service?.setSleepTimerMinutes(30) }
        findViewById<Button>(R.id.btnTimer60).setOnClickListener { service?.setSleepTimerMinutes(60) }
    }

    private fun setThemeIdx(idx: Int) {
        isAodMode = false
        prefs.lockThemeIndex = idx
        applyTheme()
    }

    private fun toggleDrawer(idx: Int) {
        activeDrawer = if (activeDrawer == idx) -1 else idx
        drawerContainer.visibility = if (activeDrawer == -1) View.GONE else View.VISIBLE
        drawerEq.visibility = if (activeDrawer == 0) View.VISIBLE else View.GONE
        drawerVibe.visibility = if (activeDrawer == 1) View.VISIBLE else View.GONE
        drawerTheme.visibility = if (activeDrawer == 2) View.VISIBLE else View.GONE
        drawerTimer.visibility = if (activeDrawer == 3) View.VISIBLE else View.GONE
    }

    private fun syncDspDrawerUI() {
        btnEqToggle.text = if (prefs.dspEnabled) "HI-FI DSP: ON" else "RAW BYPASS"
        txtEqPresetLabel.text = HiFiAudioEngine.PRESETS.getOrNull(prefs.eqPresetIndex)?.name ?: "Harman Hi-Fi"
        seekLockSubBass.progress = prefs.subBassLevel
        seekLockStage.progress = prefs.soundstageLevel
    }

    private fun applyClockStyle() {
        when (prefs.lockClockStyle) {
            0 -> { // Modern Bold
                txtClock.typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
                txtClock.textSize = 68f
                txtClock.alpha = 0.96f
            }
            1 -> { // Thin Glass
                txtClock.typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
                txtClock.textSize = 72f
                txtClock.alpha = 0.88f
            }
            2 -> { // Editorial Serif
                txtClock.typeface = Typeface.SERIF
                txtClock.textSize = 64f
                txtClock.alpha = 0.95f
            }
            else -> { // Minimal Monospace
                txtClock.typeface = Typeface.MONOSPACE
                txtClock.textSize = 56f
                txtClock.alpha = 0.90f
            }
        }
    }

    private fun applyTheme() {
        if (isAodMode) {
            imgBackdrop.visibility = View.INVISIBLE
            viewBackdropScrim.setBackgroundColor(0xFF000000.toInt())
            cardGlassWidget.alpha = 0.72f
            return
        }
        cardGlassWidget.alpha = 1.0f
        when (prefs.lockThemeIndex) {
            0 -> { // Album Blur
                imgBackdrop.visibility = View.VISIBLE
                viewBackdropScrim.setBackgroundColor(0x8808080E.toInt())
            }
            1 -> { // Dynamic Mesh
                imgBackdrop.visibility = View.INVISIBLE
                viewBackdropScrim.setBackgroundResource(R.drawable.bg_lock_mesh)
            }
            2 -> { // AMOLED Pure Black
                imgBackdrop.visibility = View.INVISIBLE
                viewBackdropScrim.setBackgroundColor(0xFF000000.toInt())
            }
            else -> { // Aurora Gradient
                imgBackdrop.visibility = View.INVISIBLE
                viewBackdropScrim.setBackgroundResource(R.drawable.bg_lock_aurora)
            }
        }
    }

    private fun applyWidgetLayout() {
        val density = resources.displayMetrics.density
        val artLp = cardAlbumContainer.layoutParams
        when (prefs.lockWidgetLayout) {
            0 -> { // Max Glass Card
                artLp.width = (86 * density).toInt()
                artLp.height = (86 * density).toInt()
                seekLockProgress.visibility = View.VISIBLE
                findViewById<View>(R.id.rowLockTimes).visibility = View.VISIBLE
                findViewById<View>(R.id.rowLockFeatureTabs).visibility = View.VISIBLE
            }
            1 -> { // Big Cover
                artLp.width = (128 * density).toInt()
                artLp.height = (128 * density).toInt()
                seekLockProgress.visibility = View.VISIBLE
                findViewById<View>(R.id.rowLockTimes).visibility = View.VISIBLE
                findViewById<View>(R.id.rowLockFeatureTabs).visibility = View.VISIBLE
            }
            else -> { // Compact Pill
                artLp.width = (56 * density).toInt()
                artLp.height = (56 * density).toInt()
                seekLockProgress.visibility = View.GONE
                findViewById<View>(R.id.rowLockTimes).visibility = View.GONE
                findViewById<View>(R.id.rowLockFeatureTabs).visibility = View.GONE
                drawerContainer.visibility = View.GONE
                activeDrawer = -1
            }
        }
        cardAlbumContainer.layoutParams = artLp
    }

    private fun updateClockText() {
        val now = Date()
        txtClock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        txtDate.text = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(now)
    }

    override fun onTrackStateChanged(state: TrackState) {
        currentDurationMs = state.durationMs
        txtLockTitle.text = state.title
        txtLockArtist.text = state.artist
        btnLockPlayPause.setImageResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        btnLockLike.setImageResource(if (state.isLiked) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline)

        if (state.artworkBitmap != null) {
            imgLockArt.setImageBitmap(state.artworkBitmap)
            imgBackdrop.setImageBitmap(state.artworkBitmap)
        } else {
            imgLockArt.setImageResource(R.drawable.album_placeholder)
            imgBackdrop.setImageResource(R.drawable.album_placeholder)
        }

        if (!isUserSeeking) {
            val permille = if (state.durationMs > 0) {
                ((state.positionMs * 1000L) / state.durationMs).toInt().coerceIn(0, 1000)
            } else 0
            seekLockProgress.progress = permille
            txtLockElapsed.text = formatTime(state.positionMs)
            txtLockTotal.text = formatTime(state.durationMs)
        }

        if (state.sleepTimerRemainingSec > 0) {
            txtTimerBadge.visibility = View.VISIBLE
            txtTimerBadge.text = "Sleep ${state.sleepTimerRemainingSec / 60}:${String.format("%02d", state.sleepTimerRemainingSec % 60)}"
        } else {
            txtTimerBadge.visibility = View.GONE
        }
    }

    private fun formatTime(ms: Long): String {
        val sec = (ms / 1000L).coerceAtLeast(0L)
        return String.format("%d:%02d", sec / 60L, sec % 60L)
    }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }
}
