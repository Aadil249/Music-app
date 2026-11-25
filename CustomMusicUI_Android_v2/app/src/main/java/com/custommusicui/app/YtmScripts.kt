package com.custommusicui.app

object YtmScripts {

    // Known ad / telemetry hosts blocked cleanly at the network layer
    val AD_HOST_SUBSTRINGS = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adservice.google.",
        "pagead2.",
        "/pagead/",
        "/api/stats/ads",
        "/ptracking",
        "youtube.com/get_midroll_info"
    )

    fun isAdUrl(url: String): Boolean {
        val lower = url.lowercase()
        return AD_HOST_SUBSTRINGS.any { lower.contains(it) }
    }

    /**
     * Injected into music.youtube.com to:
     * 1. Keep Page Visibility active when locked so playback never stops.
     * 2. Skip/fast-forward any ad slot automatically if enabled.
     * 3. Stream live track metadata, album art, position, duration, and like state to Kotlin.
     */
    fun getBridgeScript(adSkipEnabled: Boolean): String = """
        (function() {
            if (window.__cmuiInjected) {
                window.__cmuiAdSkip = ${if (adSkipEnabled) "true" else "false"};
                return;
            }
            window.__cmuiInjected = true;
            window.__cmuiAdSkip = ${if (adSkipEnabled) "true" else "false"};

            // 1. Keep visibility state always visible for seamless background/lockscreen playback
            try {
                Object.defineProperty(document, 'hidden', { get: () => false, configurable: true });
                Object.defineProperty(document, 'visibilityState', { get: () => 'visible', configurable: true });
                document.addEventListener('visibilitychange', function(e) {
                    e.stopImmediatePropagation();
                }, true);
            } catch (e) {}

            // 2. Clean CSS hiding ad banners and upsell popups
            try {
                var style = document.createElement('style');
                style.textContent =
                    'ytmusic-mealbar-promo-renderer, tp-yt-paper-dialog.ytmusic-popup-container, ' +
                    '.ytp-ad-overlay-container, .ytp-ad-message-container, #ad-text, ' +
                    'ytmusic-statement-banner-renderer { display: none !important; }';
                (document.head || document.documentElement).appendChild(style);
            } catch (e) {}

            function getVideo() {
                return document.querySelector('video');
            }

            function handleAds() {
                if (!window.__cmuiAdSkip) return;
                try {
                    var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-skip-ad-button, .ytp-ad-skip-button-modern');
                    if (skipBtn) skipBtn.click();

                    var adShowing = document.querySelector('.ad-showing, .ad-interrupting');
                    var v = getVideo();
                    if (adShowing && v && isFinite(v.duration) && v.duration > 0) {
                        v.muted = true;
                        v.currentTime = v.duration;
                    } else if (v && v.muted && !adShowing) {
                        v.muted = false;
                    }
                } catch (e) {}
            }

            function readState() {
                handleAds();
                var v = getVideo();
                var title = '';
                var artist = '';
                var artwork = '';
                var liked = false;

                try {
                    if (navigator.mediaSession && navigator.mediaSession.metadata) {
                        var m = navigator.mediaSession.metadata;
                        title = m.title || '';
                        artist = m.artist || '';
                        if (m.artwork && m.artwork.length > 0) {
                            artwork = m.artwork[m.artwork.length - 1].src || '';
                        }
                    }
                } catch (e) {}

                if (!title) {
                    var tEl = document.querySelector('ytmusic-player-bar .title, .title.ytmusic-player-bar');
                    if (tEl) title = (tEl.textContent || '').trim();
                }
                if (!artist) {
                    var aEl = document.querySelector('ytmusic-player-bar .byline, .subtitle.ytmusic-player-bar');
                    if (aEl) artist = (aEl.textContent || '').trim();
                }
                if (!artwork) {
                    var img = document.querySelector('ytmusic-player-bar img.image, #song-image img');
                    if (img && img.src) artwork = img.src;
                }
                try {
                    var likeBtn = document.querySelector('ytmusic-like-button-renderer #button-shape-like button, ytmusic-like-button-renderer .like button');
                    if (likeBtn) {
                        liked = likeBtn.getAttribute('aria-pressed') === 'true';
                    }
                } catch (e) {}

                var isPlaying = v ? (!v.paused && !v.ended && v.readyState > 1) : false;
                var posMs = v && isFinite(v.currentTime) ? Math.floor(v.currentTime * 1000) : 0;
                var durMs = v && isFinite(v.duration) ? Math.floor(v.duration * 1000) : 0;
                var rate = v ? v.playbackRate : 1.0;

                if (window.AndroidMusicBridge && title) {
                    window.AndroidMusicBridge.onStateUpdate(
                        title, artist, artwork, isPlaying, posMs, durMs, liked, rate
                    );
                }
            }

            // Expose native control helpers
            window.__cmuiControl = {
                playPause: function() {
                    var btn = document.querySelector('#play-pause-button, ytmusic-player-bar .play-pause-button');
                    if (btn) { btn.click(); return; }
                    var v = getVideo();
                    if (v) { if (v.paused) v.play(); else v.pause(); }
                },
                play: function() {
                    var v = getVideo();
                    if (v && v.paused) {
                        var btn = document.querySelector('#play-pause-button');
                        if (btn) btn.click(); else v.play();
                    }
                },
                pause: function() {
                    var v = getVideo();
                    if (v && !v.paused) {
                        var btn = document.querySelector('#play-pause-button');
                        if (btn) btn.click(); else v.pause();
                    }
                },
                next: function() {
                    var btn = document.querySelector('.next-button, ytmusic-player-bar [aria-label="Next"], ytmusic-player-bar [aria-label="Next song"]');
                    if (btn) btn.click();
                },
                prev: function() {
                    var btn = document.querySelector('.previous-button, ytmusic-player-bar [aria-label="Previous"], ytmusic-player-bar [aria-label="Previous song"]');
                    if (btn) btn.click();
                },
                seekToSec: function(sec) {
                    var v = getVideo();
                    if (v && isFinite(v.duration)) {
                        v.currentTime = Math.max(0, Math.min(v.duration, sec));
                    }
                },
                setRate: function(rate) {
                    var v = getVideo();
                    if (v) v.playbackRate = rate;
                },
                toggleLike: function() {
                    var btn = document.querySelector('ytmusic-like-button-renderer #button-shape-like button, ytmusic-like-button-renderer .like button');
                    if (btn) btn.click();
                }
            };

            setInterval(readState, 500);
        })();
    """.trimIndent()
}
