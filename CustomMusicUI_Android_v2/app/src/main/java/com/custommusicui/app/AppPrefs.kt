package com.custommusicui.app

import android.content.Context
import android.content.SharedPreferences

class AppPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("custom_music_ui_prefs", Context.MODE_PRIVATE)

    var dspEnabled: Boolean
        get() = prefs.getBoolean("dsp_enabled", true)
        set(value) = prefs.edit().putBoolean("dsp_enabled", value).apply()

    var eqPresetIndex: Int
        get() = prefs.getInt("eq_preset_index", 0)
        set(value) = prefs.edit().putInt("eq_preset_index", value).apply()

    var subBassLevel: Int
        get() = prefs.getInt("sub_bass_level", 45)
        set(value) = prefs.edit().putInt("sub_bass_level", value.coerceIn(0, 100)).apply()

    var soundstageLevel: Int
        get() = prefs.getInt("soundstage_level", 35)
        set(value) = prefs.edit().putInt("soundstage_level", value.coerceIn(0, 100)).apply()

    var playbackSpeed: Float
        get() = prefs.getFloat("playback_speed", 1.0f)
        set(value) = prefs.edit().putFloat("playback_speed", value.coerceIn(0.5f, 1.75f)).apply()

    var lockScreenEnabled: Boolean
        get() = prefs.getBoolean("lock_screen_enabled", true)
        set(value) = prefs.edit().putBoolean("lock_screen_enabled", value).apply()

    // 0 = Album Blur, 1 = Dynamic Mesh, 2 = AMOLED Pure Black, 3 = Aurora Gradient
    var lockThemeIndex: Int
        get() = prefs.getInt("lock_theme_index", 0)
        set(value) = prefs.edit().putInt("lock_theme_index", value % 4).apply()

    // 0 = Modern Bold, 1 = Thin Glass, 2 = Editorial Serif, 3 = Minimal Stack
    var lockClockStyle: Int
        get() = prefs.getInt("lock_clock_style", 0)
        set(value) = prefs.edit().putInt("lock_clock_style", value % 4).apply()

    // 0 = Max Glass Card, 1 = Big Cover, 2 = Compact Pill
    var lockWidgetLayout: Int
        get() = prefs.getInt("lock_widget_layout", 0)
        set(value) = prefs.edit().putInt("lock_widget_layout", value % 3).apply()

    var adSkipEnabled: Boolean
        get() = prefs.getBoolean("ad_skip_enabled", true)
        set(value) = prefs.edit().putBoolean("ad_skip_enabled", value).apply()

    fun getCustomEqBands(): FloatArray {
        val raw = prefs.getString("custom_eq_bands", null) ?: return HiFiAudioEngine.PRESETS[0].bands.copyOf()
        val parts = raw.split(",")
        if (parts.size != 10) return HiFiAudioEngine.PRESETS[0].bands.copyOf()
        return FloatArray(10) { i -> parts[i].toFloatOrNull() ?: 0f }
    }

    fun setCustomEqBands(bands: FloatArray) {
        prefs.edit().putString("custom_eq_bands", bands.joinToString(",")).apply()
    }
}
