# CRT Overlay (Android)

**Filter Your Screen** and similar Play Store apps sometimes disappear. This app applies a **GPU CRT-style filter** to whatever is on screen: it uses **screen capture** (MediaProjection) into an **OpenGL ES 2** pipeline, then shows the result in a **full-screen overlay** using a **SurfaceView** (opaque compositor layer).

**Product focus:** make **old games**, mainly **pixel-art** titles, look more at home on **modern screens** by emulating CRT-style presentation. The screen is **captured at full display resolution**; the shader downsamples into an **emulated CRT pixel grid** whose **minimum** size defaults to **512 × 384** and is **user-editable** (width × height — the grid is extended as needed to fill the screen aspect). On very high-resolution panels (e.g. QHD+ like 3200×1440), the app may automatically increase the emulated grid (when you keep the defaults) to avoid an overly soft blur. See **[Product goals and target resolution](docs/PRODUCT_AND_RESOLUTION.md)**.

**Touch:** Prefer a **gamepad** or **stop the overlay** to use the touchscreen; **SurfaceView** overlays often **do not** pass touch through to apps below reliably.

## Documentation

- **[Product goals and target resolution](docs/PRODUCT_AND_RESOLUTION.md)** — why the app exists, 512×384 default emulated grid, full-res capture, editable width/height.
- **[How the app works](docs/HOW_THE_APP_WORKS.md)** — architecture, components, data flow, permissions.
- **[Ghosting, touch, and fixes](docs/GHOSTING_TOUCH_AND_FIXES.md)** — ghosting mechanisms, SurfaceView vs TextureView, touch expectations.
- **[Test device notes](docs/TEST_DEVICE.md)** — handset/OS build used for testing (expand over time).

Effects include **curvature**, **Gaussian-style scanlines** (beam-shaped brightness per emulated row), **configurable mask type** (aperture grille / slot mask / shadow mask) as CRT-style RGB modulation, **vignette**, **bloom** on highlights, and **wide phosphor bleed** (soft 5×5 emulated-pixel Gaussian blend, stronger horizontally). The filter also adds CRT-like **halation (glass glow)**, **color temperature / warmth**, **gamma correction**, and an **OLED black level floor** to prevent scanline gaps from looking overly harsh. **Default sliders** aim for a **bright, natural** picture (soft pixels like a good CRT shader, not a dark tinted grid). Sliders and fields map to shader uniforms (effect sliders apply live; changing **emulated width/height** may require **restarting** the overlay).

## Requirements

- Android **8.0+** (API 26+)
- **Display over other apps** permission
- **Screen capture** consent each time you start the overlay (system dialog). Android may show a **recording / casting** indicator while capture is active.
- On **Android 14+**, a **media projection** foreground service type is used (declared in the manifest).

## Limitations

- **DRM / secure content** often appears **black** in the mirrored image (OS restriction).
- **Battery and GPU** use are higher than a simple tint overlay.
- Small **latency** between the real screen and the filtered view is normal.

## Build & install

1. Install [Android Studio](https://developer.android.com/studio).
2. **File → Open** and select the `crt-overlay` folder.
3. Let Gradle sync finish (Studio may download the Gradle wrapper). Use **JDK 11+** for Gradle (Android Studio’s bundled JBR is fine).
4. Connect your phone with USB debugging, or use **Build → Build APK(s)** and install from `app/build/outputs/apk/`.

## Use

1. Open **CRT Overlay**.
2. Allow **Display over other apps** for this app.
3. Set **emulated CRT width × height** (default 512×384 minimum grid) and adjust effect sliders if you want. On high-resolution panels, leaving emulated resolution at defaults enables an auto-boost for more CRT-like sharpness; override manually if you prefer. Then tap **Start overlay**.
4. Approve **screen capture** when Android asks.
5. A **persistent notification** appears while the overlay is on. Use **Stop** in the app or the notification action to turn it off (capture ends).

## Alternatives (if you don’t want to build)

- Search the Play Store for **“scanline overlay”** or **“screen filter overlay”** — names change often.
- **RetroVision** (paid, ~$0.50) targets **camera** retro effects; it may **not** suit normal games — read the listing carefully.

## License

Use and modify freely for personal use. No warranty.
