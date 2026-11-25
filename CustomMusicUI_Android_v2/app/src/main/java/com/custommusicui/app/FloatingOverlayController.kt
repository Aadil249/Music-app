package com.custommusicui.app

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView

class FloatingOverlayController(private val service: MusicPlaybackService) {

    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private lateinit var layoutParams: WindowManager.LayoutParams

    private var imgArt: ImageView? = null
    private var txtDspPill: TextView? = null
    private var txtTitle: TextView? = null
    private var txtArtist: TextView? = null
    private var seekBar: SeekBar? = null
    private var txtElapsed: TextView? = null
    private var txtTotal: TextView? = null
    private var btnPlayPause: ImageButton? = null

    private var isUserSeeking = false
    private var currentDurationMs = 0L

    val isShowing: Boolean
        get() = overlayView != null

    fun show(initialState: TrackState): Boolean {
        if (overlayView != null) return true
        if (!Settings.canDrawOverlays(service)) return false

        val themed = ContextThemeWrapper(service, R.style.Theme_CustomMusicUI)
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_music_widget, null)
        overlayView = view

        imgArt = view.findViewById(R.id.imgAlbumArt)
        txtDspPill = view.findViewById(R.id.txtDspPill)
        txtTitle = view.findViewById(R.id.txtSongTitle)
        txtArtist = view.findViewById(R.id.txtArtistName)
        seekBar = view.findViewById(R.id.seekProgress)
        txtElapsed = view.findViewById(R.id.txtElapsed)
        txtTotal = view.findViewById(R.id.txtTotal)
        btnPlayPause = view.findViewById(R.id.btnPlayPause)

        val density = service.resources.displayMetrics.density
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        layoutParams = WindowManager.LayoutParams(
            (336 * density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (14 * density).toInt()
            y = (110 * density).toInt()
        }

        setupDrag(view)
        setupControls(view)
        windowManager.addView(view, layoutParams)
        updateState(initialState)
        return true
    }

    fun hide() {
        overlayView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
    }

    private fun setupDrag(root: View) {
        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f
        root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = layoutParams.x
                    initY = layoutParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initX + (event.rawX - touchX).toInt()
                    layoutParams.y = initY + (event.rawY - touchY).toInt()
                    try { windowManager.updateViewLayout(root, layoutParams) } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }
    }

    private fun setupControls(view: View) {
        view.findViewById<ImageButton>(R.id.btnPrev).setOnClickListener { service.previousTrack() }
        view.findViewById<ImageButton>(R.id.btnPlayPause).setOnClickListener { service.togglePlayPause() }
        view.findViewById<ImageButton>(R.id.btnNext).setOnClickListener { service.nextTrack() }
        view.findViewById<ImageButton>(R.id.btnClose).setOnClickListener { hide() }

        txtDspPill?.setOnClickListener {
            val nextPreset = (service.prefs.eqPresetIndex + 1) % HiFiAudioEngine.PRESETS.size
            service.hiFiEngine.selectPreset(nextPreset)
            updateDspBadge()
        }

        seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && currentDurationMs > 0) {
                    txtElapsed?.text = formatTime((currentDurationMs * progress) / 1000L)
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                isUserSeeking = false
                if (currentDurationMs > 0 && sb != null) {
                    service.seekToMs((currentDurationMs * sb.progress) / 1000L)
                }
            }
        })
    }

    fun updateState(state: TrackState) {
        if (overlayView == null) return
        currentDurationMs = state.durationMs
        txtTitle?.text = state.title
        txtArtist?.text = state.artist
        btnPlayPause?.setImageResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        if (state.artworkBitmap != null) {
            imgArt?.setImageBitmap(state.artworkBitmap)
        } else {
            imgArt?.setImageResource(R.drawable.album_placeholder)
        }
        if (!isUserSeeking) {
            val permille = if (state.durationMs > 0) {
                ((state.positionMs * 1000L) / state.durationMs).toInt().coerceIn(0, 1000)
            } else 0
            seekBar?.progress = permille
            txtElapsed?.text = formatTime(state.positionMs)
            txtTotal?.text = formatTime(state.durationMs)
        }
        updateDspBadge()
    }

    private fun updateDspBadge() {
        val presetName = HiFiAudioEngine.PRESETS.getOrNull(service.prefs.eqPresetIndex)?.name ?: "Hi-Fi"
        val prefix = if (service.prefs.dspEnabled) "HI-FI • " else "RAW • "
        txtDspPill?.text = "$prefix$presetName"
    }

    private fun formatTime(ms: Long): String {
        val sec = (ms / 1000L).coerceAtLeast(0L)
        return String.format("%d:%02d", sec / 60L, sec % 60L)
    }
}
