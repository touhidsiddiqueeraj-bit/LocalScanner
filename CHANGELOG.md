# Changelog

All notable changes to LocalScanner are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the
project adheres to [Semantic Versioning](https://semver.org/).

## [1.1.0] - 2026-11-14

The perspective-shift release: manual perspective editing like CamScanner,
live filter editing, and direct image viewing from the document grid.

### Added

- **Perspective shift (CamScanner-style crop editor)**: the crop screen now
  has four draggable corner handles plus four draggable edge handles that
  slide a whole side along its normal, a magnifier loupe that follows the
  dragged handle for pixel-accurate placement, haptic feedback on grab, and
  geometry guards that reject moves which would twist or collapse the quad
  (unit-tested). The confirmed quad is perspective-warped
  (`warpPerspective`) into a straightened page as before.
- **Live effects editing**: filter (Original / Magic Color / Grayscale /
  Black & White) and brightness/contrast changes now re-render the preview
  immediately while the sliders move (debounced 220 ms) — no more "switch
  page to see the change". A horizontal thumbnail strip previews every
  filter on the current page at a glance, pressing and holding the image
  shows the untouched original for before/after comparison, and "Reset
  tone" restores neutral brightness/contrast.
- **View pages directly from the grid**: tapping a page card on the document
  screen now opens the page viewer straight at that page (long-press keeps
  the quick menu). The page viewer gained pinch-to-zoom and double-tap zoom
  (swiping is suspended while zoomed so pans stay inside the page) and a
  page-management bar — move up / move down / rotate / delete — under the
  OCR controls; rotated pages reload with the correct new aspect.
- Filter edits are committed per page live (previously they were only
  applied when paging back and forth).

## [1.0.2] - 2026-11-13

### Fixed

- **Accurate auto edge detection (researched pipeline)** - the detector now
  follows the consensus of proven open-source scanners (LiveEdgeDetection
  lineage, Scanbot's reference pipeline, makeacopy) with five candidate
  sources, replacing the previous four-source/heuristic-scoring detector that
  still mis-selected pages:
  - paper-flattening Canny (normalize -> `THRESH_TRUNC` 150 -> normalize ->
    `Canny(200,75)` -> close 10x10), now guarded to only run when the scene
    has a real dark background (on uniformly bright frames the truncation
    clamps everything and renormalizing amplifies noise into chaos);
  - Otsu -> Canny(50,150) -> close 5x5 for low-contrast backgrounds;
  - **Otsu bright-blob quads** at two scales - the classic largest-bright-
    region detector, robust when paper/background contrast is too faint for
    Canny (white paper on near-white desks);
  - HSV paper-mask blobs and flood-with-structural-barriers candidates
    (paper next to a white keyboard), gated to run only when the mask
    actually separates something (8-65% frame coverage) - on white-on-white
    scenes the mask spans everything and its blobs poisoned the candidate
    pool;
  - convex hull + escalating-epsilon `approxPolyDP` with curled-corner
    (5-point) recovery plus extreme-point quads for curved page blobs;
  - strict validation (area 4-97%, strictly convex, no angle sharper than
    ~60 deg, contour fill >= 65%, <= 2 border touches) and document-
    likelihood scoring by area, fill, border-touch and angle sanity. The
    previous hard "edge support" gate was removed: with a Canny(30,90) map
    it rejected *correct* quads on low-contrast scenes (paper edges weaker
    than the threshold).
- **Gradient edge-snap refinement** on the full-resolution still (makeacopy-
  style): detected edges snap to nearby strong gradients, refined samples are
  least-squares fitted and intersected into precise corners, guarded so
  refinement can only make small, sane corrections.
- **Never silently selects the whole frame**: when detection fails, a
  paper-filled frame keeps the full page, everything else starts from an 8%
  inset rectangle the user can drag (previously the fallback was the full
  frame, which made failed detections look like "no crop"); such pages no
  longer trigger auto-crop without confirmation.
- **Sharper scans**: the crop stage decodes captures at up to 2000px (was
  1400px) before perspective correction.
- Unit tests: quad geometry grew to 18 tests (convexity, angle sanity,
  curled-corner recovery, inset fallback, least-squares line fit and line
  intersection).

### Notes

- The detection tuning was validated with a Python replica of the exact
  pipeline on 12 curated synthetic camera scenes (desk/shadow/glare/texture/
  curled corner/perspective/white-keyboard clutter/white-on-white/
  overexposure) and 20 randomized scenes: IoU 0.84-0.995 against ground
  truth, 0 failures; the previous pipeline failed the low-contrast and
  cluttered scenes outright. Reference implementations studied:
  LiveEdgeDetection / zynkware Document-Scanning-Android-SDK, Scanbot's
  OpenCV edge-detection guide and makeacopy's corner-refinement architecture.

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
