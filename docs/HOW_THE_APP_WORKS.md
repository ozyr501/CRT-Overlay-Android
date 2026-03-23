# How the CRT Overlay app works

This document describes the architecture, data flow, and main components of the CRT Overlay Android app.

## Purpose

The app shows a **full-screen filter** on top of everything else on the device. The image you see is **not** drawn directly by the apps underneath; it is a **live copy of the screen** (via system screen capture at **display resolution**), processed on the GPU to look like a CRT (**emulated low-res pixel grid** in the shader, soft phosphor blend, scanlines, configurable CRT-style mask, curvature, vignette, bloom, halation), then drawn in a **system overlay** window **filling the panel** (no letterboxing for aspect — the emulated grid is sized to cover the screen).

**Product intent:** improve how **retro / pixel-art games** look on **high-DPI modern displays** by approximating CRT-style light and geometry—see **[Product goals and target resolution](PRODUCT_AND_RESOLUTION.md)** for the **default emulated CRT grid (min 512 × 384)** and **user-editable width and height**. Capture uses the **full physical display** so downsampling happens in the fragment shader, not by a tiny virtual display.

**Input:** The overlay uses a **SurfaceView** in a separate compositor layer for **opaque** presentation (reduces see-through ghosting). **Do not rely on touch** reaching apps under the overlay; use a **gamepad** or **stop the overlay** to use the touchscreen. The window may still use **`FLAG_NOT_TOUCHABLE`**, but SurfaceView layers often **intercept** input on many devices.

## High-level flow

1. **MainActivity** collects permissions, saves sliders and **emulated CRT width × height** to `SharedPreferences`, and starts **OverlayService** after the user approves **MediaProjection** (screen capture).
2. **OverlayService** runs as a **foreground service** (required for MediaProjection, and on Android 14+ with type `mediaProjection`).
3. The service adds a **full-screen overlay** view (`CrtEffectGlView`) via `WindowManager`, using `TYPE_APPLICATION_OVERLAY`.
4. **CrtEffectGlView** owns a dedicated **GL thread** that:
   - Creates an **EGL** context and window surface bound to a **SurfaceView** `Surface` (`SurfaceHolder`).
   - Hosts **CrtGlRenderer**, which builds an **OpenGL ES 2** program from `assets/shaders/`.
5. **CrtGlRenderer** (on first GL init) creates a **`SurfaceTexture`** backed by an **external OES** texture, wraps it in a **`Surface`**, and notifies the service via **`CaptureHost.onCaptureSurfaceReady`**.
6. The service calls **`MediaProjection.createVirtualDisplay(...)`** with that `Surface`, using **`computeCaptureSize()`**: **physical display width × height** (even dimensions). The system **mirrors the default display** into the virtual display; frames arrive on the `SurfaceTexture`.
7. The virtual display is created with a **`VirtualDisplay.Callback`** so the app knows when the captured content is **paused** (e.g. when the user swipes the app away / minimizes it) vs **resumed**. When paused, the overlay window is hidden by setting **window-level alpha** to `0` (and shown again with `alpha = 1` when resumed).
8. Each new frame triggers **`requestRender()`**; the GL thread runs **`onDrawFrame`**: `updateTexImage()`, then draws a **full-screen** quad with **`crt_frag.glsl`** sampling the external texture ( **`GL_NEAREST`** when the driver allows it, else linear). **`CrtGlRenderer.computeEmulatedSize()`** derives **`uEmulatedSize`** from **`KEY_INTERNAL_WIDTH`** / **`KEY_INTERNAL_HEIGHT`** and the **overlay view** aspect so the emulated CRT grid **covers the whole screen** (extending W or H beyond the user’s minimum if needed).
9. The user sees the filtered image in the **SurfaceView** on top of the stack. **`eglSwapBuffers`** presents frames to the overlay window.

## Main components

### `MainActivity`

