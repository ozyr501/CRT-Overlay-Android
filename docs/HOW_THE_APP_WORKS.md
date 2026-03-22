# How the CRT Overlay app works

This document describes the architecture, data flow, and main components of the CRT Overlay Android app.

## Purpose

The app shows a **full-screen filter** on top of everything else on the device. The image you see is **not** drawn directly by the apps underneath; it is a **live copy of the screen** (via system screen capture), processed on the GPU to look like a CRT (low-res sampling, scanlines, slot mask, curvature, vignette, bloom, phosphor bleed), then drawn in a **system overlay** window.

**Product intent:** improve how **retro / pixel-art games** look on **high-DPI modern displays** by approximating CRT-style light and geometry—see **[Product goals and target resolution](PRODUCT_AND_RESOLUTION.md)** for the **default internal capture size (640 × 480)** and **user-editable width and height**.

**Input:** The overlay uses a **SurfaceView** in a separate compositor layer for **opaque** presentation (reduces see-through ghosting). **Do not rely on touch** reaching apps under the overlay; use a **gamepad** or **stop the overlay** to use the touchscreen. The window may still use **`FLAG_NOT_TOUCHABLE`**, but SurfaceView layers often **intercept** input on many devices.

## High-level flow

1. **MainActivity** collects permissions, saves sliders and **internal width × height** to `SharedPreferences`, and starts **OverlayService** after the user approves **MediaProjection** (screen capture).
2. **OverlayService** runs as a **foreground service** (required for MediaProjection, and on Android 14+ with type `mediaProjection`).
3. The service adds a **full-screen overlay** view (`CrtEffectGlView`) via `WindowManager`, using `TYPE_APPLICATION_OVERLAY`.
4. **CrtEffectGlView** owns a dedicated **GL thread** that:
   - Creates an **EGL** context and window surface bound to a **SurfaceView** `Surface` (`SurfaceHolder`).
   - Hosts **CrtGlRenderer**, which builds an **OpenGL ES 2** program from `assets/shaders/`.
5. **CrtGlRenderer** (on first GL init) creates a **`SurfaceTexture`** backed by an **external OES** texture, wraps it in a **`Surface`**, and notifies the service via **`CaptureHost.onCaptureSurfaceReady`**.
6. The service calls **`MediaProjection.createVirtualDisplay(...)`** with that `Surface`, using **`computeCaptureSize()`** from prefs (**`KEY_INTERNAL_WIDTH`** / **`KEY_INTERNAL_HEIGHT`**, even dimensions, clamped). The system **mirrors the default display** into the virtual display; frames arrive on the `SurfaceTexture`.
7. Each new frame triggers **`requestRender()`**; the GL thread runs **`onDrawFrame`**: `updateTexImage()`, then draws a full-screen quad with **`crt_frag.glsl`** sampling the external texture ( **`GL_NEAREST`** when the driver allows it, else linear).
8. The user sees the filtered image in the **SurfaceView** on top of the stack. **`eglSwapBuffers`** presents frames to the overlay window. The GL quad is **letterboxed / pillarboxed** so the **aspect ratio of the internal capture buffer** (width ÷ height) matches the picture area; unused bands are **black**.

## Main components

### `MainActivity`

- UI: sliders for effect strength, **internal capture width/height** (text fields), phosphor bleed, etc. (see `activity_main.xml` and `strings.xml`).
- Persists settings under **`CrtPrefs`** (`SharedPreferences`). Internal resolution is saved when fields **lose focus** or when **Start overlay** is tapped.
- Requests **overlay permission** (`Settings.canDrawOverlays`) and **notification permission** on Android 13+.
- Launches the system screen-capture intent; on success starts **`OverlayService`** with result code and data intent.

### `OverlayService`

- **Foreground service** with a persistent notification (open app, stop action).
- Obtains **`MediaProjection`** from **`MediaProjectionManager`**.
- **`attachOverlayIfNeeded`**: computes capture width/height (`computeCaptureSize` from internal W×H prefs), constructs **`CrtGlRenderer`** and **`CrtEffectGlView`**, **`WindowManager.addView`** with **`buildOverlayLayoutParams`**.
- **`onCaptureSurfaceReady`**: creates **`VirtualDisplay`** targeting the renderer’s capture surface (even dimensions for encoder/GPU friendliness).
- **`refreshOverlayForDisplayChange`**: on configuration changes, updates layout params and resizes virtual display + `SurfaceTexture` buffer if internal size prefs yield a new pair (e.g. after rotation + prefs reload path).
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
- **`onDrawFrame`**: reads prefs (scanline strength, spacing, RGB mask, vignette, curvature, bloom, phosphor bleed), updates uniforms, samples **`samplerExternalOES`**, outputs **opaque alpha** in the fragment shader, blending disabled.

### Shaders (`app/src/main/assets/shaders/`)

- **Vertex shader**: full-screen quad, passes texture coordinates.
- **Fragment shader** (`crt_frag.glsl`):
  - **Curvature**: warps UVs (`curve()`).
  - **Border**: outside valid UV range → black.
  - **Phosphor bleed**: optional horizontal blend of neighboring samples.
  - **Scanlines**: sinusoidal brightness modulation along Y.
  - **RGB striping**: per-column tint mimicking a slot mask.
  - **Vignette**: darkens toward edges.
  - **Bloom**: adds glow on bright regions.

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

## Permissions (manifest)

- **`SYSTEM_ALERT_WINDOW`**: overlay visibility in settings on some OEMs.
- **`FOREGROUND_SERVICE`**, **`FOREGROUND_SERVICE_MEDIA_PROJECTION`**: foreground service + projection on modern Android.
- **`POST_NOTIFICATIONS`**: show the ongoing notification on Android 13+.

## Internal capture resolution

**`CrtPrefs.KEY_INTERNAL_WIDTH`** and **`KEY_INTERNAL_HEIGHT`** define the **virtual display** size used by **`MediaProjection.createVirtualDisplay`** (defaults **640 × 480**, clamped to configured min/max, forced **even**). The **overlay view** still fills the physical screen; GL **upscales** the captured texture (nearest filtering when supported).

Legacy **`KEY_CAPTURE_SCALE`** is **unused** for sizing; it may remain in old installs’ preference files.

Changing width/height while the overlay is running may require **stopping and restarting** the overlay to pick up new dimensions everywhere.

## Threading model

- **Main thread**: UI, `WindowManager`, service lifecycle, virtual display creation, `onCaptureSurfaceReady`.
- **GL thread** (`crt-overlay-gl`): EGL, all GLES calls, `SurfaceTexture.updateTexImage`, `onDrawFrame`.
- **Frame callback**: `onFrameAvailable` runs on an arbitrary thread; it posts **`requestRender`** to the GL thread.

## Known platform limits (not bugs in this repo)

- **DRM / secure layers** may appear black in the capture.
- **Latency**: capture and composition add delay vs. the live panel.
- **Battery / GPU**: continuous capture + full-screen GL is heavier than a static tint.
- **MediaProjection** may include the overlay in the mirror on some builds (possible feedback); test on target devices.

## Related docs

- **[Product goals and target resolution](PRODUCT_AND_RESOLUTION.md)** — pixel-art / retro focus, 640×480 default capture, editable resolution.
- **[Ghosting, touch, and fixes](GHOSTING_TOUCH_AND_FIXES.md)** — ghosting mechanisms, SurfaceView choice, touch expectations.
