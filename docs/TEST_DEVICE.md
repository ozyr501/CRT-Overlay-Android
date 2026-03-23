# Test device notes

Personal record of the handset used to reproduce and verify CRT Overlay behavior. **Add more rows or sections below as you collect data.**

## Device overview

| Field | Value |
|--------|--------|
| **Brand / marketing name** | POCO F7 Pro |
| **OS shell** | Xiaomi HyperOS |
| **HyperOS version** | 3.0.2.0 |
| **HyperOS build (POCO)** | 3.0.2.0.WOKEUXM |
| **Android version (reported)** | 16 |
| **Android build** | BP2A.250605.031.A3 |
| **Android security update** | 2025-10-01 |
| **UI locale (from screenshots)** | Polish (pl) |

## Storage & memory (from Settings → About phone)

| Field | Value |
|--------|--------|
| **Internal storage** | 423.1 GB used / 528 GB total |
| **Memory extension** | 16 GB (HyperOS “extended RAM” feature noted in UI) |

## Why this file exists

HyperOS and very new Android builds sometimes change **overlay**, **MediaProjection**, and **touch routing** compared to AOSP. Keeping exact **build strings** here helps when:

- Comparing ghosting or touch issues across updates.
- Searching for OEM-specific bugs or workarounds.
- Documenting “works on / broken on” for README or issues.

## Future additions (template)

Use this section for anything you log later (remove the placeholder lines when empty).

### Display

- Resolution / refresh rate:
- Panel type (if known):

### SoC / GPU

- Chipset:
- GPU (e.g. Adreno / Mali):

### App-specific notes

- CRT Overlay version / commit:
- Ghosting: yes / no / under what conditions:
- Touch pass-through: OK / fails where:
- Shader / docs: full-display capture + emulated grid in `crt_frag.glsl`, including adaptive emulated-grid sizing on QHD+ when using default internal resolution; default sliders favour brightness and soft phosphor (see `docs/HOW_THE_APP_WORKS.md`).

### Changelog (OS)

| Date | Update | Notes |
|------|--------|--------|
| | | |

---

*Last updated: device table from “About phone” (March 2026); app note added when documentation was aligned with full-res capture + emulated-grid shader.*
