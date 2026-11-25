# CustomMusicUI Hi-Fi v2.0 (Android)

Native Android Kotlin app combining **Direct YouTube Music**, **32-bit Float Hi-Fi DSP**, an **Aesthetic Lock Screen Player**, and a **Floating Glass Overlay**.

## Features Included
1. **Direct YouTube Music (`MusicPlaybackService.kt` + `BackgroundWebView.kt`)**
   - Sign in once to your Google / YouTube Music account (`CookieManager` persists your session permanently).
   - Keeps playing seamlessly when you lock your phone or switch apps (`mediaPlayback` foreground service + `PARTIAL_WAKE_LOCK` + `BackgroundWebView` visibility override).
   - Native Android `MediaSessionCompat` controls for lock screen media bar and Bluetooth earbuds.
   - Toggleable ad-request filter & automatic ad-slot skip (`YtmScripts.kt`).
2. **Native Android Hi-Fi DSP (`HiFiAudioEngine.kt`)**
   - Android 9+ `DynamicsProcessing` (10-band EQ, 6 audiophile presets, auto-headroom preamp, 3-band compressor, -0.5 dB brickwall limiter) + `Virtualizer` 3D Soundstage, with automatic fallback for Android 8.
   - Real-time Vibe FX playback speed (`Slowed 0.85x`, `Normal 1.0x`, `Nightcore 1.18x`).
3. **Aesthetic Lock Screen Widget (`LockScreenActivity.kt`)**
   - Opens over your lock screen while locked (`setShowWhenLocked(true)`).
   - 4 Themes (*Album Blur*, *Dynamic Mesh*, *AMOLED Pure Black*, *Aurora*), 4 tap-to-cycle Clock Styles, 3 Widget Layouts (*Max Glass*, *Big Cover*, *Compact Pill*), AMOLED AOD toggle, swipe-to-skip, double-tap-to-like, and 4 interactive lock-screen drawers (*Hi-Fi EQ*, *Vibe FX*, *Themes*, *Sleep Timer*).
4. **Floating Glass Overlay (`FloatingOverlayController.kt`)**
   - Draggable frosted card over apps and games with tap-to-cycle Hi-Fi presets.

## Build Ready-to-Install APK in 2 Minutes (GitHub Actions)
1. Create a GitHub repository and upload all files inside `CustomMusicUI_Android_v2/`.
2. Open the **Actions** tab -> **Build & Package Android APK + AAB** -> **Run workflow**.
3. Download **`CustomMusicUI-v2-debug-apk`** and install `app-debug.apk` directly on your phone.

## Signing a Release APK / AAB for Distribution
1. Generate a keystore:
   ```bash
   keytool -genkey -v -keystore release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias custommusicui
   ```
2. Copy `keystore.properties.template` to `keystore.properties` and fill in your passwords.
3. Run `gradle assembleRelease bundleRelease` (or open in Android Studio -> **Build -> Generate Signed App Bundle / APK**).
