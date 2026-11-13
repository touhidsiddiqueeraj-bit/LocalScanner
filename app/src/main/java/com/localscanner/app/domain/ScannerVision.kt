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
import org.opencv.core.Rect
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
 */
object ScannerVision {

    /** Minimum share of image area a plausible document quad must cover. */
    private const val MIN_QUAD_AREA = 0.08f

    /** Score/geometry thresholds validated against real desk photos. */
    private const val MIN_SCORED_AREA = 0.10
    private const val MAX_SCORED_AREA = 0.96
    private const val FRAME_TOUCH_TOL = 0.012
    private const val MIN_EDGE_SUPPORT = 0.30
    private const val LONG_EDGE_DIAG = 0.35

    /**
     * Detects the dominant document quad in the bitmap.
     * Returns a quad in normalized [0..1] coordinates, or null.
     *
     * Cluttered-scene strategy (validated against real photos of paper on a
     * wooden desk next to a white keyboard and bright floor tiles):
     *
     *  1. Candidate quads from four independent sources, because any single
     *     one fails on some scenes:
     *       - "flood": bright low-saturation paper mask split by LONG Canny
     *         components (bbox diagonal >= 35% of the frame). Long components
     *         are object boundaries (page edges, keyboard frame); short ones
     *         are text/table detail. Flooding the mask with those edges as
     *         barriers separates paper from white clutter that touches it.
     *       - mask blobs at three morphological close scales.
     *       - Canny contours under adaptive + fixed threshold sets.
     *     Corners come from multi-epsilon approxPolyDP AND from the extreme
     *     sum/difference points (robust for curved page blobs).
     *  2. Document-likelihood scoring: area share, contour fill, how many
     *     corners hug the frame, centrality and - decisively - edge support:
     *     a real document's four sides must sit on actual image edges, which
     *     rejects the frame-spanning mega-contours that plague busy photos.
     *  3. Highest score wins; null when nothing document-like is found.
     */
    fun detectQuad(src: Bitmap, maxDim: Int = 448): QuadF? {
        val scale = min(1f, maxDim.toFloat() / max(src.width, src.height).toFloat())
        val w = max(32, (src.width * scale).toInt())
        val h = max(32, (src.height * scale).toInt())
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(src, w, h, true) else src

        val rgba = Mat()
        val gray = Mat()
        val bgr = Mat()
        val hsv = Mat()
        val mask = Mat()
        val struct = Mat()
        val emap = Mat()
        val candidates = mutableListOf<Candidate>()
        return try {
            Utils.bitmapToMat(scaled, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
            // Paper-ish mask: low saturation (white/grey paper) + bright.
            // inRange is inclusive; matches S in [0,69] and V in [141,255].
            Core.inRange(hsv, Scalar(0.0, 0.0, 141.0), Scalar(180.0, 69.0, 255.0), mask)

            val dim = max(w, h).toFloat()
            structuralEdges(gray, dim, struct)
            Imgproc.Canny(gray, emap, 30.0, 90.0)
            val emapKer = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 7.0))
            try {
                Imgproc.dilate(emap, emap, emapKer)
            } finally {
                emapKer.release()
            }

            val imgArea = w.toDouble() * h.toDouble()
            floodCandidates(mask, struct, imgArea, w, h, candidates)
            maskBlobCandidates(mask, imgArea, w, h, candidates)
            cannyCandidates(gray, imgArea, w, h, candidates)

            var best: QuadF? = null
            var bestScore = 0.0
            for (c in candidates) {
                val sc = scoreQuad(c.quad, c.area, emap)
                if (sc > bestScore) {
                    bestScore = sc
                    best = c.quad
                }
            }
            best
        } catch (t: Throwable) {
            null
        } finally {
            rgba.release()
            gray.release()
            bgr.release()
            hsv.release()
            mask.release()
            struct.release()
            emap.release()
            if (scaled !== src) scaled.recycle()
        }
    }

    /** One candidate quad with the area of the contour it came from. */
    private class Candidate(val quad: QuadF, val area: Double)

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
     * Floods the paper mask while blocking structural edges; each flooded
     * region >= 8% of the image yields candidates ("flood" source).
     */
    private fun floodCandidates(
        mask: Mat,
        struct: Mat,
        imgArea: Double,
        w: Int,
        h: Int,
        out: MutableList<Candidate>,
    ) {
        val inv = Mat()
        val allowed = Mat()
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val comp = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        try {
            Core.bitwise_not(struct, inv)
            Core.bitwise_and(mask, inv, allowed)
            val n = Imgproc.connectedComponentsWithStats(allowed, labels, stats, centroids)
            val st = IntArray(5)
            for (i in 1 until n) {
                stats.get(i, 0, st)
                if (st[4] < imgArea * MIN_QUAD_AREA) continue
                Core.compare(labels, Scalar(i.toDouble()), comp, Core.CMP_EQ)
                contours.clear()
                Imgproc.findContours(comp, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                addFromContours(contours, imgArea, w, h, out, maxN = 2)
                contours.forEach { it.release() }
                contours.clear()
            }
        } finally {
            inv.release()
            allowed.release()
            labels.release()
            stats.release()
            centroids.release()
            comp.release()
            hierarchy.release()
        }
    }

    /** Paper-mask blobs at several close scales ("mask" sources). */
    private fun maskBlobCandidates(
        mask: Mat,
        imgArea: Double,
        w: Int,
        h: Int,
        out: MutableList<Candidate>,
    ) {
        val m = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        try {
            val shortSide = min(w, h).toFloat()
            for (rel in intArrayOf(28, 14, 0)) {
                mask.copyTo(m)
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
                addFromContours(contours, imgArea, w, h, out, maxN = 4)
                contours.forEach { it.release() }
                contours.clear()
            }
        } finally {
            m.release()
            hierarchy.release()
        }
    }

    /** Canny contours under adaptive + fixed thresholds ("canny" sources). */
    private fun cannyCandidates(
        gray: Mat,
        imgArea: Double,
        w: Int,
        h: Int,
        out: MutableList<Candidate>,
    ) {
        val med = Mat()
        val edges = Mat()
        val closed = Mat()
        val hierarchy = Mat()
        val k5 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        val contours = mutableListOf<MatOfPoint>()
        try {
            Imgproc.medianBlur(gray, med, 5)
            val median = medianLuminance(med)
            val thresholdSets = mutableListOf<Pair<Double, Double>>()
            if (median > 8.0) {
                val low = max(0.0, 0.66 * median)
                val high = min(255.0, 1.33 * median)
                if (high > low) thresholdSets.add(low to high)
            }
            thresholdSets.add(30.0 to 90.0)
            thresholdSets.add(50.0 to 150.0)
            for ((lo, hi) in thresholdSets) {
                Imgproc.Canny(gray, edges, lo, hi)
                Imgproc.dilate(edges, edges, k5)
                Imgproc.morphologyEx(edges, closed, Imgproc.MORPH_CLOSE, k5)
                contours.clear()
                Imgproc.findContours(closed, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                addFromContours(contours, imgArea, w, h, out, maxN = 5)
                contours.forEach { it.release() }
                contours.clear()
            }
        } finally {
            med.release()
            edges.release()
            closed.release()
            hierarchy.release()
            k5.release()
        }
    }

    /** Turns the top contours into deduped candidate quads. */
    private fun addFromContours(
        contours: List<MatOfPoint>,
        imgArea: Double,
        w: Int,
        h: Int,
        out: MutableList<Candidate>,
        maxN: Int,
    ) {
        val sorted = contours.sortedByDescending { Imgproc.contourArea(it) }
        for (contour in sorted.take(maxN)) {
            val area = Imgproc.contourArea(contour)
            if (area < imgArea * MIN_QUAD_AREA) continue
            val approx = approxQuad(contour, w, h)
            val extreme = extremeQuad(contour, w, h)
            val list = listOf(approx, extreme).filterNotNull()
            val seen = mutableListOf<QuadF>()
            for (q in list) {
                val dup = seen.any {
                    kotlin.math.abs(it.points().sumOf { p -> p.x.toDouble() } - q.points().sumOf { p -> p.x.toDouble() }) < 0.16 &&
                        kotlin.math.abs(it.points().sumOf { p -> p.y.toDouble() } - q.points().sumOf { p -> p.y.toDouble() }) < 0.16
                }
                if (!dup) {
                    seen.add(q)
                    out.add(Candidate(q, area))
                }
            }
        }
    }

    /** approxPolyDP with escalating epsilon until a convex 4-gon appears. */
    private fun approxQuad(contour: MatOfPoint, w: Int, h: Int): QuadF? {
        val c2f = MatOfPoint2f(*contour.toArray())
        val approx = MatOfPoint2f()
        return try {
            val peri = Imgproc.arcLength(c2f, true)
            for (eps in doubleArrayOf(0.01, 0.02, 0.03, 0.05)) {
                Imgproc.approxPolyDP(c2f, approx, eps * peri, true)
                if (approx.total() == 4L && Imgproc.isContourConvex(MatOfPoint(*approx.toArray()))) {
                    val pts = approx.toList().map { Pt((it.x / w).toFloat(), (it.y / h).toFloat()) }
                    val quad = QuadF.ordered(pts) ?: return null
                    return quad
                }
            }
            null
        } catch (t: Throwable) {
            null
        } finally {
            c2f.release()
            approx.release()
        }
    }

    /**
     * Corners from the extreme sum/difference contour points - robust for
     * curved page blobs where approxPolyDP never yields exactly 4 points.
     */
    private fun extremeQuad(contour: MatOfPoint, w: Int, h: Int): QuadF? {
        val pts = contour.toArray()
        if (pts.size < 4) return null
        return try {
            var minSum = Double.MAX_VALUE; var maxSum = -Double.MAX_VALUE
            var minDiff = Double.MAX_VALUE; var maxDiff = -Double.MAX_VALUE
            var pMinSum = pts[0]; var pMaxSum = pts[0]
            var pMinDiff = pts[0]; var pMaxDiff = pts[0]
            for (p in pts) {
                val s = p.x + p.y
                val d = p.y - p.x
                if (s < minSum) { minSum = s; pMinSum = p }
                if (s > maxSum) { maxSum = s; pMaxSum = p }
                if (d < minDiff) { minDiff = d; pMinDiff = p }
                if (d > maxDiff) { maxDiff = d; pMaxDiff = p }
            }
            val norm = { p: Point -> Pt((p.x / w).toFloat(), (p.y / h).toFloat()) }
            QuadF.ordered(listOf(norm(pMinSum), norm(pMinDiff), norm(pMaxSum), norm(pMaxDiff)))
        } catch (t: Throwable) {
            null
        }
    }

    /** Document-likelihood score; 0.0 for implausible quads. */
    private fun scoreQuad(q: QuadF, contourArea: Double, emap: Mat): Double {
        val qa = q.area().toDouble()
        if (qa < MIN_SCORED_AREA || qa > MAX_SCORED_AREA) return 0.0
        val tf = touchesFrame(q)
        if (tf >= 3) return 0.0
        val fill = min(1.0, contourArea / qa)

        val sup = edgeSupport(q, emap)
        if (sup < MIN_EDGE_SUPPORT) return 0.0

        val border = min(
            minOf(q.tl.x, q.tl.y),
            minOf(
                minOf(1f - q.tr.x, q.tr.y),
                minOf(minOf(1f - q.br.x, 1f - q.br.y), minOf(q.bl.x, 1f - q.bl.y)),
            ),
        )
        val borderPen = if (border > 0.015) 0.0 else 0.6 * tf / 2.0

        val c = q.centroid()
        val centerPen = 0.5 * (kotlin.math.abs(c.x - 0.5) + kotlin.math.abs(c.y - 0.5))
        val area = if (qa <= 0.85) qa else 0.85 - (qa - 0.85)
        return max(0.0, area * fill * (1.0 - borderPen) * (1.0 - min(0.6, centerPen)) * sup)
    }

    /** Number of corners within 1.2% of the frame border. */
    private fun touchesFrame(q: QuadF, tol: Float = FRAME_TOUCH_TOL.toFloat()): Int {
        var c = 0
        for (p in q.points()) {
            if (p.x < tol || p.x > 1f - tol || p.y < tol || p.y > 1f - tol) c++
        }
        return c
    }

    /**
     * Fraction of sampled points along the quad's four sides that sit on an
     * edge (7x7 tolerance window on the dilated Canny map). The decisive
     * validator: frame-hugging mega-contours cross empty background and fail.
     */
    private fun edgeSupport(q: QuadF, emap: Mat, n: Int = 24): Double {
        val w = emap.cols()
        val h = emap.rows()
        val p = q.points()
        var hit = 0
        var total = 0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            for (k in 0 until n) {
                val t = 0.05 + 0.9 * k / (n - 1)
                val x = ((a.x + (b.x - a.x) * t) * w).toInt()
                val y = ((a.y + (b.y - a.y) * t) * h).toInt()
                val x0 = max(0, x - 3)
                val y0 = max(0, y - 3)
                val rw = min(7, w - x0)
                val rh = min(7, h - y0)
                if (rw <= 0 || rh <= 0) continue
                val roi = emap.submat(Rect(x0, y0, rw, rh))
                total++
                if (Core.countNonZero(roi) > 0) hit++
            }
        }
        return if (total == 0) 0.0 else hit.toDouble() / total
    }

    /** Median of the gray histogram (robust against outliers than the mean). */
    private fun medianLuminance(gray: Mat): Double {
        val size = 256
        val hist = Mat()
        return try {
            Imgproc.calcHist(listOf(gray), MatOfInt(0), Mat(), hist, MatOfInt(size), MatOfFloat(0f, 256f))
            val total = gray.total()
            val data = FloatArray(size)
            hist.get(0, 0, data)
            var acc = 0.0
            var median = 0.0
            for (i in 0 until size) {
                acc += data[i].toDouble()
                if (acc >= total / 2.0) {
                    median = i.toDouble()
                    break
                }
            }
            median
        } catch (t: Throwable) {
            0.0
        } finally {
            hist.release()
        }
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
