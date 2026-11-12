package com.localscanner.app.domain

/**
 * A lightweight point in pure Kotlin (no Android dependencies) so quad
 * geometry can be unit tested on the JVM.
 */
data class Pt(val x: Float, val y: Float)

/**
 * A document quad in image coordinates, ordered as top-left, top-right,
 * bottom-right, bottom-left.
 */
data class QuadF(
    val tl: Pt,
    val tr: Pt,
    val br: Pt,
    val bl: Pt,
) {
    fun points(): List<Pt> = listOf(tl, tr, br, bl)

    fun centroid(): Pt = Pt(
        (tl.x + tr.x + br.x + bl.x) / 4f,
        (tl.y + tr.y + br.y + bl.y) / 4f,
    )

    /** Shoelace formula. */
    fun area(): Float {
        val p = points()
        var sum = 0f
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            sum += a.x * b.y - b.x * a.y
        }
        return Math.abs(sum) / 2f
    }

    fun perimeter(): Float {
        val p = points()
        var sum = 0f
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            sum += dist(a, b)
        }
        return sum
    }

    private fun dist(a: Pt, b: Pt): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    /**
     * A quad is considered a plausible document when it covers a reasonable
     * share of the image and its corners are roughly in the right order.
     */
    fun isPlausible(minAreaRatio: Float = 0.10f): Boolean {
        if (area() < minAreaRatio) return false
        if (perimeter() <= 0f) return false
        // Normalised corner sanity: tl above br, tr above bl (allow slack).
        val ySlack = 0.35f
        if (tl.y > br.y + ySlack || tr.y > bl.y + ySlack) return false
        return true
    }

    companion object {
        /** Orders four arbitrary points into TL/TR/BR/BL. */
        fun ordered(points: List<Pt>): QuadF? {
            if (points.size != 4) return null
            val sumSorted = points.sortedBy { it.x + it.y }
            val tl = sumSorted.first()
            val br = sumSorted.last()
            val rest = points.filter { it != tl && it != br }
            val diffSorted = rest.sortedBy { it.y - it.x }
            val tr = diffSorted.first()
            val bl = diffSorted.last()
            if (tl == br || tr == bl) return null
            return QuadF(tl, tr, br, bl)
        }

        fun fullFrame(): QuadF = QuadF(Pt(0f, 0f), Pt(1f, 0f), Pt(1f, 1f), Pt(0f, 1f))

        /** Distance between quad centroids - used for capture stability. */
        fun centroidDistance(a: QuadF, b: QuadF): Float {
            val ca = a.centroid()
            val cb = b.centroid()
            val dx = ca.x - cb.x
            val dy = ca.y - cb.y
            return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        }
    }
}
