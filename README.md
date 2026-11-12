# LocalScanner

**A fully offline, open-source document scanner for Android that replicates the local (on-device) experience of CamScanner — without ads, without accounts, without watermarks, and without any data leaving your phone.**

![CI](https://github.com/touhidsiddiqueeraj-bit/LocalScanner/actions/workflows/ci.yml/badge.svg)
![Release](https://github.com/touhidsiddiqueeraj-bit/LocalScanner/actions/workflows/release.yml/badge.svg)

## Features

| CamScanner capability | LocalScanner implementation |
| --- | --- |
| Auto edge detection while framing | OpenCV Canny + contour + `approxPolyDP` quad finder, live overlay on the CameraX preview |
| Auto-capture (auto shutter) | Stability detector: fires the shutter when a document quad stays stable for ~1s (toggleable) |
| Manual corner adjustment ("smart crop") | Draggable corner handles with dimmed background mask |
| Perspective correction / de-skew | OpenCV `getPerspectiveTransform` + `warpPerspective` at up to 2048px |
| Magic Color / shadow removal | Illumination-normalization (background division) + CLAHE local contrast + saturation boost |
| Grayscale & Black & White modes | Adaptive-threshold B&W with auto block size, classic grayscale |
| Brightness / contrast tuning | Per-page sliders applied on top of any filter |
| Multi-page batch scanning | One session, N pages, append pages to existing documents |
| Gallery import | Android Photo Picker (multi-select, up to 20 images) |
| OCR (English) | ML Kit v2 bundled Latin model — fast, on-device |
| OCR (Bengali + English) | Tesseract 5 (`ben+eng`, tessdata_fast) bundled in the APK — on-device |
| Editable OCR text | Editable per-page text, copy, share, `.txt` export |
| Full-text search | Search across document titles *and* OCR text (Room + SQLite) |
| Searchable PDF export | PDFBox: page image + **invisible OCR text layer** (selectable/searchable, Bengali font embedded) |
| PDF page sizes | Fit-to-image, A4, Letter, Legal, A3, A5 + auto/portrait/landscape |
| PDF compression | High / Medium / Small quality presets |
| No watermark | CamScanner free tier stamps "Created by CamScanner" on exports — LocalScanner never does |
| Document management | Documents with cover thumbnails, folders, rename, delete, page reorder/rotate/delete |
| Print | Android print framework (PDF) |
| Dark mode | Material You theme with system/light/dark override |
| Cloud sync, fax, e-sign, AI extras | Intentionally **not** replicated — they are cloud services, not local scanning |

**Everything runs 100% on-device.** The app has zero network permissions, uses bundled ML models, and needs no Google Play Services.

## Tech stack

- **UI:** Kotlin + Jetpack Compose (Material 3)
- **Camera:** CameraX (preview + RGBA `ImageAnalysis` + `ImageCapture`)
- **Vision:** OpenCV 4.10 (`org.opencv:opencv` from Maven Central)
- **OCR:** ML Kit Text Recognition v2 (Latin, bundled) + Tesseract4Android 4.8 (ben+eng)
- **PDF:** Android `PdfDocument` (image PDFs) + PDFBox-Android (searchable text layer, embedded Noto Sans Bengali)
- **Storage:** Room database + app-private file storage, DataStore for settings
- **Min SDK:** Android 7.0 (API 24), target SDK 34

## Architecture

```
app/
├── data/            Room entities/DAOs, repository, DataStore settings, scan session
├── domain/          ScannerVision (OpenCV), OcrEngine (ML Kit), TessOcr, PdfExporter,
│                    PageSizeCalculator, Quad geometry (all unit-testable core logic)
├── ui/              Compose screens: home → capture → crop → filter → detail
│                    + PDF export, OCR editor, settings
└── util/            Image I/O with EXIF, sharing/saving/printing
```

Scan flow (like CamScanner): **Capture → Crop → Enhance → Save**. OCR and PDF export are
on-demand from the document screen.

## Building

```bash
./gradlew :app:assembleDebug     # debug APK
./gradlew :app:testDebugUnitTest # unit tests
./gradlew :app:assembleRelease   # release APK (signed with CI key if configured)
```

Requirements: JDK 17, Android SDK 34.

## CI/CD

Two GitHub Actions workflows ship with the repository:

1. **`ci.yml`** — on every push/PR to `main`: builds the debug APK, runs unit tests, runs lint,
   uploads the APK as an artifact.
2. **`release.yml`** — on tag push `vX.Y.Z`: builds a **signed release APK** and attaches it to a
   GitHub Release with generated notes.

### Making signed releases work

Generate a keystore once:

```bash
keytool -genkey -v -keystore keystore.jks -keyalg RSA -keysize 2048 \
  -validity 10000 -alias localscanner
base64 -w0 keystore.jks   # copy the output
```

Then add these **repository secrets** (Settings → Secrets and variables → Actions):

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | base64 of the keystore |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | `localscanner` |
| `KEY_PASSWORD` | key password |

Without secrets the workflow still runs — the APK falls back to the debug signing key.
Never commit `keystore.jks` to the repository (it is git-ignored).

To cut a release:

```bash
git tag v1.0.0 && git push origin v1.0.0
```

## Roadmap

- Dewarping of curved pages (curve correction)
- OCR: more Tesseract languages (Hindi, Arabic, ...) via downloadable traineddata
- ID-card mode (front/back on one page)
- Encrypted PDF export
- Per-document password lock

## License

Apache License 2.0. Third-party components: OpenCV (Apache 2.0), Tesseract (Apache 2.0),
tessdata_fast (Apache 2.0), PDFBox-Android (Apache 2.0), ML Kit (binding under Apache 2.0,
see Google's terms), Noto Sans Bengali (OFL).
