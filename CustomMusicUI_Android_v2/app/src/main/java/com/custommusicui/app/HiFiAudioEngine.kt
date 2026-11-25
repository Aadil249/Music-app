package com.custommusicui.app

import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.os.Build
import kotlin.math.max

/**
 * Native Android Hi-Fi DSP Engine.
 * Uses Android 9+ DynamicsProcessing (32-bit float 10-band EQ, Input Gain headroom compensation,
 * Multiband Compressor, and -0.5 dB Brickwall Limiter) + Virtualizer 3D Soundstage,
 * with automatic fallback to standard Equalizer + BassBoost + Virtualizer on Android 8 or OEMs
 * that restrict session 0 DynamicsProcessing.
 */
class HiFiAudioEngine(private val prefs: AppPrefs) {

    data class EqPreset(val name: String, val bands: FloatArray)

    companion object {
        val BAND_FREQUENCIES = floatArrayOf(
            31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f
        )

        val PRESETS = listOf(
            EqPreset("Harman Hi-Fi", floatArrayOf(3.5f, 4.0f, 2.0f, 0.0f, -0.5f, 0.5f, 2.0f, 3.0f, 2.5f, 2.0f)),
            EqPreset("TWS Earbuds Pro", floatArrayOf(4.5f, 3.5f, 1.0f, -1.0f, 0.5f, 1.5f, 2.5f, 3.5f, 4.0f, 3.0f)),
            EqPreset("Clean Sub-Bass", floatArrayOf(6.5f, 5.5f, 2.5f, -0.5f, 0.0f, 0.5f, 1.0f, 1.5f, 2.0f, 1.5f)),
            EqPreset("Vocal & Acoustic", floatArrayOf(-1.0f, 0.0f, 1.0f, 2.0f, 3.0f, 3.5f, 3.0f, 2.0f, 1.0f, 0.5f)),
            EqPreset("3D Concert Hall", floatArrayOf(2.5f, 2.0f, 1.0f, 0.5f, 1.0f, 1.5f, 2.5f, 3.5f, 4.5f, 4.0f)),
            EqPreset("Studio Flat", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
        )
    }

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var fallbackEq: Equalizer? = null
    private var fallbackBass: BassBoost? = null
    private var virtualizer: Virtualizer? = null

    var isDynamicsActive: Boolean = false
        private set

    private var currentBands: FloatArray = prefs.getCustomEqBands()

    fun init(audioSessionId: Int = 0) {
        release()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2,
                    true, 10,   // Pre-EQ 10 bands
                    true, 3,    // 3-band Multiband Compressor
                    false, 0,   // Post-EQ unused
                    true        // Brickwall Limiter
                ).build()

                val dp = DynamicsProcessing(0, audioSessionId, config)
                dynamicsProcessing = dp
                isDynamicsActive = true
            } catch (_: Throwable) {
                isDynamicsActive = false
                initFallback(audioSessionId)
            }
        } else {
            initFallback(audioSessionId)
        }

        try {
            virtualizer = Virtualizer(0, audioSessionId)
        } catch (_: Throwable) {
            virtualizer = null
        }

        applyAll()
    }

    private fun initFallback(audioSessionId: Int) {
        try {
            fallbackEq = Equalizer(0, audioSessionId)
        } catch (_: Throwable) {
            fallbackEq = null
        }
        try {
            fallbackBass = BassBoost(0, audioSessionId)
        } catch (_: Throwable) {
            fallbackBass = null
        }
    }

    fun selectPreset(index: Int) {
        val safeIdx = index.coerceIn(0, PRESETS.lastIndex)
        prefs.eqPresetIndex = safeIdx
        currentBands = PRESETS[safeIdx].bands.copyOf()
        prefs.setCustomEqBands(currentBands)
        applyAll()
    }

    fun setBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex !in 0..9) return
        currentBands[bandIndex] = gainDb.coerceIn(-10f, 10f)
        prefs.setCustomEqBands(currentBands)
        applyAll()
    }

    fun getBands(): FloatArray = currentBands.copyOf()

    fun setDspEnabled(enabled: Boolean) {
        prefs.dspEnabled = enabled
        applyAll()
    }

    fun setSubBassLevel(level: Int) {
        prefs.subBassLevel = level.coerceIn(0, 100)
        applyAll()
    }

    fun setSoundstageLevel(level: Int) {
        prefs.soundstageLevel = level.coerceIn(0, 100)
        applyAll()
    }

    fun applyAll() {
        val enabled = prefs.dspEnabled
        val subBassDb = (prefs.subBassLevel / 100f) * 6.0f
        val soundstageStrength = ((prefs.soundstageLevel / 100f) * 900f).toInt().toShort()

        // Apply Android 9+ DynamicsProcessing
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dynamicsProcessing?.let { dp ->
                try {
                    dp.enabled = enabled
                    if (enabled) {
                        var maxBoost = 0f
                        val eq = DynamicsProcessing.Eq(true, true, 10)
                        for (i in 0 until 10) {
                            val subAdd = when (i) {
                                0 -> subBassDb
                                1 -> subBassDb * 0.7f
                                2 -> subBassDb * 0.25f
                                else -> 0f
                            }
                            val gain = (currentBands[i] + subAdd).coerceIn(-12f, 12f)
                            maxBoost = max(maxBoost, gain)
                            eq.setBand(i, DynamicsProcessing.EqBand(true, BAND_FREQUENCIES[i], gain))
                        }

                        // Auto-headroom input gain to prevent digital clipping
                        val preamp = if (maxBoost > 1f) -(maxBoost * 0.55f) else 0f
                        dp.setInputGainAllChannelsTo(preamp)
                        dp.setPreEqAllChannelsTo(eq)

                        // Gentle 3-band mastering dynamics
                        val mbc = DynamicsProcessing.Mbc(true, true, 3)
                        mbc.setBand(0, DynamicsProcessing.MbcBand(true, 180f, 12f, 90f, 1.8f, -14f, 4f, -85f, 1f, 0f, 0.5f))
                        mbc.setBand(1, DynamicsProcessing.MbcBand(true, 2800f, 10f, 80f, 1.5f, -12f, 4f, -85f, 1f, 0f, 0.5f))
                        mbc.setBand(2, DynamicsProcessing.MbcBand(true, 20000f, 8f, 60f, 1.6f, -10f, 3f, -85f, 1f, 0f, 0.5f))
                        dp.setMbcAllChannelsTo(mbc)

                        // -0.5 dB Brickwall Anti-Clip Limiter
                        val limiter = DynamicsProcessing.Limiter(true, true, 0, 1.5f, 55f, 10f, -0.5f, 0f)
                        dp.setLimiterAllChannelsTo(limiter)
                    }
                } catch (_: Throwable) {
                }
            }
        }

        // Apply Fallback Equalizer + BassBoost if DynamicsProcessing unavailable
        fallbackEq?.let { eq ->
            try {
                eq.enabled = enabled
                if (enabled) {
                    val numBands = eq.numberOfBands.toInt()
                    val range = eq.bandLevelRange
                    val minMb = range[0].toInt()
                    val maxMb = range[1].toInt()
                    for (b in 0 until numBands) {
                        val mappedIdx = ((b.toFloat() / max(1, numBands - 1)) * 9f).toInt().coerceIn(0, 9)
                        val mb = (currentBands[mappedIdx] * 100f).toInt().coerceIn(minMb, maxMb)
                        eq.setBandLevel(b.toShort(), mb.toShort())
                    }
                }
            } catch (_: Throwable) {
            }
        }

        fallbackBass?.let { bass ->
            try {
                bass.enabled = enabled && prefs.subBassLevel > 0
                if (bass.strengthSupported) {
                    bass.setStrength(((prefs.subBassLevel / 100f) * 850f).toInt().toShort())
                }
            } catch (_: Throwable) {
            }
        }

        virtualizer?.let { virt ->
            try {
                virt.enabled = enabled && prefs.soundstageLevel > 0
                if (virt.strengthSupported) {
                    virt.setStrength(soundstageStrength)
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun release() {
        try { dynamicsProcessing?.release() } catch (_: Throwable) {}
        try { fallbackEq?.release() } catch (_: Throwable) {}
        try { fallbackBass?.release() } catch (_: Throwable) {}
        try { virtualizer?.release() } catch (_: Throwable) {}
        dynamicsProcessing = null
        fallbackEq = null
        fallbackBass = null
        virtualizer = null
        isDynamicsActive = false
    }
}
