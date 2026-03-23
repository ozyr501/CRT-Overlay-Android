# Product goals and target resolution

## Why this app exists

The app is meant to make **older games**—especially **pixel-art** titles—look more natural on **modern, high-resolution phone screens**. Those games were often authored for **low-resolution CRT displays**; on a sharp LCD or OLED, raw pixels can look overly harsh. A **CRT-style filter** (soft phosphor-like blending, scanlines, a light mask texture, curvature, bloom, and related effects) approximates how the image would have been perceived on classic hardware.

The implementation **captures the screen at full display resolution**, then runs the mirror through a **GPU fragment shader** that treats the image as living on a **coarser emulated CRT pixel grid** before drawing the result in a **full-screen overlay** (same footprint as the phone screen).

## Target emulated resolution

**Design reference:** many retro targets used around **320 × 240** (or similar) as **logical** game resolution. The app’s **default minimum emulated grid** is **512 × 384** (a 640×480-style density variant) so filtered pixels stay a bit finer while still clearly “CRT chunky”; users can lower it (e.g. 320×240) for blockier art or raise it up to the configured max.

**Requirement:** emulated width and height **must be user-editable**. Users may want other common bases (e.g. 256×224, 320×240, 1280×720) or device-specific tuning.

### Implementation (current build)

- **`OverlayService.computeCaptureSize()`** returns the **physical display** size (even W×H). **`MediaProjection.createVirtualDisplay`** uses that size so the **capture texture** matches panel resolution — best input for shader downsampling.
- **`CrtPrefs.KEY_INTERNAL_WIDTH`** and **`KEY_INTERNAL_HEIGHT`** (defaults **512** and **384**) define the **minimum** emulated CRT grid. **`CrtGlRenderer.computeEmulatedSize()`** expands width or height so **`uEmulatedSize`** has the **same aspect ratio as the overlay view**, **covering the full screen** with no letterboxing or pillarboxing.
- **High-resolution behavior (adaptive emulated grid):** when the user keeps the internal resolution at the defaults (**512×384**), `computeEmulatedSize()` may automatically increase the emulated grid on very large displays (e.g. QHD+ / 3200×1440). This keeps the shader’s capture-to-emulated downsample ratio in a stable range, which helps avoid an overly blurry look on AMOLED panels. If you manually change internal width/height, this auto-boost is disabled and your values are used as-is (still extended to fill aspect).
- The **overlay** is **full screen**; the quad is fullscreen NDC; all “low resolution” behaviour is from **where** the shader samples in texture space (emulated pixel centres + neighbourhood blend).
- **Texture filtering** on the external OES sampler: **`GL_NEAREST`** when the driver allows it (else linear). Softness comes mainly from the **5×5 emulated-pixel Gaussian blend**, not from bilinear scaling of the whole frame.

Legacy preference **`KEY_CAPTURE_SCALE`** is **not** used for sizing anymore; it may remain in storage from older installs.

## Visual goals (tuning philosophy)

Recent shader iterations aimed to avoid:

- **Heavy green/red vertical banding** from a harsh stepped RGB mask.
- **Global darkness** from multiplying strong masks and scanlines without compensation.

Instead, the defaults favour:

- **Softer pixels** (wide phosphor kernel, asymmetric horizontal bleed).
- **Subtle RGB mask** (smooth phase-based tint, still user-crankable).
- **Brighter overall balance** (scanline/mask strength scaling, post-boost, milder vignette).

This is closer to a **high-quality emulator CRT preset** than to a heavy “retro filter” that tints the whole scene.

## RGB mask vs LCD subpixels

The fragment shader applies CRT-style **RGB modulation** along X at **three times** the **emulated horizontal** frequency, with the overall structure chosen via the app’s **mask type** setting (aperture grille / slot mask / shadow mask). Patterns are blended toward white to avoid harsh primary-colored bars.

This is **not** a simulation of your phone’s **LCD/OLED subpixel matrix** (RGB stripes per logical pixel, pentile, etc.). The final image is shown on whatever subpixel layout the hardware uses — you get **CRT-style emulation in software**, not a physically correct match to a specific tube or panel grid.

## Related documentation

- **[How the app works](HOW_THE_APP_WORKS.md)** — current architecture, capture path, shader stages, default sliders.
- **[README](../README.md)** — usage, build, and limitations.
