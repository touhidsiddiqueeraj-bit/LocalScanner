package com.localscanner.app.domain

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * OpenCV-powered document scanning pipeline replicating CamScanner's local
 * vision stack: live edge detection, perspective correction ("auto-crop and
 * straighten") and the enhancement filters (Magic Color, Grayscale, B&W).
 *
 * The edge-detection stage merges the consensus of the battle-tested
 * open-source scanners (LiveEdgeDetection lineage, Scanbot's reference
 * pipeline, makeacopy) with cluttered-scene sources proven against real
 * desk photos:
 *
 *  1. Candidate quads from five independent sources, because any single one
 *     fails on some scenes:
 *       - "paper flatten": normalize -> THRESH_TRUNC 150 -> normalize ->
 *         Canny(200, 75) -> morphological close 10x10 (LiveEdgeDetection
 *         lineage; white paper on textured/darker desks). Only runs when the
 *         scene actually has a dark background (10th-percentile luminance
 *         <= 140) - on uniformly bright frames THRESH_TRUNC clamps
 *         everything and the renormalize amplifies noise into chaos;
 *       - "otsu": Otsu binarization -> Canny(50, 150) -> close 5x5 (Scanbot's
 *         reference pipeline; low-contrast backgrounds);
 *       - "otsu blobs": the Otsu binary at two close/open scales - the
 *         classic largest-bright-blob source, separating paper from slightly
 *         darker/coloured backgrounds even when Canny sees only faint edges;
 *       - "mask blobs": bright low-saturation paper mask (HSV) at three
 *         close/open scales - separates paper from white clutter;
 *       - "flood": the paper mask flooded with long structural Canny
 *         components (>= 35% frame diagonal) as barriers, splitting paper
 *         from clutter that touches it (keyboard, tiles). The HSV sources
 *         only run when the mask actually separates something (8-65% frame
 *         coverage) - on white-on-white scenes the mask covers everything
 *         and its blobs would span the whole frame.
 *  2. For every source contour: convex hull -> approxPolyDP (escalating
 *     epsilon, 4- or 5-point quads with curled-corner recovery) plus the
 *     extreme sum/difference points (robust for curved page blobs).
 *  3. Validation: area 4-97% of the frame, strictly convex, no interior
 *     angle sharper than ~60 degrees, quad fills >= 65% of its contour,
 *     at most 2 corners touching the frame border.
 *  4. Highest score wins (area + fill - border-touch - sharp-angle); null
 *     when nothing document-like is found, so callers decide the fallback.
 */
object ScannerVision {

    /** Minimum share of the image the document quad must cover. */
    private const val MIN_AREA_FRACTION = 0.04f

    /** Above this the "quad" is suspiciously the whole frame. */
    private const val MAX_AREA_FRACTION = 0.97f

    /** Quads whose corners nearly all sit on the frame border are artifacts. */
    private const val MAX_BORDER_TOUCHES = 2

    /** How well the approximated quad must fill its source contour. */
    private const val MIN_FILL_RATIO = 0.65f

    /** Reject quads with an interior angle sharper than ~60 degrees. */
    private const val MAX_SHARP_ANGLE_COS = 0.5f

    /** Source contours smaller than this share of the frame are noise. */
    private const val MIN_SOURCE_AREA = 0.04

    /** Long structural Canny components (flood barriers) threshold. */
    private const val LONG_EDGE_DIAG = 0.35

    /** Below 8% mask coverage the HSV sources find nothing; above 65% they span the frame. */
    private const val MASK_COVERAGE_MIN = 0.08

    /** @see MASK_COVERAGE_MIN */
    private const val MASK_COVERAGE_MAX = 0.65

    /** Strategy A only helps when a real dark background exists. */
    private const val DARK_BACKGROUND_P10 = 140.0

    /**
     * Detects the dominant document quad in the bitmap.
     * Returns a quad in normalized [0..1] coordinates, or null when nothing
     * document-like was found (callers decide the fallback, not this method).
     */
    fun detectQuad(src: Bitmap, maxDim: Int = 448): QuadF? {
        val scale = min(1f, maxDim.toFloat() / max(src.width, src.height).toFloat())
        val w = max(64, (src.width * scale).toInt())
        val h = max(64, (src.height * scale).toInt())
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(src, w, h, true) else src

        val rgba = Mat()
        val gray = Mat()
        val blurred = Mat()
        val bgr = Mat()
        val hsv = Mat()
        val paperMask = Mat()
        val structural = Mat()
        val otsuBinary = Mat()
        val edges = Mat()
        return try {
            Utils.bitmapToMat(scaled, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
            // Paper-ish mask: low saturation (white/grey paper) + bright.
            // inRange is inclusive; matches S in [0,69] and V in [141,255].
            Core.inRange(hsv, Scalar(0.0, 0.0, 141.0), Scalar(180.0, 69.0, 255.0), paperMask)

            val imgArea = w.toDouble() * h.toDouble()
            val candidates = mutableListOf<QuadCandidate>()

            // Source A: bright-paper flattening then Canny (LiveEdge lineage).
            // Guarded: without a real dark background THRESH_TRUNC clamps the
            // whole frame and the renormalize turns noise into chaos.
            if (percentile(gray, 10.0) <= DARK_BACKGROUND_P10) {
                edgesFromPaperStrategy(blurred, edges)
                collectFromEdges(edges, w, h, imgArea, candidates)
            }

            // Source B + B2: Otsu binarization - Canny edges (Scanbot) and
            // bright-blob quads at two close/open scales.
            edgesFromOtsuStrategy(blurred, otsuBinary, edges)
            collectFromEdges(edges, w, h, imgArea, candidates)
            otsuBlobQuads(otsuBinary, w, h, imgArea, candidates)

            // Sources D + E (HSV mask blobs / flood): only when the mask
            // actually separates paper from its surroundings.
            val maskCoverage = Core.countNonZero(paperMask).toDouble() / imgArea
            if (maskCoverage in MASK_COVERAGE_MIN..MASK_COVERAGE_MAX) {
                maskBlobQuads(paperMask, w, h, imgArea, candidates)
                structuralEdges(gray, max(w, h).toFloat(), structural)
                floodQuads(paperMask, structural, w, h, imgArea, candidates)
            }

            var best: QuadF? = null
            var bestScore = Float.NEGATIVE_INFINITY
            for (c in candidates) {
                val score = c.score()
                if (score > bestScore) {
                    bestScore = score
                    best = c.quad
                }
            }
            best
        } catch (t: Throwable) {
            null
        } finally {
            rgba.release()
            gray.release()
            blurred.release()
            bgr.release()
            hsv.release()
            paperMask.release()
            structural.release()
            otsuBinary.release()
            edges.release()
            if (scaled !== src) scaled.recycle()
        }
    }

    /**
     * Snaps quad edges to nearby strong gradients on the full-resolution
     * bitmap (makeacopy-style edge refinement, simplified). Each edge is
     * sampled along its length, the strongest nearby gradient within a small
     * search window wins, refined samples are least-squares fitted and the
     * fitted lines are intersected into precise corners. Strict guards revert
     * to the input quad whenever refinement would degrade it.
     */
    fun refineQuad(src: Bitmap, quad: QuadF): QuadF {
        val w = src.width
        val h = src.height
        if (w < 64 || h < 64) return quad
        val rgba = Mat()
        val gray = Mat()
        return try {
            Utils.bitmapToMat(src, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(3.0, 3.0), 0.0)
            val buf = ByteArray(w * h)
            gray.get(0, 0, buf)
            refineQuadFromGray(buf, w, h, quad) ?: quad
        } catch (t: Throwable) {
            quad
        } finally {
            rgba.release()
            gray.release()
        }
    }

    /**
     * True when the image border is mostly paper-bright, i.e. the document
     * already fills the frame and the correct fallback selection is the full
     * frame rather than an inset rectangle.
     */
    fun looksPaperFilled(src: Bitmap): Boolean {
        val biggest = max(src.width, src.height)
        val scaled = if (biggest > 320) {
            val s = 320f / biggest
            Bitmap.createScaledBitmap(src, max(32, (src.width * s).toInt()), max(32, (src.height * s).toInt()), true)
        } else {
            src
        }
        val rgba = Mat()
        val gray = Mat()
        return try {
            Utils.bitmapToMat(scaled, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val w = gray.cols()
            val h = gray.rows()
            val band = max(2, (min(w, h) * 0.08f).toInt())
            val buf = ByteArray(w * h)
            gray.get(0, 0, buf)
            var total = 0
            var bright = 0
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val onEdge = x < band || y < band || x >= w - band || y >= h - band
                    if (!onEdge) continue
                    total++
                    if ((buf[y * w + x].toInt() and 0xFF) >= 185) bright++
                }
            }
            total > 0 && bright.toFloat() / total > 0.55f
        } catch (t: Throwable) {
            false
        } finally {
            rgba.release()
            gray.release()
            if (scaled !== src) scaled.recycle()
        }
    }

    // ---------------------------------------------------------------------
    // Detection internals
    // ---------------------------------------------------------------------

    /** A validated quad candidate with the metrics used for scoring. */
    private data class QuadCandidate(
        val quad: QuadF,
        val fill: Float,
        val borderTouches: Int,
        val angleCos: Float,
    ) {
        fun score(): Float = quad.area() + 0.25f * fill - 0.04f * borderTouches - 0.2f * angleCos
    }

    /**
     * Strategy A preprocessing: normalize -> THRESH_TRUNC at 150 ("paper is
     * bright, make it uniformly bright") -> normalize -> Canny(200, 75) ->
     * morphological close 10x10 to bridge the gaps of ragged paper edges.
     */
    private fun edgesFromPaperStrategy(blurred: Mat, out: Mat) {
        val norm = Mat()
        val truncated = Mat()
        val renorm = Mat()
        val cut = Mat()
        val closed = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(10.0, 10.0))
        try {
            Core.normalize(blurred, norm, 0.0, 255.0, Core.NORM_MINMAX)
            Imgproc.threshold(norm, truncated, 150.0, 255.0, Imgproc.THRESH_TRUNC)
            Core.normalize(truncated, renorm, 0.0, 255.0, Core.NORM_MINMAX)
            Imgproc.Canny(renorm, cut, 200.0, 75.0)
            Imgproc.morphologyEx(cut, closed, Imgproc.MORPH_CLOSE, kernel)
            closed.copyTo(out)
        } finally {
            norm.release()
            truncated.release()
            renorm.release()
            cut.release()
            closed.release()
            kernel.release()
        }
    }

    /**
     * Strategy B preprocessing: Otsu binarization then Canny(50, 150) with a
     * light close - separates a white document from a mid-tone background
     * even when the overall contrast is low.
     */
    private fun edgesFromOtsuStrategy(blurred: Mat, out: Mat) {
        val bin = Mat()
        val cut = Mat()
        val closed = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        try {
            Imgproc.threshold(blurred, bin, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            Imgproc.Canny(bin, cut, 50.0, 150.0)
            Imgproc.morphologyEx(cut, closed, Imgproc.MORPH_CLOSE, kernel)
            closed.copyTo(out)
        } finally {
            bin.release()
            cut.release()
            closed.release()
            kernel.release()
        }
    }

    /**
     * Strategy B preprocessing: Otsu binarization; [binaryOut] receives the
     * binary (for the blob source), [out] the Canny(50, 150) edges with a
     * light close - separates a white document from a mid-tone background
     * even when the overall contrast is low.
     */
    private fun edgesFromOtsuStrategy(blurred: Mat, binaryOut: Mat, out: Mat) {
        val cut = Mat()
        val closed = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        try {
            Imgproc.threshold(blurred, binaryOut, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            Imgproc.Canny(binaryOut, cut, 50.0, 150.0)
            Imgproc.morphologyEx(cut, closed, Imgproc.MORPH_CLOSE, kernel)
            closed.copyTo(out)
        } finally {
            cut.release()
            closed.release()
            kernel.release()
        }
    }

    /**
     * Source B2: quads of bright blobs in the Otsu binary at two close/open
     * scales - the classic "largest bright region" document detector. Works
     * when the paper/background contrast is too faint for Canny.
     */
    private fun otsuBlobQuads(binary: Mat, w: Int, h: Int, imgArea: Double, out: MutableList<QuadCandidate>) {
        val blob = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        try {
            val shortSide = min(w, h).toFloat()
            for (rel in intArrayOf(28, 0)) {
                binary.copyTo(blob)
                if (rel > 0) {
                    val k = max(9, (shortSide.toInt() / rel) or 1)
                    val ker = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(k.toDouble(), k.toDouble()))
                    try {
                        Imgproc.morphologyEx(blob, blob, Imgproc.MORPH_CLOSE, ker)
                        Imgproc.morphologyEx(blob, blob, Imgproc.MORPH_OPEN, ker)
                    } finally {
                        ker.release()
                    }
                }
                contours.clear()
                Imgproc.findContours(blob, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                extractQuadsFromContours(contours, w, h, imgArea, maxN = 6, out = out)
                contours.forEach { it.release() }
                contours.clear()
            }
        } finally {
            blob.release()
            hierarchy.release()
        }
    }

    /**
     * Marks long connected Canny components (object boundaries) into [out].
     * Text strokes and table rules are short and are filtered out.
     */
    private fun structuralEdges(gray: Mat, dim: Float, out: Mat) {
        val edges = Mat()
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val kept = Mat()
        val ker = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        try {
            Imgproc.Canny(gray, edges, 30.0, 90.0)
            val n = Imgproc.connectedComponentsWithStats(edges, labels, stats, centroids)
            val st = IntArray(5)
            for (i in 1 until n) {
                stats.get(i, 0, st)
                val bw = st[2].toDouble()
                val bh = st[3].toDouble()
                val diag = sqrt(bw * bw + bh * bh)
                if (diag >= LONG_EDGE_DIAG * dim) {
                    Core.compare(labels, Scalar(i.toDouble()), kept, Core.CMP_EQ)
                    if (out.empty()) kept.copyTo(out) else Core.bitwise_or(out, kept, out)
                }
            }
            Imgproc.dilate(out, out, ker)
        } finally {
            edges.release()
            labels.release()
            stats.release()
            centroids.release()
            kept.release()
            ker.release()
        }
    }

    /**
     * Paper-mask blobs at three close/open scales ("mask" source). The HSV
     * mask isolates bright low-saturation regions, so paper separates from
     * coloured desks; the close/open rounds merge the paper region and drop
     * small white clutter.
     */
    private fun maskBlobQuads(
        paperMask: Mat,
        w: Int,
        h: Int,
        imgArea: Double,
        out: MutableList<QuadCandidate>,
    ) {
        val m = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        try {
            val shortSide = min(w, h).toFloat()
            for (rel in intArrayOf(28, 14, 0)) {
                paperMask.copyTo(m)
                if (rel > 0) {
                    val k = max(9, (shortSide.toInt() / rel) or 1)
                    val ker = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(k.toDouble(), k.toDouble()))
                    try {
                        Imgproc.morphologyEx(m, m, Imgproc.MORPH_CLOSE, ker)
                        Imgproc.morphologyEx(m, m, Imgproc.MORPH_OPEN, ker)
                    } finally {
                        ker.release()
                    }
                }
                contours.clear()
                Imgproc.findContours(m, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                extractQuadsFromContours(contours, w, h, imgArea, maxN = 4, out = out)
                contours.forEach { it.release() }
                contours.clear()
            }
        } finally {
            m.release()
            hierarchy.release()
        }
    }

    /**
     * Floods the paper mask while blocking long structural edges ("flood"
     * source): the barriers split paper from white clutter that touches it,
     * so a page next to a white keyboard becomes its own region.
     */
    private fun floodQuads(
        paperMask: Mat,
        structural: Mat,
        w: Int,
        h: Int,
        imgArea: Double,
        out: MutableList<QuadCandidate>,
    ) {
        val inv = Mat()
        val allowed = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        try {
            Core.bitwise_not(structural, inv)
            Core.bitwise_and(paperMask, inv, allowed)
            Imgproc.findContours(allowed, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            extractQuadsFromContours(contours, w, h, imgArea, maxN = 4, out = out)
        } finally {
            inv.release()
            allowed.release()
            hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    /**
     * Extracts validated quad candidates from one Canny edge map (sources
     * A/B/C).
     */
    private fun collectFromEdges(
        edges: Mat,
        w: Int,
        h: Int,
        imgArea: Double,
        out: MutableList<QuadCandidate>,
    ) {
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        try {
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            extractQuadsFromContours(contours, w, h, imgArea, maxN = 8, out = out)
        } finally {
            hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    /**
     * Turns the top contours into validated candidate quads. For each
     * contour: convex hull, then two corner extractors - escalating-epsilon
     * approxPolyDP (4- or 5-point, curled-corner recovery) and the extreme
     * sum/difference points (robust for curved page blobs) - deduped before
     * validation.
     */
    private fun extractQuadsFromContours(
        contours: List<MatOfPoint>,
        w: Int,
        h: Int,
        imgArea: Double,
        maxN: Int,
        out: MutableList<QuadCandidate>,
    ) {
        val sorted = contours.sortedByDescending { Imgproc.contourArea(it) }
        for (contour in sorted.take(maxN)) {
            val hullInt = MatOfInt()
            var hullPts: MatOfPoint? = null
            var hull2f: MatOfPoint2f? = null
            var approx: MatOfPoint2f? = null
            try {
                if (Imgproc.contourArea(contour) < imgArea * MIN_SOURCE_AREA) continue
                Imgproc.convexHull(contour, hullInt)
                val contourPts = contour.toArray()
                hullPts = MatOfPoint(*hullInt.toArray().map { contourPts[it.toInt()] }.toTypedArray())
                hull2f = MatOfPoint2f(*hullPts.toArray())
                val hullArea = Imgproc.contourArea(hull2f)
                if (hullArea <= 0.0) continue

                val peri = Imgproc.arcLength(hull2f, true)
                approx = MatOfPoint2f()
                var approxQuad: QuadF? = null
                for (eps in doubleArrayOf(0.02, 0.03, 0.05)) {
                    Imgproc.approxPolyDP(hull2f, approx, eps * peri, true)
                    if (approx.total() == 4L || approx.total() == 5L) {
                        val polygon = approx.toList().map { Pt((it.x / w).toFloat(), (it.y / h).toFloat()) }
                        approxQuad = QuadF.fromPolygon(polygon)
                        if (approxQuad != null) break
                    }
                }
                val extremeQuad = extremeQuad(contourPts, w, h)

                // Deduplicate near-identical quads (same corners by centroid).
                val seen = mutableListOf<QuadF>()
                for (q in listOfNotNull(approxQuad, extremeQuad)) {
                    val dup = seen.any {
                        Math.abs(it.centroid().x - q.centroid().x) < 0.08f &&
                            Math.abs(it.centroid().y - q.centroid().y) < 0.08f
                    }
                    if (!dup) {
                        seen.add(q)
                        validateQuad(q, hullArea, imgArea)?.let { out.add(it) }
                    }
                }
            } finally {
                hullInt.release()
                hullPts?.release()
                hull2f?.release()
                approx?.release()
            }
        }
    }

    /** Validates one quad against its source contour; null when implausible. */
    private fun validateQuad(quad: QuadF, hullArea: Double, imgArea: Double): QuadCandidate? {
        val areaFrac = quad.area()
        if (areaFrac < MIN_AREA_FRACTION || areaFrac > MAX_AREA_FRACTION) return null
        if (!quad.isConvex()) return null
        val angleCos = quad.maxAngleCos()
        if (angleCos > MAX_SHARP_ANGLE_COS) return null
        val quadAreaPx = areaFrac.toDouble() * imgArea
        val fill = (quadAreaPx / hullArea).toFloat()
        if (fill < MIN_FILL_RATIO) return null
        var borderTouches = 0
        for (p in quad.points()) {
            if (p.x < 0.02f || p.x > 0.98f || p.y < 0.02f || p.y > 0.98f) borderTouches++
        }
        if (borderTouches > MAX_BORDER_TOUCHES) return null
        return QuadCandidate(quad, fill, borderTouches, angleCos)
    }

    /**
     * Corners from the extreme sum/difference contour points - robust for
     * curved page blobs where approxPolyDP never yields exactly 4 points.
     */
    private fun extremeQuad(pts: Array<Point>, w: Int, h: Int): QuadF? {
        if (pts.size < 4) return null
        return try {
            var minSum = Double.MAX_VALUE
            var maxSum = -Double.MAX_VALUE
            var minDiff = Double.MAX_VALUE
            var maxDiff = -Double.MAX_VALUE
            var pMinSum = pts[0]
            var pMaxSum = pts[0]
            var pMinDiff = pts[0]
            var pMaxDiff = pts[0]
            for (p in pts) {
                val s = p.x + p.y
                val d = p.y - p.x
                if (s < minSum) {
                    minSum = s
                    pMinSum = p
                }
                if (s > maxSum) {
                    maxSum = s
                    pMaxSum = p
                }
                if (d < minDiff) {
                    minDiff = d
                    pMinDiff = p
                }
                if (d > maxDiff) {
                    maxDiff = d
                    pMaxDiff = p
                }
            }
            val norm = { p: Point -> Pt((p.x / w).toFloat(), (p.y / h).toFloat()) }
            QuadF.ordered(listOf(norm(pMinSum), norm(pMinDiff), norm(pMaxSum), norm(pMaxDiff)))
        } catch (t: Throwable) {
            null
        }
    }

    /** Median of the gray histogram (more robust against outliers than the mean). */
    private fun percentile(gray: Mat, p: Double): Double {
        val size = 256
        val hist = Mat()
        return try {
            Imgproc.calcHist(listOf(gray), MatOfInt(0), Mat(), hist, MatOfInt(size), MatOfFloat(0f, 256f))
            val total = gray.total()
            val data = FloatArray(size)
            hist.get(0, 0, data)
            val target = total * p / 100.0
            var acc = 0.0
            var value = 0.0
            for (i in 0 until size) {
                acc += data[i].toDouble()
                if (acc >= target) {
                    value = i.toDouble()
                    break
                }
            }
            value
        } catch (t: Throwable) {
            0.0
        } finally {
            hist.release()
        }
    }

    // ---------------------------------------------------------------------
    // Gradient edge-snap refinement internals
    // ---------------------------------------------------------------------

    internal fun refineQuadFromGray(buf: ByteArray, w: Int, h: Int, quad: QuadF): QuadF? {
        val diag = Math.sqrt((w * w + h * h).toDouble()).toFloat()
        val search = (diag * 0.015f).coerceIn(6f, 48f)
        val samples = 24
        val edges = listOf(
            quad.tl to quad.tr,
            quad.tr to quad.br,
            quad.br to quad.bl,
            quad.bl to quad.tl,
        )

        val lines = mutableListOf<LineF>()
        for ((a, b) in edges) {
            val ax = a.x * w
            val ay = a.y * h
            val bx = b.x * w
            val by = b.y * h
            val ex = bx - ax
            val ey = by - ay
            val len = Math.sqrt((ex * ex + ey * ey).toDouble()).toFloat()
            if (len < 8f) {
                lines.add(LineF(ax, ay, 1f, 0f))
                continue
            }
            val nx = -ey / len
            val ny = ex / len
            val snapped = mutableListOf<Pt>()
            for (i in 0 until samples) {
                val t = (i + 0.5f) / samples
                val px = ax + ex * t
                val py = ay + ey * t
                val cand = snapPoint(buf, w, h, px, py, nx, ny, search)
                if (cand != null) snapped.add(cand)
            }
            // Fit a line through the snapped samples; if too few snapped,
            // keep the original edge as the line (conservative).
            val fitted = if (snapped.size >= samples * 0.4f) LineF.fit(snapped) else null
            lines.add(fitted ?: LineF(ax, ay, ex / len, ey / len))
        }

        val corners = mutableListOf<Pt>()
        for (i in 0 until 4) {
            val p = lines[i].intersect(lines[(i + 1) % 4]) ?: return null
            if (p.x < -search || p.x > w + search || p.y < -search || p.y > h + search) return null
            corners.add(p)
        }
        val refined = QuadF.ordered(corners) ?: return null

        // Guards: refinement must be a small, sane correction.
        val maxShift = diag * 0.025f
        val orig = quad.points()
        for (i in 0 until 4) {
            val dx = (corners[i].x - orig[i].x) * w
            val dy = (corners[i].y - orig[i].y) * h
            if (Math.sqrt((dx * dx + dy * dy).toDouble()) > maxShift) return null
        }
        if (!refined.isConvex() || refined.maxAngleCos() > 0.6f) return null
        val a0 = quad.area()
        val a1 = refined.area()
        if (a0 <= 0f) return null
        val ratio = a1 / a0
        if (ratio < 0.75f || ratio > 1.33f) return null
        if (a1 < 0.03f || a1 > 0.985f) return null
        return refined
    }

    /**
     * Walks along [nx],[ny] within +/- [search] px around (px, py) and snaps
     * to the nearest gradient peak with at least 55% of the window's peak
     * magnitude (preferring nearby edges over farther text lines).
     */
    private fun snapPoint(
        buf: ByteArray,
        w: Int,
        h: Int,
        px: Float,
        py: Float,
        nx: Float,
        ny: Float,
        search: Float,
    ): Pt? {
        val r = search.toInt()
        if (r < 3) return null
        val n = 2 * r + 1
        val profile = FloatArray(n)
        var peak = 0f
        for (k in 0 until n) {
            val t = (k - r).toFloat()
            val x = (px + t * nx).toInt()
            val y = (py + t * ny).toInt()
            if (x < 1 || y < 1 || x > w - 2 || y > h - 2) continue
            val gx = (lumAt(buf, w, h, x + 1, y) - lumAt(buf, w, h, x - 1, y)).toFloat()
            val gy = (lumAt(buf, w, h, x, y + 1) - lumAt(buf, w, h, x, y - 1)).toFloat()
            val magnitude = Math.abs(gx * nx + gy * ny)
            profile[k] = magnitude
            if (magnitude > peak) peak = magnitude
        }
        // No meaningful edge anywhere in the window.
        if (peak < 24f) return null
        val threshold = peak * 0.55f
        var bestK = -1
        scanOut@ for (d in 0..r) {
            for (k in intArrayOf(r - d, r + d).distinct()) {
                if (k in 0 until n && profile[k] >= threshold) {
                    bestK = k
                    break@scanOut
                }
            }
        }
        if (bestK < 0) return null
        val t = (bestK - r).toFloat()
        return Pt((px + t * nx) / w, (py + t * ny) / h)
    }

    private fun lumAt(buf: ByteArray, w: Int, h: Int, x: Int, y: Int): Int {
        val cx = x.coerceIn(0, w - 1)
        val cy = y.coerceIn(0, h - 1)
        return buf[cy * w + cx].toInt() and 0xFF
    }

    /**
     * Perspective-corrects the source bitmap using the quad (normalized coords).
     * The output dimensions follow the quad's real edge lengths, capped at maxDim.
     */
    fun warpPerspective(src: Bitmap, quad: QuadF, maxDim: Int = 2048): Bitmap? {
        val sx = src.width.toFloat()
        val sy = src.height.toFloat()

        val topW = dist(quad.tl, quad.tr, sx, sy)
        val botW = dist(quad.bl, quad.br, sx, sy)
        val leftH = dist(quad.tl, quad.bl, sx, sy)
        val rightH = dist(quad.tr, quad.br, sx, sy)
        var outW = max(topW, botW)
        var outH = max(leftH, rightH)
        if (outW < 32f || outH < 32f) return null

        val ds = min(1f, maxDim / max(outW, outH))
        val dstW = max(32, (outW * ds).toInt())
        val dstH = max(32, (outH * ds).toInt())

        val srcPts = MatOfPoint2f(
            Point((quad.tl.x * sx).toDouble(), (quad.tl.y * sy).toDouble()),
            Point((quad.tr.x * sx).toDouble(), (quad.tr.y * sy).toDouble()),
            Point((quad.br.x * sx).toDouble(), (quad.br.y * sy).toDouble()),
            Point((quad.bl.x * sx).toDouble(), (quad.bl.y * sy).toDouble()),
        )
        val dstPts = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(dstW - 1.0, 0.0),
            Point(dstW - 1.0, dstH - 1.0),
            Point(0.0, dstH - 1.0),
        )
        val srcMat = Mat()
        val out = Mat()
        return try {
            Utils.bitmapToMat(src, srcMat)
            val transform = Imgproc.getPerspectiveTransform(srcPts, dstPts)
            Imgproc.warpPerspective(srcMat, out, transform, Size(dstW.toDouble(), dstH.toDouble()))
            val result = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(out, result)
            result
        } catch (t: Throwable) {
            null
        } finally {
            srcPts.release()
            dstPts.release()
            srcMat.release()
            out.release()
        }
    }

    /**
     * Applies an enhancement filter plus manual brightness/contrast.
     *
     * @param brightness in [-50..50]
     * @param contrast in [50..150] (100 = neutral)
     */
    fun applyFilter(src: Bitmap, filter: FilterType, brightness: Int = 0, contrast: Int = 100): Bitmap {
        val alpha = contrast / 100f
        val beta = brightness.toFloat()

        val rgba = Mat()
        val bgr = Mat()
        var processed: Mat? = null
        val out = Mat()
        val outRgba = Mat()
        return try {
            Utils.bitmapToMat(src, rgba)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            processed = when (filter) {
                FilterType.ORIGINAL -> bgr
                FilterType.MAGIC_COLOR -> magicColor(bgr)
                FilterType.GRAY -> grayFilter(bgr)
                FilterType.BW -> bwFilter(bgr)
            }
            Core.convertScaleAbs(processed, out, alpha.toDouble(), beta.toDouble())
            Imgproc.cvtColor(out, outRgba, Imgproc.COLOR_BGR2RGBA)
            val result = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(outRgba, result)
            result
        } catch (t: Throwable) {
            src.copy(Bitmap.Config.ARGB_8888, true)
        } finally {
            rgba.release()
            bgr.release()
            out.release()
            outRgba.release()
            if (processed != null && processed !== bgr) processed.release()
        }
    }

    /**
     * Magic Color: shadow removal by dividing out the illumination background,
     * followed by local contrast (CLAHE) and a mild saturation boost.
     */
    private fun magicColor(srcBgr: Mat): Mat {
        val f = Mat()
        val bg = Mat()
        val fb = Mat()
        val lab = Mat()
        val hsv = Mat()
        return try {
            // 1) illumination normalisation (shadow / uneven lighting removal)
            srcBgr.convertTo(f, CvType.CV_32FC3)
            Imgproc.GaussianBlur(srcBgr, bg, Size(0.0, 0.0), 40.0)
            bg.convertTo(fb, CvType.CV_32FC3)
            Core.add(fb, Scalar(10.0, 10.0, 10.0), fb)
            Core.divide(f, fb, f)
            Core.multiply(f, Scalar(255.0, 255.0, 255.0), f)
            f.convertTo(f, CvType.CV_8UC3)

            // 2) CLAHE local contrast on the L channel of LAB
            Imgproc.cvtColor(f, lab, Imgproc.COLOR_BGR2Lab)
            val ch = ArrayList<Mat>(3)
            Core.split(lab, ch)
            val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
            val lEnh = Mat()
            clahe.apply(ch[0], lEnh)
            ch[0].release()
            ch[0] = lEnh
            val merged = Mat()
            Core.merge(ch, merged)
            ch.forEach { it.release() }
            Imgproc.cvtColor(merged, lab, Imgproc.COLOR_Lab2BGR)
            merged.release()

            // 3) mild saturation boost
            Imgproc.cvtColor(lab, hsv, Imgproc.COLOR_BGR2HSV)
            val hch = ArrayList<Mat>(3)
            Core.split(hsv, hch)
            val sBoost = Mat()
            Core.convertScaleAbs(hch[1], sBoost, 1.25, 0.0)
            hch[1].release()
            hch[1] = sBoost
            val hsvOut = Mat()
            Core.merge(hch, hsvOut)
            hch.forEach { it.release() }
            val final = Mat()
            Imgproc.cvtColor(hsvOut, final, Imgproc.COLOR_HSV2BGR)
            hsvOut.release()
            final
        } finally {
            f.release()
            bg.release()
            fb.release()
            lab.release()
            hsv.release()
        }
    }

    private fun grayFilter(srcBgr: Mat): Mat {
        val g = Mat()
        val out = Mat()
        try {
            Imgproc.cvtColor(srcBgr, g, Imgproc.COLOR_BGR2GRAY)
            Imgproc.cvtColor(g, out, Imgproc.COLOR_GRAY2BGR)
        } finally {
            g.release()
        }
        return out
    }

    private fun bwFilter(srcBgr: Mat): Mat {
        val gray = Mat()
        val bw = Mat()
        val out = Mat()
        try {
            Imgproc.cvtColor(srcBgr, gray, Imgproc.COLOR_BGR2GRAY)
            var blockSize = max(srcBgr.width(), srcBgr.height()) / 16
            if (blockSize % 2 == 0) blockSize += 1
            blockSize = blockSize.coerceIn(15, 51)
            Imgproc.adaptiveThreshold(
                gray, bw, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY,
                blockSize, 11.0,
            )
            Imgproc.cvtColor(bw, out, Imgproc.COLOR_GRAY2BGR)
        } finally {
            gray.release()
            bw.release()
        }
        return out
    }

    private fun dist(a: Pt, b: Pt, sx: Float, sy: Float): Float {
        val dx = (a.x - b.x) * sx
        val dy = (a.y - b.y) * sy
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }
}
