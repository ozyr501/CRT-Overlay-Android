package com.crtoverlay.app

object CrtPrefs {
    const val PREFS_NAME = "crt_prefs"

    const val KEY_SCAN_ALPHA = "scan_alpha"
    const val KEY_SCAN_SPACING = "scan_spacing"
    const val KEY_VIGNETTE = "vignette_strength"
    const val KEY_RGB = "rgb_strength"
    const val KEY_CURVATURE = "curvature"
    /** Legacy preference; no longer used. Kept for older installs. */
    const val KEY_CAPTURE_SCALE = "capture_scale_pct"
    const val KEY_BLOOM = "bloom"
    /** Horizontal phosphor bleed / smear strength (0–100). */
    const val KEY_PHOSPHOR_BLEED = "phosphor_bleed"

    /** Minimum emulated CRT width in pixels. The actual grid may be wider to fill the screen. */
    const val KEY_INTERNAL_WIDTH = "internal_width_px"
    /** Minimum emulated CRT height in pixels. The actual grid may be taller to fill the screen. */
    const val KEY_INTERNAL_HEIGHT = "internal_height_px"

    const val DEFAULT_INTERNAL_WIDTH = 640
    const val DEFAULT_INTERNAL_HEIGHT = 480

    /** One-time bump for installs that still had the old 320×240 default saved. */
    const val KEY_LEGACY_RES_DOUBLED = "legacy_internal_res_doubled_v1"

    const val INTERNAL_WIDTH_MIN = 160
    const val INTERNAL_WIDTH_MAX = 1280
    const val INTERNAL_HEIGHT_MIN = 120
    const val INTERNAL_HEIGHT_MAX = 720
}
