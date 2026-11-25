package com.custommusicui.app

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.MutableContextWrapper
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MusicPlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "custom_music_ui_playback"
        const val NOTIFICATION_ID = 2001
        const val ACTION_PLAY_PAUSE = "com.custommusicui.app.PLAY_PAUSE"
        const val ACTION_NEXT = "com.custommusicui.app.NEXT"
        const val ACTION_PREV = "com.custommusicui.app.PREV"
        const val ACTION_STOP = "com.custommusicui.app.STOP"
        const val ACTION_TOGGLE_OVERLAY = "com.custommusicui.app.TOGGLE_OVERLAY"
        const val YTM_HOME_URL = "https://music.youtube.com"

        // Modern mobile Chrome UA (no "; wv" token so Google Sign-In works cleanly)
        private const val CHROME_MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.6723.102 Mobile Safari/537.36"

        @Volatile
        var instance: MusicPlaybackService? = null
            private set
    }

    interface StateListener {
        fun onTrackStateChanged(state: TrackState)
    }

    inner class LocalBinder : Binder() {
        fun getService(): MusicPlaybackService = this@MusicPlaybackService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val listeners = LinkedHashSet<StateListener>()

    lateinit var prefs: AppPrefs
        private set
    lateinit var hiFiEngine: HiFiAudioEngine
        private set
    lateinit var webView: BackgroundWebView
        private set

    private var overlayController: FloatingOverlayController? = null
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    var currentState: TrackState = TrackState()
        private set

    private var lastArtworkUrl: String = ""
    private var cachedArtwork: Bitmap? = null
    private var sleepTimerSec: Int = 0

    private val sleepTimerRunnable = object : Runnable {
        override fun run() {
            if (sleepTimerSec > 0) {
                sleepTimerSec--
                updateTrackState(currentState.copy(sleepTimerRemainingSec = sleepTimerSec))
                if (sleepTimerSec == 0) {
                    pause()
                } else {
                    mainHandler.postDelayed(this, 1000L)
                }
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) {
                val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                if (km.isKeyguardLocked && prefs.lockScreenEnabled && currentState.isPlaying) {
                    launchLockScreenActivity()
                }
            }
        }
    }

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            pause()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = AppPrefs(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        hiFiEngine = HiFiAudioEngine(prefs)
        hiFiEngine.init(0)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CustomMusicUI::PlaybackWakeLock")
        wakeLock?.setReferenceCounted(false)

        createNotificationChannel()
        initMediaSession()
        initWebView()
        overlayController = FloatingOverlayController(this)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }

        startForegroundCompat(buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> togglePlayPause()
            ACTION_NEXT -> nextTrack()
            ACTION_PREV -> previousTrack()
            ACTION_TOGGLE_OVERLAY -> toggleFloatingOverlay()
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        mainHandler.removeCallbacks(sleepTimerRunnable)
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        overlayController?.hide()
        releaseAudioFocus()
        if (wakeLock?.isHeld == true) {
            try { wakeLock?.release() } catch (_: Exception) {}
        }
        hiFiEngine.release()
        mediaSession.isActive = false
        mediaSession.release()

        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        webView = BackgroundWebView(MutableContextWrapper(applicationContext))

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = CHROME_MOBILE_UA
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportMultipleWindows(false)
        }

        webView.addJavascriptInterface(JsBridge(), "AndroidMusicBridge")

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (prefs.adSkipEnabled && YtmScripts.isAdUrl(url)) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()
                if (url != null && url.contains("music.youtube.com")) {
                    view?.evaluateJavascript(YtmScripts.getBridgeScript(prefs.adSkipEnabled), null)
                    setPlaybackSpeed(prefs.playbackSpeed)
                }
            }
        }

        webView.loadUrl(YTM_HOME_URL)
    }

    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, "CustomMusicUISession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { play() }
                override fun onPause() { pause() }
                override fun onSkipToNext() { nextTrack() }
                override fun onSkipToPrevious() { previousTrack() }
                override fun onSeekTo(pos: Long) { seekToMs(pos) }
            })
            isActive = true
        }
    }

    inner class JsBridge {
        @JavascriptInterface
        fun onStateUpdate(
            title: String,
            artist: String,
            artworkUrl: String,
            isPlaying: Boolean,
            positionMs: Long,
            durationMs: Long,
            isLiked: Boolean,
            rate: Float
        ) {
            mainHandler.post {
                if (isPlaying) {
                    requestAudioFocus()
                    if (wakeLock?.isHeld != true) {
                        try { wakeLock?.acquire(6 * 60 * 60 * 1000L) } catch (_: Exception) {}
                    }
                } else {
                    if (wakeLock?.isHeld == true) {
                        try { wakeLock?.release() } catch (_: Exception) {}
                    }
                }

                if (artworkUrl.isNotBlank() && artworkUrl != lastArtworkUrl) {
                    lastArtworkUrl = artworkUrl
                    fetchArtworkAsync(artworkUrl)
                }

                val updated = currentState.copy(
                    title = title,
                    artist = artist.ifBlank { "YouTube Music" },
                    artworkUrl = artworkUrl,
                    artworkBitmap = cachedArtwork,
                    isPlaying = isPlaying,
                    positionMs = positionMs.coerceAtLeast(0L),
                    durationMs = durationMs.coerceAtLeast(0L),
                    isLiked = isLiked,
                    playbackSpeed = rate,
                    sleepTimerRemainingSec = sleepTimerSec
                )
                updateTrackState(updated)
            }
        }
    }

    private fun fetchArtworkAsync(urlStr: String) {
        ioExecutor.execute {
            try {
                // Upgrade low-res thumbnail URLs to sharper square cover art
                val hiRes = urlStr.replace(Regex("=w\\d+-h\\d+"), "=w544-h544")
                val conn = URL(hiRes).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.inputStream.use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream)
                    if (bmp != null) {
                        cachedArtwork = bmp
                        mainHandler.post {
                            updateTrackState(currentState.copy(artworkBitmap = bmp))
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun updateTrackState(state: TrackState) {
        val playbackChanged = state.isPlaying != currentState.isPlaying ||
            state.title != currentState.title ||
            state.artworkBitmap !== currentState.artworkBitmap

        currentState = state

        updateMediaSessionState(state)
        if (playbackChanged) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification())
        }

        overlayController?.updateState(state)
        listeners.forEach { it.onTrackStateChanged(state) }
    }

    private fun updateMediaSessionState(state: TrackState) {
        val pbState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SEEK_TO
            )
            .setState(
                if (state.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                state.positionMs,
                state.playbackSpeed
            )
            .build()
        mediaSession.setPlaybackState(pbState)

        val meta = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, state.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, state.artist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.durationMs)
            .apply {
                state.artworkBitmap?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
            }
            .build()
        mediaSession.setMetadata(meta)
    }

    fun addListener(listener: StateListener) {
        listeners.add(listener)
        listener.onTrackStateChanged(currentState)
    }

    fun removeListener(listener: StateListener) {
        listeners.remove(listener)
    }

    fun ensurePlaybackAlive() {
        mainHandler.post {
            try {
                webView.onResume()
                webView.resumeTimers()
            } catch (_: Exception) {}
        }
    }

    fun togglePlayPause() {
        runJs("window.__cmuiControl && window.__cmuiControl.playPause();")
    }

    fun play() {
        runJs("window.__cmuiControl && window.__cmuiControl.play();")
    }

    fun pause() {
        runJs("window.__cmuiControl && window.__cmuiControl.pause();")
    }

    fun nextTrack() {
        runJs("window.__cmuiControl && window.__cmuiControl.next();")
    }

    fun previousTrack() {
        runJs("window.__cmuiControl && window.__cmuiControl.prev();")
    }

    fun seekToMs(ms: Long) {
        val sec = (ms / 1000.0).coerceAtLeast(0.0)
        runJs("window.__cmuiControl && window.__cmuiControl.seekToSec($sec);")
    }

    fun setPlaybackSpeed(speed: Float) {
        val safe = speed.coerceIn(0.5f, 1.75f)
        prefs.playbackSpeed = safe
        runJs("window.__cmuiControl && window.__cmuiControl.setRate($safe);")
    }

    fun toggleLike() {
        runJs("window.__cmuiControl && window.__cmuiControl.toggleLike();")
    }

    fun setSleepTimerMinutes(minutes: Int) {
        mainHandler.removeCallbacks(sleepTimerRunnable)
        sleepTimerSec = (minutes * 60).coerceAtLeast(0)
        updateTrackState(currentState.copy(sleepTimerRemainingSec = sleepTimerSec))
        if (sleepTimerSec > 0) {
            mainHandler.postDelayed(sleepTimerRunnable, 1000L)
        }
    }

    fun toggleFloatingOverlay(): Boolean {
        val ctrl = overlayController ?: return false
        return if (ctrl.isShowing) {
            ctrl.hide()
            false
        } else {
            ctrl.show(currentState)
        }
    }

    fun isOverlayShowing(): Boolean = overlayController?.isShowing == true

    fun launchLockScreenActivity() {
        val intent = Intent(this, LockScreenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    private fun runJs(script: String) {
        mainHandler.post {
            try {
                webView.evaluateJavascript(script, null)
            } catch (_: Exception) {
            }
        }
    }

    private fun requestAudioFocus() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(audioFocusListener)
            .build()
        focusRequest = req
        audioManager.requestAudioFocus(req)
    }

    private fun releaseAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "CustomMusicUI Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Seamless YouTube Music playback and lock screen controls"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val prevIntent = servicePendingIntent(ACTION_PREV, 1)
        val playPauseIntent = servicePendingIntent(ACTION_PLAY_PAUSE, 2)
        val nextIntent = servicePendingIntent(ACTION_NEXT, 3)

        val playIcon = if (currentState.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        val playTitle = if (currentState.isPlaying) "Pause" else "Play"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(currentState.title)
            .setContentText(currentState.artist)
            .setLargeIcon(currentState.artworkBitmap)
            .setContentIntent(openIntent)
            .setOngoing(currentState.isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_prev, "Previous", prevIntent)
            .addAction(playIcon, playTitle, playPauseIntent)
            .addAction(R.drawable.ic_next, "Next", nextIntent)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun servicePendingIntent(action: String, reqCode: Int): PendingIntent {
        val intent = Intent(this, MusicPlaybackService::class.java).apply { this.action = action }
        return PendingIntent.getService(this, reqCode, intent, PendingIntent.FLAG_IMMUTABLE)
    }
}