- UI: sliders for effect strength (scanlines, RGB mask strength, bloom, phosphor bleed, etc.), plus CRT presentation controls (**color temperature**, **OLED black level**, **halation**, **gamma**) and a **mask type** selector; **emulated CRT width/height** remains configurable (see `activity_main.xml` and `strings.xml`).
- Persists settings under **`CrtPrefs`** (`SharedPreferences`). Emulated resolution is saved when fields **lose focus** or when **Start overlay** is tapped.
- Requests **overlay permission** (`Settings.canDrawOverlays`) and **notification permission** on Android 13+.
- Launches the system screen-capture intent; on success starts **`OverlayService`** with result code and data intent.

### `OverlayService`

- **Foreground service** with a persistent notification (open app, stop action).
- Obtains **`MediaProjection`** from **`MediaProjectionManager`**.
- **`attachOverlayIfNeeded`**: computes capture width/height (`computeCaptureSize` = **display** size), constructs **`CrtGlRenderer`** and **`CrtEffectGlView`**, **`WindowManager.addView`** with **`buildOverlayLayoutParams`**.
- **`onCaptureSurfaceReady`**: creates **`VirtualDisplay`** targeting the renderer’s capture surface (even dimensions for encoder/GPU friendliness) with a **`VirtualDisplay.Callback`**; when paused it hides the overlay window (to avoid “black frame while minimized”), and when resumed it shows it again.
- **`refreshOverlayForDisplayChange`**: on configuration changes, updates layout params and resizes the virtual display + `SurfaceTexture` buffer when the **display** size changes (rotation, etc.).
- **`tearDown`**: removes overlay, releases projection, clears state.
- Exposes **`OverlayService.isRunning`** for the activity status line.

### `CrtEffectGlView` (`FrameLayout`)

- Child: full-screen **`SurfaceView`** with **`setZOrderOnTop(true)`** and **`setZOrderMediaOverlay(true)`**, **`PixelFormat.OPAQUE`** on the holder.
- **`HandlerThread`** (`crt-overlay-gl`) runs all EGL and `CrtGlRenderer` calls.
- **`SurfaceHolder.Callback`**: on first valid size, **`initEgl(surface)`**, then `renderer.onSurfaceCreated` / `onSurfaceChanged`; on destroy, **`shutdownSync`**.
- **`initEgl`**: chooses EGL config (prefers **zero alpha** when the GPU allows this window surface; otherwise **RGBA8888**), creates context + window surface, **`eglSwapBuffers`** after each frame.
- Root layout: black background, **`fitsSystemWindows = false`**, **`WindowInsetsCompat.CONSUMED`** so system insets do not shrink drawable area (helps avoid “holes” at cutouts).
- **Do not** call **`Surface.release()`** on the holder’s surface; the view owns it.
- **`onPause` / `shutdownSync`**: tear down GL and EGL when the view is removed or the surface is destroyed.

### `CrtGlRenderer`

- Implements the same callback shape as **`GLSurfaceView.Renderer`** but is driven manually from the service’s GL thread.
- Loads **`crt_vert.glsl`** / **`crt_frag.glsl`** via **`CrtGlProgram`**.
- **`onSurfaceCreated`**: compiles program, sets up quad buffers, creates **`SurfaceTexture(oesTexId)`** with **`setOnFrameAvailableListener`** → **`onFrameAvailable`** (schedules **`requestRender`** on the view).
- **`onDrawFrame`**: reads prefs (scanline strength/spacing, RGB mask strength, vignette, curvature, bloom, phosphor bleed, plus CRT presentation controls like color temperature, halation, gamma, OLED black level, and mask type), maps them to normalized uniforms, computes **`uEmulatedSize`**, updates all shader uniforms, samples **`samplerExternalOES`**, outputs **opaque alpha** in the fragment shader, blending disabled.

### Shaders (`app/src/main/assets/shaders/`)

