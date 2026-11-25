package com.custommusicui.app

import android.graphics.Bitmap

data class TrackState(
    val title: String = "Open YouTube Music",
    val artist: String = "Sign in once for your personal mixes",
    val artworkUrl: String = "",
    val artworkBitmap: Bitmap? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isLiked: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val sleepTimerRemainingSec: Int = 0
)
