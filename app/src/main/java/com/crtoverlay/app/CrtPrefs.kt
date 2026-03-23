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

    /** Color temperature / warmth (0–100). Higher = warmer/redder. */
    const val KEY_COLOR_TEMP = "color_temp"

    /** OLED minimum black level (0–20). Lifts black floor in scanline gaps. */
    const val KEY_BLACK_LEVEL = "black_level"

    /** Halation / glass glow strength (0–100). */
    const val KEY_HALATION = "halation"

    /** Mask type: 0 = aperture grille, 1 = slot mask, 2 = shadow mask. */
    const val KEY_MASK_TYPE = "mask_type"

    /** Gamma (18–30, representing 1.8–3.0). CRT ~2.5, LCD ~2.2. */
    const val KEY_GAMMA = "gamma"

    /** Minimum emulated CRT width in pixels. The actual grid may be wider to fill the screen. */
    const val KEY_INTERNAL_WIDTH = "internal_width_px"
    /** Minimum emulated CRT height in pixels. The actual grid may be taller to fill the screen. */
    const val KEY_INTERNAL_HEIGHT = "internal_height_px"

    // Default tuned for more visible CRT pixel boundaries on modern panels.
    const val DEFAULT_INTERNAL_WIDTH = 512
    const val DEFAULT_INTERNAL_HEIGHT = 384

    /** One-time bump for installs that still had the old 320×240 default saved. */
    const val KEY_LEGACY_RES_DOUBLED = "legacy_internal_res_doubled_v1"

    const val INTERNAL_WIDTH_MIN = 160
    const val INTERNAL_WIDTH_MAX = 1280
    const val INTERNAL_HEIGHT_MIN = 120
    const val INTERNAL_HEIGHT_MAX = 720
}
