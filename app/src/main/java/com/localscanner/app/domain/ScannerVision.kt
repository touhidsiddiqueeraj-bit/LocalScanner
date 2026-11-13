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

/**
 * OpenCV-powered document scanning pipeline replicating CamScanner's local
 * vision stack: live edge detection, perspective correction ("auto-crop and
 * straighten") and the enhancement filters (Magic Color, Grayscale, B&W).
 */
object ScannerVision {

    /** Minimum share of image area a plausible document quad must cover. */
    private const val MIN_QUAD_AREA = 0.08f

    /**
     * Detects the dominant document quad in the bitmap.
     * Returns a quad in normalized [0..1] coordinates, or null.
     *
     * Robustness strategy (photos are noisier than the live preview):
     *  1. Multi-pass Canny - adaptive thresholds derived from the luminance
     *     median, plus two fixed fallbacks, so dim/bright pages still yield edges.
     *  2. Morphological close to connect broken document outlines.
     *  3. Convex 4-point approximation preferred (top contours, area >= 8%).
     *  4. If no clean quad is found, the minimum-area rotated rect of the
     *     largest contour is used - CamScanner-style fallback that still crops
     *     and straightens instead of degrading to the full frame.
     */
    fun detectQuad(src: Bitmap, maxDim: Int = 448): QuadF? {
        val scale = min(1f, maxDim.toFloat() / max(src.width, src.height).toFloat())
        val w = max(32, (src.width * scale).toInt())
        val h = max(32, (src.height * scale).toInt())
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(src, w, h, true) else src

        val rgba = Mat()
        val gray = Mat()
        val med = Mat()
        val edges = Mat()
        val closed = Mat()
        val hierarchy = Mat()
        var kernel: Mat? = null
        return try {
            Utils.bitmapToMat(scaled, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

            // Adaptive Canny thresholds from the luminance median (auto-canny heuristic).
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

            val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            kernel = k
            val imgArea = w.toDouble() * h.toDouble()
            var best: QuadF? = null
            var bestArea = 0.0

            for ((low, high) in thresholdSets) {
                Imgproc.Canny(gray, edges, low, high)
                Imgproc.dilate(edges, edges, kernel)
                Imgproc.morphologyEx(edges, closed, Imgproc.MORPH_CLOSE, kernel)

                val contours = mutableListOf<MatOfPoint>()
                try {
                    Imgproc.findContours(closed, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                    val candidates = contours
                        .sortedByDescending { Imgproc.contourArea(it) }
                        .take(10)
                        .filter { Imgproc.contourArea(it) >= imgArea * MIN_QUAD_AREA }
                    for (contour in candidates) {
                        val quad = quadFromContour(contour, w, h) ?: continue
                        val area = Imgproc.contourArea(contour)
                        if (area > bestArea) {
                            best = quad
                            bestArea = area
                        }
                    }
                } finally {
                    contours.forEach { it.release() }
                }
                if (best != null) return best
            }

            // Last resort: min-area rotated rect of the largest contour.
            Imgproc.Canny(gray, edges, 25.0, 75.0)
            Imgproc.dilate(edges, edges, kernel)
            val contours = mutableListOf<MatOfPoint>()
            try {
                Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                val largest = contours.maxByOrNull { Imgproc.contourArea(it) }
                if (largest != null && Imgproc.contourArea(largest) >= imgArea * MIN_QUAD_AREA) {
                    best = rectFromContour(largest, w, h)
                }
            } finally {
                contours.forEach { it.release() }
            }
            best
        } catch (t: Throwable) {
            null
        } finally {
            rgba.release()
            gray.release()
            med.release()
            edges.release()
            closed.release()
            hierarchy.release()
            kernel?.release()
            if (scaled !== src) scaled.recycle()
        }
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

    /** Convex 4-point quad from a contour, in normalized coordinates. */
    private fun quadFromContour(contour: MatOfPoint, w: Int, h: Int): QuadF? {
        val c2f = MatOfPoint2f(*contour.toArray())
        val approx = MatOfPoint2f()
        return try {
            val peri = Imgproc.arcLength(c2f, true)
            Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true)
            if (approx.total() != 4L) return null
            if (!Imgproc.isContourConvex(MatOfPoint(*approx.toArray()))) return null
            val pts = approx.toList().map { Pt((it.x / w).toFloat(), (it.y / h).toFloat()) }
            val quad = QuadF.ordered(pts) ?: return null
            if (!quad.isPlausible(MIN_QUAD_AREA)) null else quad
        } catch (t: Throwable) {
            null
        } finally {
            c2f.release()
            approx.release()
        }
    }

    /** Minimum-area rotated rect around the contour, as a fallback quad. */
    private fun rectFromContour(contour: MatOfPoint, w: Int, h: Int): QuadF? {
        val c2f = MatOfPoint2f(*contour.toArray())
        return try {
            val rect = Imgproc.minAreaRect(c2f)
            val pts = arrayOf(Point(), Point(), Point(), Point())
            rect.points(pts)
            val quadPts = pts.map { Pt((it.x / w).toFloat(), (it.y / h).toFloat()) }
            val quad = QuadF.ordered(quadPts) ?: return null
            if (!quad.isPlausible(MIN_QUAD_AREA)) null else quad
        } catch (t: Throwable) {
            null
        } finally {
            c2f.release()
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