- **Vertex shader**: full-screen quad, passes texture coordinates.
- **Fragment shader** (`crt_frag.glsl`) — processing order / ideas:
  - **Curvature**: warps UVs (`curve()`).
  - **Border**: outside valid UV range after warp → black.
  - **Emulated grid**: continuous coordinates in **`uEmulatedSize`** space (`emCont`); all CRT structure is relative to this grid, not to raw capture texels only.
  - **Downscale integration**: **`uTexSize` / `uEmulatedSize`** gives an approximate **capture-to-emulated scale**. For the **centre** cell of the phosphor kernel only, the shader **box-filters** the capture inside that emulated cell (**2×2** stratified taps when scale ≥ **1.5**, **3×3** when scale ≥ **5**); other neighbors use a **single** centre tap to limit texture fetches. That averages many physical texels per logical CRT pixel and reduces **jagged curves** before phosphor blur.
  - **Phosphor softness**: **5×5** neighborhood of **emulated pixels**, Gaussian weights with **wider horizontal spread** than vertical (`uPhosphorBleed` scales spread). Slightly softer falloff than earlier builds pairs with the integration step.
  - **Curvature vs sampling**: `emCont` uses **warped** UVs while cell sampling uses **linear** normalized UV (`nPx / uEmulatedSize`). At **high curvature** this is approximate; a future improvement could map samples through an **inverse** of `curve()`.
  - **Scanlines**: **Gaussian beam** along each emulated row (`exp` falloff from row center); **`uScanSpacing`** widens/narrows the bright band; strength is mixed with **`uScanStrength`** (scaled so defaults stay fairly bright).
  - **RGB mask**: **smooth phase-based** RGB modulation (cosines, 120° apart) tiled at **3×** emulated horizontal frequency, blended toward white so the effect is **texture / slight fringing**, not strong green–red bars. The exact pattern is selected via **`uMaskType`** (aperture grille / slot mask / shadow mask). **`uMaskStrength`** scales how far the mask departs from neutral white.
  - **Brightness compensation**: small **post-multiply** after mask × scanlines so the image does not read as overly dark.
  - **Vignette**: darkens toward edges (coefficient tuned softer than earlier builds).
  - **Bloom**: adds glow on bright regions after the above (threshold in shader).
  - **Halation**: wide “glass glow” additively blended around highlights (separate from phosphor bleed and bloom).
  - **Color temperature / warmth**: applies a subtle channel-dependent RGB shift.
  - **Gamma correction**: applies a CRT-like gamma curve before final clamp.
  - **OLED black level floor**: lifts the minimum output floor to reduce harsh ultra-black scan gaps.

## Overlay window parameters

Defined in **`OverlayService.buildOverlayLayoutParams`**:

| Aspect | Role |
|--------|------|
| `TYPE_APPLICATION_OVERLAY` | Draw on top of other apps (requires “Display over other apps”). |
| `FLAG_NOT_FOCUSABLE` | Overlay does not take focus from the foreground app. |
| `FLAG_NOT_TOUCHABLE` | Best-effort: window should not receive touches; **SurfaceView may still block** touch to apps below. |
| `FLAG_LAYOUT_IN_SCREEN` / `FLAG_LAYOUT_NO_LIMITS` | Full-screen placement. |
| `FLAG_HARDWARE_ACCELERATED` | GPU composition for the view hierarchy. |
| `PixelFormat.OPAQUE` | Declares an opaque window pixel format. |
| `layoutInDisplayCutoutMode` | Draws into display cutout (API 28+), reducing undrawn strips at status bar / notch. |
| `alpha` (dynamic) | The app updates **window-level** `LayoutParams.alpha` to hide/show the overlay when captured content is paused. This is required because the overlay uses **`SurfaceView`** (`setZOrderOnTop(true)`), which lives in a separate compositor layer and does **not** reliably respect parent `View.alpha`. |

## Permissions (manifest)

- **`SYSTEM_ALERT_WINDOW`**: overlay visibility in settings on some OEMs.
- **`FOREGROUND_SERVICE`**, **`FOREGROUND_SERVICE_MEDIA_PROJECTION`**: foreground service + projection on modern Android.
- **`POST_NOTIFICATIONS`**: show the ongoing notification on Android 13+.

