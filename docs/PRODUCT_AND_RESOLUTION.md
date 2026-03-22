# Product goals and target resolution

## Why this app exists

The app is meant to make **older games**—especially **pixel-art** titles—look more natural on **modern, high-resolution phone screens**. Those games were often authored for **low-resolution CRT displays**; on a sharp LCD or OLED, raw pixels can look overly harsh. A **CRT-style filter** (scanlines, mask structure, curvature, bloom, phosphor bleed, and related effects) approximates how the image would have been perceived on classic hardware.

The implementation captures what is on screen and runs it through a GPU shader pipeline, then shows the result in a full-screen overlay so you can play or watch content with the effect applied.

## Target output resolution

**Design reference:** many retro targets used around **320 × 240** (or similar) as **logical** game resolution. The app’s **default internal capture size** is **640 × 480** (2× that baseline) for a sharper filtered image while still downsampling the mirror; users can lower it (e.g. 320×240) for chunkier pixels or raise it up to the configured max.

**Requirement:** width and height **must be user-editable** (settings or equivalent). Users may want other common bases (e.g. 256×224, 320×240, 1280×720) or device-specific tuning.

### Implementation (current build)

- **`OverlayService.computeCaptureSize()`** reads **`CrtPrefs.KEY_INTERNAL_WIDTH`** and **`KEY_INTERNAL_HEIGHT`** (defaults **640** and **480**), clamps to configured min/max, and forces **even** width and height for the virtual display and `SurfaceTexture` buffer.
- The **overlay view** still fills the physical screen; **OpenGL** draws the captured texture full viewport, using **`GL_NEAREST`** sampling on the external OES texture when the driver allows it (otherwise **linear**).
- The system **scales the mirrored display** into the virtual display buffer (platform-defined fit into the capture WxH). The **overlay output** then **preserves that buffer’s aspect ratio** on the physical screen (black bars top/bottom or left/right) so the CRT image is not stretched to fill a tall phone display.

Legacy preference **`KEY_CAPTURE_SCALE`** is **not** used for virtual display sizing anymore; it may remain in storage from older installs.

## RGB mask vs LCD subpixels

The fragment shader applies a **repeating vertical R–G–B tint** every **three columns** of the **capture buffer** (slot-mask / side-by-side phosphor stripe **look**). That is **not** a simulation of your phone’s **LCD/OLED subpixel matrix** (e.g. RGB stripes per logical pixel, pentile, etc.). The final image is nearest-neighbor upscaled to the full panel, then shown on whatever subpixel layout the hardware uses—so you get **CRT-style emulation in software**, not a physically correct match to a specific CRT tube or to the panel’s native grid.

## Related documentation

- **[How the app works](HOW_THE_APP_WORKS.md)** — current architecture and capture path.
- **[README](../README.md)** — usage, build, and limitations.
