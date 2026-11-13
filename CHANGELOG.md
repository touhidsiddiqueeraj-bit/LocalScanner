# Changelog

All notable changes to LocalScanner are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the
project adheres to [Semantic Versioning](https://semver.org/).

## [1.0.1] - 2026-11-13

### Fixed

- **Page detection on real photos**: on cluttered scenes (paper on a wooden desk
  beside a white keyboard, over bright floor tiles) the still-photo detector
  found no quad at all, so every page fell back to the full frame and nothing
  was auto-cropped. The detector now gathers candidate quads from independent
  sources - paper-mask regions flooded around *long* structural Canny edges
  (page outlines, keyboard frame - while text and table rules are filtered out),
  paper-mask blobs at several morphological scales, and Canny contours under
  adaptive thresholds - then scores every candidate by document likelihood:
  area share, contour fill, frame-hug rejection, centrality and perimeter edge
  support (a real document's four sides must sit on actual image edges). The
  winning quad auto-crops and perspective-corrects like before; when nothing
  document-like is found the full frame is kept so pixels are never lost.

## [1.0.0] - 2026-11-13

First full feature release — local CamScanner-parity scanning, all on-device.

### Added

- **Camera capture** with CameraX: live preview, Canny + contour edge detection
  overlay, flash toggle, and **Single / Batch shooting modes** (CamScanner-style
  mode pills; the choice persists in settings).
- **CamScanner-style auto-crop**: a robust multi-pass quad detector (adaptive
  Canny thresholds, morphological close, min-area-rect fallback) finds the page
  on the still photo; Single mode applies the crop automatically and advances,
  Batch mode gets an "Auto-crop all remaining pages" action. The live-view quad
  is carried through the shutter as an aspect-aware hint for hard photos.
- **Crop stage**: draggable corner handles, dimmed mask, re-detect, rotate,
  perspective correction (`warpPerspective`) at up to 2048px.
- **Enhance stage**: Magic Color (illumination normalization + CLAHE), Grayscale,
  Black & White (adaptive threshold) filters plus brightness/contrast sliders and
  per-page or apply-to-all editing.
- **Gallery import** (Photo Picker, up to 20 images) that skips cropping.
- **Document management**: Room-backed documents and pages, folders, rename,
  move, delete, page reorder/rotate/delete, cover thumbnails (Coil).
- **OCR on the page image**: ML Kit v2 (Latin, bundled) and Tesseract 5
  (`ben+eng`, traineddata in the APK) — no separate OCR screen. The page viewer
  draws recognized lines on the image: tap a line to select and copy it, search
  highlights matching lines on every page and jumps to them, a bottom sheet
  offers selectable full text with copy/`.txt`/share.
- **Full-text search** across document titles and OCR text.
- **PDF export**: Android `PdfDocument` for image PDFs and PDFBox for searchable
  PDFs with an invisible OCR text layer (embedded Noto Sans Bengali), page size
  presets (A4/Letter/Legal/A3/A5/fit), orientation, quality presets, share /
  save to Downloads / print.
- **Settings**: theme (system/light/dark), default filter, default capture mode,
  default PDF options, default OCR engine; DataStore-backed.
- **CI/CD**: `ci.yml` (build + tests + lint + APK artifact on push/PR),
  `release.yml` (signed release APK + GitHub Release on `v*` tags),
  `screenshots.yml` (device-free UI screenshots committed to `docs/screenshots/`).
- **Testing**: 20 JVM unit tests for quad geometry, page-size calculation, text
  utilities and the OCR line codec (incl. the v2 on-image format); Robolectric +
  Roborazzi screenshot tests that render the real Compose screens to PNG
  without an emulator.

### Notes

- The release APK is signed with the debug key until repository secrets
  (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) are
  configured — see the README for the signing setup.
- No network permission is requested; nothing ever leaves the device.
