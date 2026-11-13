# Changelog

All notable changes to LocalScanner are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the
project adheres to [Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-11-13

First full feature release — local CamScanner-parity scanning, all on-device.

### Added

- **Camera capture** with CameraX: live preview, Canny + contour edge detection
  overlay, stability-based auto-capture, flash toggle, multi-page sessions.
- **Crop stage**: draggable corner handles, dimmed mask, re-detect, rotate,
  perspective correction (`warpPerspective`) at up to 2048px.
- **Enhance stage**: Magic Color (illumination normalization + CLAHE), Grayscale,
  Black & White (adaptive threshold) filters plus brightness/contrast sliders and
  per-page or apply-to-all editing.
- **Gallery import** (Photo Picker, up to 20 images) that skips cropping.
- **Document management**: Room-backed documents and pages, folders, rename,
  move, delete, page reorder/rotate/delete, cover thumbnails (Coil).
- **OCR**: ML Kit v2 (Latin, bundled) and Tesseract 5 (`ben+eng`, traineddata in
  the APK), editable per-page text, copy/share/`.txt` export.
- **Full-text search** across document titles and OCR text.
- **PDF export**: Android `PdfDocument` for image PDFs and PDFBox for searchable
  PDFs with an invisible OCR text layer (embedded Noto Sans Bengali), page size
  presets (A4/Letter/Legal/A3/A5/fit), orientation, quality presets, share /
  save to Downloads / print.
- **Settings**: theme (system/light/dark), default filter, auto-capture toggle,
  default PDF options, default OCR engine; DataStore-backed.
- **CI/CD**: `ci.yml` (build + tests + lint + APK artifact on push/PR),
  `release.yml` (signed release APK + GitHub Release on `v*` tags),
  `screenshots.yml` (device-free UI screenshots committed to `docs/screenshots/`).
- **Testing**: 19 JVM unit tests for quad geometry, page-size calculation, text
  utilities and the OCR line codec; Robolectric + Roborazzi screenshot tests that
  render the real Compose screens to PNG without an emulator.

### Notes

- The release APK is signed with the debug key until repository secrets
  (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) are
  configured — see the README for the signing setup.
- No network permission is requested; nothing ever leaves the device.
