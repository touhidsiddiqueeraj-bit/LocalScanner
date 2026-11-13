# Architecture

## Overview

LocalScanner is a single-module Kotlin application (`:app`) built with Jetpack Compose.
Everything — vision, OCR, PDF — executes on-device; the manifest requests only the
`CAMERA` permission and the app contains no networking code beyond what bundled
libraries require for *local* model loading (ML Kit bundled models are inside the APK).

```
┌────────────────────────── UI (Jetpack Compose, Material 3) ──────────────────────────┐
│  Home ──► Capture ──► Crop ──► Filter ──► Document Detail ──► PDF Export             │
│   │        (CameraX)   (quad)   (OpenCV)      │              │                       │
│   └── gallery import ─────────────────────────┘              └── Page viewer (OCR)    │
└──────────────────────────────────────────────────────────────────────────────────────┘
                     │ scan session (process-lifetime state)          │ ViewModels
┌────────────────────▼─────────────────────────── Domain ────────────▼──────────────────┐
│  ScannerVision (OpenCV 4.10)      OcrEngine (ML Kit v2 Latin)     PdfExporter        │
│   • detectQuad: Canny+contours     TessOcr (Tesseract 5 ben+eng)   • plain (PdfDocument)│
│   • warpPerspective                                               • searchable (PDFBox)│
│   • filters: magic color/gray/bw    Quad, PageSizeCalculator, TextUtils (pure Kotlin)│
└──────────────────────────────────────────────────────────────────────────────────────┘
                     │
┌────────────────────▼── Data ─────────────────────────────────────────────────────────┐
│  Room DB (documents, pages)   •  DocsRepository (files + DB)   • DataStore settings  │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

## Scan pipeline (mirrors CamScanner's local flow)

1. **Capture** — CameraX `Preview` (FILL_CENTER) + `ImageAnalysis` (RGBA_8888, KEEP_ONLY_LATEST)
   feeding `ScannerVision.detectQuad` on ~384px frames. A quad overlay maps frame-normalized
   coordinates through the same FILL_CENTER transform so edges align with the preview.
   **Single / Batch modes** (persisted): Single fires one page and moves straight to the crop
   stage; Batch keeps a page counter and a Done action. Auto-capture was removed — only the
   shutter takes pictures.
2. **Crop** — the still photo is re-scanned by a robust multi-pass `detectQuad` (adaptive Canny
   thresholds from the luminance median, morphological close, and a `minAreaRect` fallback when
   no clean 4-point contour exists); the live-view quad rides along as an aspect-aware hint for
   photos where detection fails. In Single mode a confident quad is applied automatically
   (auto-advance, cancelled by any corner drag); Batch mode adds "Auto-crop all remaining pages".
   `warpPerspective` produces a de-skewed page of up to 2048px (dimensions derived from the
   quad's real edge lengths). Draggable corner handles remain for manual fixes.
3. **Enhance** — Magic Color (illumination normalization → CLAHE → saturation boost),
   Grayscale, or adaptive-threshold B&W, plus brightness/contrast (`convertScaleAbs`).
4. **Save** — pages are written to `files/documents/<docId>/page_<id>.jpg` with 512px
   thumbnails; Room records path, index, OCR fields.

## OCR

Two bundled engines, chosen per run:

| Engine | Languages | Model size | Notes |
| --- | --- | --- | --- |
| ML Kit v2 (bundled) | Latin (English) | ~4 MB | fast, per-line bounding boxes |
| Tesseract 5 (Tesseract4Android) | Bengali + English | 5 MB traineddata | `ben+eng`, line boxes via `ResultIterator` |

Trained data ships in `assets/tessdata/` and is copied to `files/tesseract/tessdata/` on
first use. Line boxes are stored as JSON (`OcrLinesCodec` v2, including the reference bitmap
size) on each page row.

## Page viewer (OCR on the image)

There is no separate OCR screen. `PageViewerScreen` shows each page full-screen (pager) with
the recognized text living on the image:

- **Selectable** — tapping a recognized line highlights it and shows its text with a copy
  action; a bottom sheet offers the whole page text in a `SelectionContainer` plus
  copy/`.txt`/share. OCR can be (re-)run per page or across all pages with progress.
- **Searchable** — the search bar matches against every page's OCR text, highlights matching
  line boxes on the images, and jumps to the page containing a tapped result.
- Line boxes are normalized with the stored reference bitmap size, so they map onto any
  displayed size (same `ContentScale.Fit` math as the crop overlay).

## PDF export

- **Image PDF**: `android.graphics.pdf.PdfDocument`, page sizes FIT/A4/Letter/Legal/A3/A5,
  orientation auto/portrait/landscape, JPEG-quality presets.
- **Searchable PDF**: PDFBox-Android draws the page image full-bleed, then overlays each OCR
  line as **invisible text** (`PDExtendedGraphicsState` with non-stroking alpha 0). Bengali
  lines use an embedded subset of Noto Sans Bengali (`PDType0Font`); Latin lines use
  Helvetica. Glyph failures degrade per-line, never failing the export.
- Exports live in `files/exports/` and can be shared (FileProvider), saved to Downloads
  (MediaStore, API 29+), or printed (PrintManager).

## Search

Document titles and concatenated page OCR text are indexed in Room; the home screen searches
both, replicating CamScanner's full-text document search locally.

## CI/CD

- `ci.yml`: assembleDebug + unit tests + lint on push/PR, APK artifact upload.
- `release.yml`: on `v*` tags, optional keystore secrets → signed APK → GitHub Release;
  debug-signed fallback when secrets are absent.


## Testing

Core logic is pure Kotlin and unit-tested on the JVM (quad geometry, page-size
calculation, text cleanup, OCR line codec). UI-level verification uses
Robolectric + Roborazzi: `AppScreenshots` boots the real screens with seeded
Room/DataStore data inside `ScreenshotApp` (which skips native engine loading via
the `initEngines`/`onContainerReady` hooks) and captures the rendering to PNG.
The Screenshots workflow commits refreshed captures to `docs/screenshots/`,
which the README embeds.
