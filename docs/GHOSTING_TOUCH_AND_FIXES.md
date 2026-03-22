# Ghosting effect, touch, and presentation choice

This document explains the “double image” (ghosting) users can see, why **TextureView** vs **SurfaceView** matters, and what the app does **today**.

## What “ghosting” looks like

Users report:

- UI elements (text, icons, status bar) appearing **twice**, slightly **offset**.
- A **sharper** layer and a **darker / filtered** layer (the CRT-processed capture).
- The effect is **stronger when curvature is on** or when **content moves** (scrolling, video).

That is consistent with **two different images** being visible at once, not a single layer that is “50% transparent” in a simple way.

## Root causes (two mechanisms)

### 1. Compositing / see-through (static misalignment)

The overlay shows the **filtered capture**. If any part of the overlay stack is treated as **non-opaque** by SurfaceFlinger—or there are **gaps** (e.g. around display cutout)—the **live** pixels from the panel can show through.

Because the CRT shader **warps** geometry (**curvature**), the filtered image **does not line up pixel-perfect** with the unwarped UI underneath. Even a small amount of bleed looks like a **ghost** next to the real text and icons.

**Mitigations in code** (see `OverlayService` and `CrtEffectGlView`):

- Window: **`PixelFormat.OPAQUE`**, **`params.alpha = 1f`**, explicit **`params.format = PixelFormat.OPAQUE`**.
- **`layoutInDisplayCutoutMode`**: **`SHORT_EDGES`** (API 28–29) or **`ALWAYS`** (API 30+) so the overlay draws into the cutout and does not leave bands where only the live app shows.
- Root **`FrameLayout`**: solid **black** background.
- **Presentation:** **`SurfaceView`** with **`setZOrderOnTop(true)`** so the GL output lives in a **separate compositor layer** that tends to be **truly opaque**, greatly reducing TextureView-style bleed-through.
- **EGL**: prefer a config with **`EGL_ALPHA_SIZE == 0`** when the GPU can still create a window surface for this `Surface`; otherwise fall back to **RGBA8888** (see `initEgl` in `CrtEffectGlView`).
- **Insets**: **`WindowInsetsCompat.CONSUMED`** on the overlay root so system bars do not shrink content and leave exposed strips.

These reduce **cutout / alpha** artifacts; they do not remove capture lag (below).

### 2. Temporal lag (motion “ghost”)

**MediaProjection** + virtual display + GPU pipeline introduce **delay** (often a few frames). The **live** UI under the overlay updates **immediately**; the **texture** on the overlay shows an **older** frame.

If the user can perceive **both** (because of residual compositing quirks or mental comparison with peripheral vision / brightness), **motion** makes the offset obvious—like a trailing copy.

**Curvature** exaggerates this: the delayed layer is **warped**; the mental reference is the **unwarped** live UI, so the mismatch reads as a strong double image.

There is **no complete fix** in app code for system-level capture latency. A **lower internal capture resolution** (e.g. 320×240) changes workload and how detail is sampled but does not eliminate lag. Default capture is **640×480**; reduce width/height in settings for a chunkier look.

## Touch and SurfaceView

### Historical note (TextureView)

Earlier builds used **`TextureView`** so **`FLAG_NOT_TOUCHABLE`** could allow **touch pass-through** to apps below. Tradeoff: **ghosting** (compositing bleed-through) could remain noticeable.

### Current decision (SurfaceView)

The app uses **`SurfaceView`** with **`setZOrderOnTop(true)`** for **opaque** presentation and **stronger reduction** of see-through ghosting.

**Touch:** Do **not** rely on the touchscreen reaching apps under the overlay. Many devices route input to the SurfaceView layer even when the window uses **`FLAG_NOT_TOUCHABLE`**. The product expectation is **gamepad / controller** or **stopping the overlay** to tap the UI.

## Summary table

| Approach | Touch under overlay | Ghosting (typical compositing) |
|----------|----------------------|--------------------------------|
| **TextureView** | Often works with `FLAG_NOT_TOUCHABLE` | Can remain noticeable |
| **SurfaceView + z-order on top** (current) | **Unreliable / often blocked** | **Lower see-through** |
| **SurfaceView default Z-order** | Unreliable on Android 11+ overlay | Mixed |

## EGL opaque-config failure (startup crash)

An early change always requested **`EGL_ALPHA_SIZE = 0`**. On some devices **`eglChooseConfig` succeeded** but **`eglCreateWindowSurface` failed** for the output `Surface`, so GL never initialized and the overlay appeared “dead.”

**Fix:** try **opaque** config first; if window surface creation fails, **destroy the context** and retry with **RGBA8888** (`EGL_ALPHA_SIZE = 8`).

## If you iterate further (ideas)

- **Reduce curvature** when motion ghosting bothers you most (shader uniform exposed).
- **OEM-specific** compositor behavior may need experimentation—no one-size-fits-all without device testing.
- **MediaProjection** may include the overlay in the mirror on some builds (feedback loop); no fully reliable public API to exclude your own overlay.

## Files most involved

| Topic | Files |
|-------|--------|
| Overlay flags, cutout, virtual display | `OverlayService.kt` |
| SurfaceView, EGL, insets | `CrtEffectGlView.kt` |
| Capture texture + draw | `CrtGlRenderer.kt`, `crt_frag.glsl`, `crt_vert.glsl` |
| User prefs | `CrtPrefs.kt`, `MainActivity.kt` |

## See also

- **[How the app works](HOW_THE_APP_WORKS.md)** — end-to-end architecture and data flow.