## Emulated CRT resolution vs capture buffer

| Concept | Role |
|--------|------|
| **Capture buffer** | Size of **`VirtualDisplay`** = **`computeCaptureSize()`** ≈ **full display** (even W×H). High-res input for the shader. |
| **Emulated grid** | **`CrtPrefs.KEY_INTERNAL_WIDTH`** × **`KEY_INTERNAL_HEIGHT`** are **minimum** emulated dimensions; **`computeEmulatedSize()`** in **`CrtGlRenderer`** extends width or height so the **aspect of `uEmulatedSize` matches the overlay view**, filling the screen without letterboxing. Defaults **512 × 384**. Values are clamped to min/max and forced **even** when parsed from the UI. If you keep the defaults on very high-resolution panels (e.g. QHD+ / 3200×1440), the renderer may auto-increase the emulated grid to keep the shader downsample behavior from getting overly soft. |

Legacy **`KEY_CAPTURE_SCALE`** is **unused**; it may remain in old installs’ preference files.

Changing emulated width/height while the overlay is running may require **stopping and restarting** the overlay so capture/view metrics and uniforms stay consistent.

## Default slider values (fresh install)

These match **`activity_main.xml`** / **`MainActivity.loadPrefsIntoUi`** fallbacks and **`CrtGlRenderer`** defaults when a key is missing. They are tuned for a **relatively bright, soft** look; users can increase RGB mask or scanlines for a harsher CRT.

| Setting | Default | Notes |
|---------|---------|--------|
| Scanline strength | 50 / 150 | Softer beam modulation in shader via strength scaling. |
| Scanline gap width | 5 / 6 | Wider gap → broader bright row core. |
| Vignette | 30% | Milder edge darkening. |
| RGB mask | 12 / 100 | Subtle mask; high values add more colour texture. |
| Curvature | 35 / 100 | Moderate barrel distortion. |
| Bloom | 18 / 100 | Highlight glow. |
| Phosphor bleed | 30 / 100 | Wider 5×5 blend kernel spread. |
| Color temperature | 20 / 100 | Subtle channel-dependent warmth shift. |
| OLED black level | 3 / 20 | Lifts minimum output floor to reduce ultra-black harshness. |
| Halation | 15 / 100 | Adds large-radius glass glow around highlights. |
| Mask type | 0 (aperture grille) | Dropdown: aperture / slot / shadow mask. |
| Gamma | 22 / 30 | Applies CRT-like gamma curve (gamma=2.2 by default mapping). |
| Emulated W×H | 512 × 384 | Minimum grid; shader extends to fill screen aspect. |

## Threading model

- **Main thread**: UI, `WindowManager`, service lifecycle, virtual display creation, `onCaptureSurfaceReady`.
- **GL thread** (`crt-overlay-gl`): EGL, all GLES calls, `SurfaceTexture.updateTexImage`, `onDrawFrame`.
- **Frame callback**: `onFrameAvailable` runs on an arbitrary thread; it posts **`requestRender`** to the GL thread.

## Known platform limits (not bugs in this repo)

- **DRM / secure layers** may appear black in the capture.
- **Latency**: capture and composition add delay vs. the live panel.
- **Battery / GPU**: continuous capture + full-screen GL is heavier than a static tint.
- **MediaProjection** may include the overlay in the mirror on some builds (possible feedback); test on target devices.
- When the captured content is paused/minimized, some devices may stop producing new capture frames; the overlay is auto-hidden to avoid rendering a blank/black result.

## Related docs

- **[Product goals and target resolution](PRODUCT_AND_RESOLUTION.md)** — pixel-art / retro focus, full-res capture, 512×384 default emulated grid, editable resolution.
- **[Ghosting, touch, and fixes](GHOSTING_TOUCH_AND_FIXES.md)** — ghosting mechanisms, SurfaceView choice, touch expectations.
